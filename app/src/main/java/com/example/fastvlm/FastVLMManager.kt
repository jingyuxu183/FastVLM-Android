package com.example.fastvlm

import ai.onnxruntime.*
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.nio.LongBuffer
import com.example.fastvlm.tokenizer.BpeTokenizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FastVLMManager {
    companion object {
        private const val TAG = "FastVLMManager"
        private const val BASE_PATH = "/data/local/tmp/FastVLM-onnx/onnx"
        private const val VISION_MODEL_PATH = "$BASE_PATH/vision_encoder_q4.onnx"
        private const val DECODER_MODEL_PATH = "$BASE_PATH/decoder_model_merged_q4.onnx"
        private const val EMBED_MODEL_PATH = "$BASE_PATH/embed_tokens_q4.onnx"
		private const val VPROJ_L1_W = "$BASE_PATH/vision_projector_l1_w.bin"
		private const val VPROJ_L1_B = "$BASE_PATH/vision_projector_l1_b.bin"
		private const val VPROJ_L2_W = "$BASE_PATH/vision_projector_l2_w.bin"
		private const val VPROJ_L2_B = "$BASE_PATH/vision_projector_l2_b.bin"
	}

	private var env: OrtEnvironment? = null
	private var vision: OrtSession? = null
	private var decoder: OrtSession? = null
	private var embed: OrtSession? = null
	private var tokenizer: BpeTokenizer? = null
	private var ready = false

	// projector
	private var projL1W: FloatArray? = null
	private var projL1B: FloatArray? = null
	private var projL2W: FloatArray? = null
	private var projL2B: FloatArray? = null
	private var projInDim: Int = 0
	private var hiddenDim: Int = 0

	suspend fun initializeModel(): Boolean = withContext(Dispatchers.IO) {
		return@withContext try {
			env = OrtEnvironment.getEnvironment()
			val opt = OrtSession.SessionOptions().apply {
				// 提升CPU线程数
				setIntraOpNumThreads(4)
				// 尝试启用 NNAPI 与 XNNPACK（若运行时可用）
				try {
					val m = this::class.java.getMethod("addNnapi")
					m.invoke(this)
					Log.i(TAG, "NNAPI EP enabled")
				} catch (_: Throwable) { Log.w(TAG, "NNAPI EP not available on this build") }
				try {
					val mx = this::class.java.getMethod("addXnnpack", Int::class.javaPrimitiveType)
					mx.invoke(this, 4)
					Log.i(TAG, "XNNPACK EP enabled (threads=4)")
				} catch (_: Throwable) { Log.w(TAG, "XNNPACK EP not available on this build") }
			}
			vision = env!!.createSession(VISION_MODEL_PATH, opt)
			decoder = env!!.createSession(DECODER_MODEL_PATH, opt)
			embed = env!!.createSession(EMBED_MODEL_PATH, opt)
			tokenizer = BpeTokenizer(BASE_PATH, env!!)
			loadProjectorIfExists()
			ready = true
			Log.i(TAG, "Initialized")
			true
            } catch (t: Throwable) {
			Log.e(TAG, "Init fail: ${t.message}", t)
			cleanup(); false
		}
	}

	fun isModelReady() = ready
	fun setDebugExportEnabled(enabled: Boolean) { /* no-op for compatibility */ }

	fun cleanup() {
		try { vision?.close() } catch (_: Throwable) {}
		try { decoder?.close() } catch (_: Throwable) {}
		try { embed?.close() } catch (_: Throwable) {}
		vision = null; decoder = null; embed = null; ready = false
	}

	suspend fun analyzeImageWithText(image: Bitmap, prompt: String): String = withContext(Dispatchers.Default) {
		if (!ready) return@withContext "❌ model not ready"
		try {
			// 1) preprocess to [1,3,1024,1024]
			val t0 = System.nanoTime()
			val imgCHW = preprocess(image)
			val t1 = System.nanoTime(); Log.i(TAG, "⏱ preprocess: ${(t1 - t0)/1_000_000} ms")
			// 2) vision encoder → [1, T, dim]
			val vOut = runVision(imgCHW)
			val vShape = vOut.first
			val vFeat = vOut.second
			val T = vShape[1].toInt()
			val featDim = vShape[2].toInt()
			val t2 = System.nanoTime(); Log.i(TAG, "⏱ vision encoder: ${(t2 - t1)/1_000_000} ms (T=${'$'}T, dim=${'$'}featDim)")
			// 3) chat template via tokenizer (align with PC)
			val tpl = tokenizer!!.applyChatTemplate(prompt)
			val idsMap = tokenizer!!.tokenizeToTensors(tpl)
			val inputIds = idsMap["input_ids"] as OnnxTensor
			// 4) text embeds
			val embOut = embed!!.run(mapOf("input_ids" to inputIds))
			val embT = embOut[0] as OnnxTensor
			val eShape = embT.info.shape!! // [1, L, hidden]
			val L = eShape[1].toInt(); val hidden = eShape[2].toInt()
			hiddenDim = hidden
			val embFloats = FloatArray(L * hidden)
			embT.floatBuffer.rewind(); embT.floatBuffer.get(embFloats)
			// 5) find <image> position and build inputs_embeds with visual tokens
			val imageTokId = tokenizer!!.getImageTokenId() ?: 151646
			val idsArr = LongArray(L); run { val lb = inputIds.longBuffer; lb.rewind(); var k=0; while (k<L){ idsArr[k]=lb.get(); k++ } }
			var imagePos = -1; run { var i=0; while (i<L) { if (idsArr[i].toInt() == imageTokId) { imagePos = i; break }; i++ } }
			val totalLen = if (imagePos >= 0) (L - 1 + T) else L
			val inputsEmbeds = FloatArray(totalLen * hidden)
			var w = 0
			for (p in 0 until L) {
				if (p == imagePos) {
					var ti = 0
					while (ti < T) {
						val baseOut = (w * hidden)
						if (featDim == hidden) {
							val baseIn = ti * featDim
							System.arraycopy(vFeat, baseIn, inputsEmbeds, baseOut, hidden)
						} else if (projL1W != null && projL1B != null) {
							projectOneToken(vFeat, ti * featDim, featDim, inputsEmbeds, baseOut, hidden)
                } else {
							// fallback: copy min(featDim, hidden)
							val baseIn = ti * featDim
							val copy = kotlin.math.min(hidden, featDim)
							System.arraycopy(vFeat, baseIn, inputsEmbeds, baseOut, copy)
						}
						w += 1; ti += 1
					}
                } else {
					val src = p * hidden; val dst = w * hidden
					System.arraycopy(embFloats, src, inputsEmbeds, dst, hidden)
					w += 1
				}
			}
			// 6) AR decoding with cache
			val attn0 = LongArray(totalLen) { 1L }
			val pos0 = LongArray(totalLen) { it.toLong() }
			val feed0 = HashMap<String, OnnxTensor>()
			feed0["inputs_embeds"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputsEmbeds), longArrayOf(1, totalLen.toLong(), hidden.toLong()))
			feed0["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(attn0), longArrayOf(1, totalLen.toLong()))
			feed0["position_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(pos0), longArrayOf(1, totalLen.toLong()))
			// empty past
			val layerInputs = decoder!!.inputInfo.keys.filter { it.startsWith("past_key_values.") }
            val layerCount = (layerInputs.size / 2).coerceAtLeast(1)
			val kv0 = decoder!!.inputInfo["past_key_values.0.key"] as NodeInfo
			val kvShape = (kv0.info as TensorInfo).shape
			val heads = kvShape[1]; val headDim = kvShape[3]
			val empty = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(0)), longArrayOf(1, heads, 0, headDim))
			for (l in 0 until layerCount) { feed0["past_key_values.${l}.key"] = empty; feed0["past_key_values.${l}.value"] = empty }
			val t3 = System.nanoTime()
			var out = decoder!!.run(feed0)
			val t4 = System.nanoTime(); Log.i(TAG, "⏱ decoder prefill: ${(t4 - t3)/1_000_000} ms (len=${'$'}totalLen)")
			var lastLogits = (out[0] as OnnxTensor)
			var last = FloatArray((lastLogits.info.shape!![2]).toInt())
			run { val fb = lastLogits.floatBuffer; val seq = lastLogits.info.shape!![1].toInt(); val vocab = last.size; fb.position((seq-1)*vocab); fb.get(last) }
			val generated = ArrayList<Int>()
			val eos = tokenizer!!.getEosId() ?: 151645
			val temperature = 0.7f; val topP = 0.95f; val rep = 1.1f
			var step = 0
			val genStart = System.nanoTime()
			while (step < 96) {
				val nextId = sample(last, generated, temperature, topP, rep)
				if (nextId == eos) break
				generated.add(nextId)
				// embed new token
				val stepIds = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(nextId.toLong())), longArrayOf(1,1))
				val eOut = embed!!.run(mapOf("input_ids" to stepIds))
				val eTok = eOut[0] as OnnxTensor
				val feed = HashMap<String, OnnxTensor>()
				feed["inputs_embeds"] = eTok
				val k0t = out[1] as OnnxTensor; val pastLen = (k0t.info.shape!![2]).toInt()
				feed["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(pastLen+1){1L}), longArrayOf(1,(pastLen+1).toLong()))
				feed["position_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(pastLen.toLong())), longArrayOf(1,1))
				var idx = 1
				for (l in 0 until layerCount) { feed["past_key_values.${l}.key"] = out[idx] as OnnxTensor; feed["past_key_values.${l}.value"] = out[idx+1] as OnnxTensor; idx += 2 }
				out = decoder!!.run(feed)
				lastLogits = out[0] as OnnxTensor
				last = FloatArray((lastLogits.info.shape!![2]).toInt())
				run { val fb = lastLogits.floatBuffer; val seq = lastLogits.info.shape!![1].toInt(); val vocab = last.size; fb.position((seq-1)*vocab); fb.get(last) }
				step++
			}
			val genEnd = System.nanoTime(); Log.i(TAG, "⏱ generate ${'$'}step tokens: ${(genEnd - genStart)/1_000_000} ms, avg=${'$'}{if (step>0) ((genEnd-genStart)/1_000_000.0/step) else 0.0} ms/token")
			// decode text (Chinese per tokenizer config)
			val text = tokenizer!!.decode(generated)
			text
            } catch (t: Throwable) {
			Log.e(TAG, "infer fail: ${t.message}", t)
			"❌ inference failed: ${t.message}"
		}
	}

	private fun preprocess(bitmap: Bitmap): FloatArray {
		val resized = Bitmap.createScaledBitmap(bitmap, 1024, 1024, true)
		val pixels = IntArray(1024*1024)
		resized.getPixels(pixels, 0, 1024, 0, 0, 1024, 1024)
		val arr = FloatArray(3*1024*1024)
		val s = 1f/255f
                    var i = 0
		while (i < pixels.size) {
			val p = pixels[i]
			val r = ((p ushr 16) and 0xFF) * s
			val g = ((p ushr 8) and 0xFF) * s
			val b = (p and 0xFF) * s
			arr[i] = r
			arr[i+1024*1024] = g
			arr[i+2*1024*1024] = b
			i++
		}
		return arr
	}

	private fun runVision(inputCHW: FloatArray): Pair<LongArray, FloatArray> {
		val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputCHW), longArrayOf(1,3,1024,1024))
		val out = vision!!.run(mapOf(vision!!.inputNames.iterator().next() to tensor))
		val t = out[0] as OnnxTensor
		val shape = t.info.shape!! // [1,T,dim]
		val fb = t.floatBuffer
		val arr = FloatArray((shape[1]*shape[2]).toInt())
		fb.rewind(); fb.get(arr)
		return Pair(shape, arr)
	}

	private fun sample(logits: FloatArray, generated: List<Int>, temperature: Float, topP: Float, repetitionPenalty: Float): Int {
		val temp = if (temperature <= 0f) 1e-6f else temperature
		val scores = FloatArray(logits.size)
		val seen = HashMap<Int, Int>()
		for (id in generated) seen[id] = (seen[id] ?: 0) + 1
            var i = 0
		while (i < logits.size) {
			var s = logits[i]
			val cnt = seen[i] ?: 0
			if (cnt > 0) s = if (s > 0) s / repetitionPenalty else s * repetitionPenalty
			scores[i] = s / temp
			i++
		}
		val maxLogit = scores.maxOrNull() ?: 0f
            var sum = 0.0
		val probs = DoubleArray(scores.size)
		i = 0
		while (i < scores.size) { val e = kotlin.math.exp((scores[i] - maxLogit).toDouble()); probs[i]=e; sum+=e; i++ }
		if (sum <= 0.0) return 0
		i = 0; while (i < probs.size) { probs[i] /= sum; i++ }
		val idx = (0 until probs.size).toList().sortedByDescending { probs[it] }
		var cumulative = 0.0
		val kept = ArrayList<Int>()
		for (k in idx) { kept.add(k); cumulative += probs[k]; if (cumulative >= topP.toDouble()) break }
		var keptSum = 0.0; for (k in kept) keptSum += probs[k]
		var r = java.util.concurrent.ThreadLocalRandom.current().nextDouble(0.0, keptSum)
		for (k in kept) { r -= probs[k]; if (r <= 0) return k }
		return kept.lastOrNull() ?: 0
	}

	private fun loadProjectorIfExists() {
		try {
			val l1w = File(VPROJ_L1_W)
			val l1b = File(VPROJ_L1_B)
			if (l1w.exists() && l1b.exists()) {
				val wBytes = l1w.readBytes(); val bBytes = l1b.readBytes()
				val bb = ByteBuffer.wrap(bBytes).order(ByteOrder.LITTLE_ENDIAN)
				val hidden = bBytes.size / 4
				projL1B = FloatArray(hidden)
				var i=0; while (i<hidden) { projL1B!![i]=bb.float; i++ }
				val wFloats = wBytes.size / 4
				if (wFloats % hidden != 0) throw IllegalStateException("L1 W size mismatch")
				projInDim = wFloats / hidden
				val wb = ByteBuffer.wrap(wBytes).order(ByteOrder.LITTLE_ENDIAN)
				projL1W = FloatArray(wFloats)
				i=0; while (i<wFloats) { projL1W!![i]=wb.float; i++ }
				// optional L2
				val l2wF = File(VPROJ_L2_W); val l2bF = File(VPROJ_L2_B)
				if (l2wF.exists() && l2bF.exists()) {
					val l2wB = l2wF.readBytes(); val l2bB = l2bF.readBytes()
					if (l2bB.size/4 != hidden) throw IllegalStateException("L2 b size mismatch")
					val bb2 = ByteBuffer.wrap(l2bB).order(ByteOrder.LITTLE_ENDIAN)
					projL2B = FloatArray(hidden); i=0; while (i<hidden) { projL2B!![i]=bb2.float; i++ }
					if (l2wB.size/4 != hidden*hidden) throw IllegalStateException("L2 W size mismatch")
					val wb2 = ByteBuffer.wrap(l2wB).order(ByteOrder.LITTLE_ENDIAN)
					projL2W = FloatArray(hidden*hidden); i=0; while (i<hidden*hidden) { projL2W!![i]=wb2.float; i++ }
				}
				Log.i(TAG, "Projector loaded: hidden=$hidden, in=$projInDim, l2=${projL2W!=null}")
			}
		} catch (t: Throwable) {
			Log.w(TAG, "Projector load failed: ${t.message}")
		}
	}

	private fun gelu(x: Float): Float {
		return (0.5f * x * (1f + kotlin.math.tanh(0.7978845608f * (x + 0.044715f * x * x * x)))).toFloat()
	}

	private fun projectOneToken(src: FloatArray, srcBase: Int, srcDim: Int, dst: FloatArray, dstBase: Int, hidden: Int) {
		val w1 = projL1W!!; val b1 = projL1B!!
		// build input of size projInDim from src slice (repeat/truncate)
		val inVec = FloatArray(projInDim)
		val copy = kotlin.math.min(projInDim, srcDim)
		System.arraycopy(src, srcBase, inVec, 0, copy)
		var h = 0
		val layer1 = FloatArray(hidden)
		while (h < hidden) {
			var acc = b1[h]
			val row = h * projInDim
			var k = 0
			while (k < projInDim) { acc += inVec[k] * w1[row + k]; k++ }
			layer1[h] = gelu(acc)
			h++
		}
		val w2 = projL2W; val b2 = projL2B
		if (w2 != null && b2 != null) {
			var i=0
			while (i<hidden) {
				var acc = b2[i]
				val row = i*hidden
				var k=0
				while (k<hidden) { acc += layer1[k]*w2[row+k]; k++ }
				dst[dstBase+i] = acc
				i++
			}
                } else {
			System.arraycopy(layer1, 0, dst, dstBase, hidden)
        }
    }
}
