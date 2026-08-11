import bpy
import os
import subprocess
import json
import tempfile
import threading
import time
import shutil

from ..config import __addon_name__
from bpy.props import *
from .Defs import *

def try_java(exe_path):
    """验证单个Java是否可用，可用返回路径，否则返回None"""
    if not os.path.exists(exe_path):
        return None
    try:
        result = subprocess.run([exe_path, "-version"], capture_output=True, timeout=15)
        output = (result.stdout + result.stderr).decode(errors="ignore").lower()
        if result.returncode != 0:
            return None
        if "error:" in output or "a fatal error" in output:
            return None
        if "32-bit" in output:
            return None
        return exe_path
    except Exception:
        return None

def search_java_in_dir(dir_java, java_exe_name):
    """在目录及其子目录中查找第一个可用的Java，找到即返回"""
    if not os.path.isdir(dir_java):
        return None
    skip_keywords = ("system32", "javapath", "javatmp")
    for root, dirs, files in os.walk(dir_java):
        if any(keyword in root.lower() for keyword in skip_keywords):
            dirs[:] = []
            continue
        if java_exe_name not in files:
            continue
        java_path = os.path.join(root, java_exe_name)
        if os.path.islink(java_path):
            continue
        java_path = try_java(java_path)
        if java_path:
            return java_path
    return None

def find_java_path(prefs):
    """按优先级顺序查找可用的Java，命中即停"""
    java_exe_name = "java.exe" if os.name == "nt" else "java"
    # 1. 手动指定
    if prefs.Java_Path:
        java_path = try_java(prefs.Java_Path)
        if java_path:
            return java_path
    # 2. 环境变量
    for env_name in ("JAVA_HOME", "JDK_HOME"):
        env_home = os.environ.get(env_name)
        if env_home:
            java_path = try_java(os.path.join(env_home, "bin", java_exe_name))
            if java_path:
                return java_path
    # 3. PATH
    path_java = shutil.which("java")
    if path_java:
        java_path = try_java(path_java)
        if java_path:
            return java_path
    # 4. 官方启动器自带JRE
    java_path = search_java_in_dir(os.path.join(os.environ.get("APPDATA", ""), ".minecraft", "runtime"), java_exe_name)
    if java_path:
        return java_path
    java_path = search_java_in_dir(os.path.join(os.environ.get("LOCALAPPDATA", ""), "Packages", "Microsoft.4297127D64EC6_8wekyb3d8bbwe", "LocalCache", "Local", "runtime"), java_exe_name)
    if java_path:
        return java_path
    java_path = search_java_in_dir(os.path.join(os.environ.get("ProgramFiles(x86)", "C:\\Program Files (x86)"), "Minecraft Launcher", "runtime"), java_exe_name)
    if java_path:
        return java_path
    java_path = search_java_in_dir(os.path.join(os.environ.get("ProgramFiles(x86)", "C:\\Program Files (x86)"), "Minecraft", "runtime"), java_exe_name)
    if java_path:
        return java_path
    if os.name != "nt":
    # 5. 非Windows平台的常见目录
        java_path = search_java_in_dir(os.path.expanduser("~/.sdkman/candidates/java"), java_exe_name)
        if java_path:
            return java_path
        java_path = search_java_in_dir("/usr/lib/jvm", java_exe_name)
        if java_path:
            return java_path
        return None
    # 6. 第三方启动器
    java_path = search_java_in_dir(os.path.join(os.environ.get("APPDATA", ""), ".hmcl", "java"), java_exe_name)
    if java_path:
        return java_path
    java_path = search_java_in_dir(os.path.join(os.environ.get("APPDATA", ""), "ATLauncher", "runtimes", "minecraft"), java_exe_name)
    if java_path:
        return java_path
    java_path = search_java_in_dir(os.path.join(os.environ.get("APPDATA", ""), "ModrinthApp", "meta", "java_versions"), java_exe_name)
    if java_path:
        return java_path
    java_path = search_java_in_dir(os.path.join(os.environ.get("APPDATA", ""), "PrismLauncher", "java"), java_exe_name)
    if java_path:
        return java_path
    java_path = search_java_in_dir(os.path.join(os.path.expanduser("~"), "curseforge", "minecraft", "Install", "runtime"), java_exe_name)
    if java_path:
        return java_path
    java_path = search_java_in_dir(os.path.join(os.environ.get("LOCALAPPDATA", ""), ".ftba", "bin", "runtime"), java_exe_name)
    if java_path:
        return java_path
    java_path = search_java_in_dir(os.path.join(os.path.expanduser("~"), ".jdks"), java_exe_name)
    if java_path:
        return java_path
    # 7. 官方安装目录
    java_path = search_java_in_dir(os.path.join(os.environ.get("ProgramFiles", "C:\\Program Files"), "Java"), java_exe_name)
    if java_path:
        return java_path
    java_path = search_java_in_dir(os.path.join(os.environ.get("ProgramFiles", "C:\\Program Files"), "Eclipse Adoptium"), java_exe_name)
    if java_path:
        return java_path
    java_path = search_java_in_dir(os.path.join(os.environ.get("ProgramFiles", "C:\\Program Files"), "Amazon Corretto"), java_exe_name)
    if java_path:
        return java_path
    java_path = search_java_in_dir(os.path.join(os.environ.get("ProgramFiles", "C:\\Program Files"), "Zulu"), java_exe_name)
    if java_path:
        return java_path
    dir_microsoft = os.path.join(os.environ.get("ProgramFiles", "C:\\Program Files"), "Microsoft")
    if os.path.isdir(dir_microsoft):
        for dir_jdk in os.listdir(dir_microsoft):
            if dir_jdk.startswith("jdk-"):
                java_path = search_java_in_dir(os.path.join(dir_microsoft, dir_jdk), java_exe_name)
                if java_path:
                    return java_path
    return None

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

        push_log('[unknown] 地图选择器', 'INFO')
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

        # 检查JAR文件是否存在
        if dir_jar_resource and os.path.exists(dir_jar_resource):
            self.report({'INFO'}, f"找到Minecraft JAR文件: {dir_jar_resource}")
        else:
            self.report({'WARNING'}, "未找到Minecraft JAR文件，将使用默认颜色")

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
            # 检测Java路径
            java_path = find_java_path(addon_prefs)
            if not java_path:
                self.report({'ERROR'}, "Java not found, please specify the path")
                bpy.ops.crafter.java_input('INVOKE_DEFAULT')
                return {'CANCELLED'}
            if addon_prefs.Java_Path != java_path:
                addon_prefs.Java_Path = java_path
            self.report({'INFO'}, f"使用Java: {java_path}")

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
                java_path, "-jar", jar_path,
                "--world-path", worldPath,
                "--output-file", coord_file,
                "--min-y", str(min_y),
                "--max-y", str(max_y)
            ]

            # 如果找到了JAR文件，添加JAR路径参数
            if dir_jar_resource and os.path.exists(dir_jar_resource):
                cmd.extend(["--jar-path", dir_jar_resource])
            
            print(f"传递Y坐标范围: {min_y} 到 {max_y}")
            if dir_jar_resource and os.path.exists(dir_jar_resource):
                print(f"传递JAR路径: {dir_jar_resource}")

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
                                if os.path.exists(coord_file):
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

# Java设置弹窗
class VIEW3D_OT_CrafterJavaSettings(bpy.types.Operator):
    bl_label = "Java Settings"
    bl_idname = "crafter.java_settings"
    bl_description = "设置地图选择器使用的Java路径"
    bl_options = {'REGISTER', 'UNDO'}

    old_java_path: StringProperty(name="Old Java Path",
                                  default="")# type: ignore

    def draw(self, context):
        layout = self.layout
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        layout.prop(addon_prefs, "Java_Path")
        if addon_prefs.Java_Path:
            if try_java(addon_prefs.Java_Path):
                layout.label(text="Java available", icon="CHECKMARK")
            else:
                layout.label(text="Java path invalid", icon="ERROR")
        else:
            layout.label(text="Empty = auto detect", icon="INFO")

    def invoke(self, context, event):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        self.old_java_path = addon_prefs.Java_Path
        return context.window_manager.invoke_props_dialog(self)

    def execute(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        if addon_prefs.Java_Path and not try_java(addon_prefs.Java_Path):
            addon_prefs.Java_Path = self.old_java_path
            self.report({'ERROR'}, "Java path invalid")
            return {'CANCELLED'}
        return {'FINISHED'}

    def cancel(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        addon_prefs.Java_Path = self.old_java_path

# Java手动指定弹窗
class VIEW3D_OT_CrafterJavaInput(bpy.types.Operator):
    bl_label = "Java Path"
    bl_idname = "crafter.java_input"
    bl_description = "未找到可用的Java，手动指定路径"
    bl_options = {'REGISTER', 'UNDO'}

    java_input: StringProperty(name="Java Path",
                               subtype="FILE_PATH",
                               default="")# type: ignore

    def draw(self, context):
        layout = self.layout
        layout.label(text="Java not found, please specify the path", icon="ERROR")
        layout.prop(self, "java_input")
        if self.java_input:
            if try_java(self.java_input):
                layout.label(text="Java available", icon="CHECKMARK")
            else:
                layout.label(text="Java path invalid", icon="ERROR")

    def invoke(self, context, event):
        return context.window_manager.invoke_props_dialog(self)

    def execute(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        if not self.java_input:
            self.report({'ERROR'}, "Java path is empty")
            return {'CANCELLED'}
        if not try_java(self.java_input):
            self.report({'ERROR'}, "Java path invalid")
            return {'CANCELLED'}
        addon_prefs.Java_Path = self.java_input
        bpy.ops.crafter.map_selector()
        return {'FINISHED'}
