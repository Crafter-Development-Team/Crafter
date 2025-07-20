import bpy
import os
import subprocess
import json
import tempfile
import threading
import time
from bpy.props import *

# 地图选择器操作符
class VIEW3D_OT_CrafterMapSelector(bpy.types.Operator):
    bl_label = "打开选择器"
    bl_idname = "crafter.map_selector"
    bl_description = "打开可视化地图选择器来选择坐标"
    bl_options = {'REGISTER', 'UNDO'}
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        # 简化条件，让按钮总是可以点击
        return True

    def execute(self, context):
        self.report({'INFO'}, "启动地图选择器...")
        
        # 获取Crafter插件的preferences
        try:
            addon_prefs = None
            for addon_name in context.preferences.addons.keys():
                if "Crafter" in addon_name:
                    addon_prefs = context.preferences.addons[addon_name].preferences
                    break
            
            if not addon_prefs:
                self.report({'ERROR'}, "找不到Crafter插件设置")
                return {'CANCELLED'}
                
            world_path = getattr(addon_prefs, 'World_Path', None)
            
        except Exception as e:
            self.report({'ERROR'}, f"无法访问Crafter插件设置: {str(e)}")
            return {'CANCELLED'}
        
        # 检查世界路径
        if not world_path:
            self.report({'ERROR'}, "请先在上方输入框中设置世界路径")
            return {'CANCELLED'}
            
        if not os.path.exists(world_path):
            self.report({'ERROR'}, f"世界路径不存在: {world_path}")
            return {'CANCELLED'}
        
        self.report({'INFO'}, f"使用世界路径: {world_path}")

        # JAR文件路径 - 动态获取插件目录
        addon_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        jar_path = os.path.join(addon_dir, "importer", "minecraft-map-selector-1.0.0.jar")

        print(f"Debug: addon_dir = {addon_dir}")
        print(f"Debug: jar_path = {jar_path}")
        print(f"Debug: jar exists = {os.path.exists(jar_path)}")

        if not os.path.exists(jar_path):
            self.report({'ERROR'}, f"找不到地图选择器JAR文件: {jar_path}")
            return {'CANCELLED'}

        # 创建临时文件用于坐标通信
        temp_dir = tempfile.gettempdir()
        coord_file = os.path.join(temp_dir, "minecraft_coords.json")
        
        # 如果存在旧的坐标文件，删除它
        if os.path.exists(coord_file):
            try:
                os.remove(coord_file)
            except:
                pass
        
        # 启动地图选择器
        try:
            # 获取Y坐标范围
            xyz1 = getattr(addon_prefs, 'XYZ_1', (0, 0, 0))
            xyz2 = getattr(addon_prefs, 'XYZ_2', (0, 255, 0))
            
            # 计算Y坐标范围
            min_y = min(xyz1[1], xyz2[1])
            max_y = max(xyz1[1], xyz2[1])
            
            # 如果Y坐标范围无效，使用默认值
            if min_y == max_y:
                min_y = 0
                max_y = 255
            
            # 构建命令
            cmd = [
                "java", "-jar", jar_path,
                "--world-path", world_path,
                "--output-file", coord_file,
                "--min-y", str(min_y),
                "--max-y", str(max_y)
            ]
            
            print(f"传递Y坐标范围: {min_y} 到 {max_y}")
            
            self.report({'INFO'}, "正在启动地图选择器...")
            
            # 在后台线程中启动进程
            def run_map_selector():
                try:
                    process = subprocess.Popen(
                        cmd,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        cwd=os.path.dirname(jar_path)
                    )
                    
                    # 等待进程完成
                    _, stderr = process.communicate()
                    
                    if process.returncode == 0:
                        # 检查坐标文件是否存在
                        if os.path.exists(coord_file):
                            try:
                                with open(coord_file, 'r') as f:
                                    coords = json.load(f)
                                
                                # 更新Blender中的坐标
                                def update_coords():
                                    addon_prefs.XYZ_1 = (coords['minX'], coords['minY'], coords['minZ'])
                                    addon_prefs.XYZ_2 = (coords['maxX'], coords['maxY'], coords['maxZ'])
                                    
                                    # 强制刷新UI
                                    for area in bpy.context.screen.areas:
                                        area.tag_redraw()
                                    
                                    print(f"坐标已更新: XYZ_1={addon_prefs.XYZ_1}, XYZ_2={addon_prefs.XYZ_2}")
                                
                                # 在主线程中更新坐标
                                bpy.app.timers.register(update_coords, first_interval=0.1)
                                
                                # 清理临时文件
                                os.remove(coord_file)
                                
                            except Exception as e:
                                print(f"读取坐标文件时出错: {e}")
                        else:
                            print("地图选择器已关闭，未选择坐标")
                    else:
                        print(f"地图选择器启动失败，返回码: {process.returncode}")
                        if stderr:
                            print(f"错误信息: {stderr.decode()}")
                
                except Exception as e:
                    print(f"启动地图选择器时出错: {e}")
            
            # 启动后台线程
            thread = threading.Thread(target=run_map_selector)
            thread.daemon = True
            thread.start()
            
            return {'FINISHED'}
            
        except Exception as e:
            self.report({'ERROR'}, f"启动地图选择器失败: {str(e)}")
            return {'CANCELLED'}

# 注册类
classes = [
    VIEW3D_OT_CrafterMapSelector,
]

def register():
    for cls in classes:
        try:
            bpy.utils.register_class(cls)
        except ValueError:
            # 类已经注册，先注销再注册
            try:
                bpy.utils.unregister_class(cls)
                bpy.utils.register_class(cls)
            except:
                pass

def unregister():
    for cls in reversed(classes):
        try:
            bpy.utils.unregister_class(cls)
        except:
            pass

if __name__ == "__main__":
    register()
