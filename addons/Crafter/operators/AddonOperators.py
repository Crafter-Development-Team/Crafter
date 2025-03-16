import bpy
import os
import time
import subprocess
import threading
import platform
import json
import zipfile

from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from ..__init__ import dir_cafter_data, dir_resourcepacks_plans, dir_materials, dir_classification_basis, dir_blend_append, dir_init_main, dir_backgrounds

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

def add_node_moving_texture_without_list(node_tex, nodes, links):
    '''
    为基础色节点添加动态纹理节点并连接
    node_tex_base: 基础纹理节点
    nodes: 目标材质节点组
    links:目标材质连接组
    return:动态纹理节点
    '''
    dir_image = os.path.dirname(node_tex.image.filepath)
    dir_mcmeta = os.path.join(bpy.path.abspath(dir_image), node_tex.image.name + ".mcmeta")
    if os.path.exists(dir_mcmeta):
        node_Moving_texture = nodes.new(type="ShaderNodeGroup")
        node_Moving_texture.location = (node_tex.location.x - 200, node_tex.location.y)
        node_Moving_texture.node_tree = bpy.data.node_groups["C-Moving_texture"]
        try:
            with open(dir_mcmeta, 'r', encoding='utf-8') as file:
                mcmeta = json.load(file)
                frametime = mcmeta["animation"]["frametime"]
                node_Moving_texture.inputs["frametime"].default_value = frametime
        except:
            pass
        node_Moving_texture.inputs["row"].default_value = node_tex.image.size[1] / node_tex.image.size[0]
        links.new(node_Moving_texture.outputs["Vector"], node_tex.inputs["Vector"])
        return node_Moving_texture

def load_normal_and_PBR_and_link_all(node_tex_base, group_COn, nodes, links):
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
    add_node_moving_texture_without_list(node_tex_base, nodes, links)
    if os.path.exists(bpy.path.abspath(dir_n)):
        node_tex = nodes.new(type="ShaderNodeTexImage")
        node_tex.location = (node_tex_base.location.x, node_tex_base.location.y - 300)
        node_tex.image = bpy.data.images.load(dir_n)
        node_tex.interpolation = "Closest"
        bpy.data.images[node_tex.image.name].colorspace_settings.name = "Non-Color"
        links.new(node_tex.outputs["Color"], group_COn.inputs["Normal"])
        links.new(node_tex.outputs["Alpha"], group_COn.inputs["Normal Alpha"])
        add_node_moving_texture_without_list(node_tex, nodes, links)
    if os.path.exists(bpy.path.abspath(dir_s)):
        node_tex = nodes.new(type="ShaderNodeTexImage")
        node_tex.location = (node_tex_base.location.x, node_tex_base.location.y - 600)
        node_tex.image = bpy.data.images.load(dir_s)
        node_tex.interpolation = "Closest"
        bpy.data.images[node_tex.image.name].colorspace_settings.name = "Non-Color"
        links.new(node_tex.outputs["Color"], group_COn.inputs["PBR"])
        links.new(node_tex.outputs["Alpha"], group_COn.inputs["PBR Alpha"])
        add_node_moving_texture_without_list(node_tex, nodes, links)
    elif os.path.exists(bpy.path.abspath(dir_a)):
        node_tex = nodes.new(type="ShaderNodeTexImage")
        node_tex.location = (node_tex_base.location.x, node_tex_base.location.y - 600)
        node_tex.image = bpy.data.images.load(dir_a)
        node_tex.interpolation = "Closest"
        bpy.data.images[node_tex.image.name].colorspace_settings.name = "Non-Color"
        links.new(node_tex.outputs["Color"], group_COn.inputs["PBR"])
        links.new(node_tex.outputs["Alpha"], group_COn.inputs["PBR Alpha"])
        add_node_moving_texture_without_list(node_tex, nodes, links)

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
        for object_material in object.data.materials:
            list_name_object_material.append(object_material.name)
        if object.name != "CrafterIn" and object.type == "MESH" and object.data.materials:
            for name_material in list_name_object_material:
                if (name_material not in context.scene.Crafter_mcmts) and (name_material != "CrafterIn"):
                    new_mcmt = context.scene.Crafter_mcmts.add()
                    new_mcmt.name = name_material
        for i in range(len(context.scene.Crafter_mcmts)-1,-1,-1):
            if context.scene.Crafter_mcmts[i].name not in list_name_context_material:
                context.scene.Crafter_mcmts.remove(i)

def find_CO_group(classification_list,real_block_name,group_CO):
    '''
    classification_list: 分类列表
    real_block_name: 真实方块名称
    group_COn: CO节点
    return: 是否找到
    '''
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
                        break
            if banout:
                continue
            if "banlist" in classification_list[type_name][group_name]:
                for item in classification_list[type_name][group_name]["banlist"]:
                    if item == real_block_name:
                        banout = True
                        break
            if banout:
                continue
            if "key_words" in classification_list[type_name][group_name]:
                for item in classification_list[type_name][group_name]["key_words"]:
                    if item in real_block_name:
                        group_CO.node_tree = bpy.data.node_groups["CO-" + group_name]
                        found = True
                        break
            if found:
                break
            if "full_name" in classification_list[type_name][group_name]:
                for item in classification_list[type_name][group_name]["full_name"]:
                    if item == real_block_name:
                        group_CO.node_tree = bpy.data.node_groups["CO-" + group_name]
                        found = True
                        break
            if found:
                break
        if found:
            break
    if not found:
                group_CO.node_tree = bpy.data.node_groups["CO-"]

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

class VIEW3D_OT_CrafterReloadBackgrounds(bpy.types.Operator):#刷新背景列表
    bl_label = "Reload Backgrounds"
    bl_idname = "crafter.reload_background"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        addon_prefs.Backgrounds_List.clear()
        for folder in os.listdir(dir_backgrounds):
            base, extension = os.path.splitext(folder)
            if extension == ".blend":
                material_name = addon_prefs.Backgrounds_List.add()
                material_name.name = base
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
        bpy.ops.crafter.reload_background()
        return {'FINISHED'}

#==========导入世界操作==========
class VIEW3D_UL_CrafterHistoryWorldRootsList(bpy.types.UIList):
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        layout.label(text=item.name)

class VIEW3D_UL_CrafterHistoryWorldVersionsList(bpy.types.UIList):
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        layout.label(text=item.name)

class VIEW3D_UL_CrafterHistoryWorldSavesList(bpy.types.UIList):
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        layout.label(text=item.name)

class VIEW3D_UL_CrafterHistoryWorldSettingsList(bpy.types.UIList):
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        settings=item.name.split(" ")
        layout.label(text=f"{settings[0]}     {settings[1]}     {settings[2]}   |   {settings[3]}     {settings[4]}     {settings[5]}")

class VIEW3D_OT_CrafterReloadHistoryWorldsList(bpy.types.Operator):#刷新历史世界列表
    bl_label = "Reload History Worlds List"
    bl_idname = "crafter.reload_history_worlds_list"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        dir_json_history_worlds = os.path.join(dir_cafter_data, "history_worlds.json")
        with open(dir_json_history_worlds, 'r', encoding='utf-8') as file:
            json_history_worlds = json.load(file)
        
        addon_prefs.History_World_Roots_List.clear()
        addon_prefs.History_World_Versions_List.clear()
        addon_prefs.History_World_Saves_List.clear()
        addon_prefs.History_World_Settings_List.clear()
        for root in json_history_worlds:
            history_world_root = addon_prefs.History_World_Roots_List.add()
            history_world_root.name = root
        if len(addon_prefs.History_World_Roots_List) > 0:
            if addon_prefs.History_World_Roots_List_index <0 or addon_prefs.History_World_Roots_List_index >= len(addon_prefs.History_World_Roots_List):
                addon_prefs.History_World_Roots_List_index = 0
            for version in json_history_worlds[addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name]:
                history_world_version = addon_prefs.History_World_Versions_List.add()
                history_world_version.name = version
            if len(addon_prefs.History_World_Versions_List) > 0:
                if addon_prefs.History_World_Versions_List_index <0 or addon_prefs.History_World_Versions_List_index >= len(addon_prefs.History_World_Versions_List):
                    addon_prefs.History_World_Versions_List_index = 0
                for save in json_history_worlds[addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name][addon_prefs.History_World_Versions_List[addon_prefs.History_World_Versions_List_index].name]:
                    history_world_save = addon_prefs.History_World_Saves_List.add()
                    history_world_save.name = save
                if len(addon_prefs.History_World_Saves_List) > 0:
                    if addon_prefs.History_World_Saves_List_index <0 or addon_prefs.History_World_Saves_List_index >= len(addon_prefs.History_World_Saves_List):
                        addon_prefs.History_World_Saves_List_index = 0
                    for settings in json_history_worlds[addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name][addon_prefs.History_World_Versions_List[addon_prefs.History_World_Versions_List_index].name][addon_prefs.History_World_Saves_List[addon_prefs.History_World_Saves_List_index].name]:
                        history_world_setting = addon_prefs.History_World_Settings_List.add()
                        history_world_setting.name = f"{settings[0][0]} {settings[0][1]} {settings[0][2]} {settings[1][0]} {settings[1][1]} {settings[1][2]}" 
        return {'FINISHED'}

class VIEW3D_OT_UseCrafterHistoryWorlds(bpy.types.Operator):#使用历史世界
    bl_label = "History Worlds"
    bl_idname = "crafter.use_history_worlds"
    bl_description = "To use the history world settings"

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
    def execute(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        if len(addon_prefs.History_World_Saves_List) > 0:
            dir_root = addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name
            dir_version = os.path.join(dir_root,"versions",addon_prefs.History_World_Versions_List[addon_prefs.History_World_Versions_List_index].name)
            dir_save = os.path.join(dir_version,"saves",addon_prefs.History_World_Saves_List[addon_prefs.History_World_Saves_List_index].name)
            addon_prefs.World_Path = dir_save
            if len(addon_prefs.History_World_Settings_List) > 0:
                settings = addon_prefs.History_World_Settings_List[addon_prefs.History_World_Settings_List_index].name
                setting = settings.split(" ")
                addon_prefs.XYZ_1 = (int(setting[0]),int(setting[1]),int(setting[2]))
                addon_prefs.XYZ_2 = (int(setting[3]),int(setting[4]),int(setting[5]))
        return {'FINISHED'}

    def invoke(self, context, event):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        #补全history_worlds.json
        dir_json_history_worlds = os.path.join(dir_cafter_data, "history_worlds.json")
        if not os.path.exists(dir_json_history_worlds):
            self.report({ 'ERROR' }, "Haven't history worlds")
            return {'CANCELLED'}
        with open(dir_json_history_worlds, 'r', encoding='utf-8') as file:
            json_history_worlds = json.load(file)
        if type(json_history_worlds) == list:
            json_history_worlds = {}
        for root in reversed(json_history_worlds):
            #地址不存在则移除该root
            if not os.path.exists(root):
                del json_history_worlds[root]
                continue
            for version in reversed(json_history_worlds[root]):
                #版本不存在则移除该version
                if not os.path.exists(os.path.join(root, "versions", version)):
                    del json_history_worlds[root][version]
                    continue
            dir_versions = os.path.join(root, "versions")
            for version in os.listdir(dir_versions):
                if os.path.isdir(os.path.join(dir_versions, version)):
                    json_history_worlds[root].setdefault(version, {})
            for version in json_history_worlds[root]:
                dir_version = os.path.join(root, "versions", version)
                dir_saves = os.path.join(dir_version, "saves")
                if not os.path.exists(dir_saves):
                    continue
                for save in reversed(json_history_worlds[root][version]):
                    #存档不存在则移除该save
                    if not os.path.exists(os.path.join(dir_saves, save)):
                        del json_history_worlds[root][version][save]
                        continue
                for save in os.listdir(dir_saves):
                    if os.path.isdir(os.path.join(dir_saves, save)):
                        json_history_worlds[root][version].setdefault(save, [])
        with open(dir_json_history_worlds, 'w', encoding='utf-8') as file:
            json.dump(json_history_worlds, file, indent=4)
                
        #刷新list
        bpy.ops.crafter.reload_history_worlds_list()
                        
        return context.window_manager.invoke_props_dialog(self)

    def draw(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        layout = self.layout

        if len(addon_prefs.History_World_Roots_List) > 0:
            row_root = min(len(addon_prefs.History_World_Roots_List),4)
            layout.template_list("VIEW3D_UL_CrafterHistoryWorldRootsList", "", addon_prefs, "History_World_Roots_List", addon_prefs, "History_World_Roots_List_index", rows=row_root)
        if len(addon_prefs.History_World_Versions_List) > 0:
            row_version = min(len(addon_prefs.History_World_Versions_List),4)
            layout.template_list("VIEW3D_UL_CrafterHistoryWorldVersionsList", "", addon_prefs, "History_World_Versions_List", addon_prefs, "History_World_Versions_List_index", rows=row_version)
        if len(addon_prefs.History_World_Saves_List) > 0:
            row_save = min(len(addon_prefs.History_World_Saves_List),4)
            layout.template_list("VIEW3D_UL_CrafterHistoryWorldSavesList", "", addon_prefs, "History_World_Saves_List", addon_prefs, "History_World_Saves_List_index", rows=row_save)
        if len(addon_prefs.History_World_Settings_List) > 0:
            layout.template_list("VIEW3D_UL_CrafterHistoryWorldSettingsList", "", addon_prefs, "History_World_Settings_List", addon_prefs, "History_World_Settings_List_index", rows=10)

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

        worldPath = os.path.normpath(addon_prefs.World_Path)
        save = os.path.basename(worldPath)
        versionPath = os.path.dirname(os.path.dirname(worldPath))
        dir_level_dat = os.path.join(worldPath, "level.dat")
        if not os.path.exists(dir_level_dat):
            self.report({'ERROR'}, "It's not a world path!")
            return {"CANCELLED"}
        selectedGameVersion = os.path.basename(versionPath)
        if not os.path.exists(os.path.join(versionPath,selectedGameVersion+".jar")):
            self.report({'ERROR'}, "Please set the save file into the Minecraft game folder!")
            return {"CANCELLED"}
        dot_minecraftPath = os.path.dirname(os.path.dirname(versionPath))

        point_cloud_mode = addon_prefs.Point_Cloud_Mode

        if point_cloud_mode:
            status = 2
        else:
            status = 1
        
        worldconfig = {
            "worldPath": worldPath,
            "packagePath": versionPath,
            "selectedGameVersion":selectedGameVersion,
            "biomeMappingFile": "config\\jsons\\biomes.json",
            "minX": min(addon_prefs.XYZ_1[0], addon_prefs.XYZ_2[0]),
            "maxX": max(addon_prefs.XYZ_1[0], addon_prefs.XYZ_2[0]),
            "minY": min(addon_prefs.XYZ_1[1], addon_prefs.XYZ_2[1]),
            "maxY": max(addon_prefs.XYZ_1[1], addon_prefs.XYZ_2[1]),
            "minZ": min(addon_prefs.XYZ_1[2], addon_prefs.XYZ_2[2]),
            "maxZ": max(addon_prefs.XYZ_1[2], addon_prefs.XYZ_2[2]),
            "status": status,
            "solid": 0,
        }

        dir_importer = os.path.join(dir_init_main, "importer")
        dir_config = os.path.join(dir_importer, "config")
        dir_exe_importer = os.path.join(dir_importer, "WorldImporter.exe")
        dir_json_config = os.path.join(dir_config, "config.json")
        with open(dir_json_config, 'w', encoding='utf-8') as config:
            for key, value in worldconfig.items():
                config.write(f"{key} = {value}\n")
            # json.dump(worldconfig, config, indent=4)

        self.report({'INFO'}, f"World config saved to {dir_config}")
        #导入obj
        pre_import_objects = set(bpy.data.objects)#纪录当前场景中的所有对象
        start_time = time.perf_counter()#记录开始时间

        if os.path.exists(dir_exe_importer):
            try:
                # 在新的进程中运行WorldImporter.exe
                CREATE_NEW_PROCESS_GROUP = 0x00000200
                DETACHED_PROCESS = 0x00000008
                process = subprocess.Popen(
                    [dir_exe_importer],
                    cwd=dir_importer,
                    creationflags=CREATE_NEW_PROCESS_GROUP | DETACHED_PROCESS,
                    shell=True
                )
                self.report({'INFO'}, f"WorldImporter.exe started in a new process")
                #等待进程结束
                process.wait()
            except Exception as e:
                self.report({'ERROR'}, f"Error: {e}")
                return {"CANCELLED"}
            #导入obj
            try:
                if point_cloud_mode:
                    dir_obj_output = os.path.join(dir_importer, "output.obj")
                    bpy.ops.wm.obj_import(filepath=dir_obj_output)
                else:
                    dir_obj_region_models = os.path.join(dir_importer, "region_models.obj")
                    bpy.ops.wm.obj_import(filepath=dir_obj_region_models)
            except:
                self.report({'ERROR'}, "WorldImporter didn't export obj!")
                return {"CANCELLED"}
            bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
            # 计算新增对象
            post_import_objects = set(bpy.data.objects)
            new_objects = post_import_objects - pre_import_objects
            imported_objects = list(new_objects)
            for object in imported_objects:
                add_to_mcmts_collection(object=object,context=context)
        #完成导入
        end_time = time.perf_counter()
        used_time = end_time - start_time
        self.report({'INFO'}, i18n("Importing finished.Time used:") + str(used_time)[:6] + "s")
        # 自动定位到视图
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
                            
                
        # 保存历史世界
        dir_json_history_worlds = os.path.join(dir_cafter_data, "history_worlds.json")
        if os.path.exists(dir_json_history_worlds):
            with open(dir_json_history_worlds, 'r', encoding='utf-8') as file:
                json_old_history_worlds = json.load(file)
        else:
            json_old_history_worlds = {}
        json_old_history_worlds.setdefault(dot_minecraftPath, {})
        json_old_history_worlds[dot_minecraftPath].setdefault(selectedGameVersion, {})
        json_old_history_worlds[dot_minecraftPath][selectedGameVersion].setdefault(save, [])
        json_history_settings = json_old_history_worlds[dot_minecraftPath][selectedGameVersion][save]
        if json_history_settings == None:
            json_history_settings = []
        world_settings_now = [list(addon_prefs.XYZ_1), list(addon_prefs.XYZ_2)]
        if len(json_history_settings) == 0:
            json_history_settings.append(None)
            json_history_settings[0] = world_settings_now
        elif not json_history_settings[0] == world_settings_now:
            if len(json_history_settings) < 10:
                json_history_settings.append(None)
            for i in range (len(json_history_settings) - 1,0,-1):
                json_history_settings[i] = json_history_settings[i - 1]
            json_history_settings[0] = world_settings_now
        with open(dir_json_history_worlds, 'w', encoding='utf-8') as file:
            json.dump(json_old_history_worlds, file, indent=4)
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
        return {'FINISHED'}

#==========加载资源包操作==========
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

class VIEW3D_UL_CrafterResources(bpy.types.UIList):
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        if self.layout_type in {"DEFAULT","COMPACT"}:
            layout.label(text=item.name)

class VIEW3D_UL_CrafterResourcesInfo(bpy.types.UIList):
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        dir_resourcepacks = os.path.join(dir_resourcepacks_plans, addon_prefs.Resources_Plans_List[addon_prefs.Resources_Plans_List_index].name)
        dir_resourcepack = os.path.join(dir_resourcepacks, item.name)
        
        if self.layout_type in {"DEFAULT","COMPACT"}:
            item_name = ""
            i = 0
            while i < len(item.name):
                if item.name[i] == "§":
                    i+=1
                elif item.name[i] == ".":
                    break
                elif item.name[i] != "!":
                    item_name += item.name[i]
                i+=1
            # if "pack.png" in os.listdir(dir_resourcepack):
            #     layout.label(text=item_name,icon="crafter_resources" + item.name)
            # else:
            #     layout.label(text=item_name)
            layout.label(text=item_name)

class VIEW3D_OT_CrafterLoadResources(bpy.types.Operator):#加载资源包
    bl_label = "Load Resources"
    bl_idname = "crafter.load_resources"
    bl_description = "Load resources"
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
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
                        load_normal_and_PBR_and_link_all(node_tex_base=node_tex_base, group_COn=group_COn, nodes=nodes, links=links)

        return {'FINISHED'}
    def invoke(self, context, event):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        bpy.ops.crafter.reload_all()
        return context.window_manager.invoke_props_dialog(self)

    def draw(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        layout = self.layout

        row_Plans_List = layout.row()
        row_Plans_List.template_list("VIEW3D_UL_CrafterResources", "", addon_prefs, "Resources_Plans_List", addon_prefs, "Resources_Plans_List_index", rows=1)
        col_Plans_List_ops = row_Plans_List.column()
        col_Plans_List_ops.operator("crafter.open_resources_plans",icon="FILE_FOLDER",text="")
        col_Plans_List_ops.operator("crafter.reload_all",icon="FILE_REFRESH",text="")

        if len(addon_prefs.Resources_List) > 0:
            row_Resources_List = layout.row()
            row_Resources_List.template_list("VIEW3D_UL_CrafterResourcesInfo", "", addon_prefs, "Resources_List", addon_prefs, "Resources_List_index", rows=1)
            if len(addon_prefs.Resources_List) > 1:
                col_Resources_List_ops = row_Resources_List.column(align=True)
                col_Resources_List_ops.operator("crafter.up_resource",icon="TRIA_UP",text="")
                col_Resources_List_ops.operator("crafter.down_resource",icon="TRIA_DOWN",text="")

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
                    continue
                if node.type == "GROUP":
                    if node.node_tree.name != None:
                        if (node.node_tree.name.startswith("C-")) and (node.node_tree.name != "C-biomeTex") :
                            node_group_C_Group = node
                            continue
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
        PBR_value = [0.291769,0.039546,0,1]
        if addon_prefs.PBR_Parser == "old_continuum":
            PBR_value = [0.291769,0,0,1]
        elif addon_prefs.PBR_Parser == "old_BSL":
            PBR_value = [0.5,0,0,1]
        elif addon_prefs.PBR_Parser == "SEUS_PBR":
            PBR_value = [0.5,0,0,1]
        for material in bpy.data.materials:
            if material.node_tree != None:
                for node in material.node_tree.nodes:
                    if node.type == "GROUP":
                        if node.node_tree.name != None:
                            if node.node_tree.name.startswith("CO-"):
                                try:
                                    node.inputs["PBR"].default_value = PBR_value
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

class VIEW3D_UL_CrafterMaterials(bpy.types.UIList):
    def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        if self.layout_type in {"DEFAULT","COMPACT"}:
            layout.label(text=item.name)

class VIEW3D_UL_CrafterClassificationBasis(bpy.types.UIList):
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        if self.layout_type in {"DEFAULT","COMPACT"}:
            layout.label(text=item.name)

class VIEW3D_OT_CrafterLoadMaterial(bpy.types.Operator):#加载材质
    bl_label = "Load Material"
    bl_idname = "crafter.load_material"
    bl_description = "Load Material"
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
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
            add_to_mcmts_collection(object=object,context=context)
        for name_material in context.scene.Crafter_mcmts:
            material = bpy.data.materials[name_material.name]
            node_tree_material = material.node_tree
            if node_tree_material == None:
                continue
            nodes = node_tree_material.nodes
            links = node_tree_material.links
            
            node_tex_base = None
            #处理lod材质
            if material.name.startswith("color#"):
                material.displacement_method = "DISPLACEMENT"
                for node in nodes:
                    if node.type == "OUTPUT_MATERIAL" and node.is_active_output:
                        node_output = node
                    if node.type == "BSDF_PRINCIPLED":
                        nodes.remove(node)
                group_CO = nodes.new(type="ShaderNodeGroup")
                group_CO.location = (node_output.location.x - 200, node_output.location.y)
                real_name = fuq_bl_dot_number(name_material.name)
                if len(real_name) > 24:
                    last_mao_index = real_name.rfind(':')
                    real_block_name = real_name[last_mao_index+1:]
                    find_CO_group(group_CO=group_CO, real_block_name=real_block_name,classification_list=classification_list)
                else:
                    group_CO.node_tree = bpy.data.node_groups["CO-"]
                group_CO.inputs["Base Color"].default_value = [float(material.name[6:11]),float(material.name[12:17]),float(material.name[18:23]),1]
                for output in group_CO.outputs:
                    links.new(output, node_output.inputs[output.name])
                continue
            #获取基础贴图节点
            for node in nodes:
                if node.type == "TEX_IMAGE" and node.image != None:
                    name_image = fuq_bl_dot_number(node.image.name)
                    if name_image.endswith("_n.png") or name_image.endswith("_s.png") or name_image.endswith("_a.png"):
                        bpy.data.images.remove(node.image)
                        nodes.remove(node)
                    elif node_tex_base != None:
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
                if node.type == "GROUP" and node.node_tree == None:
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
            find_CO_group(group_CO=group_COn, real_block_name=real_block_name,classification_list=classification_list)
            # 连接CO节点
            for output in group_COn.outputs:
                links.new(output, node_output.inputs[output.name])
            if node_tex_base == None:
                continue
            load_normal_and_PBR_and_link_all(node_tex_base=node_tex_base, group_COn=group_COn, nodes=nodes, links=links)
        #连接startswith(CO-)、startswith(CI-)节点组
        for aCO in COs:
            group_CO = bpy.data.node_groups[aCO]
            nodes = group_CO.nodes
            links = group_CO.links
            for node in nodes:
                if node.type == "GROUP_OUTPUT" and node.is_active_output:
                    node_output = node
                    continue
                if node.type == "GROUP_INPUT":
                    node_input = node
                    continue
                if node.type == "GROUP":
                    if node.node_tree.name != None:
                        if node.node_tree.name == "C-biomeTex":
                            node_group_C_biomeTex = node
                            continue
                        if node.node_tree.name.startswith("C-"):
                            node_group_Parse = node
            group_CI = nodes.new(type='ShaderNodeGroup')
            group_CI.location = (node_output.location.x - 200, node_output.location.y)
            #尝试匹配CI-节点组
            try:
                group_CI.node_tree = bpy.data.node_groups["CI-" + aCO[3:]]
            except:
                group_CI.node_tree = bpy.data.node_groups["CI-"]
            #尝试匹配解析器节点组
            try:
                node_group_Parse.node_tree = bpy.data.node_groups["C-" + addon_prefs.PBR_Parser]
            except:
                pass
            #尝试匹配接口
            for output in group_CI.outputs:
                #匹配 CI-节点组 和 输出
                try:
                    links.new(output, node_output.inputs[output.name])
                except:
                    pass
            for input in group_CI.inputs:
                #匹配 CI-节点组 和 输入
                try:
                    links.new(input, node_input.outputs[input.name])
                except:
                    pass
                #匹配 CI-节点组 和 解析器节点组
                try:
                    links.new(input, node_group_Parse.outputs[input.name])
                except:
                    pass
                #匹配 CI-节点组 和 群系图节点组
                try:
                    links.new(input, node_group_C_biomeTex.outputs[input.name])
                except:
                    pass
            for input in node_group_Parse.inputs:
                #匹配 解析器节点组 和 输入
                try:
                    links.new(input, node_input.outputs[input.name])
                except:
                    pass
        PBR_value = [0.291769,0.039546,0,1]
        if addon_prefs.PBR_Parser == "old_continuum":
            PBR_value = [0.291769,0,0,1]
        elif addon_prefs.PBR_Parser == "old_BSL":
            PBR_value = [0.5,0,0,1]
        elif addon_prefs.PBR_Parser == "SEUS_PBR":
            PBR_value = [0.5,0,0,1]
        for material in bpy.data.materials:
            if material.node_tree != None:
                for node in material.node_tree.nodes:
                    if node.type == "GROUP":
                        if node.node_tree != None:
                            if node.node_tree.name.startswith("CO-"):
                                try:
                                    node.inputs["PBR"].default_value = PBR_value
                                except:
                                    pass
        return {'FINISHED'}

    def invoke(self, context, event):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        bpy.ops.crafter.reload_all()
        return context.window_manager.invoke_props_dialog(self)

    def draw(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        layout = self.layout

        row_PBR_Parser = layout.row()
        row_PBR_Parser.prop(addon_prefs, "PBR_Parser")

        row_Materials_List = layout.row()
        row_Materials_List.template_list("VIEW3D_UL_CrafterMaterials", "", addon_prefs, "Materials_List", addon_prefs, "Materials_List_index", rows=1)
        col_Materials_List_ops = row_Materials_List.column()
        col_Materials_List_ops.operator("crafter.open_materials",icon="FILE_FOLDER",text="")
        col_Materials_List_ops.operator("crafter.reload_all",icon="FILE_REFRESH",text="")

        row_Classification_Basis = layout.row()
        row_Classification_Basis.template_list("VIEW3D_UL_CrafterClassificationBasis", "", addon_prefs, "Classification_Basis_List", addon_prefs, "Classification_Basis_List_index", rows=1)
        row_Classification_Basis_ops = row_Classification_Basis.column()
        row_Classification_Basis_ops.operator("crafter.open_classification_basis",icon="FILE_FOLDER",text="")
        row_Classification_Basis_ops.operator("crafter.reload_all",icon="FILE_REFRESH",text="")

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

#==========加载背景操作==========

class VIEW3D_OT_CrafterOpenBackgrounds(bpy.types.Operator):#打开背景列表文件夹
    bl_label = "Open Backgrounds"
    bl_idname = "crafter.open_backgrounds"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        folder_path = dir_backgrounds
        open_folder(folder_path)

        return {'FINISHED'}

class VIEW3D_UL_CrafterBackgroundsList(bpy.types.UIList):
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        layout.label(text=item.name)

class VIEW3D_OT_CrafterLoadBackground(bpy.types.Operator):#加载背景
    bl_label = "Load Background"
    bl_idname = "crafter.load_background"
    bl_description = "Load Background"
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        bpy.ops.crafter.reload_all()
        if not (-1 < addon_prefs.Backgrounds_List_index and addon_prefs.Backgrounds_List_index < len(addon_prefs.Backgrounds_List)):
            self.report({'ERROR'}, "No Selected Background!")
            return {'FINISHED'}
            
        dir_background = os.path.join(dir_backgrounds, addon_prefs.Backgrounds_List[addon_prefs.Backgrounds_List_index].name + ".blend")
        if context.scene.world:
            bpy.data.worlds.remove(context.scene.world)
        with bpy.data.libraries.load(dir_background) as (data_from, data_to):
            data_to.worlds = data_from.worlds
        context.scene.world = data_to.worlds[0]
        
        return {'FINISHED'}
    def invoke(self, context, event):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        bpy.ops.crafter.reload_all()
        return context.window_manager.invoke_props_dialog(self)

    def draw(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        layout = self.layout

        row_Backgrounds = layout.row()
        col_Background_List = row_Backgrounds.column()
        col_Background_List.template_list("VIEW3D_UL_CrafterBackgroundsList", "", addon_prefs, "Backgrounds_List", addon_prefs, "History_Worlds_List_index", rows=1)
        col_Background_List_ops = row_Backgrounds.column()
        col_Background_List_ops.operator("crafter.open_backgrounds",icon="FILE_FOLDER",text="")
        col_Background_List_ops.operator("crafter.reload_all",icon="FILE_REFRESH",text="")
