import bpy
import os
import subprocess
import json
import tempfile
import threading
import time

from ..config import __addon_name__
from bpy.props import *
from .Defs import *

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
        
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        #获取世界路径，检测路径合法性
        bpy.ops.crafter.reload_all()
        bpy.ops.crafter.reload_resources()
        worldPath = os.path.normpath(addon_prefs.World_Path)
        dir_saves = os.path.dirname(worldPath)
        dir_level_dat = os.path.join(worldPath, "level.dat")
        if not os.path.exists(dir_level_dat):
            self.report({'ERROR'}, "It's not a world path!")
            return {"CANCELLED"}
        
        dir_jar_resource = ""
        addon_prefs.is_Game_Path = True
        #计算游戏文件路径
        dir_saves = os.path.dirname(worldPath)
        dir_back_saves = os.path.dirname(dir_saves)

        if not os.path.basename(dir_back_saves) == ".minecraft":# 判断是否开启版本隔离
            dir_version = dir_back_saves_2_dir_version(dir_back_saves)
            dir_jar_resource = dir_version_2_dir_jar(dir_version)

        self.report({'INFO'}, f"使用世界路径: {worldPath}")

        # JAR文件路径 - 动态获取插件目录
        dir_importer = os.path.join(dir_init_main, "importer")
        jar_path = os.path.join(dir_importer, "minecraft-map-selector-1.0.0.jar")

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
                "--world-path", worldPath,
                # "--jar-path", dir_jar_resource,
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
                                addon_prefs.XYZ_1 = (coords['minX'], coords['minY'], coords['minZ'])
                                addon_prefs.XYZ_2 = (coords['maxX'], coords['maxY'], coords['maxZ'])
                                
                                # 强制刷新UI
                                reloadwindow()
                                print(f"坐标已更新: XYZ_1={addon_prefs.XYZ_1}, XYZ_2={addon_prefs.XYZ_2}")
                                
                                
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
