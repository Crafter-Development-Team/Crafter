import bpy
import os
import subprocess
import platform
import json
import zipfile
import sqlite3
import ctypes
import tempfile
import textwrap

# 只在Windows上导入wintypes
if platform.system() == "Windows":
    from ctypes import wintypes
    print("[DEBUG] Windows")


from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from bpy.props import *
from ..__init__ import dir_blend_append, dir_init_main
from..properties import dirs_temp

donot = ["Crafter Materials Settings"]
len_color_jin = 21
name_library = "Crafter"
names_Crafter_Moving_texture = ["Crafter-Moving_texture_Start", "Crafter-Moving_texture_Start_interpolate", "Crafter-Moving_texture_End"]


def load_icon_from_zip(zip_path, icons, name_icons, index):
    dir_temp = tempfile.mkdtemp()
    with zipfile.ZipFile(zip_path, "r") as zip_file:
        if "pack.png" in zip_file.namelist():
            zip_file.extract("pack.png", dir_temp)
            have = True
        else:
            have = False
    dir_icon = os.path.join(dir_temp, "pack.png")
    icons.load(name_icons + "_icon_" + str(index), dir_icon, 'IMAGE')
    dirs_temp.append(dir_temp)
    return have

def get_dir_saves(context):
    addon_prefs = context.preferences.addons[__addon_name__].preferences

    dir_root = addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name

    divided = False

    dir_versions = dir_root_2_dir_versions(dir_root)
    list_folder_versions = os.listdir(dir_versions)
    if len(list_folder_versions) > 0:
        dir_version = os.path.join(dir_versions, list_folder_versions[0])
        dir_saves = os.path.join(dir_version, "saves")
        if os.path.exists(dir_saves):
            divided = True

    if divided:
        dir_verisons = dir_root_2_dir_versions(dir_root)
        dir_version = os.path.join(dir_verisons,addon_prefs.History_World_Versions_List[addon_prefs.History_World_Versions_List_index].name)
        dir_saves = dir_version_2_dir_saves(dir_version)
        
        return dir_saves
    else:
        dir_undivided_saves = os.path.join(dir_root,"saves")
        
        return dir_undivided_saves

def get_dir_save(context):
    addon_prefs = context.preferences.addons[__addon_name__].preferences

    dir_save = os.path.join(get_dir_saves(context), addon_prefs.History_World_Saves_List[addon_prefs.History_World_Saves_List_index].name)
    
    return dir_save

def dir_root_2_dir_versions(dir_root):
    list_folder_minecraft = os.listdir(dir_root)
    if "Instances" in list_folder_minecraft:
        dir_versions = os.path.join(dir_root, "Instances")
    elif "profiles" in list_folder_minecraft:
        dir_versions = os.path.join(dir_root, "profiles")
    else:
        dir_versions = os.path.join(dir_root, "versions")
    return dir_versions

def dir_version_2_dir_saves(dir_version):
    list_floder_version = os.listdir(dir_version)
    if ("instance.cfg" in list_floder_version) and ("mmc-pack.json" in list_floder_version):
        dir_saves = os.path.join(dir_version, "minecraft", "saves")
    else:
        dir_saves = os.path.join(dir_version, "saves")
    return dir_saves

def dir_back_saves_2_dir_version(dir_back_saves):
    dir_back_back_saves = os.path.dirname(dir_back_saves)
    list_floder_back_back_saves = os.listdir(dir_back_back_saves)
    if ("instance.cfg" in list_floder_back_back_saves) and ("mmc-pack.json" in list_floder_back_back_saves):
         dir_versions = dir_back_back_saves
    else:
        dir_versions = dir_back_saves
    return dir_versions

def dir_version_2_dir_jar(dir_version):
    dir_versions = os.path.dirname(dir_version)
    name_versions = os.path.basename(dir_versions)
    if name_versions == "Instances":
        list_floder_version = os.listdir(dir_version)
        if ("instance.cfg" in list_floder_version) and ("mmc-pack.json" in list_floder_version):
            
            dir_jar = ""# 暂未支持prime laucher（似乎与官方启动器使用同样的jar存储方式）
        else:
            dir_json_Instance = os.path.join(dir_version, "minecraftinstance.json")
            with open(dir_json_Instance, "r", encoding="utf-8") as file:
                json_instance = json.load(file)
            json_versionjson = json.loads(json_instance["baseModLoader"]["versionJson"])
            name_folder = json_versionjson["id"]
            name_jar = name_folder + ".jar"
            dir_minecraft = os.path.dirname(dir_versions)
            dir_jar = os.path.join(dir_minecraft, "Install", "versions", name_folder, name_jar)
    elif name_versions == "profiles":
        name_version = os.path.basename(dir_version)
        dir_ModrinthApp = os.path.dirname(dir_versions)
        dir_db_app = os.path.join(dir_ModrinthApp, "app.db")
        dir_meta = os.path.join(dir_ModrinthApp, "meta")
        dir_versions_meta = os.path.join(dir_meta, "versions")

        db_app = sqlite3.connect(dir_db_app)
        cursor = db_app.cursor()
        cursor.execute("SELECT * FROM profiles")
        rows = cursor.fetchall()
        for row in rows:
            if row[0] == name_version:
                if row[6] == None:
                    name_jar = row[4]
                else:
                    name_jar = row[4] + "-" + row[6]
                break
        dir_jar = os.path.join(dir_versions_meta, name_jar, name_jar + ".jar")
    else:
        name_version = os.path.basename(dir_version)
        dir_jar = os.path.join(dir_version, name_version+".jar")
    return dir_jar

def reloadwindow():
    bpy.ops.wm.redraw_timer(type="DRAW_WIN_SWAP")

def view_2_active_object(context):
    for window in context.window_manager.windows:
        for area in window.screen.areas:
            if area.type == 'VIEW_3D':
                # 需要同时覆盖window/area/region三个上下文参数
                for region in area.regions:
                    if region.type == 'WINDOW':  # 只处理主区域
                        try:
                            with context.temp_override(window=window, area=area, region=region):
                                bpy.ops.view3d.view_selected()
                        except:
                            pass
                        break
    reloadwindow()

def draw_multiline_label( text, parent,context):
    # 获取当前面板宽度和UI缩放比例
    panel_width = context.region.width
    
    # 计算有效字符宽度（考虑UI缩放对字体大小的影响）
    chars_per_line = max(10, int(panel_width / 50))  # 防下限
    
    # 使用textwrap分割文本
    wrapper = textwrap.TextWrapper(
        width=chars_per_line,
        break_long_words=True,
        replace_whitespace=False
    )
    lines = wrapper.wrap(text=text)
    
    # 动态生成多行标签
    for line in lines:
        parent.label(text=line)

def run_as_admin_and_wait(exe_path, work_dir=None, shell=False):
    """
    跨平台运行可执行文件并等待完成
    Windows: 使用管理员权限运行
    macOS/Linux: 使用普通权限运行
    """
    current_platform = platform.system()
    print(f"[DEBUG] Platform detected: {current_platform}")
    print(f"[DEBUG] Executable path: {exe_path}")
    print(f"[DEBUG] Working directory: {work_dir}")

    if current_platform == "Windows":
        print("[DEBUG] Using Windows execution path")
        return _run_windows_admin(exe_path, work_dir, shell)
    elif current_platform == "Darwin":  # macOS
        print("[DEBUG] Using macOS execution path")
        return _run_macos(exe_path, work_dir, shell)
    else:  # Linux或其他Unix系统
        print(f"[DEBUG] Using Unix execution path for {current_platform}")
        return _run_unix(exe_path, work_dir, shell)

def _run_windows_admin(exe_path, work_dir=None, shell=False):
    """Windows平台使用管理员权限运行"""
    # 只在Windows平台上执行
    if platform.system() != "Windows":
        return False

    try:
        # 定义SHELLEXECUTEINFOW结构体
        class SHELLEXECUTEINFOW(ctypes.Structure):
            _fields_ = [
                ("cbSize", wintypes.DWORD),
                ("fMask", ctypes.c_ulong),
                ("hwnd", wintypes.HWND),
                ("lpVerb", wintypes.LPCWSTR),
                ("lpFile", wintypes.LPCWSTR),
                ("lpParameters", wintypes.LPCWSTR),
                ("lpDirectory", wintypes.LPCWSTR),
                ("nShow", ctypes.c_int),
                ("hInstApp", wintypes.HINSTANCE),
                ("lpIDList", ctypes.c_void_p),
                ("lpClass", wintypes.LPCWSTR),
                ("hKeyClass", wintypes.HKEY),
                ("dwHotKey", wintypes.DWORD),
                ("hIcon", wintypes.HANDLE),
                ("hProcess", wintypes.HANDLE)
            ]

        # 配置结构体参数
        sei = SHELLEXECUTEINFOW()
        sei.cbSize = ctypes.sizeof(SHELLEXECUTEINFOW)
        sei.fMask = 0x00000040  # SEE_MASK_NOCLOSEPROCESS
        sei.lpVerb = 'runas'    # 管理员权限
        sei.lpFile = exe_path.replace("\\", "\\\\")  # 处理Windows路径转义
        sei.lpDirectory = work_dir.replace("\\", "\\\\") if work_dir else None
        sei.nShow = shell  # SW_SHOWNORMAL

        # 调用ShellExecuteExW
        if not ctypes.windll.shell32.ShellExecuteExW(ctypes.byref(sei)):
            error_code = ctypes.GetLastError()
            error_msg = ctypes.FormatError(error_code)
            return False

        # 等待进程结束
        WAIT_TIMEOUT = 0x00000102
        WAIT_OBJECT_0 = 0x0
        while True:
            wait_result = ctypes.windll.kernel32.WaitForSingleObject(sei.hProcess, 100)  # 100ms间隔
            if wait_result == WAIT_OBJECT_0:
                break
            elif wait_result == WAIT_TIMEOUT:
                continue
            else:
                ctypes.windll.kernel32.CloseHandle(sei.hProcess)
                return False

        # 获取退出码
        exit_code = wintypes.DWORD()
        ctypes.windll.kernel32.GetExitCodeProcess(sei.hProcess, ctypes.byref(exit_code))
        ctypes.windll.kernel32.CloseHandle(sei.hProcess)

        return exit_code.value == 0

    except Exception as e:
        print(f"Windows execution error: {e}")
        return False

def _run_macos(exe_path, work_dir=None, shell=False):
    """macOS平台运行"""
    try:
        # 检查是否是Windows可执行文件，如果是则替换为macOS版本
        if exe_path.endswith("WorldImporter.exe"):
            macos_exe_path = exe_path.replace("WorldImporter.exe", "WorldImporter")
            if os.path.exists(macos_exe_path):
                exe_path = macos_exe_path
                print("macOS")  # 输出给Blender
            else:
                print(f"macOS executable not found: {macos_exe_path}")
                return False

        print(f"[DEBUG] Final executable path: {exe_path}")
        print(f"[DEBUG] Working directory: {work_dir}")

        # 检查配置文件是否存在
        config_path = os.path.join(work_dir, "config_macos", "config.json")
        print(f"[DEBUG] Expected config path: {config_path}")
        if os.path.exists(config_path):
            print(f"[DEBUG] Config file exists and size: {os.path.getsize(config_path)} bytes")
        else:
            print(f"[DEBUG] Config file does not exist!")

        # 使用subprocess运行程序
        process = subprocess.Popen(
            [exe_path],
            cwd=work_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )

        # 等待进程完成
        stdout, stderr = process.communicate()

        # 打印输出（可选）
        if stdout:
            print("=== STDOUT ===")
            print(stdout.decode('utf-8'))
        if stderr:
            print("=== STDERR ===")
            print(stderr.decode('utf-8'))

        print(f"[DEBUG] Process return code: {process.returncode}")
        return process.returncode == 0

    except Exception as e:
        print(f"macOS execution error: {e}")
        import traceback
        traceback.print_exc()
        return False

def _run_unix(exe_path, work_dir=None, shell=False):
    """Unix/Linux平台运行"""
    try:
        process = subprocess.Popen(
            [exe_path],
            cwd=work_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )

        stdout, stderr = process.communicate()

        if stdout:
            print(stdout.decode('utf-8'))
        if stderr:
            print(stderr.decode('utf-8'))

        return process.returncode == 0

    except Exception as e:
        print(f"Unix execution error: {e}")
        return False


def open_folder(folder_path: str):
    '''
    打开目标文件夹
    folder_path: 文件夹路径
    '''
    if platform.system() == "Windows":
        os.startfile(folder_path)
    elif platform.system() == "Darwin":  # MacOS
        subprocess.run(["open", folder_path])
    else:  # Linux
        subprocess.run(["xdg-open", folder_path])

def make_dict_together(dict1, dict2):
    '''
    递归合并json最底层的键值对
    dict1: 字典1
    dict2: 字典2
    '''
    for key, value in dict2.items():
        if key in dict1:
            if isinstance(dict1[key], dict) and isinstance(value, dict):
                make_dict_together(dict1[key], value)
            elif isinstance(dict1[key], list) and isinstance(value, list):
                dict1[key] = list(set(dict1[key] + value))
            else:
                dict1[key] = value
        else:
            dict1[key] = value
    return dict1

def unzip(zip_path, extract_to):
    '''
    解压压缩文件
    zip_path: 压缩文件路径
    extract_to: 解压路径
    '''
    os.makedirs(extract_to, exist_ok=True)
    with zipfile.ZipFile(zip_path, 'r') as zip_ref:
        zip_ref.extractall(extract_to)

def add_node_group_if_not_exists(names):
    for name in names:
        if name not in bpy.data.node_groups:
            try:
                with bpy.data.libraries.load(dir_blend_append, link=False) as (data_from, data_to):
                    data_to.node_groups = [name]
                bpy.data.node_groups[name].use_fake_user = True
            except Exception as e:
                print(f"Error loading node group '{name}': {e}")
                continue

def add_node_moving_texture(node_tex, nodes, links):
    '''
    为基础色节点添加动态纹理节点并连接
    node_tex_base: 基础纹理节点
    nodes: 目标材质节点组
    links:目标材质连接组
    return:动态纹理节点
    '''
    # ["Crafter-Moving_texture_Start", "Crafter-Moving_texture_Start_interpolate", "Crafter-Moving_texture_End"]
    if node_tex.image.size[0] == 0:
        return None
    else:
        row = node_tex.image.size[1] / node_tex.image.size[0]

    dir_image = os.path.dirname(node_tex.image.filepath)
    dir_mcmeta = os.path.join(bpy.path.abspath(dir_image), fuq_bl_dot_number(node_tex.image.name) + ".mcmeta")
    if not os.path.exists(dir_mcmeta):
        return None
    
    if os.path.exists(dir_mcmeta):
        node_Moving_texture_end = nodes.new(type="ShaderNodeGroup")
        node_Moving_texture_end.location = (node_tex.location.x - 200, node_tex.location.y)
        node_Moving_texture_end.node_tree = bpy.data.node_groups["Crafter-Moving_texture_End"]

        node_Fac = nodes.new(type="ShaderNodeValToRGB")
        node_Fac.location = (node_tex.location.x - 750, node_tex.location.y)
        node_Fac.color_ramp.interpolation = "CONSTANT"

        node_Moving_texture_start = nodes.new(type="ShaderNodeGroup")
        node_Moving_texture_start.location = (node_tex.location.x - 900, node_tex.location.y)
        
        with open(dir_mcmeta, 'r', encoding='utf-8') as file:
            mcmeta = json.load(file)
        frametime = 1
        interpolate = False
        frames = row
        list_frames = []
        haveframe = True
        if "animation" in mcmeta:
            if "frametime" in mcmeta["animation"]:
                frametime = mcmeta["animation"]["frametime"]
            if "interpolate" in mcmeta["animation"]:
                interpolate = mcmeta["animation"]["interpolate"]
            if "height" in mcmeta["animation"] and "width" in mcmeta["animation"]:
                row = mcmeta["animation"]["height"] / mcmeta["animation"]["width"]
                frames = row
            if "frames" in mcmeta["animation"]:
                frames = 0
                for frame in mcmeta["animation"]["frames"]:
                    if type(frame) == int:
                        frames += 1
                        list_frames.append([frame,1])
                    else:
                        info_frame = json.loads(frame)
                        frames += info_frame["time"] / frametime
                        list_frames.append([info_frame["index"],info_frame["time"] / frametime])
            else:
                haveframe = False
        else:
            haveframe = False
        if not haveframe:
            frames = row
            for i in range(int(frames)):
                list_frames.append([i,1])

        if interpolate:
            node_Moving_texture_start.node_tree = bpy.data.node_groups["Crafter-Moving_texture_Start_interpolate"]
            node_Moving_texture_start.inputs["20 / frametime"].default_value = 20 / frametime
            node_Moving_texture_start.inputs["frames"].default_value = frames
        else:
            node_Moving_texture_start.node_tree = bpy.data.node_groups["Crafter-Moving_texture_Start"]
            node_Moving_texture_start.inputs["20 / frametime / frames"].default_value = 20 / frametime / frames
            
        links.new(node_Moving_texture_end.outputs["Vector"], node_tex.inputs["Vector"])
        links.new(node_Moving_texture_start.outputs["Fac"], node_Fac.inputs["Fac"])

        n = -1
        adding_frames = 0
        for i in list_frames:
            n += 1
            if (n // 32 > 0) and (n % 32 == 0):
                if n // 32 ==1:
                    last_out_put_alpha = node_Fac.outputs["Alpha"]
                else:
                    last_out_put_alpha = node_Mix.outputs["Result"]
                node_Fac = nodes.new(type="ShaderNodeValToRGB")
                node_Fac.location = (node_tex.location.x - 750, node_tex.location.y + ((n // 32) * 250))
                node_Fac.color_ramp.interpolation = "CONSTANT"
                links.new(node_Moving_texture_start.outputs["Fac"], node_Fac.inputs["Fac"])

                node_Math = nodes.new(type="ShaderNodeMath")
                node_Math.location = (node_tex.location.x - 500, node_tex.location.y + ((n // 32) * 250) - 250)
                node_Math.operation = "LESS_THAN"
                node_Math.use_clamp = False
                links.new(node_Moving_texture_start.outputs["Fac"], node_Math.inputs["Value"])
                node_Math.inputs["Value_001"].default_value = adding_frames / frames

                node_Mix = nodes.new(type="ShaderNodeMix")
                node_Mix.location = (node_tex.location.x - 350, node_tex.location.y + ((n // 32) * 250) - 250)
                node_Mix.data_type = "FLOAT"
                node_Mix.clamp_factor = False

                links.new(node_Math.outputs["Value"], node_Mix.inputs["Factor"])
                links.new(node_Fac.outputs["Alpha"], node_Mix.inputs["A"])
                links.new(last_out_put_alpha, node_Mix.inputs["B"])
            if (n % 32) > 1:
                node_Fac.color_ramp.elements.new(1)
                
            frame_chu_row = i[0] / row
            node_Fac.color_ramp.elements[n % 32].position = adding_frames / frames
            node_Fac.color_ramp.elements[n % 32].color = [frame_chu_row, frame_chu_row, frame_chu_row, frame_chu_row]
            adding_frames  += i[1]
        if n // 32 == 0:
            last_out_put_alpha = node_Fac.outputs["Alpha"]
        else:
            last_out_put_alpha = node_Mix.outputs["Result"]

        links.new(last_out_put_alpha, node_Moving_texture_end.inputs["frame / row"])
        node_Moving_texture_end.inputs["row"].default_value = row

        return node_Moving_texture_end
    else:
        return None

def fuq_bl_dot_number(name: str):
    '''
    去除blender中重复时烦人的.xxx
    name: 待处理的字符串
    return: 处理后的字符串
    '''
    last_dot_index = name.rfind('.')
    if not last_dot_index == -1:
        if all("0" <= i <= "9" for i in name[last_dot_index + 1:]):
            name = name[:last_dot_index]
    return name

def add_to_mcmts_collection(object,context):
    '''
    object: 目标对象
    context: 目标上下文
    '''
    if object.type == "MESH":
        list_name_context_material = []
        for context_material in bpy.data.materials:
            list_name_context_material.append(context_material.name)
        list_name_object_material = []
        for material_object in object.data.materials:
            list_name_object_material.append(material_object.name)
        if (object.name not in donot) and object.type == "MESH" and object.data.materials:
            for name_material in list_name_object_material:
                if (name_material not in context.scene.Crafter_mcmts) and (name_material not in donot):
                    new_mcmt = context.scene.Crafter_mcmts.add()
                    new_mcmt.name = name_material
        for i in range(len(context.scene.Crafter_mcmts)-1,-1,-1):
            if context.scene.Crafter_mcmts[i].name not in list_name_context_material:
                context.scene.Crafter_mcmts.remove(i)

def add_to_crafter_mcmts_collection(object,context):
    '''
    object: 目标对象
    context: 目标上下文
    '''
    if object.type == "MESH":
        list_name_context_material = []
        for context_material in bpy.data.materials:
            list_name_context_material.append(context_material.name)
        list_name_object_material = []
        for object_material in object.data.materials:
            list_name_object_material.append(object_material.name)
        if (object.name not in donot) and object.type == "MESH" and object.data.materials:
            for name_material in list_name_object_material:
                if (name_material not in context.scene.Crafter_crafter_mcmts) and (name_material not in donot):
                    new_mcmt = context.scene.Crafter_crafter_mcmts.add()
                    new_mcmt.name = name_material
        for i in range(len(context.scene.Crafter_crafter_mcmts)-1,-1,-1):
            if context.scene.Crafter_crafter_mcmts[i].name not in list_name_context_material:
                context.scene.Crafter_crafter_mcmts.remove(i)
                
def find_CI_group(classification_list,real_block_name,group_CI):
    '''
    classification_list: 分类列表
    real_block_name: 真实方块名称
    group_CI: CI节点组
    '''
    found = False
    for group_name in classification_list:
        if group_name == "ban" or group_name == "ban_keyw":
            continue
        banout = False
        if "ban_keyw" in classification_list[group_name]:
            for item in classification_list[group_name]["ban_keyw"]:
                if item in real_block_name:
                    banout = True
                    break
        if banout:
            continue
        if "ban" in classification_list[group_name]:
            for item in classification_list[group_name]["ban"]:
                if item == real_block_name:
                    banout = True
                    break
        if banout:
            continue
        if "full" in classification_list[group_name]:
            for item in classification_list[group_name]["full"]:
                if item == real_block_name:
                    name_node = "CI-" + group_name
                    if name_node in bpy.data.node_groups:
                        group_CI.node_tree = bpy.data.node_groups[name_node]
                    else:
                        group_CI.node_tree = bpy.data.node_groups["CI-"]
                    found = True
                    break
        if found:
            break
        if "keyw" in classification_list[group_name]:
            for item in classification_list[group_name]["keyw"]:
                if item in real_block_name:
                    name_node = "CI-" + group_name
                    if name_node in bpy.data.node_groups:
                        group_CI.node_tree = bpy.data.node_groups[name_node]
                    else:
                        group_CI.node_tree = bpy.data.node_groups["CI-"]
                    found = True
                    break
        if found:
            break
    if not found:
                group_CI.node_tree = bpy.data.node_groups["CI-"]

def link_CI_output(group_CI, node_output_EEVEE, node_output_Cycles, links):
    '''
    group_CI: 材质组节点
    node_output_EEVEE: EEVEE输出节点
    node_output_Cycles: Cycles输出节点
    nodes: 目标材质节点组
    links:目标材质连接组
    '''
    if "EEVEE-Surface" in group_CI.outputs:
        links.new(group_CI.outputs["EEVEE-Surface"], node_output_EEVEE.inputs["Surface"])
    if "EEVEE-Volume" in group_CI.outputs: 
        links.new(group_CI.outputs["EEVEE-Volume"], node_output_EEVEE.inputs["Volume"])
    if "EEVEE-Displacement" in group_CI.outputs: 
        links.new(group_CI.outputs["EEVEE-Displacement"], node_output_EEVEE.inputs["Displacement"])
    if "EEVEE-Thickness" in group_CI.outputs: 
        if "Thickness" in node_output_EEVEE.inputs:
            links.new(group_CI.outputs["EEVEE-Thickness"], node_output_EEVEE.inputs["Thickness"])
        
    if "Cycles-Surface" in group_CI.outputs: 
        links.new(group_CI.outputs["Cycles-Surface"], node_output_Cycles.inputs["Surface"])
    if "Cycles-Volume" in group_CI.outputs: 
        links.new(group_CI.outputs["Cycles-Volume"], node_output_Cycles.inputs["Volume"])
    if "Cycles-Displacement" in group_CI.outputs: 
        links.new(group_CI.outputs["Cycles-Displacement"], node_output_Cycles.inputs["Displacement"])
    if "Cycles-Thickness" in group_CI.outputs: 
        if "Thickness" in node_output_Cycles.inputs:
            links.new(group_CI.outputs["Cycles-Thickness"], node_output_Cycles.inputs["Thickness"])

def add_node_parser(group_CI, nodes, links):
    '''
    gout_CI: 材质组节点
    nodes: 目标材质节点组
    links:目标材质连接组
    return: node_C_PBR_Parser
    '''
    node_C_PBR_Parser = nodes.new(type="ShaderNodeGroup")
    node_C_PBR_Parser.location = (group_CI.location.x - 200, group_CI.location.y - 160)
    node_C_PBR_Parser.node_tree = bpy.data.node_groups["C-PBR_Parser"]
    for output in node_C_PBR_Parser.outputs:
        if output.name in group_CI.inputs:
            links.new(output, group_CI.inputs[output.name])
    return node_C_PBR_Parser

def load_normal_and_PBR(node_tex_base, nodes, links):
    '''
    以基础色节点添加法向贴图节点和PBR贴图节点、连接并添加动态纹理节点
    node_tex_base: 基础色节点
    nodes: 目标材质节点组
    links:目标材质连接组
    '''
    node_tex_normal = None
    node_tex_PBR = None
    if node_tex_base != None:
        name_image = fuq_bl_dot_number(node_tex_base.image.name)
        name_block = name_image[:-4]
        dir_image = os.path.dirname(node_tex_base.image.filepath)
        dir_n = os.path.join(dir_image,name_block + "_n.png")
        dir_s = os.path.join(dir_image,name_block + "_s.png")
        dir_a = os.path.join(dir_image,name_block + "_a.png")
        add_node_moving_texture(node_tex_base, nodes, links)
        node_tex_normal = None
        node_tex_PBR = None
        if os.path.exists(bpy.path.abspath(dir_n)):
            node_tex_normal = nodes.new(type="ShaderNodeTexImage")
            node_tex_normal.location = (node_tex_base.location.x, node_tex_base.location.y - 300)
            node_tex_normal.image = bpy.data.images.load(dir_n)
            node_tex_normal.interpolation = "Closest"
            bpy.data.images[node_tex_normal.image.name].colorspace_settings.name = "Non-Color"
            add_node_moving_texture(node_tex_normal, nodes, links)
        if os.path.exists(bpy.path.abspath(dir_s)):
            node_tex_PBR = nodes.new(type="ShaderNodeTexImage")
            node_tex_PBR.location = (node_tex_base.location.x, node_tex_base.location.y - 600)
            node_tex_PBR.image = bpy.data.images.load(dir_s)
            node_tex_PBR.interpolation = "Closest"
            bpy.data.images[node_tex_PBR.image.name].colorspace_settings.name = "Non-Color"
            add_node_moving_texture(node_tex_PBR, nodes, links)
        elif os.path.exists(bpy.path.abspath(dir_a)):
            node_tex_PBR = nodes.new(type="ShaderNodeTexImage")
            node_tex_PBR.location = (node_tex_base.location.x, node_tex_base.location.y - 600)
            node_tex_PBR.image = bpy.data.images.load(dir_a)
            node_tex_PBR.interpolation = "Closest"
            bpy.data.images[node_tex_PBR.image.name].colorspace_settings.name = "Non-Color"
            add_node_moving_texture(node_tex_PBR, nodes, links)
    return node_tex_normal, node_tex_PBR

def link_base_normal_PBR(node_tex_base, group_CI, links, node_C_PBR_Parser, node_tex_normal, node_tex_PBR):
    if node_tex_base != None:
        if "Base Color" in group_CI.inputs:
            links.new(node_tex_base.outputs["Color"], group_CI.inputs["Base Color"])
        if "Alpha" in group_CI.inputs:
            links.new(node_tex_base.outputs["Alpha"], group_CI.inputs["Alpha"])
    if node_tex_normal != None:
        links.new(node_tex_normal.outputs["Color"], node_C_PBR_Parser.inputs["Normal"])
        links.new(node_tex_normal.outputs["Alpha"], node_C_PBR_Parser.inputs["Normal Alpha"])
        if "Normal" in group_CI.inputs:
            links.new(node_tex_normal.outputs["Color"], group_CI.inputs["Normal"])
        if "Normal Alpha" in group_CI.inputs:
            links.new(node_tex_normal.outputs["Alpha"], group_CI.inputs["Normal Alpha"])
    if node_tex_PBR != None:
        links.new(node_tex_PBR.outputs["Color"], node_C_PBR_Parser.inputs["PBR"])
        links.new(node_tex_PBR.outputs["Alpha"], node_C_PBR_Parser.inputs["PBR Alpha"])
        if "PBR" in group_CI.inputs:
            links.new(node_tex_PBR.outputs["Color"], group_CI.inputs["PBR"])
        if "PBR Alpha" in group_CI.inputs:
            links.new(node_tex_PBR.outputs["Alpha"], group_CI.inputs["PBR Alpha"])

def add_Crafter_time(obj):
    if not "Crafter-time" in bpy.data.node_groups:
        with bpy.data.libraries.load(dir_blend_append, link=False) as (data_from, data_to):
            data_to.node_groups = ["Crafter-time"]
    # 检查是否已存在该节点修改器
    has_modifier = any(
        mod.type == 'NODES' and 
        mod.node_group == bpy.data.node_groups["Crafter-time"]
        for mod in obj.modifiers)

    if not has_modifier:
        # 添加几何节点修改器
        new_mod = obj.modifiers.new("Crafter-time", 'NODES')
        new_mod.node_group = bpy.data.node_groups["Crafter-time"]

def link_biome_tex(node_biomeTex, group_CI, links):
    if not node_biomeTex == None:
        for output in node_biomeTex.outputs:
            if output.name in group_CI.inputs:
                links.new(output, group_CI.inputs[output.name])

def reload_Undivided_Vsersions(context: bpy.types.Context,dir_versions):#刷新无版本隔离列表

        addon_prefs = context.preferences.addons[__addon_name__].preferences

        list_versions = os.listdir(dir_versions)
        addon_prefs.Undivided_Vsersions_List.clear()
        for version in list_versions:
            versionPath = os.path.join(dir_versions, version)
            undivided_version = addon_prefs.Undivided_Vsersions_List.add()
            undivided_version.name = versionPath
        if (len(addon_prefs.Undivided_Vsersions_List) - 1 < addon_prefs.Undivided_Vsersions_List_index) or (addon_prefs.Undivided_Vsersions_List_index < 0):
            addon_prefs.Undivided_Vsersions_List_index = 0
    
def node_moving_tex_info(node):
    info = [False,None]
    if node == None:
        return info
    if len(node.inputs["Vector"].links) >0:
        info[0] = True
        info[1] = node.inputs["Vector"].links[0].from_node

    return info

def creat_parallax_node(node_tex_normal, iterations, smooth, info_moving_normal, nodes, links):
    # 创建框，方便清除
    node_frame = nodes.new(type="NodeFrame")
    location = [node_tex_normal.location.x - 1500, node_tex_normal.location.y]
    node_frame.location = location
    node_frame.label = "Crafter_Parallax"

    move = 190
    iterations = max(iterations, 1)

    if info_moving_normal[0]:
        row_dao = 1 / info_moving_normal[1].inputs["row"].default_value
    else:
        row_dao = 1

    i = 1
    while iterations >= i:

        node_parallax = nodes.new("ShaderNodeGroup")
        node_parallax.node_tree = bpy.data.node_groups["CP-Parallax"]
        node_parallax.location = location
        node_parallax.inputs["1 / row"].default_value = row_dao
        location[0] -= 1.5 * move# location
        node_parallax.parent = node_frame
        if i == 1:
            node_final_parallax = node_parallax
        else:
            links.new(node_parallax.outputs["Next Start"], node_last.inputs["Start"])
            links.new(node_parallax.outputs["UV"], node_last.inputs["UV"])
            links.new(node_parallax.outputs["UV"], node_height.inputs["Vector"])
        node_last = node_parallax

        node_height = nodes.new("ShaderNodeTexImage")
        node_height.image = node_tex_normal.image
        node_height.location = location
        location[0] -= move# location
        node_height.parent = node_frame
        if smooth:
            node_height.interpolation = "Linear"
        else:
            node_height.interpolation = "Closest"

        links.new(node_height.outputs["Alpha"], node_last.inputs["Depth"])

        if i == iterations:
            if info_moving_normal[0]:
                node_moving = copy_node_tree_recursive(source_node=info_moving_normal[1], nodes=nodes, links=links, to_location=location, parent=node_frame)

                links.new(node_moving.outputs["Vector"], node_height.inputs["Vector"])
                links.new(node_moving.outputs["Vector"], node_last.inputs["UV"])
            else:
                node_TexCoord = nodes.new("ShaderNodeTexCoord")
                node_TexCoord.location = location
                node_TexCoord.parent = node_frame

                links.new(node_TexCoord.outputs["UV"], node_height.inputs["Vector"])
                links.new(node_TexCoord.outputs["UV"], node_last.inputs["UV"])

        i += 1

        links.new(node_final_parallax.outputs["UV"], node_tex_normal.inputs["Vector"])

    return node_final_parallax, node_frame

def create_parallax_final(node, node_final_depth, node_frame, info_moving, nodes, links):
    node_final = nodes.new("ShaderNodeGroup")
    if info_moving[0]:
        node_final.node_tree = bpy.data.node_groups["CP-Final_Parallax_moving"]
        node_final.inputs["row"].default_value = info_moving[1]
        node_final.inputs["frametime"].default_value = info_moving[2]
        node_final.inputs["interpolate"].default_value = info_moving[3]
    else:
        node_final.node_tree = bpy.data.node_groups["CP-Final_Parallax"]
    node_final.location = node.location.x - 600, node.location.y
    node_final.parent = node_frame

    links.new(node_final_depth.outputs["Current_Depth"], node_final.inputs["Depth"])
    links.new(node_final.outputs["UV"], node.inputs["Vector"])

    return node_final

def is_alpha_channel_all_one(image_node):
    """
    判断图像纹理节点的图像纹理的alpha通道是否全部为1。
    
    参数:
        image_node (ShaderNodeTexImage): 图像纹理节点对象。
        
    返回:
        bool: 如果所有alpha通道值都为1，则返回True；否则返回False。
    """
    if not image_node or image_node.type != 'TEX_IMAGE' or not image_node.image:
        return False  # 确保提供了有效的图像纹理节点
    
    image = image_node.image
    pixels = image.pixels[:]  # 获取像素数据（RGBA格式）

    for i in range(0, len(pixels), 4):
        alpha = pixels[i + 3]  # 提取alpha通道的值
        if alpha < 1.0 - 1e-6:  # 使用一个小的容差来处理浮点精度问题
            return False  # 发现非1的alpha值，直接返回False

    return True  # 所有alpha值都是1


def copy_node_tree_recursive(source_node, nodes, links, node_mapping=None, to_location=[0,0], parent=None):
    """
    递归复制节点及其所有上游节点，并保持它们之间的连接关系
    
    参数:
        source_node: 要复制的源节点
        target_nodes: 目标节点集合 (nodes)
        target_links: 目标连接集合 (links)
        node_mapping: 用于跟踪已复制节点的字典，避免重复复制
    
    返回:
        复制后的新节点
    """
    

    # 初始化映射字典
    if node_mapping is None:
        node_mapping = {}
    
    # 如果节点已经复制过，直接返回已复制的节点
    if source_node in node_mapping:
        return node_mapping[source_node]
    
    # 复制当前节点
    new_node = nodes.new(type=source_node.bl_idname)

    new_node.location = to_location[0], to_location[1]
    if parent != None:
        new_node.parent = parent
    
    # 复制特殊属性（根据节点类型）
    if hasattr(source_node, 'node_tree') and source_node.node_tree:
        new_node.node_tree = source_node.node_tree
    
    if source_node.type == 'TEX_IMAGE' and source_node.image:
        new_node.image = source_node.image
        new_node.projection = source_node.projection
        new_node.interpolation = source_node.interpolation
        
    # 复制颜色渐变的插值模式
    if source_node.type == 'VALTORGB' and hasattr(source_node, 'color_ramp'):
        new_node.color_ramp.interpolation = source_node.color_ramp.interpolation
        new_node.color_ramp.color_mode = source_node.color_ramp.color_mode
        new_node.color_ramp.hue_interpolation = source_node.color_ramp.hue_interpolation
        
        # 复制所有颜色元素
        for i, element in enumerate(source_node.color_ramp.elements):
            if i == 0:
                # 第一个元素已经存在，只需设置其属性
                new_element = new_node.color_ramp.elements[0]
                new_element.position = element.position
                new_element.color = element.color
            else:
                # 添加新元素
                new_element = new_node.color_ramp.elements.new(element.position)
                new_element.color = element.color
    
    # 复制节点属性
    for input in source_node.inputs:
        new_node.inputs[input.name].default_value = input.default_value
        
    # 将新节点添加到映射字典中
    node_mapping[source_node] = new_node
    
    # 递归处理所有上游节点（输入连接）
    for input_socket in source_node.inputs:
        for link in input_socket.links:
            from_node = link.from_node
            from_socket = link.from_socket
            to_socket = link.to_socket
            
            # 递归复制上游节点
            new_from_node = copy_node_tree_recursive(from_node, nodes, links, node_mapping, to_location=[from_node.location.x - source_node.location.x + to_location[0], from_node.location.y - source_node.location.y + to_location[1]], parent=parent)
            
            # 找到对应的输出socket
            new_from_socket = None
            if from_socket.name in new_from_node.outputs:
                new_from_socket = new_from_node.outputs[from_socket.name]
            else:
                # 如果名称不匹配，尝试按索引查找
                try:
                    socket_index = list(from_node.outputs).index(from_socket)
                    if socket_index < len(new_from_node.outputs):
                        new_from_socket = new_from_node.outputs[socket_index]
                except ValueError:
                    pass
            
            # 找到新节点上对应的输入socket
            new_to_socket = None
            if to_socket.name in new_node.inputs:
                new_to_socket = new_node.inputs[to_socket.name]
            else:
                # 如果名称不匹配，尝试按索引查找
                try:
                    socket_index = list(source_node.inputs).index(to_socket)
                    if socket_index < len(new_node.inputs):
                        new_to_socket = new_node.inputs[socket_index]
                except ValueError:
                    pass
            
            # 创建连接
            if new_from_socket and new_to_socket:
                links.new(new_from_socket, new_to_socket)
    
    return new_node

def nodes_distance(node1, node2):
    """
    计算两个节点之间的欧几里得距离
    
    参数:
        node1: 第一个节点对象
        node2: 第二个节点对象
        
    返回:
        float: 两个节点之间的距离
    """
    loc1 = node1.location
    loc2 = node2.location
    
    # 使用欧几里得距离公式计算距离
    distance = ((loc1.x - loc2.x) ** 2 + (loc1.y - loc2.y) ** 2) ** 0.5
    return distance

def similar_nodes(node1, node2, visited_pairs=None):
    """
    递归向上游对比两个节点及其所有上游节点，检查每个接口的数值是否相同
    
    参数:
        node1: 第一个节点对象
        node2: 第二个节点对象
        visited_pairs: 已经对比过的节点对，防止无限循环
    
    返回:
        bool: 如果所有节点及其接口数值都相同返回True，否则返回False
    """
    # 初始化已访问节点对集合
    if visited_pairs is None:
        visited_pairs = set()
    
    # 如果节点对已经对比过，跳过以防止循环引用导致的无限递归
    node_pair = (node1, node2)
    if node_pair in visited_pairs:
        return True  # 假设已经对比过的节点对是相同的
    
    # 将当前节点对添加到已访问集合中
    visited_pairs.add(node_pair)
    
    # 首先检查节点本身是否相同类型
    if node1.bl_idname != node2.bl_idname:
        return False
    
    # 检查节点属性是否相同
    if hasattr(node1, 'node_tree') and hasattr(node2, 'node_tree'):
        if node1.node_tree != node2.node_tree:
            return False
    
    # 检查输入接口数量是否相同
    if len(node1.inputs) != len(node2.inputs):
        return False
    
    # 检查输出接口数量是否相同
    if len(node1.outputs) != len(node2.outputs):
        return False
    
    # 检查输入接口的默认值是否相同
    for i in range(len(node1.inputs)):
        input1 = node1.inputs[i]
        input2 = node2.inputs[i]
        
        # 检查接口名称是否相同
        if input1.name != input2.name:
            return False
        
        # 检查默认值是否相同
        if input1.default_value != input2.default_value:
            # 对于颜色等向量值，需要特殊处理
            if hasattr(input1.default_value, '__iter__') and hasattr(input2.default_value, '__iter__'):
                try:
                    if tuple(input1.default_value) != tuple(input2.default_value):
                        return False
                except:
                    # 如果无法转换为元组，则直接比较
                    if input1.default_value != input2.default_value:
                        return False
            else:
                return False
    
    # 检查特殊属性（如图像纹理节点的图像）
    if node1.type == 'TEX_IMAGE' and node2.type == 'TEX_IMAGE':
        if node1.image != node2.image:
            return False
        if node1.projection != node2.projection:
            return False
        if node1.interpolation != node2.interpolation:
            return False
    
    # 检查颜色渐变节点
    if node1.type == 'VALTORGB' and node2.type == 'VALTORGB':
        if node1.color_ramp.interpolation != node2.color_ramp.interpolation:
            return False
        if len(node1.color_ramp.elements) != len(node2.color_ramp.elements):
            return False
        for i in range(len(node1.color_ramp.elements)):
            if node1.color_ramp.elements[i].position != node2.color_ramp.elements[i].position:
                return False
            if tuple(node1.color_ramp.elements[i].color) != tuple(node2.color_ramp.elements[i].color):
                return False
    
    # 递归检查所有上游连接的节点
    upstream_nodes1 = {}
    upstream_nodes2 = {}
    
    # 收集第一个节点的所有上游节点
    for input_socket in node1.inputs:
        for link in input_socket.links:
            from_node = link.from_node
            from_socket_name = link.from_socket.name
            to_socket_name = link.to_socket.name
            upstream_nodes1[(from_socket_name, to_socket_name)] = from_node
    
    # 收集第二个节点的所有上游节点
    for input_socket in node2.inputs:
        for link in input_socket.links:
            from_node = link.from_node
            from_socket_name = link.from_socket.name
            to_socket_name = link.to_socket.name
            upstream_nodes2[(from_socket_name, to_socket_name)] = from_node
    
    # 检查上游连接是否相同
    if set(upstream_nodes1.keys()) != set(upstream_nodes2.keys()):
        return False
    
    # 递归对比所有上游节点
    for key in upstream_nodes1.keys():
        if key not in upstream_nodes2:
            return False
        
        upstream_node1 = upstream_nodes1[key]
        upstream_node2 = upstream_nodes2[key]
        
        # 递归对比上游节点
        if not similar_nodes(upstream_node1, upstream_node2, visited_pairs):
            return False
    
    return True