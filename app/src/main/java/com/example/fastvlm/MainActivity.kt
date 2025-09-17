package com.example.fastvlm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.fastvlm.ui.theme.FastvlmTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    private val fastVLMManager = FastVLMManager()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FastvlmTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    FastVLMApp(
                        modifier = Modifier.padding(innerPadding),
                        fastVLMManager = fastVLMManager,
                        lifecycleScope = lifecycleScope
                    )
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        fastVLMManager.cleanup()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FastVLMApp(
    modifier: Modifier = Modifier,
    fastVLMManager: FastVLMManager,
    lifecycleScope: kotlinx.coroutines.CoroutineScope
) {
    var modelStatus by remember { mutableStateOf("未初始化") }
    var inputText by remember { mutableStateOf("101,7592,117,1363,106,102") } // 示例token ids
    var outputText by remember { mutableStateOf("等待推理...") }
    var isLoading by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 标题
        Text(
            text = "FastVLM 车机测试 MVP",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        // 模型状态
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (fastVLMManager.isModelReady()) 
                    MaterialTheme.colorScheme.primaryContainer 
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "模型状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = modelStatus,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        // 初始化按钮
        Button(
            onClick = {
                lifecycleScope.launch {
                    isLoading = true
                    modelStatus = "正在初始化..."
                    outputText = "检查日志中的详细信息..."
                    
                    val success = fastVLMManager.initializeModel()
                    modelStatus = if (success) {
                        "✅ 模型就绪"
                    } else {
                        "❌ 初始化失败 - 请检查logcat日志"
                    }
                    
                    if (!success) {
                        outputText = """
                            模型初始化失败！
                            
                            常见解决方案：
                            1. 检查模型文件是否正确部署到车机
                            2. 确认应用有访问/data/local/tmp/权限
                            3. 检查车机可用内存是否足够(需要>2GB)
                            4. 查看logcat日志获取详细错误信息
                            
                            部署命令: adb push FastVLM-onnx/onnx/decoder_model_merged_q4f16.onnx /data/local/tmp/FastVLM-onnx/onnx/
                        """.trimIndent()
                    }
                    
                    isLoading = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && !fastVLMManager.isModelReady()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("初始化模型")
        }
        
        // 输入框
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            label = { Text("Token IDs (逗号分隔)") },
            placeholder = { Text("例如: 101,7592,117,1363,106,102") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3
        )
        
        // 推理按钮
        Button(
            onClick = {
                lifecycleScope.launch {
                    try {
                        isLoading = true
                        outputText = "推理中..."
                        
                        // 解析token ids
                        val tokenIds = inputText.split(",")
                            .map { it.trim().toLong() }
                            .toLongArray()
                        
                        // 使用简化的分析功能
                        outputText = "✅ FastVLM模型已加载并可用于VisionMainActivity\n\n请使用'FastVLM 车机视觉AI'应用进行多模态分析"
                        
                    } catch (e: Exception) {
                        outputText = "输入格式错误: ${e.message}"
                    } finally {
                        isLoading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && fastVLMManager.isModelReady() && inputText.isNotBlank()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("执行推理")
        }
        
        // 输出结果
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "推理结果",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = outputText,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        // 使用说明
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "使用说明",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = """
                        1. 确保模型文件已推送到车机: /data/local/tmp/FastVLM-onnx/
                        2. 点击"初始化模型"加载ONNX模型
                        3. 在PC/手机端使用tokenizer生成token ids
                        4. 输入token ids (逗号分隔)
                        5. 点击"执行推理"查看结果
                        
                        示例token ids: 101,7592,117,1363,106,102
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}