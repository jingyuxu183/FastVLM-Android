#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
8295车机ATR摄像头视频流分析脚本
分析行车记录仪摄像头数据流架构: Linux/QNX → Android
"""

import subprocess
import json
import os
import sys
import re

class VehicleVideoStreamAnalyzer:
    def __init__(self):
        self.adb_available = self.check_adb_connection()
        self.results = {
            'linux_video_devices': [],
            'android_camera_devices': [],
            'video_streams': [],
            'recommended_interfaces': []
        }

    def check_adb_connection(self):
        """检查ADB连接状态"""
        try:
            result = subprocess.run(['adb', 'devices'], capture_output=True, text=True)
            if 'device' in result.stdout and 'List of devices' in result.stdout:
                print("✅ ADB连接正常")
                return True
            else:
                print("❌ 未检测到车机设备，请确保USB调试已开启")
                return False
        except FileNotFoundError:
            print("❌ ADB工具未找到，请安装Android SDK")
            return False

    def analyze_linux_video_devices(self):
        """分析Linux层视频设备"""
        if not self.adb_available:
            return
        
        print("\n🔍 分析Linux层视频设备...")
        
        # 检查/dev/video*设备
        try:
            result = subprocess.run([
                'adb', 'shell', 'ls -la /dev/video*'
            ], capture_output=True, text=True)
            
            if result.returncode == 0:
                video_devices = result.stdout.strip().split('\n')
                for device in video_devices:
                    if '/dev/video' in device:
                        self.results['linux_video_devices'].append({
                            'device': device.split()[-1],
                            'permissions': device.split()[0],
                            'details': device
                        })
                        print(f"  📹 发现视频设备: {device.split()[-1]}")
        except Exception as e:
            print(f"  ⚠️ 检查Linux视频设备失败: {e}")

        # 检查camera相关设备
        try:
            result = subprocess.run([
                'adb', 'shell', 'find /dev -name "*cam*" -o -name "*video*" 2>/dev/null'
            ], capture_output=True, text=True)
            
            if result.returncode == 0:
                for device in result.stdout.strip().split('\n'):
                    if device and '/dev/' in device:
                        print(f"  📷 相关设备: {device}")
        except Exception as e:
            print(f"  ⚠️ 检查相关设备失败: {e}")

    def analyze_android_camera_interface(self):
        """分析Android Camera API接口"""
        if not self.adb_available:
            return
            
        print("\n🔍 分析Android Camera接口...")
        
        # 检查Camera2 API可用摄像头
        camera_script = '''
        import android.content.Context;
        import android.hardware.camera2.CameraManager;
        import android.hardware.camera2.CameraCharacteristics;
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String[] cameraIds = manager.getCameraIdList();
            for (String cameraId : cameraIds) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                System.out.println("Camera ID: " + cameraId + ", Facing: " + facing);
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
        '''
        
        # 使用dumpsys检查camera服务
        try:
            result = subprocess.run([
                'adb', 'shell', 'dumpsys media.camera'
            ], capture_output=True, text=True)
            
            if result.returncode == 0:
                camera_info = result.stdout
                # 解析摄像头信息
                camera_ids = re.findall(r'Camera (\d+)', camera_info)
                for camera_id in camera_ids:
                    self.results['android_camera_devices'].append({
                        'camera_id': camera_id,
                        'status': 'available'
                    })
                    print(f"  📱 Android Camera ID: {camera_id}")
                    
        except Exception as e:
            print(f"  ⚠️ 检查Android Camera失败: {e}")

    def analyze_atr_interface(self):
        """分析ATR行车记录仪接口"""
        print("\n🔍 分析ATR行车记录仪接口...")
        
        if not self.adb_available:
            return
            
        # 检查ATR相关属性
        atr_properties = [
            'ro.vendor.camera.atr',
            'persist.vendor.camera.atr',
            'vendor.camera.atr.enable',
            'ro.hardware.camera.atr'
        ]
        
        for prop in atr_properties:
            try:
                result = subprocess.run([
                    'adb', 'shell', f'getprop {prop}'
                ], capture_output=True, text=True)
                
                value = result.stdout.strip()
                if value:
                    print(f"  🎯 ATR属性 {prop}: {value}")
                    
            except Exception as e:
                print(f"  ⚠️ 检查属性 {prop} 失败: {e}")

        # 检查可能的ATR设备节点
        atr_paths = [
            '/dev/atr',
            '/dev/atr0',
            '/dev/atr_camera',
            '/sys/class/video4linux/*/name'
        ]
        
        for path in atr_paths:
            try:
                result = subprocess.run([
                    'adb', 'shell', f'ls -la {path} 2>/dev/null'
                ], capture_output=True, text=True)
                
                if result.returncode == 0 and result.stdout.strip():
                    print(f"  📡 ATR设备路径: {path}")
                    print(f"     详情: {result.stdout.strip()}")
                    
            except Exception as e:
                continue

    def check_hal_camera_interface(self):
        """检查HAL Camera接口"""
        print("\n🔍 检查HAL Camera接口...")
        
        if not self.adb_available:
            return
            
        # 检查Camera HAL库
        hal_paths = [
            '/vendor/lib64/hw/camera*',
            '/vendor/lib/hw/camera*',
            '/system/lib64/hw/camera*'
        ]
        
        for path in hal_paths:
            try:
                result = subprocess.run([
                    'adb', 'shell', f'ls {path} 2>/dev/null'
                ], capture_output=True, text=True)
                
                if result.returncode == 0:
                    for lib in result.stdout.strip().split('\n'):
                        if lib and 'camera' in lib:
                            print(f"  🔧 HAL库: {lib}")
                            
            except Exception as e:
                continue

    def generate_recommendations(self):
        """生成视频流接入建议"""
        print("\n💡 视频流接入建议:")
        
        recommendations = []
        
        # 基于发现的设备给出建议
        if self.results['linux_video_devices']:
            recommendations.append({
                'interface': 'V4L2 (Video4Linux2)',
                'description': '通过Linux视频设备接口直接访问',
                'implementation': 'Camera2 API + Native V4L2',
                'priority': 'High'
            })
        
        if self.results['android_camera_devices']:
            recommendations.append({
                'interface': 'Android Camera2 API',
                'description': '使用Android标准Camera接口',
                'implementation': 'CameraManager + ImageReader',
                'priority': 'Medium'
            })
        
        # 默认推荐方案
        recommendations.append({
            'interface': 'MediaProjection API',
            'description': '屏幕录制方式获取视频内容',
            'implementation': 'MediaProjectionManager + VirtualDisplay',
            'priority': 'Fallback'
        })
        
        recommendations.append({
            'interface': 'SurfaceView + Camera Preview',
            'description': '相机预览界面获取帧数据',
            'implementation': 'SurfaceView + TextureView',
            'priority': 'Alternative'
        })
        
        self.results['recommended_interfaces'] = recommendations
        
        for i, rec in enumerate(recommendations, 1):
            print(f"  {i}. {rec['interface']} ({rec['priority']})")
            print(f"     描述: {rec['description']}")
            print(f"     实现: {rec['implementation']}")
            print()

    def generate_implementation_script(self):
        """生成实现脚本"""
        print("📝 生成视频流检测脚本...")
        
        import datetime
        script_content = f'''#!/bin/bash
# 8295车机视频流检测脚本
# 生成时间: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}

echo "=== 8295车机视频流分析 ==="

# 1. 检查Linux视频设备
echo "1. Linux视频设备:"
adb shell "ls -la /dev/video* 2>/dev/null || echo '未发现/dev/video设备'"

# 2. 检查Camera相关设备  
echo "2. Camera设备:"
adb shell "find /dev -name '*cam*' -o -name '*video*' 2>/dev/null"

# 3. 检查Android Camera服务
echo "3. Android Camera状态:"
adb shell "dumpsys media.camera | grep -E 'Camera|State|Device'"

# 4. 检查ATR属性
echo "4. ATR相关属性:"
adb shell "getprop | grep -i atr"

# 5. 检查HAL Camera库
echo "5. Camera HAL库:"
adb shell "ls /vendor/lib*/hw/camera* 2>/dev/null"

# 6. 检查视频相关进程
echo "6. 视频相关进程:"
adb shell "ps | grep -E 'camera|video|atr'"

echo "=== 分析完成 ==="
'''
        
        with open('check_video_stream.sh', 'w', encoding='utf-8') as f:
            f.write(script_content)
            
        os.chmod('check_video_stream.sh', 0o755)
        print("✅ 已生成 check_video_stream.sh 脚本")

    def save_results(self):
        """保存分析结果"""
        with open('vehicle_video_analysis.json', 'w', encoding='utf-8') as f:
            json.dump(self.results, f, ensure_ascii=False, indent=2)
        print("📊 分析结果已保存到 vehicle_video_analysis.json")

    def run_analysis(self):
        """执行完整分析"""
        print("🚗 开始分析8295车机ATR视频流架构")
        print("=" * 50)
        
        self.analyze_linux_video_devices()
        self.analyze_android_camera_interface()
        self.analyze_atr_interface()
        self.check_hal_camera_interface()
        self.generate_recommendations()
        self.generate_implementation_script()
        self.save_results()
        
        print("\n🎉 分析完成！")
        print("📋 后续步骤:")
        print("  1. 运行 check_video_stream.sh 获取详细设备信息")
        print("  2. 根据 vehicle_video_analysis.json 选择最佳接入方案")
        print("  3. 实现 Android 应用的视频流接入功能")

if __name__ == "__main__":
    analyzer = VehicleVideoStreamAnalyzer()
    analyzer.run_analysis()
