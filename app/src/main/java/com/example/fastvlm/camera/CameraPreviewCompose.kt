package com.example.fastvlm.camera

import android.content.Context
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Camera2预览组件 - 专为8295车机优化
 */
@Composable
fun CameraPreviewSurface(
    cameraService: VehicleCameraService,
    onSurfaceReady: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var surfaceReady by remember { mutableStateOf(false) }
    var surfaceSize by remember { mutableStateOf("未知") }
    
    Column(
        modifier = modifier
    ) {
        // 预览状态信息
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (surfaceReady) 
                    MaterialTheme.colorScheme.primaryContainer 
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                Text(
                    text = "📺 预览状态",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (surfaceReady) "✅ Surface就绪 ($surfaceSize)" else "⏳ 等待Surface创建",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // SurfaceView预览区域（等比16:9，匹配捕获分辨率1280x720，可随屏等比放大）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        ) {
            AndroidView(
                factory = { context ->
                    Log.d("CameraPreviewSurface", "创建SurfaceView")
                    
                    SurfaceView(context).apply {
                        // 设置SurfaceView属性
                        setZOrderMediaOverlay(true) // 重要：确保在Compose中正确显示
                        
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                Log.i("CameraPreviewSurface", "✅ Surface创建成功")
                                // 固定底层Surface尺寸为1280x720，避免横向拉伸；外层使用16:9等比放大
                                try {
                                    holder.setFixedSize(1280, 720)
                                } catch (e: Exception) {
                                    Log.w("CameraPreviewSurface", "设置Surface固定尺寸失败: ${e.message}")
                                }
                                surfaceReady = true
                                surfaceSize = "创建完成"
                                onSurfaceReady(true)
                                
                                // 启动摄像头预览
                                scope.launch {
                                    try {
                                        Log.d("CameraPreviewSurface", "开始启动摄像头预览")
                                        val success = cameraService.startCamera(holder.surface)
                                        if (success) {
                                            Log.i("CameraPreviewSurface", "✅ 摄像头预览启动成功")
                                        } else {
                                            Log.e("CameraPreviewSurface", "❌ 摄像头预览启动失败")
                                            surfaceReady = false
                                        }
                                    } catch (e: Exception) {
                                        Log.e("CameraPreviewSurface", "摄像头预览异常: ${e.message}", e)
                                        surfaceReady = false
                                    }
                                }
                            }
                            
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                Log.i("CameraPreviewSurface", "Surface尺寸变化: ${width}x${height}, 格式: $format")
                                surfaceSize = "${width}x${height}"
                            }
                            
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                Log.i("CameraPreviewSurface", "Surface已销毁")
                                surfaceReady = false
                                cameraService.stopCamera()
                            }
                        })
                        
                        // 强制布局
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) { surfaceView ->
                // 避免在每次重组时反复设置，降低卡顿
                // 预览尺寸已在surfaceCreated中固定为1280x720
            }
        }
        
        // 预览控制按钮
        Button(
            onClick = {
                Log.d("CameraPreviewSurface", "手动捕获图像")
                cameraService.captureImage()
            },
            modifier = Modifier.fillMaxWidth()
            // 暂时移除enabled限制来测试捕获功能
        ) {
            Text("📸 捕获")
        }
    }
}
