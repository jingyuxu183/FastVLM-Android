package com.example.fastvlm.tokenizer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.io.File
import java.nio.LongBuffer
import java.nio.charset.Charset
import java.util.regex.Pattern
import org.json.JSONObject

/**
 * Byte-Level BPE Tokenizer (HF-compatible for GPT2/Qwen-style models).
 * - Loads vocab.json, merges.txt, tokenizer_config.json (chat_template, special ids)
 * - Implements bytes_to_unicode mapping, GPT2 regex pre-tokenizer, BPE merges
 * - Supports special tokens (<|im_start|>, <|im_end|>, <|endoftext|>, <image>) passthrough
 * - Provides tokenizeToIds and decode for streaming
 */
class BpeTokenizer(private val basePath: String, private val ortEnv: OrtEnvironment) {

    private val vocab: MutableMap<String, Int> = HashMap()
    private val idToToken: MutableMap<Int, String> = HashMap()
    private val bpeRanks: MutableMap<Pair<String, String>, Int> = HashMap()
    private var chatTemplate: String? = null
    private var bosId: Int? = null
    private var eosId: Int? = null
    private var padId: Int? = null
    private var imageTokenId: Int? = null
    private val specialTokenToId: MutableMap<String, Int> = HashMap()
    private val idToSpecial: MutableMap<Int, String> = HashMap()

    // GPT2 regex
    private val pattern: Pattern = Pattern.compile(
        "''s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+"
    )

    private val byteEncoder: Map<Int, String> = buildBytesToUnicode()
    private val byteDecoder: Map<String, Int> = byteEncoder.entries.associate { it.value to it.key }

    init {
        loadFiles()
    }

    private fun resolveTokenizerDir(): File {
        // 支持两种目录：basePath 与 basePath/../tokenizer
        val root = File(basePath)
        val direct = root
        val sibling = File(root.parentFile ?: root, "tokenizer")
        return when {
            File(direct, "vocab.json").exists() -> direct
            File(sibling, "vocab.json").exists() -> sibling
            else -> direct // 兜底
        }
    }

    private fun loadFiles() {
        val tdir = resolveTokenizerDir()

        // vocab.json
        val vocabFile = File(tdir, "vocab.json")
        if (vocabFile.exists()) {
            val json = JSONObject(vocabFile.readText())
            for (key in json.keys()) {
                val id = json.getInt(key)
                vocab[key] = id
                idToToken[id] = key
            }
        }

        // merges.txt
        val mergesFile = File(tdir, "merges.txt")
        if (mergesFile.exists()) {
            val lines = mergesFile.readLines(Charset.forName("UTF-8"))
            var rank = 0
            for (line in lines) {
                val ln = line.trim()
                if (ln.isEmpty() || ln.startsWith("#")) continue
                val parts = ln.split(" ")
                if (parts.size == 2) {
                    bpeRanks[Pair(parts[0], parts[1])] = rank++
                }
            }
        }

        // tokenizer_config.json
        val configFile = File(tdir, "tokenizer_config.json")
        if (configFile.exists()) {
            val cfg = JSONObject(configFile.readText())
            if (cfg.has("chat_template")) chatTemplate = cfg.getString("chat_template")
            if (cfg.has("bos_token_id")) bosId = cfg.optInt("bos_token_id", -1).takeIf { it >= 0 }
            if (cfg.has("eos_token")) {
                val eosStr = cfg.optString("eos_token", "")
                val id = (cfg.optJSONObject("added_tokens_decoder")?.keys()?.asSequence()
                    ?.map { it.toString().toInt() }?.firstOrNull { k ->
                        cfg.getJSONObject("added_tokens_decoder").getJSONObject(k.toString()).optString("content") == eosStr
                    })
                if (id != null) eosId = id
            }
            if (cfg.has("added_tokens_decoder")) {
                val added = cfg.getJSONObject("added_tokens_decoder")
                for (k in added.keys()) {
                    val id = k.toString().toInt()
                    val content = added.getJSONObject(k).getString("content")
                    specialTokenToId[content] = id
                    idToSpecial[id] = content
                    if (content == "<image>") imageTokenId = id
                }
            }
            // pad from tokenizer_config if available
            if (cfg.has("pad_token")) {
                val padStr = cfg.optString("pad_token", "")
                padId = specialTokenToId[padStr]
            }
        }
        // special_tokens_map.json (optional)
        val specMap = File(tdir, "special_tokens_map.json")
        if (specMap.exists()) {
            val sm = JSONObject(specMap.readText())
            if (sm.has("eos_token")) {
                val eosStr = sm.getJSONObject("eos_token").optString("content", "")
                specialTokenToId[eosStr]?.let { eosId = it }
            }
            if (sm.has("pad_token")) {
                val padStr = sm.getJSONObject("pad_token").optString("content", "")
                padId = specialTokenToId[padStr]
            }
        }
    }

    fun applyChatTemplate(userPrompt: String): String {
        val tpl = chatTemplate
        // 对应 Qwen2 模板：
        // {% for message in messages %}
        //   {{ '<|im_start|>' + role + '\n' + content + '<|im_end|>\n' }}
        // {% endfor %}
        // {% if add_generation_prompt %}{{ '<|im_start|>assistant\n' }}{% endif %}
        val system = "<|im_start|>system\nYou are a helpful assistant. Please answer in English only.<|im_end|>\n"
        val userContent = "<image>\n" + userPrompt
        val user = "<|im_start|>user\n" + userContent + "<|im_end|>\n"
        val assistantHead = "<|im_start|>assistant\n"
        return if (!tpl.isNullOrEmpty()) {
            // 直接构造渲染结果（满足模板语义）
            system + user + assistantHead
        } else {
            system + user + assistantHead
        }
    }

    fun tokenizeToIds(raw: String): IntArray {
        val ids = ArrayList<Int>()
        // 特殊符号优先切分，防止进入BPE
        var cursor = 0
        while (cursor < raw.length) {
            val next = findNextSpecial(raw, cursor)
            if (next == null) {
                // 普通文本片段
                val segment = raw.substring(cursor)
                ids.addAll(encodeTextSegment(segment))
                break
            } else {
                // 普通片段
                if (next.start > cursor) {
                    ids.addAll(encodeTextSegment(raw.substring(cursor, next.start)))
                }
                // 特殊token映射
                specialTokenToId[next.token]?.let { ids.add(it) }
                cursor = next.end
            }
        }
        return ids.toIntArray()
    }

    fun tokenizeToTensors(prompt: String): Map<String, OnnxTensor> {
        val ids = tokenizeToIds(prompt)
        val inputIds = OnnxTensor.createTensor(
            ortEnv,
            LongBuffer.wrap(ids.map { it.toLong() }.toLongArray()),
            longArrayOf(1, ids.size.toLong())
        )
        val mask = LongArray(ids.size) { 1L }
        val attentionMask = OnnxTensor.createTensor(
            ortEnv,
            LongBuffer.wrap(mask),
            longArrayOf(1, ids.size.toLong())
        )
        return mapOf("input_ids" to inputIds, "attention_mask" to attentionMask)
    }

    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        val bytes = ArrayList<Int>()
        for (id in ids) {
            // 跳过特殊tokens
            if (idToSpecial.containsKey(id)) continue
            val tok = idToToken[id] ?: continue
            for (ch in tok) {
                val b = byteDecoder[ch.toString()]
                if (b != null) bytes.add(b)
            }
        }
        val arr = bytes.map { it.toByte() }.toByteArray()
        return try {
            String(arr, Charset.forName("UTF-8")).trim()
        } catch (t: Throwable) {
            String(arr)
        }
    }

    fun decodeToken(id: Int): String = decode(listOf(id))
    fun getEosId(): Int? = eosId
    fun getImageTokenId(): Int? = imageTokenId

    private data class SpecialMatch(val token: String, val start: Int, val end: Int)

    private fun findNextSpecial(text: String, from: Int): SpecialMatch? {
        var best: SpecialMatch? = null
        for (tok in specialTokenToId.keys) {
            val idx = text.indexOf(tok, from)
            if (idx >= 0) {
                val candidate = SpecialMatch(tok, idx, idx + tok.length)
                if (best == null || candidate.start < best!!.start) best = candidate
            }
        }
        return best
    }

    private fun encodeTextSegment(segment: String): List<Int> {
        if (segment.isEmpty()) return emptyList()
        val m = pattern.matcher(segment)
        val out = ArrayList<Int>()
        while (m.find()) {
            val piece = m.group()
            val encoded = piece.encodeToByteArray().map { b -> byteEncoder[b.toInt() and 0xFF]!! }.joinToString("")
            val bpeTokens = bpe(encoded)
            for (t in bpeTokens) out.add(vocab[t] ?: vocab["<unk>"] ?: 0)
        }
        return out
    }

    private fun bpe(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        var word = token.map { it.toString() }
        var pairs = getPairs(word)
        if (pairs.isEmpty()) return listOf(token)
        while (true) {
            val bigram = pairs.minByOrNull { bpeRanks[it] ?: Int.MAX_VALUE } ?: break
            if (!bpeRanks.containsKey(bigram)) break
            val first = bigram.first
            val second = bigram.second
            val newWord = ArrayList<String>()
            var i = 0
            while (i < word.size) {
                // 手动查找从 i 开始的 first 出现位置，避免误用 List.indexOf 带起始参数
                var j = i
                var found = false
                while (j < word.size) {
                    if (word[j] == first) { found = true; break }
                    j++
                }
                if (!found) {
                    newWord.addAll(word.subList(i, word.size))
                    break
                }
                newWord.addAll(word.subList(i, j))
                i = j
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    newWord.add(first + second)
                    i += 2
                } else {
                    newWord.add(word[i])
                    i += 1
                }
            }
            word = newWord
            if (word.size == 1) break
            pairs = getPairs(word)
        }
        return word
    }

    private fun getPairs(tokens: List<String>): Set<Pair<String, String>> {
        val s = HashSet<Pair<String, String>>()
        var prev: String? = null
        for (t in tokens) {
            if (prev != null) s.add(Pair(prev!!, t))
            prev = t
        }
        return s
    }

    private fun buildBytesToUnicode(): Map<Int, String> {
        val bs = mutableListOf<Int>()
        bs.addAll(33..126) // printable ascii without space
        bs.addAll(161..172)
        bs.addAll(174..255)
        val cs = bs.toMutableList()
        var n = 0
        for (b in 0..255) {
            if (!bs.contains(b)) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        val map = HashMap<Int, String>()
        for (i in bs.indices) {
            map[bs[i]] = cs[i].toChar().toString()
        }
        return map
    }
}
