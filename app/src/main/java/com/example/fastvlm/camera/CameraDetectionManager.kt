package com.example.fastvlm.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Log
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 8295车机摄像头检测管理器
 * 负责识别21个摄像头设备中的行车记录仪摄像头
 */
class CameraDetectionManager(private val context: Context) {
    
    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    
    companion object {
        private const val TAG = "CameraDetectionManager"
    }
    
    data class CameraInfo(
        val cameraId: String,
        val facing: Int,
        val outputSizes: List<Size>,
        val supportedFps: IntArray,
        val isBackCamera: Boolean,
        val isFrontCamera: Boolean,
        val isExternal: Boolean,
        val description: String,
        val priority: Int // 0最高优先级，越大优先级越低
    )
    
    /**
     * 检测所有可用摄像头
     */
    suspend fun detectAllCameras(): List<CameraInfo> = withContext(Dispatchers.IO) {
        val cameraInfoList = mutableListOf<CameraInfo>()
        
        try {
            Log.i(TAG, "=== 开始检测8295车机摄像头设备 ===")
            val cameraIds = cameraManager.cameraIdList
            Log.i(TAG, "发现 ${cameraIds.size} 个摄像头设备")
            
            for (cameraId in cameraIds) {
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val cameraInfo = analyzeCameraCharacteristics(cameraId, characteristics)
                    cameraInfoList.add(cameraInfo)
                    
                    Log.i(TAG, "📷 Camera $cameraId: ${cameraInfo.description} (优先级: ${cameraInfo.priority})")
                    
                } catch (e: Exception) {
                    Log.w(TAG, "分析摄像头 $cameraId 失败: ${e.message}")
                }
            }
            
            // 按优先级排序
            cameraInfoList.sortBy { it.priority }
            
            Log.i(TAG, "=== 摄像头检测完成，推荐使用顺序 ===")
            cameraInfoList.forEachIndexed { index, info ->
                Log.i(TAG, "${index + 1}. Camera ${info.cameraId}: ${info.description}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "摄像头检测失败: ${e.message}", e)
        }
        
        cameraInfoList
    }
    
    /**
     * 分析摄像头特征
     */
    private fun analyzeCameraCharacteristics(cameraId: String, characteristics: CameraCharacteristics): CameraInfo {
        // 基本信息
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraMetadata.LENS_FACING_EXTERNAL
        val isBackCamera = facing == CameraMetadata.LENS_FACING_BACK
        val isFrontCamera = facing == CameraMetadata.LENS_FACING_FRONT
        val isExternal = facing == CameraMetadata.LENS_FACING_EXTERNAL
        
        // 输出尺寸
        val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSizes = streamConfigMap?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.toList() ?: emptyList()
        
        // FPS范围
        val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val supportedFps = fpsRanges.flatMap { listOf(it.lower, it.upper) }.distinct().toIntArray()
        
        // 车机摄像头识别逻辑
        var description = "未知摄像头"
        var priority = 999 // 默认最低优先级
        
        when {
            // 判断是否为行车记录仪前摄像头 (最高优先级)
            isBackCamera && outputSizes.any { it.width >= 1920 && it.height >= 1080 } -> {
                description = "🎯 行车记录仪前摄像头 (推荐)"
                priority = 0
            }
            
            // 判断是否为车内摄像头
            isFrontCamera && outputSizes.any { it.width >= 1280 } -> {
                description = "👥 车内监控摄像头"
                priority = 1
            }
            
            // 外部摄像头可能是行车记录仪
            isExternal && outputSizes.any { it.width >= 1920 } -> {
                description = "🚗 外部摄像头 (可能是行车记录仪)"
                priority = 2
            }
            
            // 高分辨率后摄像头
            isBackCamera && outputSizes.any { it.width >= 1280 } -> {
                description = "📹 后摄像头 (中等优先级)"
                priority = 3
            }
            
            // 其他摄像头
            outputSizes.isNotEmpty() -> {
                val maxSize = outputSizes.maxByOrNull { it.width * it.height }
                description = "📷 ${getFacingName(facing)}摄像头 (${maxSize?.width}x${maxSize?.height})"
                priority = 10
            }
            
            else -> {
                description = "❓ 无效摄像头设备"
                priority = 999
            }
        }
        
        return CameraInfo(
            cameraId = cameraId,
            facing = facing,
            outputSizes = outputSizes,
            supportedFps = supportedFps,
            isBackCamera = isBackCamera,
            isFrontCamera = isFrontCamera,
            isExternal = isExternal,
            description = description,
            priority = priority
        )
    }
    
    /**
     * 获取推荐的行车记录仪摄像头
     */
    suspend fun getRecommendedDashCam(): CameraInfo? {
        val allCameras = detectAllCameras()
        // 先尝试最高优先级，如果失败则尝试其他摄像头
        return allCameras.firstOrNull { it.priority <= 2 } // 只返回高优先级摄像头
    }
    
    /**
     * 获取备选摄像头列表（用于故障恢复）
     */
    suspend fun getAlternativeCameras(): List<CameraInfo> {
        val allCameras = detectAllCameras()
        
        // 🎯 强制优先使用摄像头19 (已从原生应用接管)
        val camera19 = allCameras.find { it.cameraId == "19" }
        if (camera19 != null) {
            Log.i(TAG, "🎯 强制使用摄像头19 (从原生应用接管)")
            return listOf(camera19)
        }
        
        return allCameras.filter { it.priority <= 2 }.take(3) // 返回前3个高优先级摄像头
    }
    
    /**
     * 获取最佳视频分辨率
     */
    fun getBestVideoSize(cameraInfo: CameraInfo): Size? {
        return cameraInfo.outputSizes
            .filter { it.width <= 1920 && it.height <= 1080 } // 不超过1080p
            .maxByOrNull { it.width * it.height } // 选择最大分辨率
    }
    
    /**
     * 获取朝向名称
     */
    private fun getFacingName(facing: Int): String {
        return when (facing) {
            CameraMetadata.LENS_FACING_FRONT -> "前置"
            CameraMetadata.LENS_FACING_BACK -> "后置"
            CameraMetadata.LENS_FACING_EXTERNAL -> "外部"
            else -> "未知"
        }
    }
    
    /**
     * 检查摄像头权限
     */
    fun checkCameraPermission(): Boolean {
        return try {
            cameraManager.cameraIdList.isNotEmpty()
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "摄像头权限不足: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "检查摄像头权限失败: ${e.message}")
            false
        }
    }
}
