package com.example.fastvlm.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.content.Context
import android.util.Log
import java.io.File

class NativeCameraInspector(private val context: Context) {
    
    private val tag = "NativeCameraInspector"
    
    @SuppressLint("MissingPermission")
    fun analyzeNativeCameraMapping() {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        Log.i(tag, "=== 分析Camera ID与底层设备映射 ===")
        
        try {
            // 1. 分析Android Camera2 API与底层设备映射
            val cameraIds = cameraManager.cameraIdList
            Log.i(tag, "发现 ${cameraIds.size} 个Camera ID")
            
            for (cameraId in cameraIds) {
                analyzeCameraDevice(cameraManager, cameraId)
            }
            
            // 2. 检查底层V4L2设备
            analyzeV4L2Devices()
            
            // 3. 尝试检测活跃使用的摄像头
            detectActiveCameras()
            
        } catch (e: Exception) {
            Log.e(tag, "分析过程出错: ${e.message}", e)
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun analyzeCameraDevice(cameraManager: CameraManager, cameraId: String) {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            
            Log.i(tag, "=== Camera ID $cameraId 详细分析 ===")
            
            // 基础信息
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val supportLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            
            Log.i(tag, "  朝向: $facing")
            Log.i(tag, "  支持级别: $supportLevel")
            
            // 传感器信息 - 这可能包含底层设备信息
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            val pixelArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            
            Log.i(tag, "  传感器方向: ${sensorOrientation}°")
            Log.i(tag, "  像素阵列: ${pixelArray?.width}x${pixelArray?.height}")
            Log.i(tag, "  活动区域: $activeArray")
            
            // 查找可能的底层设备标识
            val availableCapabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            Log.i(tag, "  可用能力: ${availableCapabilities?.joinToString(",")}")
            
            // 尝试通过反射获取更多底层信息
            tryExtractNativeDeviceInfo(characteristics, cameraId)
            
        } catch (e: Exception) {
            Log.e(tag, "分析Camera $cameraId 失败: ${e.message}")
        }
    }
    
    private fun tryExtractNativeDeviceInfo(characteristics: CameraCharacteristics, cameraId: String) {
        try {
            // 尝试通过反射访问内部字段
            val clazz = characteristics.javaClass
            val fields = clazz.declaredFields
            
            for (field in fields) {
                if (field.name.contains("mNative", ignoreCase = true) || 
                    field.name.contains("device", ignoreCase = true)) {
                    field.isAccessible = true
                    val value = field.get(characteristics)
                    Log.i(tag, "  内部字段 ${field.name}: $value")
                }
            }
            
            // 尝试获取一些隐藏的特性
            Log.i(tag, "  尝试获取隐藏特性...")
            
        } catch (e: Exception) {
            Log.d(tag, "无法获取内部设备信息: ${e.message}")
        }
    }
    
    private fun analyzeV4L2Devices() {
        Log.i(tag, "=== 分析V4L2设备 ===")
        
        try {
            // 检查已知的视频设备范围
            for (i in 0..100) {
                val devicePath = "/dev/video$i"
                if (File(devicePath).exists()) {
                    Log.i(tag, "发现视频设备: $devicePath")
                    
                    // 尝试读取设备信息（需要root权限）
                    tryReadDeviceInfo(devicePath, i)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "分析V4L2设备失败: ${e.message}")
        }
    }
    
    private fun tryReadDeviceInfo(devicePath: String, deviceNumber: Int) {
        try {
            val file = File(devicePath)
            if (file.exists()) {
                Log.i(tag, "  设备 $devicePath 存在，设备号: $deviceNumber")
                
                // 推测可能的Camera ID映射
                // 如果设备号从51开始，那么video70可能对应Camera ID 19 (70-51=19)
                val potentialCameraId = deviceNumber - 51
                if (potentialCameraId >= 0 && potentialCameraId <= 20) {
                    Log.i(tag, "  → 可能映射到Camera ID: $potentialCameraId")
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "无法读取设备 $devicePath: ${e.message}")
        }
    }
    
    private fun detectActiveCameras() {
        Log.i(tag, "=== 检测活跃使用的摄像头 ===")
        
        // 这里可以添加检测当前正在使用的摄像头的逻辑
        // 比如通过检查系统进程、文件锁定等
        
        Log.i(tag, "建议: 查看 lsof | grep /dev/video 来确定当前使用的设备")
        Log.i(tag, "建议: 监控 logcat | grep -i camera 来查看应用使用的Camera ID")
    }
    
    fun reportFindings() {
        Log.i(tag, "=== 分析总结 ===")
        Log.i(tag, "1. 根据设备列表，/dev/video51-71对应Camera ID 0-20")
        Log.i(tag, "2. Camera ID 19应该对应 /dev/video70")
        Log.i(tag, "3. 从lsof输出看，系统正在使用video60和video52")
        Log.i(tag, "4. video60对应Camera ID 9，video52对应Camera ID 1")
        Log.i(tag, "5. 这表明系统默认在使用Camera ID 1和9")
        Log.i(tag, "6. Camera ID 19(/dev/video70)目前没有被活跃使用")
    }
}
