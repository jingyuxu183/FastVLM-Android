package com.example.fastvlm.camera

import android.content.Context
import android.hardware.camera2.*
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class RealCameraFinder(private val context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val TAG = "RealCameraFinder"

    suspend fun findRealCamera(): String? {
        Log.i(TAG, "=== 开始寻找真实可用的摄像头 ===")
        
        try {
            val cameraIds = cameraManager.cameraIdList
            Log.i(TAG, "检测到 ${cameraIds.size} 个摄像头: ${cameraIds.joinToString(", ")}")
            
            for (cameraId in cameraIds) {
                Log.i(TAG, "测试摄像头 ID: $cameraId")
                
                try {
                    val isReal = testCameraReal(cameraId)
                    if (isReal) {
                        Log.i(TAG, "🎉 找到真实摄像头: ID $cameraId")
                        return cameraId
                    } else {
                        Log.i(TAG, "❌ 摄像头 $cameraId 无法实际使用")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "测试摄像头 $cameraId 时出错: ${e.message}")
                }
            }
            
            Log.w(TAG, "未找到可用的真实摄像头")
            return null
            
        } catch (e: Exception) {
            Log.e(TAG, "查找摄像头过程出错: ${e.message}")
            return null
        }
    }
    
    private suspend fun testCameraReal(cameraId: String): Boolean = suspendCancellableCoroutine { continuation ->
        var resumed = false
        
        val stateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.i(TAG, "摄像头 $cameraId 成功打开")
                camera.close()
                if (!resumed) {
                    resumed = true
                    continuation.resume(true)
                }
            }
            
            override fun onDisconnected(camera: CameraDevice) {
                Log.i(TAG, "摄像头 $cameraId 断开连接")
                camera.close()
                if (!resumed) {
                    resumed = true
                    continuation.resume(false)
                }
            }
            
            override fun onError(camera: CameraDevice, error: Int) {
                Log.w(TAG, "摄像头 $cameraId 打开失败，错误码: $error")
                camera.close()
                if (!resumed) {
                    resumed = true
                    continuation.resume(false)
                }
            }
        }
        
        try {
            Log.i(TAG, "尝试打开摄像头 $cameraId...")
            cameraManager.openCamera(cameraId, stateCallback, null)
            
            continuation.invokeOnCancellation {
                Log.i(TAG, "取消摄像头 $cameraId 测试")
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "无法打开摄像头 $cameraId: ${e.message}")
            if (!resumed) {
                resumed = true
                continuation.resume(false)
            }
        }
    }
    
    fun logCameraInfo(cameraId: String) {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            Log.i(TAG, "=== 摄像头 $cameraId 详细信息 ===")
            Log.i(TAG, "朝向: ${characteristics.get(CameraCharacteristics.LENS_FACING)}")
            Log.i(TAG, "方向: ${characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)}")
            Log.i(TAG, "硬件级别: ${characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)}")
            
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            Log.i(TAG, "传感器尺寸: ${sensorSize?.width()}x${sensorSize?.height()}")
            
        } catch (e: Exception) {
            Log.w(TAG, "获取摄像头 $cameraId 信息失败: ${e.message}")
        }
    }
}
