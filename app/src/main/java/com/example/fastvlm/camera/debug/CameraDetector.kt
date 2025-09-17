package com.example.fastvlm.camera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * 专用ATR摄像头检测器
 * 快速检测车机系统中所有可用的摄像头ID
 */
class CameraDetector(private val context: Context) {
    
    companion object {
        private const val TAG = "CameraDetector"
    }
    
    data class CameraInfo(
        val id: String,
        val facing: Int,
        val available: Boolean,
        val supportLevel: Int,
        val errorMessage: String? = null
    )
    
    /**
     * 快速检测所有摄像头
     */
    fun detectAllCameras(): List<CameraInfo> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val results = mutableListOf<CameraInfo>()
        
        Log.i(TAG, "=== 开始检测车机摄像头 ===")
        
        try {
            // 获取所有摄像头ID列表
            val cameraIds = cameraManager.cameraIdList
            Log.i(TAG, "发现摄像头数量: ${cameraIds.size}")
            Log.i(TAG, "摄像头ID列表: ${cameraIds.joinToString(", ")}")
            
            // 逐个检测每个摄像头
            for (cameraId in cameraIds) {
                val info = detectSingleCamera(cameraManager, cameraId)
                results.add(info)
                
                // 立即输出结果
                logCameraInfo(info)
            }
            
            // 特别检测ID 19 (您提到的ATR摄像头)
            if (!cameraIds.contains("19")) {
                Log.w(TAG, "⚠️ 摄像头ID 19 不在系统列表中，尝试强制检测...")
                val info19 = detectSingleCamera(cameraManager, "19")
                results.add(info19)
                logCameraInfo(info19)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 检测过程出错: ${e.message}", e)
        }
        
        // 输出总结
        printSummary(results)
        
        return results
    }
    
    /**
     * 检测单个摄像头
     */
    private fun detectSingleCamera(cameraManager: CameraManager, cameraId: String): CameraInfo {
        return try {
            Log.d(TAG, "检测摄像头 ID: $cameraId")
            
            // 获取摄像头特性
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: -1
            val supportLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
            
            // 尝试获取更多信息
            val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val hasStreamMap = streamMap != null
            
            Log.d(TAG, "✅ 摄像头 $cameraId - 朝向: $facing, 支持级别: $supportLevel, 流配置: $hasStreamMap")
            
            CameraInfo(
                id = cameraId,
                facing = facing,
                available = true,
                supportLevel = supportLevel
            )
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "❌ 摄像头 $cameraId 访问失败: ${e.message}")
            CameraInfo(
                id = cameraId,
                facing = -1,
                available = false,
                supportLevel = -1,
                errorMessage = e.message
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ 摄像头 $cameraId 检测异常: ${e.message}")
            CameraInfo(
                id = cameraId,
                facing = -1,
                available = false,
                supportLevel = -1,
                errorMessage = e.message
            )
        }
    }
    
    /**
     * 输出摄像头信息
     */
    private fun logCameraInfo(info: CameraInfo) {
        if (info.available) {
            val facingStr = when (info.facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                CameraCharacteristics.LENS_FACING_BACK -> "后置"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "外部"
                else -> "未知($info.facing)"
            }
            
            val levelStr = when (info.supportLevel) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level3"
                else -> "未知($info.supportLevel)"
            }
            
            Log.i(TAG, "✅ 摄像头 ${info.id}: ${facingStr}, ${levelStr}")
            
            // 特别标记ID 19
            if (info.id == "19") {
                Log.i(TAG, "🎯 找到目标ATR摄像头 ID 19!")
            }
        } else {
            Log.e(TAG, "❌ 摄像头 ${info.id}: ${info.errorMessage}")
        }
    }
    
    /**
     * 输出检测总结
     */
    private fun printSummary(results: List<CameraInfo>) {
        val available = results.filter { it.available }
        val unavailable = results.filter { !it.available }
        
        Log.i(TAG, "")
        Log.i(TAG, "=== 摄像头检测总结 ===")
        Log.i(TAG, "总计: ${results.size} 个摄像头")
        Log.i(TAG, "可用: ${available.size} 个")
        Log.i(TAG, "不可用: ${unavailable.size} 个")
        
        if (available.isNotEmpty()) {
            Log.i(TAG, "✅ 可用摄像头ID: ${available.map { it.id }.joinToString(", ")}")
        }
        
        if (unavailable.isNotEmpty()) {
            Log.i(TAG, "❌ 不可用摄像头ID: ${unavailable.map { it.id }.joinToString(", ")}")
        }
        
        // 检查ID 19状态
        val camera19 = results.find { it.id == "19" }
        if (camera19 != null) {
            if (camera19.available) {
                Log.i(TAG, "🎯 ATR摄像头 ID 19 状态: 可用 ✅")
            } else {
                Log.e(TAG, "🎯 ATR摄像头 ID 19 状态: 不可用 ❌ - ${camera19.errorMessage}")
            }
        } else {
            Log.w(TAG, "🎯 ATR摄像头 ID 19 状态: 未找到 ⚠️")
        }
        
        Log.i(TAG, "=== 检测完成 ===")
    }
    
    /**
     * 深度验证摄像头是否为真实物理摄像头
     */
    fun deepVerifyCamera(cameraId: String): CameraVerificationResult {
        Log.i(TAG, "=== 深度验证摄像头 $cameraId ===")
        
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            
            // 检查关键特性
            val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = streamMap?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
            val jpegSizes = streamMap?.getOutputSizes(android.graphics.ImageFormat.JPEG)
            
            // 检查传感器信息
            val sensorInfoActiveArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val sensorInfoPixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            
            // 检查可用流配置
            val availableStreamConfigs = streamMap?.outputFormats
            
            val hasRealSizes = outputSizes != null && outputSizes.isNotEmpty()
            val hasJpegOutput = jpegSizes != null && jpegSizes.isNotEmpty()
            val hasSensorInfo = sensorInfoActiveArraySize != null
            val hasValidOrientation = sensorOrientation != null
            
            Log.i(TAG, "摄像头 $cameraId 验证结果:")
            Log.i(TAG, "  • YUV输出尺寸: ${outputSizes?.size ?: 0}个")
            Log.i(TAG, "  • JPEG输出尺寸: ${jpegSizes?.size ?: 0}个")
            Log.i(TAG, "  • 传感器活动区域: $sensorInfoActiveArraySize")
            Log.i(TAG, "  • 传感器像素阵列: $sensorInfoPixelArraySize")
            Log.i(TAG, "  • 传感器方向: $sensorOrientation°")
            Log.i(TAG, "  • 可用格式: ${availableStreamConfigs?.joinToString(",") ?: "无"}")
            
            // 判断是否为真实摄像头
            val isLikelyReal = hasRealSizes && hasJpegOutput && hasSensorInfo && hasValidOrientation
            
            if (isLikelyReal) {
                Log.i(TAG, "✅ 摄像头 $cameraId 疑似真实物理摄像头")
            } else {
                Log.w(TAG, "⚠️ 摄像头 $cameraId 可能是虚拟摄像头")
            }
            
            CameraVerificationResult(
                cameraId = cameraId,
                isLikelyReal = isLikelyReal,
                outputSizes = outputSizes?.toList() ?: emptyList(),
                jpegSizes = jpegSizes?.toList() ?: emptyList(),
                sensorInfo = sensorInfoActiveArraySize?.toString() ?: "无",
                orientation = sensorOrientation ?: -1,
                availableFormats = availableStreamConfigs?.toList() ?: emptyList()
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "摄像头 $cameraId 深度验证失败: ${e.message}")
            CameraVerificationResult(
                cameraId = cameraId,
                isLikelyReal = false,
                errorMessage = e.message
            )
        }
    }
    
    /**
     * 专门测试摄像头ID 19
     */
    fun testCamera19(): Boolean {
        Log.i(TAG, "=== 专门测试ATR摄像头ID 19 ===")
        
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val info = detectSingleCamera(cameraManager, "19")
        
        if (info.available) {
            Log.i(TAG, "🎉 ATR摄像头ID 19基础检测成功!")
            
            // 深度验证
            val verification = deepVerifyCamera("19")
            return if (verification.isLikelyReal) {
                Log.i(TAG, "🎯 ATR摄像头ID 19深度验证: 真实物理摄像头!")
                true
            } else {
                Log.w(TAG, "⚠️ ATR摄像头ID 19深度验证: 可能是虚拟摄像头")
                false
            }
        } else {
            Log.e(TAG, "💥 ATR摄像头ID 19基础检测失败: ${info.errorMessage}")
            return false
        }
    }
    
    /**
     * 验证所有摄像头，找出真实的物理摄像头
     */
    fun findRealCameras(): List<CameraVerificationResult> {
        Log.i(TAG, "=== 查找真实物理摄像头 ===")
        
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val realCameras = mutableListOf<CameraVerificationResult>()
        
        try {
            val cameraIds = cameraManager.cameraIdList
            
            for (cameraId in cameraIds) {
                val verification = deepVerifyCamera(cameraId)
                if (verification.isLikelyReal) {
                    realCameras.add(verification)
                }
            }
            
            Log.i(TAG, "找到 ${realCameras.size} 个疑似真实摄像头:")
            realCameras.forEach { camera ->
                Log.i(TAG, "  • ID ${camera.cameraId}: ${camera.outputSizes.size}个输出尺寸")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "查找真实摄像头失败: ${e.message}")
        }
        
        return realCameras
    }
}

/**
 * 摄像头深度验证结果
 */
data class CameraVerificationResult(
    val cameraId: String,
    val isLikelyReal: Boolean,
    val outputSizes: List<android.util.Size> = emptyList(),
    val jpegSizes: List<android.util.Size> = emptyList(),
    val sensorInfo: String = "无",
    val orientation: Int = -1,
    val availableFormats: List<Int> = emptyList(),
    val errorMessage: String? = null
)
