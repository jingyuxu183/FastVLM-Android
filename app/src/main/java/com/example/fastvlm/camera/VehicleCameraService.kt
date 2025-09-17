package com.example.fastvlm.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 8295车机Camera2服务
 * 提供视频流预览和图像捕获功能
 */
class VehicleCameraService(private val context: Context) {
    
    private val cameraDetectionManager = CameraDetectionManager(context)
    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    
    // Camera2组件
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    
    // 后台线程
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    // 当前使用的摄像头信息
    private var currentCameraInfo: CameraDetectionManager.CameraInfo? = null
    
    // 回调接口
    var onImageCaptured: ((Bitmap) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    companion object {
        private const val TAG = "VehicleCameraService"
        private const val MAX_IMAGES = 2
    }
    
    /**
     * 启动Camera服务
     */
    suspend fun startCamera(previewSurface: Surface): Boolean = withContext(Dispatchers.Main) {
        try {
            Log.i(TAG, "=== 启动8295车机Camera服务 ===")
            
            // 检查权限
            if (!cameraDetectionManager.checkCameraPermission()) {
                onError?.invoke("摄像头权限不足")
                return@withContext false
            }
            
            // 启动后台线程
            startBackgroundThread()
            
            // 检测推荐摄像头，支持多个备选方案
            val cameras = cameraDetectionManager.getAlternativeCameras()
            if (cameras.isEmpty()) {
                onError?.invoke("未找到合适的行车记录仪摄像头")
                return@withContext false
            }
            
            this@VehicleCameraService.previewSurface = previewSurface
            
            // 尝试多个摄像头，直到找到工作的
            var success = false
            for ((index, camera) in cameras.withIndex()) {
                try {
                    Log.i(TAG, "尝试摄像头 ${index + 1}/${cameras.size}: ${camera.description}")
                    
                    currentCameraInfo = camera
                    
                    // 设置图像读取器 - 尝试较低分辨率提高兼容性
                    val bestSize = Size(1280, 720) // 固定使用720p提高兼容性
                    
                    setupImageReader(bestSize)
                    
                    // 打开摄像头
                    success = openCamera(camera.cameraId)
                    if (success) {
                        Log.i(TAG, "✅ 成功启用摄像头: ${camera.description}")
                        break
                    } else {
                        Log.w(TAG, "❌ 摄像头启动失败，尝试下一个")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "摄像头 ${camera.cameraId} 启动异常: ${e.message}")
                    // 清理并继续尝试下一个
                    stopCamera()
                }
            }
            if (success) {
                Log.i(TAG, "✅ 摄像头服务启动成功")
            } else {
                Log.e(TAG, "❌ 摄像头服务启动失败")
            }
            
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "启动摄像头服务失败: ${e.message}", e)
            onError?.invoke("启动摄像头失败: ${e.message}")
            false
        }
    }
    
    /**
     * 停止Camera服务
     */
    fun stopCamera() {
        try {
            Log.i(TAG, "停止摄像头服务")
            
            captureSession?.close()
            captureSession = null
            
            cameraDevice?.close()
            cameraDevice = null
            
            imageReader?.close()
            imageReader = null
            
            stopBackgroundThread()
            
            Log.i(TAG, "✅ 摄像头服务已停止")
            
        } catch (e: Exception) {
            Log.e(TAG, "停止摄像头服务失败: ${e.message}", e)
        }
    }
    
    /**
     * 捕获图像
     */
    fun captureImage() {
        try {
            Log.d(TAG, "开始捕获图像...")
            
            val session = captureSession
            val device = cameraDevice
            val reader = imageReader
            
            if (session == null || device == null || reader == null) {
                Log.w(TAG, "摄像头未就绪，无法捕获图像")
                return
            }
            
            // 创建捕获请求
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(reader.surface)
            
            // 设置自动对焦和曝光
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            
            // 执行捕获
            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.d(TAG, "✅ 图像捕获完成")
                }
                
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Log.e(TAG, "❌ 图像捕获失败: ${failure.reason}")
                }
            }, backgroundHandler)
            
        } catch (e: Exception) {
            Log.e(TAG, "捕获图像失败: ${e.message}", e)
            onError?.invoke("图像捕获失败: ${e.message}")
        }
    }
    
    /**
     * 打开摄像头
     */
    private suspend fun openCamera(cameraId: String): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            Log.i(TAG, "正在打开摄像头: $cameraId")
            
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.i(TAG, "✅ 摄像头已打开: $cameraId")
                    cameraDevice = camera
                    
                    // 创建预览会话
                    createCameraPreviewSession { success ->
                        if (continuation.isActive) {
                            continuation.resume(success)
                        }
                    }
                }
                
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "摄像头断开连接: $cameraId")
                    camera.close()
                    cameraDevice = null
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
                
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "摄像头错误: $cameraId, 错误码: $error")
                    camera.close()
                    cameraDevice = null
                    if (continuation.isActive) {
                        continuation.resumeWithException(Exception("摄像头错误: $error"))
                    }
                }
            }, backgroundHandler)
            
        } catch (e: SecurityException) {
            Log.e(TAG, "摄像头权限不足: ${e.message}")
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开摄像头失败: ${e.message}", e)
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }
    }
    
    /**
     * 创建预览会话
     */
    private fun createCameraPreviewSession(callback: (Boolean) -> Unit) {
        try {
            val device = cameraDevice
            val preview = previewSurface
            val reader = imageReader
            
            if (device == null || preview == null || reader == null) {
                Log.e(TAG, "创建预览会话失败：设备未就绪")
                callback(false)
                return
            }
            
            Log.i(TAG, "创建预览会话...")
            
            // 创建输出表面列表
            val surfaces = listOf(preview, reader.surface)
            
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) {
                        Log.w(TAG, "摄像头已关闭，无法配置会话")
                        callback(false)
                        return
                    }
                    
                    Log.i(TAG, "✅ 预览会话已配置")
                    captureSession = session
                    
                    try {
                        // 创建简化的预览请求，提高兼容性
                        val previewRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        previewRequest.addTarget(preview) // 只添加预览Surface，暂不添加ImageReader
                        
                        // 简化摄像头参数，提高兼容性
                        try {
                            previewRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                            previewRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        } catch (e: Exception) {
                            Log.w(TAG, "设置摄像头参数失败，使用默认配置: ${e.message}")
                        }
                        
                        // 开始预览
                        session.setRepeatingRequest(previewRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                // 预览帧处理成功
                                Log.v(TAG, "预览帧处理完成")
                            }
                            
                            override fun onCaptureFailed(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                failure: CaptureFailure
                            ) {
                                Log.w(TAG, "预览帧处理失败: ${failure.reason}")
                            }
                        }, backgroundHandler)
                        
                        Log.i(TAG, "✅ 预览已开始")
                        callback(true)
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "启动预览失败: ${e.message}", e)
                        callback(false)
                    }
                }
                
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "❌ 预览会话配置失败")
                    callback(false)
                }
            }, backgroundHandler)
            
        } catch (e: Exception) {
            Log.e(TAG, "创建预览会话异常: ${e.message}", e)
            callback(false)
        }
    }
    
    /**
     * 设置图像读取器
     */
    private fun setupImageReader(size: Size) {
        Log.i(TAG, "设置图像读取器: ${size.width}x${size.height}")
        
        imageReader?.close()
        // 使用JPEG格式，兼容性更好
        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, MAX_IMAGES)
        
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                Log.d(TAG, "📸 捕获到图像: ${image.width}x${image.height}, 格式: ${image.format}")
                processImage(image)
                image.close()
            }
        }, backgroundHandler)
    }
    
    /**
     * 处理捕获的图像
     */
    private fun processImage(image: Image) {
        try {
            Log.d(TAG, "处理图像: ${image.width}x${image.height}, 格式: ${image.format}")
            
            // 转换为Bitmap
            val bitmap = when (image.format) {
                ImageFormat.JPEG -> jpegToBitmap(image)
                ImageFormat.YUV_420_888 -> yuvToBitmap(image)
                else -> {
                    Log.w(TAG, "不支持的图像格式: ${image.format}")
                    null
                }
            }
            
            if (bitmap != null) {
                Log.d(TAG, "✅ 图像转换成功，大小: ${bitmap.width}x${bitmap.height}")
                onImageCaptured?.invoke(bitmap)
            } else {
                Log.w(TAG, "图像转换失败")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理图像失败: ${e.message}", e)
        }
    }
    
    /**
     * 将JPEG图像转换为Bitmap
     */
    private fun jpegToBitmap(image: Image): Bitmap? {
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            
        } catch (e: Exception) {
            Log.e(TAG, "JPEG转Bitmap失败: ${e.message}", e)
            return null
        }
    }
    
    /**
     * 将YUV图像转换为RGB Bitmap（简化版本）
     */
    private fun yuvToBitmap(image: Image): Bitmap? {
        try {
            // 这里使用简化的转换，实际项目中建议使用RenderScript
            // 或者Camera2 API的其他转换方法
            Log.w(TAG, "YUV转换暂未完全实现，返回占位Bitmap")
            return Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            
        } catch (e: Exception) {
            Log.e(TAG, "YUV转RGB失败: ${e.message}", e)
            return null
        }
    }
    
    /**
     * 启动后台线程
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply {
            start()
            backgroundHandler = Handler(looper)
        }
        Log.d(TAG, "后台线程已启动")
    }
    
    /**
     * 停止后台线程
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
            Log.d(TAG, "后台线程已停止")
        } catch (e: InterruptedException) {
            Log.e(TAG, "停止后台线程失败: ${e.message}")
        }
    }
    
    /**
     * 获取当前摄像头信息
     */
    fun getCurrentCameraInfo(): CameraDetectionManager.CameraInfo? = currentCameraInfo
}
