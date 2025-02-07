import bpy
import os
import time
import subprocess
import threading
import platform
import json
import zipfile

from ..config import __addon_name__
from ..__init__ import dir_resourcepacks_plans, dir_materials, dir_classification_basis, dir_blend_append

# crafter_resources_icons = bpy.utils.previews.new()
#==========通用操作==========
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

def make_json_together(dict1, dict2):
    '''
    递归合并json最底层的键值对
    dict1: 字典1
    dict2: 字典2
    '''
    for key, value in dict2.items():
        if key in dict1:
            if isinstance(dict1[key], dict) and isinstance(value, dict):
                make_json_together(dict1[key], value)
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

def add_node_moving_texture(node_tex, nodes, links):
    '''
    为基础色节点添加动态纹理节点并连接
    node_tex_base: 基础纹理节点
    nodes: 目标材质节点组
    links:目标材质连接组
    return:动态纹理节点
    '''
    if node_tex.image.size[0] != node_tex.image.size[1]:
        dir_image = os.path.dirname(node_tex.image.filepath)
        node_Moving_texture = nodes.new(type="ShaderNodeGroup")
        node_Moving_texture.location = (node_tex.location.x - 200, node_tex.location.y)
        node_Moving_texture.node_tree = bpy.data.node_groups["C-Moving_texture"]
        try:
            dir_mcmeta = os.path.join(bpy.path.abspath(dir_image), node_tex.image.name + ".mcmeta")
            with open(dir_mcmeta, 'r', encoding='utf-8') as file:
                mcmeta = json.load(file)
                frametime = mcmeta["animation"]["frametime"]
                node_Moving_texture.inputs["frametime"].default_value = frametime
        except:
            pass
        node_Moving_texture.inputs["row"].default_value = node_tex.image.size[1] / node_tex.image.size[0]
        links.new(node_Moving_texture.outputs["Vector"], node_tex.inputs["Vector"])
        return node_Moving_texture

def import_normal_and_PBR_and_link_all(node_tex_base, group_COn, nodes, links):
    '''
    以基础色节点添加法向贴图节点和PBR贴图节点、连接并添加动态纹理节点
    node_tex_base: 基础色节点
    group_COn: 材质组节点
    nodes: 目标材质节点组
    links:目标材质连接组
    '''
    name_image = fuq_bl_dot_number(node_tex_base.image.name)
    name_block = name_image[:-4]
    links.new(node_tex_base.outputs["Color"], group_COn.inputs["Base Color"])
    links.new(node_tex_base.outputs["Alpha"], group_COn.inputs["Alpha"])
    dir_image = os.path.dirname(node_tex_base.image.filepath)
    dir_n = os.path.join(dir_image,name_block + "_n.png")
    dir_s = os.path.join(dir_image,name_block + "_s.png")
    dir_a = os.path.join(dir_image,name_block + "_a.png")
    add_node_moving_texture(node_tex_base, nodes, links)
    if os.path.exists(bpy.path.abspath(dir_n)):
        node_tex = nodes.new(type="ShaderNodeTexImage")
        node_tex.location = (node_tex_base.location.x, node_tex_base.location.y - 300)
        node_tex.image = bpy.data.images.load(dir_n)
        node_tex.interpolation = "Closest"
        bpy.data.images[node_tex.image.name].colorspace_settings.name = "Non-Color"
        links.new(node_tex.outputs["Color"], group_COn.inputs["Normal"])
        links.new(node_tex.outputs["Alpha"], group_COn.inputs["Normal Alpha"])
        add_node_moving_texture(node_tex, nodes, links)
    if os.path.exists(bpy.path.abspath(dir_s)):
        node_tex = nodes.new(type="ShaderNodeTexImage")
        node_tex.location = (node_tex_base.location.x, node_tex_base.location.y - 600)
        node_tex.image = bpy.data.images.load(dir_s)
        node_tex.interpolation = "Closest"
        bpy.data.images[node_tex.image.name].colorspace_settings.name = "Non-Color"
        links.new(node_tex.outputs["Color"], group_COn.inputs["PBR"])
        links.new(node_tex.outputs["Alpha"], group_COn.inputs["PBR Alpha"])
        add_node_moving_texture(node_tex, nodes, links)
    elif os.path.exists(bpy.path.abspath(dir_a)):
        node_tex = nodes.new(type="ShaderNodeTexImage")
        node_tex.location = (node_tex_base.location.x, node_tex_base.location.y - 600)
        node_tex.image = bpy.data.images.load(dir_a)
        node_tex.interpolation = "Closest"
        bpy.data.images[node_tex.image.name].colorspace_settings.name = "Non-Color"
        links.new(node_tex.outputs["Color"], group_COn.inputs["PBR"])
        links.new(node_tex.outputs["Alpha"], group_COn.inputs["PBR Alpha"])
        add_node_moving_texture(node_tex, nodes, links)

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

class VIEW3D_OT_CrafterReloadResourcesPlans(bpy.types.Operator):#刷新资源包预设列表
    bl_label = "Reload Resources Plans"
    bl_idname = "crafter.reload_resources_plans"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        addon_prefs.Resources_Plans_List.clear()
        for folder in os.listdir(dir_resourcepacks_plans):
            if os.path.isdir(os.path.join(dir_resourcepacks_plans, folder)):
                plan_name = addon_prefs.Resources_Plans_List.add()
                plan_name.name = folder
        return {'FINISHED'}

class VIEW3D_OT_CrafterReloadResources(bpy.types.Operator):#刷新资源包列表
    bl_label = "Reload Resources"
    bl_idname = "crafter.reload_resources"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        dir_resourcepacks = os.path.join(dir_resourcepacks_plans, addon_prefs.Resources_Plans_List[addon_prefs.Resources_Plans_List_index].name)
        list_dir_resourcepacks = os.listdir(dir_resourcepacks)
        dir_crafter_json = os.path.join(dir_resourcepacks, "crafter.json")

        addon_prefs.Resources_List.clear()
        if "crafter.json" in list_dir_resourcepacks:
            with open(dir_crafter_json, "r", encoding="utf-8") as file:
                crafter_json = json.load(file)
            crafter_json_copy =crafter_json.copy()
            crafter_json = []
            for folder in list_dir_resourcepacks:
                if folder.endswith(".zip"):
                    dir_resourcepack = os.path.join(dir_resourcepacks, folder[:-4])
                    os.makedirs(dir_resourcepack, exist_ok=True)
                    try:
                        unzip(os.path.join(dir_resourcepacks, folder), dir_resourcepack)
                    except Exception as e:
                        print(e)
                    os.remove(os.path.join(dir_resourcepacks, folder))
                    crafter_json.append(folder[:-4])
            for resourcepack in crafter_json_copy:
                if  os.path.isdir(os.path.join(dir_resourcepacks, resourcepack)) and resourcepack not in crafter_json:
                    crafter_json.append(resourcepack)
            with open(dir_crafter_json, "w", encoding="utf-8") as file:
                json.dump(crafter_json, file, ensure_ascii=False, indent=4)
            for resourcepack in crafter_json:
                # dir_resourcepack = os.path.join(dir_resourcepacks, resourcepack)
                # crafter_resources_icons.clear()
                resourcespack_name = addon_prefs.Resources_List.add()
                resourcespack_name.name = resourcepack
                # if "pack.png" in os.listdir(dir_resourcepack):
                #     crafter_resources_icons.load("crafter_resources" + resourcepack,os.path.join(dir_resourcepack,"pack.png"),"IMAGE")
                #     for icon in crafter_resources_icons:
        else:
            crafter_json = []
            for folder in list_dir_resourcepacks:
                if os.path.isdir(os.path.join(dir_resourcepacks, folder)):
                    crafter_json.append(folder)
                if folder.endswith(".zip"):
                    dir_resourcepack = os.path.join(dir_resourcepacks, folder[:-4])
                    os.makedirs(dir_resourcepack, exist_ok=True)
                    try:
                        unzip(os.path.join(dir_resourcepacks, folder), dir_resourcepack)
                    except Exception as e:
                        print(e)
                    os.remove(os.path.join(dir_resourcepacks, folder))
                    crafter_json.append(folder[:-4])
            with open(dir_crafter_json, "w", encoding="utf-8") as file:
                json.dump(crafter_json, file, ensure_ascii=False, indent=4)
            bpy.ops.crafter.reload_resources()

        return {'FINISHED'}

class VIEW3D_OT_CrafterReloadMaterials(bpy.types.Operator):#刷新材质列表
    bl_label = "Reload Materials"
    bl_idname = "crafter.reload_materials"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        addon_prefs.Materials_List.clear()
        for folder in os.listdir(dir_materials):
            base, extension = os.path.splitext(folder)
            if extension == ".blend":
                material_name = addon_prefs.Materials_List.add()
                material_name.name = base
        return {'FINISHED'}

class VIEW3D_OT_CrafterReloadClassificationBasis(bpy.types.Operator):#刷新分类依据菜单
    bl_label = "Reload Classification Basis"
    bl_idname = "crafter.reload_classification_basis"
    bl_description = " "
    bl_options = {'REGISTER'}
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        addon_prefs.Classification_Basis_List.clear()
        for folder in os.listdir(dir_classification_basis):
            if os.path.isdir(os.path.join(dir_classification_basis, folder)):
                plan_name = addon_prefs.Classification_Basis_List.add()
                plan_name.name = folder

        return {'FINISHED'}

class VIEW3D_OT_CrafterReloadAll(bpy.types.Operator):#刷新全部
    bl_label = "Reload"
    bl_idname = "crafter.reload_all"
    bl_description = " "
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        bpy.ops.crafter.reload_resources_plans()
        bpy.ops.crafter.reload_resources()
        bpy.ops.crafter.reload_materials()
        bpy.ops.crafter.reload_classification_basis()
        return {'FINISHED'}

#==========导入世界操作==========
class VIEW3D_OT_CrafterImportWorld(bpy.types.Operator):#导入世界
    bl_label = "Import World"
    bl_idname = "crafter.import_world"
    bl_description = "Import world"
    bl_options = {'INTERNAL'}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        worldconfig = {
            "worldPath": addon_prefs.World_Path,
            "biomeMappingFile": "config\\mappings\\biomes_mapping.json",
            "solid": addon_prefs.solid,
            "minX": min(addon_prefs.XYZ_1[0], addon_prefs.XYZ_2[0]),
            "maxX": max(addon_prefs.XYZ_1[0], addon_prefs.XYZ_2[0]),
            "minY": min(addon_prefs.XYZ_1[1], addon_prefs.XYZ_2[1]),
            "maxY": max(addon_prefs.XYZ_1[1], addon_prefs.XYZ_2[1]),
            "minZ": min(addon_prefs.XYZ_1[2], addon_prefs.XYZ_2[2]),
            "maxZ": max(addon_prefs.XYZ_1[2], addon_prefs.XYZ_2[2]),
            "status": 0,
        }

        parent_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        importer_dir = os.path.join(parent_dir, "importer")
        config_dir = os.path.join(importer_dir, "config")
        os.makedirs(config_dir, exist_ok=True)

        config_file_path = os.path.join(config_dir, "config.json")

        with open(config_file_path, 'w', encoding='gbk') as config_file:
            for key, value in worldconfig.items():
                config_file.write(f"{key} = {value}\n")

        self.report({'INFO'}, f"World config saved to {config_file_path}")

        importer_exe = os.path.join(importer_dir, "WorldImporter.exe")
        
        if os.path.exists(importer_exe):
            try:
                # 在新的进程中运行WorldImporter.exe
                CREATE_NEW_PROCESS_GROUP = 0x00000200
                DETACHED_PROCESS = 0x00000008
                subprocess.Popen(
                    [importer_exe],
                    cwd=importer_dir,
                    creationflags=CREATE_NEW_PROCESS_GROUP | DETACHED_PROCESS
                )
                self.report({'INFO'}, f"WorldImporter.exe started in a new process")

                # 使用线程监控状态
                threading.Thread(target=self.monitor_status, args=(config_file_path,), daemon=True).start()

            except Exception as e:
                self.report({'ERROR'}, f"Error: {e}")
        else:
            self.report({'ERROR'}, f"WorldImporter.exe not found at {importer_exe}")

        return {'FINISHED'}

    def monitor_status(self, config_file_path):
        while True:
            time.sleep(0.2)  # 每秒检查一次
            try:
                with open(config_file_path, 'r', encoding='gbk') as config_file:
                    content = config_file.read()
                    if "status = 3" in content:
                        content = content.replace("status = 3", "status = 0")
                        with open(config_file_path, 'w', encoding='gbk') as config_file:
                            config_file.write(content)
                        bpy.app.timers.register(self.import_output)
                        break
            except Exception as e:
                print(f"Error reading config file: {e}")
                continue

    def import_output(self):
        parent_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        importer_dir = os.path.join(parent_dir, "importer")
        output_obj = os.path.join(importer_dir, "output.obj")
        if os.path.exists(output_obj):
            bpy.ops.wm.obj_import(filepath=output_obj)
            self.report({'INFO'}, f"Imported {output_obj}")
        else:
            self.report({'WARNING'}, f"output.obj not found at {output_obj}")

class VIEW3D_OT_CrafterImportSurfaceWorld(bpy.types.Operator):#导入表层世界
    bl_label = "Import Surface World"
    bl_idname = "crafter.import_surface_world"
    bl_description = "Import the surface world"
    bl_options = {'REGISTER', 'UNDO'}
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        addon_prefs.solid = 0
        bpy.ops.crafter.improt_world()
        return {'FINISHED'}

class VIEW3D_OT_CrafterImportSolidArea(bpy.types.Operator):#导入可编辑区域==========未完善==========
    bl_label = "Import Solid Area"
    bl_idname = "crafter.import_solid_area"
    bl_description = "Import the solid area"
    bl_options = {'REGISTER', 'UNDO'}
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return False#完善后开启

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        #这里应该还要删除物体在坐标内的顶点，但时间紧任务重，稍后再写
        addon_prefs.solid = 1
        bpy.ops.crafter.improt_world()
        return {'FINISHED'}

#==========导入资源包操作==========
class VIEW3D_OT_CrafterOpenResourcesPlans(bpy.types.Operator):#打开资源包列表文件夹
    bl_label = "Open Resources Plans"
    bl_idname = "crafter.open_resources_plans"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        folder_path = dir_resourcepacks_plans
        open_folder(folder_path)

        return {'FINISHED'}

class VIEW3D_OT_CrafterUpResource(bpy.types.Operator):#提高资源包优先级
    bl_label = "Up resource's priority"    
    bl_idname = "crafter.up_resource"
    bl_description = " "

    @classmethod
    def poll(cls, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        return addon_prefs.Resources_List_index > 0

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        dir_resourcepacks = os.path.join(dir_resourcepacks_plans, addon_prefs.Resources_Plans_List[addon_prefs.Resources_Plans_List_index].name)
        dir_crafter_json = os.path.join(dir_resourcepacks, "crafter.json")

        with open(dir_crafter_json, 'r', encoding='utf-8') as file:
            crafter_json = json.load(file)
        target_name = addon_prefs.Resources_List[addon_prefs.Resources_List_index].name
        for i in range(len(crafter_json)):
            if crafter_json[i] == target_name:
                if i > 0:
                    crafter_json[i], crafter_json[i - 1] = crafter_json[i - 1], crafter_json[i]
                    addon_prefs.Resources_List_index -= 1
                    break
        with open(dir_crafter_json, 'w', encoding='utf-8') as file:
            json.dump(crafter_json, file, indent=4)
        bpy.ops.crafter.reload_resources()

        return {'FINISHED'}

class VIEW3D_OT_CrafterDownResource(bpy.types.Operator):#降低资源包优先级
    bl_label = "Down resource's priority"    
    bl_idname = "crafter.down_resource"
    bl_description = " "

    @classmethod
    def poll(cls, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        return addon_prefs.Resources_List_index < len(addon_prefs.Resources_List) - 1

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        dir_resourcepacks = os.path.join(dir_resourcepacks_plans, addon_prefs.Resources_Plans_List[addon_prefs.Resources_Plans_List_index].name)
        dir_crafter_json = os.path.join(dir_resourcepacks, "crafter.json")

        with open(dir_crafter_json, 'r', encoding='utf-8') as file:
            crafter_json = json.load(file)
        target_name = addon_prefs.Resources_List[addon_prefs.Resources_List_index].name
        for i in range(len(crafter_json)):
            if crafter_json[i] == target_name:
                if i < len(addon_prefs.Resources_List) - 1:
                    crafter_json[i], crafter_json[i + 1] = crafter_json[i + 1], crafter_json[i]
                    addon_prefs.Resources_List_index += 1
                    break
        with open(dir_crafter_json, 'w', encoding='utf-8') as file:
            json.dump(crafter_json, file, indent=4)
        bpy.ops.crafter.reload_resources()

        return {'FINISHED'}

class VIEW3D_OT_CrafterImportResources(bpy.types.Operator):#导入资源包
    bl_label = "Import Resources"
    bl_idname = "crafter.import_resources"
    bl_description = "Import resources"
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return any(obj.type == "MESH" for obj in context.selected_objects)

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
        bpy.ops.crafter.reload_all()
        dir_resourcepacks = os.path.join(dir_resourcepacks_plans, addon_prefs.Resources_Plans_List[addon_prefs.Resources_Plans_List_index].name)
        dir_crafter_json = os.path.join(dir_resourcepacks, "crafter.json")
        with open(dir_crafter_json, 'r', encoding='utf-8') as file:
            crafter_json = json.load(file)
        images = []
        for resource in crafter_json:
            dir_resourcepack = os.path.join(dir_resourcepacks, resource)
            dir_assets =os.path.join(dir_resourcepack, "assets")
            files_list = []
            for root, dirs, files in os.walk(dir_assets):
                for file in files:
                    if not root.endswith("colormap"):
                        file_path = os.path.join(root, file)
                        files_list.append((file, file_path))
            images.append(files_list)
        is_original = False
        if len(crafter_json) == 0:
            is_original = True
        for object in context.selected_objects:
            if object.name == "CrafterIn":
                continue
            if object.type == "MESH":
                for material in object.data.materials:
                    node_tree_material = material.node_tree
                    nodes = node_tree_material.nodes
                    links = node_tree_material.links
                    is_materialed = False
                    for node in nodes:
                        if node.type == 'TEX_IMAGE':
                            if node.image == None:
                                nodes.remove(node)
                            else:
                                name_image = fuq_bl_dot_number(node.image.name)
                                if name_image.endswith("_n.png") or name_image.endswith("_s.png") or name_image.endswith("_a.png"):
                                    # 移除pbr、法向材质节点
                                    bpy.data.images.remove(node.image)
                                    nodes.remove(node)
                                elif name_image.endswith(".png"):
                                    node.interpolation = "Closest"
                                    if not is_original:
                                        node_tex_base = node
                                        found_texture = False
                                        i = 0
                                        while i < len(images) and not found_texture:
                                            j = 0
                                            while j < len(images[i]) and not found_texture:
                                                if name_image == images[i][j][0]:
                                                    node.image = bpy.data.images.load(images[i][j][1])
                                                    found_texture = True
                                                j += 1
                                            i += 1
                        elif node.type == 'GROUP':
                            if node.node_tree != None:
                                if node.node_tree.name.startswith("CO-"):
                                    is_materialed = True
                                    group_COn = node
                    if is_materialed and (not is_original):
                        import_normal_and_PBR_and_link_all(node_tex_base=node_tex_base, group_COn=group_COn, nodes=nodes, links=links)

        return {'FINISHED'}

class VIEW3D_OT_CrafterSetTextureInterpolation(bpy.types.Operator):#设置纹理插值
    bl_label = "Set Texture Interpolation"    
    bl_idname = "crafter.set_texture_interpolation"
    bl_description = "Set Texture Interpolation"
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context):
        return any(obj.type == "MESH" for obj in context.selected_objects)

    def execute(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        for object in context.selected_objects:
            if object.type == "MESH":
                for material in object.data.materials:
                    for node in material.node_tree.nodes:
                        if node.type == 'TEX_IMAGE':
                            node.interpolation = addon_prefs.Texture_Interpolation
        return {'FINISHED'}

#==========加载材质操作==========
class VIEW3D_OT_CrafterSetPBRParser(bpy.types.Operator):#设置PBR解析器
    bl_label = "Set PBR Parser"
    bl_idname = "crafter.set_pbr_parser"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        COs = []
        for group in bpy.data.node_groups:
            if group.name.startswith("CO-"):
                COs.append(group.name)
        for aCO in COs:
            group_CO = bpy.data.node_groups[aCO]
            nodes = group_CO.nodes
            links = group_CO.links
            for node in nodes:
                if node.type == "GROUP_INPUT":
                    node_input = node
                if node.type == "GROUP":
                    if node.node_tree.name != None:
                        if node.node_tree.name.startswith("C-"):
                            node_group_C_Group = node
                        if node.node_tree.name.startswith("CI-"):
                            group_CI = node
            try:
                node_group_C_Group.node_tree = bpy.data.node_groups["C-" + addon_prefs.PBR_Parser]
            except:
                pass
            for input in group_CI.inputs:
                try:
                    links.new(input, node_group_C_Group.outputs[input.name])
                except:
                    pass
            for input in node_group_C_Group.inputs:
                try:
                    links.new(input, node_input.outputs[input.name])
                except:
                    pass
        return {'FINISHED'}

class VIEW3D_OT_CrafterOpenMaterials(bpy.types.Operator):#打开材质列表文件夹
    bl_label = "Open Materials"
    bl_idname = "crafter.open_materials"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        folder_path = dir_materials
        open_folder(folder_path)

        return {'FINISHED'}

class VIEW3D_OT_CrafterLoadMaterial(bpy.types.Operator):#加载材质
    bl_label = "Load Material"
    bl_idname = "crafter.load_material"
    bl_description = "Load Material"
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return any(obj.type == "MESH" for obj in context.selected_objects)

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        bpy.ops.crafter.reload_all()
        bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
        # 删除startswith(CO-)、startswith(CI-)节点组、startswith(C-)节点组
        for node in bpy.data.node_groups:
            if node.name.startswith("CO-") or node.name.startswith("CI-") or node.name.startswith("C-"):
                bpy.data.node_groups.remove(node)
        # 删除CrafterIn物体、材质
        try:
            bpy.data.objects.remove(bpy.data.objects["CrafterIn"])
        except:
            pass
        try:
            bpy.data.materials.remove(bpy.data.materials["CrafterIn"], do_unlink=True)
        except:
            pass
        # 导入CO-节点组
        CO_node_groups = ["CO-","C-Moving_texture","C-lab_PBR_1.3","C-old_continuum","C-old_BSL","C-SEUS_PBR"]
        with bpy.data.libraries.load(dir_blend_append, link=False) as (data_from, data_to):
            data_to.node_groups = [name for name in data_from.node_groups if name in CO_node_groups]
        for node_group in bpy.data.node_groups:
            if node_group.name in CO_node_groups:
                node_group.use_fake_user = True
        # 导入CrafterIn物体、材质、startswith(CI-)
        blend_material_dir = os.path.join(dir_materials, addon_prefs.Materials_List[addon_prefs.Materials_List_index].name + ".blend")
        with bpy.data.libraries.load(blend_material_dir, link=False) as (data_from, data_to):
            data_to.objects = [name for name in data_from.objects if name == "CrafterIn"]
        if "CrafterIns"  in bpy.data.collections:
            collection_CrafterIns = bpy.data.collections["CrafterIns"]
        else:
            collection_CrafterIns = bpy.data.collections.new(name="CrafterIns")
            bpy.context.scene.collection.children.link(collection_CrafterIns)
        collection_CrafterIns.objects.link(bpy.data.objects["CrafterIn"])
        bpy.data.objects["CrafterIn"].hide_viewport = True
        bpy.data.objects["CrafterIn"].hide_render = True
        # 获取分类依据地址
        classification_folder_name = addon_prefs.Classification_Basis_List[addon_prefs.Classification_Basis_List_index].name
        classification_folder_dir = os.path.join(dir_classification_basis, classification_folder_name)
        # 初始化COs，classification_list,banlist
        COs = ["CO-"]
        classification_list = {}
        banlist = []
        banlist_key_words = []
        # 获取classification_list
        for filename in os.listdir(classification_folder_dir):
            file_path = os.path.join(classification_folder_dir, filename)
            if filename.endswith(".json"):
                try:
                    with open(file_path, 'r', encoding='utf-8') as file:
                        data = json.load(file)
                        classification_list = make_json_together(classification_list, data)
                        if "banlist" in data:
                            banlist.extend(data["banlist"])
                        if "banlist_key_words" in data:
                            banlist_key_words.extend(data["banlist_key_words"])
                except Exception as e:
                    print(e)
        # 创建所有startswith(CO-)节点组
        group_CO = bpy.data.node_groups['CO-']
        for type_name in classification_list:
            if type_name == "banlist" or type_name == "banlist_key_words":
                continue
            for group_name in classification_list[type_name]:
                group_new = group_CO.copy()
                group_new.name = "CO-" + group_name
                COs.append("CO-" + group_name)
        # 删去原有着色器 并 重新添加startswith(CO-)节点组
        for object in context.selected_objects:
            if object.name == "CrafterIn":
                continue
            if object.type == "MESH":
                for material in object.data.materials:
                    node_tree_material = material.node_tree
                    nodes = node_tree_material.nodes
                    links = node_tree_material.links
                    
                    node_tex_base = None
                    for node in nodes:
                        if node.type == "TEX_IMAGE":
                            if node.image != None:
                                name_image = fuq_bl_dot_number(node.image.name)
                                if name_image.endswith("_n.png") or name_image.endswith("_s.png") or name_image.endswith("_a.png"):
                                    bpy.data.images.remove(node.image)
                                    nodes.remove(node)
                                elif name_image.endswith(".png"):
                                    node.interpolation = "Closest"
                                    node_tex_base = node
                                    block_name = fuq_bl_dot_number(node_tex_base.image.name)
                                    real_block_name = block_name[:-4]
                    # 注释部分为旧的通过材质名获得mod_name和type_name的方式，暂作保留

                    # real_block_name = material.name
                    # real_block_name = fuq_bl_dot_number(real_block_name)
                    # mod_name = "minecraft"
                    # type_name = "block"
                    # 获得real_material_name(如果有mod_name,type_name,获得之,但目前好像没用...)
                    # last_hen_index = real_material_name.rfind('-')
                    # if not last_hen_index == -1:
                    #     mod_and_type = real_material_name[:last_hen_index]
                    #     real_material_name = real_material_name[last_hen_index+1:]
                    #     last____index = mod_and_type.rfind('_')
                    #     mod_name = real_material_name[:last____index]
                    #     type_name = real_material_name[last____index+1:last_hen_index]
                    # 如果在banlist里直接跳过
                    ban = False
                    for ban_key in banlist_key_words:
                        if real_block_name in ban_key:
                            ban = True
                            break
                    if ban or real_block_name in banlist:
                        continue
                    # 设置材质置换方式为仅置换
                    material.displacement_method = "DISPLACEMENT"
                    #获得node_output 并 删去无内容节点组 并 删去Rain_value
                    for node in nodes:
                        if node.type == "OUTPUT_MATERIAL" and node.is_active_output:
                            node_output = node
                        if (node.type == "GROUP" and node.node_tree == None) or (node.type == "VALUE" and node.name.startswith("Rain_value")):
                            node_tree_material.nodes.remove(node)
                    # 删去原有着色器
                    try:
                        from_node = node_output.inputs[0].links[0].from_node
                        if from_node.type == "BSDF_PRINCIPLED" and material.name != "CrafterIn":
                            node_tree_material.nodes.remove(from_node)
                    except:
                        pass
                    # 重新添加startswith(CO-)节点组
                    group_COn = nodes.new(type="ShaderNodeGroup")
                    group_COn.location = (node_output.location.x - 200, node_output.location.y)
                    found = False
                    for type_name in classification_list:
                        if type_name == "banlist" or type_name == "banlist_key_words":
                            continue
                        for group_name in classification_list[type_name]:
                            banout = False
                            if "banlist_key_words" in classification_list[type_name][group_name]:
                                for item in classification_list[type_name][group_name]["banlist"]:
                                    if item in real_block_name:
                                        banout = True
                                        print("in ban",item)
                                        break
                            if banout:
                                break
                            if "banlist" in classification_list[type_name][group_name]:
                                for item in classification_list[type_name][group_name]["banlist"]:
                                    if item == real_block_name:
                                        print("= Ban", item)
                                        banout = True
                                        break
                            if banout:
                                break
                            if "key_words" in classification_list[type_name][group_name]:
                                for item in classification_list[type_name][group_name]["key_words"]:
                                    if item in real_block_name:
                                        group_COn.node_tree = bpy.data.node_groups["CO-" + group_name]
                                        found = True
                                        break
                            if found:
                                break
                            if "full_name" in classification_list[type_name][group_name]:
                                for item in classification_list[type_name][group_name]["full_name"]:
                                    if item == real_block_name:
                                        group_COn.node_tree = bpy.data.node_groups["CO-" + group_name]
                                        found = True
                                        break
                            if found:
                                break
                        if found:
                            break
                    if not found:
                        group_COn.node_tree = bpy.data.node_groups["CO-"]
                    # 提供Rain_value节点
                    CO_Crafter_Rain_value = nodes.new(type="ShaderNodeValue")
                    CO_Crafter_Rain_value.location = (group_COn.location.x - 200, group_COn.location.y)
                    CO_Crafter_Rain_value.bl_label = "Value"
                    CO_Crafter_Rain_value.name = "Rain_value"
                    fcurve = CO_Crafter_Rain_value.outputs["Value"].driver_add("default_value")
                    driver = fcurve.driver
                    var = driver.variables.new()
                    var.name = "Crafter_rain_var"
                    var.type = 'SINGLE_PROP'
                    var.targets[0].id_type = 'SCENE'
                    var.targets[0].id = bpy.context.scene
                    var.targets[0].data_path = '["Crafter_rain"]'
                    driver.expression = "Crafter_rain_var"
                    links.new(CO_Crafter_Rain_value.outputs["Value"], group_COn.inputs["Rain"])
                    # 连接CO节点
                    for output in group_COn.outputs:
                        links.new(output, node_output.inputs[output.name])
                    if node_tex_base == None:
                        continue
                    import_normal_and_PBR_and_link_all(node_tex_base=node_tex_base, group_COn=group_COn, nodes=nodes, links=links)
        #连接startswith(CO-)、startswith(CI-)节点组
        for aCO in COs:
            group_CO = bpy.data.node_groups[aCO]
            nodes = group_CO.nodes
            links = group_CO.links
            for node in nodes:
                if node.type == "GROUP_OUTPUT" and node.is_active_output:
                    node_output = node
                if node.type == "GROUP_INPUT":
                    node_input = node
                if node.type == "GROUP":
                    if node.node_tree.name != None:
                        if node.node_tree.name.startswith("C-"):
                            node_group_C_Group = node
            group_CI = nodes.new(type='ShaderNodeGroup')
            group_CI.location = (node_output.location.x - 200, node_output.location.y)
            try:
                group_CI.node_tree = bpy.data.node_groups["CI-" + aCO[3:]]
            except:
                group_CI.node_tree = bpy.data.node_groups["CI-"]
            try:
                node_group_C_Group.node_tree = bpy.data.node_groups["C-" + addon_prefs.PBR_Parser]
            except:
                pass
            for output in group_CI.outputs:
                try:
                    links.new(output, node_output.inputs[output.name])
                except:
                    pass
            for input in group_CI.inputs:
                try:
                    links.new(input, node_input.outputs[input.name])
                except:
                    pass
                try:
                    links.new(input, node_group_C_Group.outputs[input.name])
                except:
                    pass
            for input in node_group_C_Group.inputs:
                try:
                    links.new(input, node_input.outputs[input.name])
                except:
                    pass
        return {'FINISHED'}

class VIEW3D_OT_CrafterOpenClassificationBasis(bpy.types.Operator):#打开分类依据文件夹
    bl_label = "Open Classification Basis"
    bl_idname = "crafter.open_classification_basis"
    bl_description = " "
    bl_options = {'REGISTER'}
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        folder_path = dir_classification_basis
        open_folder(folder_path)

        return {'FINISHED'}




