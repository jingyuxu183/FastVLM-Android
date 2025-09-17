package com.example.fastvlm

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.fastvlm.camera.VehicleCameraService
import com.example.fastvlm.camera.CameraPreviewSurface
import com.example.fastvlm.ui.theme.FastvlmTheme
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * FastVLM视觉版本主界面
 * 集成摄像头预览和AI推理功能
 */
class VisionMainActivity : ComponentActivity() {
    
    private lateinit var cameraService: VehicleCameraService
    private lateinit var fastVLMManager: FastVLMManager
    private var debugEnabled = mutableStateOf(true)
    
    companion object {
        private const val TAG = "VisionMainActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
    
    // 权限请求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.i(TAG, "✅ 所有权限已授予")
            initializeCamera()
        } else {
            Log.e(TAG, "❌ 权限被拒绝")
            Toast.makeText(this, "需要摄像头权限才能使用视频流功能", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "=== FastVLM视觉AI应用启动 ===")
        
        // 初始化相机服务和AI管理器
        cameraService = VehicleCameraService(this)
        fastVLMManager = FastVLMManager()
        
        // 初始化FastVLM模型
        lifecycleScope.launch {
            Log.i(TAG, "开始初始化FastVLM模型...")
            val success = fastVLMManager.initializeModel()
            if (success) {
                Log.i(TAG, "✅ FastVLM模型初始化成功")
            } else {
                Log.e(TAG, "❌ FastVLM模型初始化失败")
            }
        }
        // 启用调试导出（用于一次性导出投影器前后向量）
        try { fastVLMManager.setDebugExportEnabled(true) } catch (_: Throwable) {}
        
        setContent {
            FastvlmTheme {
                VisionMainScreen(
                    cameraService = cameraService,
                    fastVLMManager = fastVLMManager,
                    context = this,
                    debugEnabled = debugEnabled,
                    onRequestPermissions = { /* 权限自动处理，无需手动按钮 */ },
                    onCaptureImage = { cameraService.captureImage() }
                )
            }
        }
        
        // 自动检查并请求权限
        checkAndRequestPermissions()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraService.stopCamera()
        fastVLMManager.cleanup()
        Log.i(TAG, "VisionMainActivity已销毁")
    }
    
    private fun checkAndRequestPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isEmpty()) {
            Log.i(TAG, "所有权限已具备")
            initializeCamera()
        } else {
            Log.i(TAG, "需要请求权限: ${missingPermissions.joinToString()}")
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
    
    private fun requestCameraPermissions() {
        requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
    }
    
    private fun initializeCamera() {
        Log.i(TAG, "开始初始化摄像头...")
        
        cameraService.onError = { error ->
            Log.e(TAG, "摄像头错误: $error")
            runOnUiThread {
                Toast.makeText(this, "摄像头错误: $error", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionMainScreen(
    cameraService: VehicleCameraService,
    fastVLMManager: FastVLMManager,
    context: ComponentActivity,
    debugEnabled: MutableState<Boolean>,
    onRequestPermissions: () -> Unit,
    onCaptureImage: () -> Unit
) {
    var cameraStatus by remember { mutableStateOf("未初始化") }
    var lastCapturedImage by remember { mutableStateOf<Bitmap?>(null) }
    var cameraStarted by remember { mutableStateOf(false) }
    var inputField by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("Describe this image in detail in English.")) }
    val inputText = inputField.text
    var aiResponse by remember { mutableStateOf("等待图像输入...") }
    var isAutoCapturing by remember { mutableStateOf(false) }
    // 避免多线程竞态，使用稳定的单向流传递最新提示词
    var pendingUserPrompt by rememberSaveable { mutableStateOf<String?>(null) }
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 监听摄像头捕获的图像
    LaunchedEffect(Unit) {
        cameraService.onImageCaptured = { bitmap ->
            lastCapturedImage = bitmap
            Log.d("VisionMainScreen", "更新UI显示的图像，开始AI分析")
            // --- BEGIN INJECTED DEBUG CODE ---
            try {
                val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                if (picturesDir != null) {
                    val file = File(picturesDir, "vlm_debug_snapshot_${System.currentTimeMillis()}.png")
                    FileOutputStream(file).use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    }
                    Log.d("VLM_DIAGNOSTIC", "Snapshot for analysis saved to: ${file.absolutePath}")
                } else {
                    Log.w("VLM_DIAGNOSTIC", "getExternalFilesDir returned null; cannot save snapshot")
                }
            } catch (e: Exception) {
                Log.e("VLM_DIAGNOSTIC", "Failed to save snapshot", e)
            }
            // --- END INJECTED DEBUG CODE ---
            // 若存在用户主动问题，则优先用用户问题进行分析，并清除pending标记
            val prompt = pendingUserPrompt
            if (prompt != null) {
                aiResponse = "🤔 AI正在分析图像...\n\n用户问题: $prompt"
                pendingUserPrompt = null
                scope.launch {
                    try {
                        val result = fastVLMManager.analyzeImageWithText(bitmap, prompt)
                        aiResponse = result
                    } catch (e: Exception) {
                        aiResponse = "❌ AI分析出错: ${e.message}"
                        Log.e("VisionMainScreen", "FastVLM分析失败(用户问题)", e)
                    }
                }
            } else {
                // 自动触发AI分析，使用当前输入框中的提示词（可能为默认）
                aiResponse = "🤔 AI正在分析图像...\n\n分析问题: $inputText"
                scope.launch {
                    try {
                        val result = fastVLMManager.analyzeImageWithText(bitmap, inputText)
                        aiResponse = result
                    } catch (e: Exception) {
                        aiResponse = "❌ AI分析出错: ${e.message}"
                        Log.e("VisionMainScreen", "FastVLM分析失败(自动)", e)
                    }
                }
            }
        }
    }
    
    // 35秒定时自动截图
    LaunchedEffect(isAutoCapturing) {
        if (isAutoCapturing && cameraStarted) {
            while (isAutoCapturing) {
                kotlinx.coroutines.delay(60000) // 60秒
                if (isAutoCapturing && cameraStarted) {
                    Log.d("VisionMainScreen", "自动截图触发")
                    cameraService.captureImage()
                }
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 标题
        Text(
            text = "🚗 FastVLM 车机视觉AI",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Switch(checked = debugEnabled.value, onCheckedChange = { checked ->
                debugEnabled.value = checked
                fastVLMManager.setDebugExportEnabled(checked)
            })
            Text(text = if (debugEnabled.value) "调试已开启" else "调试已关闭", style = MaterialTheme.typography.bodySmall)
        }
        
        // 摄像头控制区域 - 简化版本
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (cameraStarted) 
                    MaterialTheme.colorScheme.primaryContainer 
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "📷 摄像头控制",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = cameraStatus,
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 简化的摄像头控制按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = {
                            cameraStatus = "正在启动摄像头..."
                            cameraStarted = true
                            isAutoCapturing = true // 启动自动截图
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !cameraStarted
                    ) {
                        Text("📹 Start Camera")
                    }
                    
                    Button(
                        onClick = {
                            cameraService.stopCamera()
                            cameraStatus = "摄像头已停止"
                            cameraStarted = false
                            isAutoCapturing = false // 停止自动截图
                        },
                        modifier = Modifier.weight(1f),
                        enabled = cameraStarted
                    ) {
                        Text("⏹️ Stop Camera")
                    }
                }
                
                // 自动截图状态指示
                if (isAutoCapturing) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "🔄 自动截图已启动 (每60秒)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        
        // 视频预览区域
        if (cameraStarted) {
            CameraPreviewSurface(
                cameraService = cameraService,
                onSurfaceReady = { ready ->
                    if (ready) {
                        cameraStatus = "✅ 摄像头运行中 | 摄像头ID: 19"
                    } else {
                        cameraStatus = "❌ 摄像头启动失败"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "📹",
                            style = MaterialTheme.typography.displayLarge
                        )
                        Text(
                            text = "Camera Preview",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Click 'Start Camera' to begin",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // 捕获图像显示区域
        if (lastCapturedImage != null) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "� 最新捕获图像",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Image(
                        bitmap = lastCapturedImage!!.asImageBitmap(),
                        contentDescription = "捕获的图像",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    )
                }
            }
        }
        
        // 对话输入区域 - 增加发送按钮
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "💬 AI Chat",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = inputField,
                        onValueChange = { newValue -> inputField = newValue },
                        label = { Text("Ask AI (English)") },
                        placeholder = { Text("e.g., Describe the image") },
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.isNotBlank()) {
                                    if (cameraStarted) {
                                        pendingUserPrompt = inputText
                                        aiResponse = "🤔 AI正在分析图像...\n\n用户问题: $inputText"
                                        cameraService.captureImage()
                                    } else if (lastCapturedImage != null) {
                                        pendingUserPrompt = null
                                        aiResponse = "🤔 AI正在分析图像...\n\n用户问题: $inputText"
                                        scope.launch {
                                            try {
                                                val result = fastVLMManager.analyzeImageWithText(lastCapturedImage!!, inputText)
                                                aiResponse = result
                                            } catch (e: Exception) {
                                                aiResponse = "❌ AI分析出错: ${e.message}"
                                                Log.e("VisionMainScreen", "手动FastVLM分析失败", e)
                                            }
                                        }
                                    } else {
                                        aiResponse = "❌ 请先启动摄像头并等待图像捕获"
                                    }
                                }
                            }
                        )
                    )
                    
                    // 发送按钮
                    Button(
                        onClick = {
                            if (cameraStarted) {
                                // 标记用户问题，并触发一次新捕获，保证针对最新画面回答
                                pendingUserPrompt = inputText
                                aiResponse = "🤔 AI正在分析图像...\n\n用户问题: $inputText"
                                cameraService.captureImage()
                            } else if (lastCapturedImage != null) {
                                // 备用：未启动摄像头但已有最近图像，则直接用最近图像
                                pendingUserPrompt = null
                                aiResponse = "🤔 AI正在分析图像...\n\n用户问题: $inputText"
                                scope.launch {
                                    try {
                                        val result = fastVLMManager.analyzeImageWithText(lastCapturedImage!!, inputText)
                                        aiResponse = result
                                    } catch (e: Exception) {
                                        aiResponse = "❌ AI分析出错: ${e.message}"
                                        Log.e("VisionMainScreen", "手动FastVLM分析失败", e)
                                    }
                                }
                            } else {
                                aiResponse = "❌ 请先启动摄像头并等待图像捕获"
                            }
                        },
                        enabled = inputText.isNotBlank()
                    ) {
                        Text("📤")
                    }
                }
            }
        }
        
        // AI回答区域
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "🤖 AI分析结果",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = aiResponse,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        // 功能状态指示
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "⚡ 自动化功能",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = """
                        ✅ 自动20秒定时截图
                        ✅ 自动AI图像分析  
                        ✅ 实时摄像头预览
                        ✅ 中英文对话支持
                        
                        📋 使用说明:
                        1. 点击'启动摄像头'开始
                        2. 系统自动每60秒截图
                        3. 自动进行AI分析
                        4. 可随时修改问题并发送
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
