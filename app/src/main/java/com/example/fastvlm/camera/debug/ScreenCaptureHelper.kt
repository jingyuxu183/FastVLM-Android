package com.example.fastvlm.camera

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * 屏幕截取助手 - 用于截取原生摄像头应用画面并嵌入APP内显示
 * 解决8295车机摄像头黑屏问题的核心方案
 */
class ScreenCaptureHelper(private val activity: ComponentActivity) {
    
    companion object {
        private const val TAG = "ScreenCaptureHelper"
        private const val CAPTURE_WIDTH = 640
        private const val CAPTURE_HEIGHT = 480
    }
    
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    // 回调接口
    var onCaptureReady: ((Boolean) -> Unit)? = null
    var onImageCaptured: ((Bitmap) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    // 权限请求启动器
    private val mediaProjectionLauncher: ActivityResultLauncher<Intent> = 
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Log.i(TAG, "✅ 屏幕录制权限已获得")
                result.data?.let { data ->
                    startScreenCapture(data)
                }
            } else {
                Log.e(TAG, "❌ 屏幕录制权限被拒绝")
                onError?.invoke("屏幕录制权限被拒绝")
            }
        }
    
    /**
     * 初始化屏幕截取功能
     */
    fun initialize() {
        try {
            Log.i(TAG, "=== 初始化屏幕截取助手 ===")
            
            mediaProjectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) 
                as MediaProjectionManager
            
            startBackgroundThread()
            setupImageReader()
            
            Log.i(TAG, "✅ 屏幕截取助手初始化完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "初始化屏幕截取失败: ${e.message}", e)
            onError?.invoke("初始化失败: ${e.message}")
        }
    }
    
    /**
     * 请求屏幕录制权限并开始截取
     */
    fun requestScreenCapture() {
        try {
            Log.i(TAG, "请求屏幕录制权限")
            val captureIntent = mediaProjectionManager?.createScreenCaptureIntent()
            if (captureIntent != null) {
                mediaProjectionLauncher.launch(captureIntent)
            } else {
                onError?.invoke("无法创建屏幕录制意图")
            }
        } catch (e: Exception) {
            Log.e(TAG, "请求屏幕录制权限失败: ${e.message}", e)
            onError?.invoke("权限请求失败: ${e.message}")
        }
    }
    
    /**
     * 开始屏幕截取
     */
    private fun startScreenCapture(data: Intent) {
        try {
            Log.i(TAG, "开始屏幕截取")
            
            mediaProjection = mediaProjectionManager?.getMediaProjection(Activity.RESULT_OK, data)
            
            if (mediaProjection == null) {
                onError?.invoke("无法创建MediaProjection")
                return
            }
            
            createVirtualDisplay()
            
        } catch (e: Exception) {
            Log.e(TAG, "开始屏幕截取失败: ${e.message}", e)
            onError?.invoke("截取失败: ${e.message}")
        }
    }
    
    /**
     * 创建虚拟显示器
     */
    private fun createVirtualDisplay() {
        try {
            Log.i(TAG, "创建虚拟显示器用于屏幕截取")
            
            val displayMetrics = DisplayMetrics()
            activity.windowManager.defaultDisplay.getMetrics(displayMetrics)
            
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                CAPTURE_WIDTH,
                CAPTURE_HEIGHT,
                displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                backgroundHandler
            )
            
            if (virtualDisplay != null) {
                Log.i(TAG, "✅ 虚拟显示器创建成功")
                onCaptureReady?.invoke(true)
            } else {
                Log.e(TAG, "❌ 虚拟显示器创建失败")
                onCaptureReady?.invoke(false)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "创建虚拟显示器失败: ${e.message}", e)
            onCaptureReady?.invoke(false)
        }
    }
    
    /**
     * 设置图像读取器
     */
    private fun setupImageReader() {
        Log.i(TAG, "设置屏幕截取图像读取器")
        
        imageReader = ImageReader.newInstance(
            CAPTURE_WIDTH, 
            CAPTURE_HEIGHT, 
            PixelFormat.RGBA_8888, 
            2
        )
        
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                processScreenshotImage(image)
                image.close()
            }
        }, backgroundHandler)
    }
    
    /**
     * 处理截取的屏幕图像
     */
    private fun processScreenshotImage(image: Image) {
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * CAPTURE_WIDTH
            
            // 创建Bitmap
            val bitmap = Bitmap.createBitmap(
                CAPTURE_WIDTH + rowPadding / pixelStride,
                CAPTURE_HEIGHT,
                Bitmap.Config.ARGB_8888
            )
            
            bitmap.copyPixelsFromBuffer(buffer)
            
            // 裁剪到正确尺寸
            val croppedBitmap = Bitmap.createBitmap(
                bitmap, 
                0, 
                0, 
                CAPTURE_WIDTH, 
                CAPTURE_HEIGHT
            )
            
            Log.v(TAG, "📸 截取到屏幕画面: ${croppedBitmap.width}x${croppedBitmap.height}")
            onImageCaptured?.invoke(croppedBitmap)
            
            // 回收资源
            if (bitmap != croppedBitmap) {
                bitmap.recycle()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理屏幕截图失败: ${e.message}", e)
        }
    }
    
    /**
     * 手动触发单次截图
     */
    fun captureScreenshot(): Boolean {
        return try {
            if (virtualDisplay == null) {
                Log.w(TAG, "虚拟显示器未就绪，无法截图")
                return false
            }
            
            Log.d(TAG, "手动触发屏幕截图")
            // MediaProjection会持续截取，这里只是记录日志
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "手动截图失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 停止屏幕截取
     */
    fun stopScreenCapture() {
        try {
            Log.i(TAG, "停止屏幕截取")
            
            virtualDisplay?.release()
            virtualDisplay = null
            
            mediaProjection?.stop()
            mediaProjection = null
            
            imageReader?.close()
            imageReader = null
            
            stopBackgroundThread()
            
            Log.i(TAG, "✅ 屏幕截取已停止")
            
        } catch (e: Exception) {
            Log.e(TAG, "停止屏幕截取失败: ${e.message}", e)
        }
    }
    
    /**
     * 启动后台线程
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("ScreenCapture").apply {
            start()
            backgroundHandler = Handler(looper)
        }
        Log.d(TAG, "屏幕截取后台线程已启动")
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
            Log.d(TAG, "屏幕截取后台线程已停止")
        } catch (e: InterruptedException) {
            Log.e(TAG, "停止后台线程失败: ${e.message}")
        }
    }
    
    /**
     * 检查是否正在截取
     */
    fun isCapturing(): Boolean {
        return virtualDisplay != null && mediaProjection != null
    }
    
    /**
     * 获取截取状态信息
     */
    fun getCaptureStatus(): String {
        return when {
            isCapturing() -> "✅ 正在截取屏幕 (${CAPTURE_WIDTH}x${CAPTURE_HEIGHT})"
            mediaProjection != null -> "⏸️ MediaProjection已准备"
            else -> "❌ 未开始截取"
        }
    }
}
