import bpy
import os
import time
import subprocess
import threading
import platform
import json
import zipfile
from pathlib import Path

from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from bpy.props import StringProperty, IntProperty, BoolProperty, IntVectorProperty, EnumProperty, CollectionProperty, FloatProperty
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
        if object.name != "Crafter Materials Settings" and object.type == "MESH" and object.data.materials:
            for name_material in list_name_object_material:
                if (name_material not in context.scene.Crafter_mcmts) and (name_material != "Crafter Materials Settings"):
                    new_mcmt = context.scene.Crafter_mcmts.add()
                    new_mcmt.name = name_material
        for i in range(len(context.scene.Crafter_mcmts)-1,-1,-1):
            if context.scene.Crafter_mcmts[i].name not in list_name_context_material:
                context.scene.Crafter_mcmts.remove(i)

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
    return: 是否置换
    '''
    Displacement = False
    if "EEVEE-Surface" in group_CI.outputs:
        links.new(group_CI.outputs["EEVEE-Surface"], node_output_EEVEE.inputs["Surface"])
    if "Cycles-Surface" in group_CI.outputs: 
        links.new(group_CI.outputs["Cycles-Surface"], node_output_Cycles.inputs["Surface"])
    if "EEVEE-Volume" in group_CI.outputs: 
        links.new(group_CI.outputs["EEVEE-Volume"], node_output_EEVEE.inputs["Volume"])
    if "Cycles-Volume" in group_CI.outputs: 
        links.new(group_CI.outputs["Cycles-Volume"], node_output_Cycles.inputs["Volume"])
    if "EEVEE-Displacement" in group_CI.outputs: 
        links.new(group_CI.outputs["EEVEE-Displacement"], node_output_EEVEE.inputs["Displacement"])
        Displacement = True
    if "Cycles-Displacement" in group_CI.outputs: 
        links.new(group_CI.outputs["Cycles-Displacement"], node_output_Cycles.inputs["Displacement"])
        Displacement = True
    if "EEVEE-Thickness" in group_CI.outputs: 
        links.new(group_CI.outputs["EEVEE-Thickness"], node_output_EEVEE.inputs["Thickness"])
    if "Cycles-Thickness" in group_CI.outputs: 
        links.new(group_CI.outputs["Cycles-Thickness"], node_output_Cycles.inputs["Thickness"])
    return Displacement

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

def load_normal_and_PBR_and_link(node_tex_base, group_CI, nodes, links, node_C_PBR_Parser):
    '''
    以基础色节点添加法向贴图节点和PBR贴图节点、连接并添加动态纹理节点
    node_tex_base: 基础色节点
    group_CI: 材质组节点
    nodes: 目标材质节点组
    links:目标材质连接组
    '''
    name_image = fuq_bl_dot_number(node_tex_base.image.name)
    name_block = name_image[:-4]
    if "Base Color" in group_CI.inputs:
        links.new(node_tex_base.outputs["Color"], group_CI.inputs["Base Color"])
    if "Alpha" in group_CI.inputs:
        links.new(node_tex_base.outputs["Alpha"], group_CI.inputs["Alpha"])
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
        links.new(node_tex.outputs["Color"], node_C_PBR_Parser.inputs["Normal"])
        links.new(node_tex.outputs["Alpha"], node_C_PBR_Parser.inputs["Normal Alpha"])
        add_node_moving_texture_without_list(node_tex, nodes, links)
    if os.path.exists(bpy.path.abspath(dir_s)):
        node_tex = nodes.new(type="ShaderNodeTexImage")
        node_tex.location = (node_tex_base.location.x, node_tex_base.location.y - 600)
        node_tex.image = bpy.data.images.load(dir_s)
        node_tex.interpolation = "Closest"
        bpy.data.images[node_tex.image.name].colorspace_settings.name = "Non-Color"
        links.new(node_tex.outputs["Color"], node_C_PBR_Parser.inputs["PBR"])
        links.new(node_tex.outputs["Alpha"], node_C_PBR_Parser.inputs["PBR Alpha"])
        add_node_moving_texture_without_list(node_tex, nodes, links)
    elif os.path.exists(bpy.path.abspath(dir_a)):
        node_tex = nodes.new(type="ShaderNodeTexImage")
        node_tex.location = (node_tex_base.location.x, node_tex_base.location.y - 600)
        node_tex.image = bpy.data.images.load(dir_a)
        node_tex.interpolation = "Closest"
        bpy.data.images[node_tex.image.name].colorspace_settings.name = "Non-Color"
        links.new(node_tex.outputs["Color"], node_C_PBR_Parser.inputs["PBR"])
        links.new(node_tex.outputs["Alpha"], node_C_PBR_Parser.inputs["PBR Alpha"])
        add_node_moving_texture_without_list(node_tex, nodes, links)

def add_C_time(obj):
    if not "C-time" in bpy.data.node_groups:
        with bpy.data.libraries.load(dir_blend_append, link=False) as (data_from, data_to):
            for nodegroup in data_from.node_groups:
                if nodegroup == "C-time":
                    print("found")
            data_to.node_groups = ["C-time"]
    # 检查是否已存在该节点修改器
    has_modifier = any(
        mod.type == 'NODES' and 
        mod.node_group == bpy.data.node_groups["C-time"]
        for mod in obj.modifiers)

    if not has_modifier:
        # 添加几何节点修改器
        new_mod = obj.modifiers.new("C-time", 'NODES')
        new_mod.node_group = bpy.data.node_groups["C-time"]

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
    
class VIEW3D_OT_CrafterReloadGameResources(bpy.types.Operator):#刷新游戏资源包列表
    bl_label = "Reload Game Resources"
    bl_idname = "crafter.reload_game_resources"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        dir_json_resourcepacks = os.path.join(dir_cafter_data, "resourcepacks.json")

        worldPath = os.path.normpath(addon_prefs.World_Path)
        dir_saves = os.path.dirname(worldPath)
        
        addon_prefs.Game_Resources_List.clear()
        addon_prefs.Game_unuse_Resources_List.clear()

        if os.path.exists(dir_json_resourcepacks):
            with open(dir_json_resourcepacks, "r", encoding="utf-8") as file:
                json_resourcepacks = json.load(file)
        else:
            return {'FINISHED'}
        resourcepacks = json_resourcepacks[dir_saves]
        for resourcepack in resourcepacks[0]:
            resourcepack_use = addon_prefs.Game_Resources_List.add()
            resourcepack_use.name = resourcepack
        for resourcepack in resourcepacks[1]:
            resourcepack_unuse = addon_prefs.Game_unuse_Resources_List.add()
            resourcepack_unuse.name = resourcepack


        return {'FINISHED'}

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
class VIEW3D_UL_CrafterLatestWorldList(bpy.types.UIList):
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
       world = item.name.split("|")
       text = f"{world[0]}     {world[1]}     {world[2]}"
       layout.label(text=text)

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

class VIEW3D_OT_CrafterReloadLatestWorldsList(bpy.types.Operator):#刷新最近世界列表
    bl_label = "Reload Latest Worlds List"
    bl_idname = "crafter.reload_latest_worlds_list"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        bpy.ops.crafter.reload_history_worlds_list()

        dir_json_latest_worlds = os.path.join(dir_cafter_data, "latest_worlds.json")
        with open(dir_json_latest_worlds, 'r', encoding='utf-8') as file:
            json_latest_worlds = json.load(file)
        
        addon_prefs.Latest_World_List.clear()
   
        for latest_world in json_latest_worlds:
            latest_world_name = addon_prefs.Latest_World_List.add()
            latest_world_name.name = latest_world
        if len(addon_prefs.Latest_World_List) > 0:
            if addon_prefs.Latest_World_List_index < 0 or addon_prefs.Latest_World_List_index >= len(addon_prefs.Latest_World_List):
                addon_prefs.Latest_World_List_index = 0
            world_now = addon_prefs.Latest_World_List[addon_prefs.Latest_World_List_index].name
            world_now = world_now.split("|")
            for i in range(len(addon_prefs.History_World_Roots_List)):
                if addon_prefs.History_World_Roots_List[i].name == world_now[2]:
                    addon_prefs.History_World_Roots_List_index = i
                    bpy.ops.crafter.reload_history_worlds_list()
                    if addon_prefs.is_Undivided:# 是否开启版本隔离
                        dir_versions = os.path.join(world_now[2], "versions")
                        reload_Undivided_Vsersions(context=context,dir_versions=dir_versions)
                        for j in range(len(addon_prefs.Undivided_Vsersions_List)):
                            if addon_prefs.Undivided_Vsersions_List[j].name == world_now[1]:
                                addon_prefs.Undivided_Vsersions_List_index = j
                                break
                        for j in range(len(addon_prefs.History_World_Saves_List)):
                                    if addon_prefs.History_World_Saves_List[j].name == world_now[0]:
                                        addon_prefs.History_World_Saves_List_index = j
                                        break
                        bpy.ops.crafter.reload_history_worlds_list()
                        break
                    else:
                        for j in range(len(addon_prefs.History_World_Versions_List)):
                            if addon_prefs.History_World_Versions_List[j].name == world_now[1]:
                                addon_prefs.History_World_Versions_List_index = j
                                bpy.ops.crafter.reload_history_worlds_list()
                                for k in range(len(addon_prefs.History_World_Saves_List)):
                                    if addon_prefs.History_World_Saves_List[k].name == world_now[0]:
                                        addon_prefs.History_World_Saves_List_index = k
                                        bpy.ops.crafter.reload_history_worlds_list()
                                        break
                                break
                        break

        return {'FINISHED'}

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
            if addon_prefs.History_World_Roots_List_index < 0 or addon_prefs.History_World_Roots_List_index >= len(addon_prefs.History_World_Roots_List):
                addon_prefs.History_World_Roots_List_index = 0
            if type(json_history_worlds[addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name]) == list:
                addon_prefs.is_Undivided = True
                for save in json_history_worlds[addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name][0]:
                    history_world_save = addon_prefs.History_World_Saves_List.add()
                    history_world_save.name = save
                    if len(addon_prefs.History_World_Saves_List) > 0:
                        if addon_prefs.History_World_Saves_List_index < 0 or addon_prefs.History_World_Saves_List_index >= len(addon_prefs.History_World_Saves_List):
                            addon_prefs.History_World_Saves_List_index = 0
                        for settings in json_history_worlds[addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name][0][addon_prefs.History_World_Saves_List[addon_prefs.History_World_Saves_List_index].name]["settings"]:
                            history_world_setting = addon_prefs.History_World_Settings_List.add()
                            history_world_setting.name = f"{settings[0][0]} {settings[0][1]} {settings[0][2]} {settings[1][0]} {settings[1][1]} {settings[1][2]}" 
            else:
                addon_prefs.is_Undivided = False
                for version in json_history_worlds[addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name]:
                    history_world_version = addon_prefs.History_World_Versions_List.add()
                    history_world_version.name = version
                if len(addon_prefs.History_World_Versions_List) > 0:
                    if addon_prefs.History_World_Versions_List_index < 0 or addon_prefs.History_World_Versions_List_index >= len(addon_prefs.History_World_Versions_List):
                        addon_prefs.History_World_Versions_List_index = 0
                    for save in json_history_worlds[addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name][addon_prefs.History_World_Versions_List[addon_prefs.History_World_Versions_List_index].name]:
                        history_world_save = addon_prefs.History_World_Saves_List.add()
                        history_world_save.name = save
                    if len(addon_prefs.History_World_Saves_List) > 0:
                        if addon_prefs.History_World_Saves_List_index < 0 or addon_prefs.History_World_Saves_List_index >= len(addon_prefs.History_World_Saves_List):
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
    
    def invoke(self, context, event):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        #补全history_worlds.json
        dir_json_history_worlds = os.path.join(dir_cafter_data, "history_worlds.json")
        dir_json_latest_worlds = os.path.join(dir_cafter_data, "latest_worlds.json")
        
        if not os.path.exists(dir_json_history_worlds):
            self.report({ 'ERROR' }, "Haven't history worlds")
            return {'CANCELLED'}
        if os.path.exists(dir_json_latest_worlds):
            with open(dir_json_latest_worlds, 'r', encoding='utf-8') as file:
                json_latest_worlds = json.load(file)
        else:
            json_latest_worlds = []
        
        with open(dir_json_history_worlds, 'r', encoding='utf-8') as file:
            json_history_worlds = json.load(file)
        if type(json_history_worlds) == list:
            json_history_worlds = {}
        # 整理json
        for root in list(json_history_worlds):
            #地址不存在则移除该root
            if not os.path.exists(root):
                del json_history_worlds[root]
                continue
            if type(json_history_worlds[root]) == list:#判断是否版本隔离
                for save in list(json_history_worlds[root][0]):
                    #存档不存在则移除该save
                    if not os.path.exists(os.path.join(root, "saves", save)):
                        del json_history_worlds[root][0][save]
                        continue
                    #加入新找到的save
                    for save in os.listdir(os.path.join(root, "saves")):
                        if os.path.isdir(os.path.join(root, "saves", save)):
                            json_history_worlds[root][0].setdefault(save, {})
            else:
                dir_versions = os.path.join(root, "versions")
                # 添加版本
                for version in os.listdir(dir_versions):
                    if os.path.exists(os.path.join(dir_versions, version, version + ".jar")):
                        json_history_worlds[root].setdefault(version, {})
                for version in list(json_history_worlds[root]):
                    #版本不存在则移除该version
                    if not os.path.exists(os.path.join(dir_versions, version)):
                        del json_history_worlds[root][version]
                        continue
                    #存档不存在则移除该save
                    dir_version = os.path.join(dir_versions, version)
                    dir_saves = os.path.join(dir_version, "saves")
                    for save in list(json_history_worlds[root][version]):
                        if not os.path.exists(os.path.join(dir_saves, save)):
                            del json_history_worlds[root][version][save]
                            continue
                    #加入新找到的save
                    if os.path.exists(dir_saves):
                        for save in os.listdir(dir_saves):
                            if os.path.isdir(os.path.join(dir_saves, save)):
                                json_history_worlds[root][version].setdefault(save, [])
        # 清理最近世界历史记录
        for i in range(len(json_latest_worlds)-1,-1,-1):
            world = json_latest_worlds[i].split("|")
            if not world[2] in json_history_worlds:
                del json_latest_worlds[i]
                continue
            if type(json_history_worlds[world[2]]) == list:#判断是否版本隔离
                if not world[0] in json_history_worlds[world[2]][0]:
                    del json_latest_worlds[i]
                    continue
            else:#版本隔离
                if not world[1] in json_history_worlds[world[2]]:
                    del json_latest_worlds[i]
                    continue
                if not world[0] in json_history_worlds[world[2]][world[1]]:
                    del json_latest_worlds[i]
                    continue

        with open(dir_json_history_worlds, 'w', encoding='utf-8') as file:
            json.dump(json_history_worlds, file, indent=4)

        with open(dir_json_latest_worlds, 'w', encoding='utf-8') as file:
            json.dump(json_latest_worlds, file, indent=4)
                
        #刷新list
        if len(json_latest_worlds) > 0:
            bpy.ops.crafter.reload_latest_worlds_list()
        else:
            bpy.ops.crafter.reload_history_worlds_list()
                        
        return context.window_manager.invoke_props_dialog(self)
    
    def draw(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        layout = self.layout

        if len(addon_prefs.Latest_World_List) > 0:
            row_world = min(len(addon_prefs.Latest_World_List),4)
            layout.template_list("VIEW3D_UL_CrafterLatestWorldList", "", addon_prefs, "Latest_World_List", addon_prefs, "Latest_World_List_index", rows=row_world)

        if len(addon_prefs.History_World_Roots_List) > 0:
            layout.label(text="Minecraft Saves")
            row_root = min(len(addon_prefs.History_World_Roots_List),4)
            layout.template_list("VIEW3D_UL_CrafterHistoryWorldRootsList", "", addon_prefs, "History_World_Roots_List", addon_prefs, "History_World_Roots_List_index", rows=row_root)
        if addon_prefs.is_Undivided:
            if len(addon_prefs.Undivided_Vsersions_List) > 0:
                row_version = min(len(addon_prefs.Undivided_Vsersions_List),4)
                layout.template_list("VIEW3D_UL_CrafterDividedVersions", "", addon_prefs, "Undivided_Vsersions_List", addon_prefs, "Undivided_Vsersions_List_index", rows=row_version)
            pass
        else:
            if len(addon_prefs.History_World_Versions_List) > 0:
                row_version = min(len(addon_prefs.History_World_Versions_List),4)
                layout.template_list("VIEW3D_UL_CrafterHistoryWorldVersionsList", "", addon_prefs, "History_World_Versions_List", addon_prefs, "History_World_Versions_List_index", rows=row_version)
        if len(addon_prefs.History_World_Saves_List) > 0:
            row_save = min(len(addon_prefs.History_World_Saves_List),4)
            layout.template_list("VIEW3D_UL_CrafterHistoryWorldSavesList", "", addon_prefs, "History_World_Saves_List", addon_prefs, "History_World_Saves_List_index", rows=row_save)
        if len(addon_prefs.History_World_Settings_List) > 0:
            layout.template_list("VIEW3D_UL_CrafterHistoryWorldSettingsList", "", addon_prefs, "History_World_Settings_List", addon_prefs, "History_World_Settings_List_index", rows=1,maxrows=10)

    def execute(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        if len(addon_prefs.History_World_Saves_List) > 0:
            dir_root = addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name
            dir_undivided_saves = os.path.join(dir_root,"saves")
            if os.path.exists(dir_undivided_saves):
                dir_save = os.path.join(dir_undivided_saves,addon_prefs.History_World_Saves_List[addon_prefs.History_World_Saves_List_index].name)
            else:
                dir_version = os.path.join(dir_root,"versions",addon_prefs.History_World_Versions_List[addon_prefs.History_World_Versions_List_index].name)
                dir_save = os.path.join(dir_version,"saves",addon_prefs.History_World_Saves_List[addon_prefs.History_World_Saves_List_index].name)
            addon_prefs.World_Path = dir_save
            if len(addon_prefs.History_World_Settings_List) > 0:
                settings = addon_prefs.History_World_Settings_List[addon_prefs.History_World_Settings_List_index].name
                setting = settings.split(" ")
                addon_prefs.XYZ_1 = (int(setting[0]),int(setting[1]),int(setting[2]))
                addon_prefs.XYZ_2 = (int(setting[3]),int(setting[4]),int(setting[5]))
        return {'FINISHED'}

class VIEW3D_UL_CrafterDividedVersions(bpy.types.UIList):
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        text = item.name
        index_na = text.rfind("\\")
        text = text[index_na+1:]
        layout.label(text=text)

class VIEW3D_UL_CrafterGameResources(bpy.types.UIList):
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        text = item.name
        index_na = text.rfind("\\")
        text = text[index_na+1:-4]
        true_text = ""
        i = 0
        while i < len(text):
            if text[i] == "§":
                i+=1
            elif text[i] != "!":
                true_text += text[i]
            i+=1
        layout.label(text=true_text)

class VIEW3D_UL_CrafterGameUnuseResources(bpy.types.UIList):
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        text = item.name
        index_na = text.rfind("\\")
        text = text[index_na+1:-4]
        true_text = ""
        i = 0
        while i < len(text):
            if text[i] == "§":
                i+=1
            elif text[i] != "!":
                true_text += text[i]
            i+=1
        layout.label(text=true_text)

class VIEW3D_OT_CrafterUpGameResource(bpy.types.Operator):#提高游戏资源包优先级
    bl_label = "Up resource's priority"    
    bl_idname = "crafter.up_game_resource"
    bl_description = " "

    @classmethod
    def poll(cls, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        return addon_prefs.Game_Resources_List_index > 0

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        dir_json_resourcepacks = os.path.join(dir_cafter_data, "resourcepacks.json")
        with open(dir_json_resourcepacks, "r", encoding="utf-8") as file:
            json_resourcepacks = json.load(file)

        worldPath = addon_prefs.World_Path
        dir_saves = os.path.dirname(worldPath)
        target_name = addon_prefs.Game_Resources_List[addon_prefs.Game_Resources_List_index].name
        for i in range(len(json_resourcepacks[dir_saves][0])):
            if json_resourcepacks[dir_saves][0][i] == target_name:
                if i > 0:
                    json_resourcepacks[dir_saves][0][i], json_resourcepacks[dir_saves][0][i - 1] = json_resourcepacks[dir_saves][0][i - 1], json_resourcepacks[dir_saves][0][i]
                    addon_prefs.Game_Resources_List_index -= 1
                    break
        with open(dir_json_resourcepacks, 'w', encoding='utf-8') as file:
            json.dump(json_resourcepacks, file, indent=4)
        bpy.ops.crafter.reload_game_resources()

        return {'FINISHED'}

class VIEW3D_OT_CrafterDownGameResource(bpy.types.Operator):#降低游戏资源包优先级
    bl_label = "Down resource's priority"    
    bl_idname = "crafter.down_game_resource"
    bl_description = " "

    @classmethod
    def poll(cls, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        return addon_prefs.Game_Resources_List_index < len(addon_prefs.Game_Resources_List) - 1

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        dir_json_resourcepacks = os.path.join(dir_cafter_data, "resourcepacks.json")
        with open(dir_json_resourcepacks, "r", encoding="utf-8") as file:
            json_resourcepacks = json.load(file)

        worldPath = addon_prefs.World_Path
        dir_saves = os.path.dirname(worldPath)
        target_name = addon_prefs.Game_Resources_List[addon_prefs.Game_Resources_List_index].name
        for i in range(len(json_resourcepacks[dir_saves][0])):
            print(i)
            print(json_resourcepacks[dir_saves][0][i])
            print(target_name)
            if json_resourcepacks[dir_saves][0][i] == target_name:
                print('=')
                if i < len(addon_prefs.Game_Resources_List) - 1:
                    print("down")
                    json_resourcepacks[dir_saves][0][i], json_resourcepacks[dir_saves][0][i + 1] = json_resourcepacks[dir_saves][0][i + 1], json_resourcepacks[dir_saves][0][i]
                    addon_prefs.Game_Resources_List_index += 1
                    break

        with open(dir_json_resourcepacks, 'w', encoding='utf-8') as file:
            json.dump(json_resourcepacks, file, indent=4)

        bpy.ops.crafter.reload_game_resources()

        return {'FINISHED'}
    
class VIEW3D_OT_CrafterBanGameResource(bpy.types.Operator):#Ban游戏资源包
    bl_label = "Ban resource"    
    bl_idname = "crafter.ban_game_resource"
    bl_description = " "

    @classmethod
    def poll(cls, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        dir_json_resourcepacks = os.path.join(dir_cafter_data, "resourcepacks.json")
        with open(dir_json_resourcepacks, "r", encoding="utf-8") as file:
            json_resourcepacks = json.load(file)

        worldPath = addon_prefs.World_Path
        dir_saves = os.path.dirname(worldPath)
        use_list = json_resourcepacks[dir_saves][0].copy()
        unuse_list = json_resourcepacks[dir_saves][1].copy()
        json_resourcepacks[dir_saves][0] = []
        json_resourcepacks[dir_saves][1] = []
        for i in range(len(use_list)):
            if i == addon_prefs.Game_Resources_List_index:
                continue
            json_resourcepacks[dir_saves][0].append(use_list[i])
        json_resourcepacks[dir_saves][1].append(addon_prefs.Game_Resources_List[addon_prefs.Game_Resources_List_index].name)
        for resource in unuse_list:
            json_resourcepacks[dir_saves][1].append(resource)

        with open(dir_json_resourcepacks, 'w', encoding='utf-8') as file:
            json.dump(json_resourcepacks, file, indent=4)

        if (addon_prefs.Game_Resources_List_index > 0) and (addon_prefs.Game_Resources_List_index == len(addon_prefs.Game_Resources_List) - 1):
            addon_prefs.Game_Resources_List_index -= 1

        bpy.ops.crafter.reload_game_resources()

        return {'FINISHED'}

class VIEW3D_OT_CrafterUseGameResource(bpy.types.Operator):#使用游戏资源包
    bl_label = "Use resource"    
    bl_idname = "crafter.use_game_resource"
    bl_description = " "

    @classmethod
    def poll(cls, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        dir_json_resourcepacks = os.path.join(dir_cafter_data, "resourcepacks.json")
        with open(dir_json_resourcepacks, "r", encoding="utf-8") as file:
            json_resourcepacks = json.load(file)

        worldPath = addon_prefs.World_Path
        dir_saves = os.path.dirname(worldPath)
        use_list = json_resourcepacks[dir_saves][0].copy()
        unuse_list = json_resourcepacks[dir_saves][1].copy()
        json_resourcepacks[dir_saves][0] = []
        json_resourcepacks[dir_saves][1] = []
        json_resourcepacks[dir_saves][0].append(addon_prefs.Game_unuse_Resources_List[addon_prefs.Game_unuse_Resources_List_index].name)
        for resource in use_list:
            json_resourcepacks[dir_saves][0].append(resource)
        for i in range(len(unuse_list)):
            if i == addon_prefs.Game_unuse_Resources_List_index:
                continue
            json_resourcepacks[dir_saves][1].append(unuse_list[i])

        with open(dir_json_resourcepacks, 'w', encoding='utf-8') as file:
            json.dump(json_resourcepacks, file, indent=4)

        if (addon_prefs.Game_unuse_Resources_List_index > 0) and (addon_prefs.Game_unuse_Resources_List_index == len(addon_prefs.Game_unuse_Resources_List) - 1):
            addon_prefs.Game_unuse_Resources_List_index -= 1

        bpy.ops.crafter.reload_game_resources()

        return {'FINISHED'}

class VIEW3D_OT_CrafterImportSurfaceWorld(bpy.types.Operator):#导入表层世界
    bl_label = "Import World"
    bl_idname = "crafter.import_surface_world"
    bl_description = "Import the surface world"
    bl_options = {'REGISTER', 'UNDO'}
    
    worldPath = StringProperty(name="World path")#type: ignore
    jarPath: StringProperty(name="Jar path")#type: ignore
    modsPath = StringProperty(name="Mods path")#type: ignore

    save: StringProperty(name="Save")#type: ignore
    version: StringProperty(name="Version")#type: ignore
    dot_minecraftPath: StringProperty(name=".minecraft path")#type: ignore

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
    def draw(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        layout = self.layout

        row = layout.row()

        col_1 = row.column()
        col_1.prop(addon_prefs, "useChunkPrecision")
        col_1.prop(addon_prefs, "strictDeduplication")
        col_1.prop(addon_prefs, "allowDoubleFace")
        col_1.prop(addon_prefs, "exportLightBlock")

        col_2 = row.column()
        col_2.prop(addon_prefs, "keepBoundary")
        col_2.prop(addon_prefs, "cullCave")
        col_2.prop(addon_prefs, "shell")

        if addon_prefs.exportLightBlock:
            row_Light_Block = layout.row()
            row_Light_Block.prop(addon_prefs, "exportLightBlockOnly")
            row_Light_Block.prop(addon_prefs, "lightBlockSize")


        row_exportFullModel = layout.row()
        row_exportFullModel.prop(addon_prefs, "exportFullModel")
        if addon_prefs.exportFullModel:
            row_exportFullModel.prop(addon_prefs, "partitionSize")
        
        row_if_lod = layout.row()
        row_if_lod.prop(addon_prefs, "activeLOD")
        if addon_prefs.activeLOD:
            row_lod = layout.row()
            
            col_lod_1 = row_lod.column()
            col_lod_1.prop(addon_prefs, "LOD0renderDistance")
            col_lod_1.prop(addon_prefs, "LOD1renderDistance")
            col_lod_1.prop(addon_prefs, "LOD2renderDistance")
            col_lod_1.prop(addon_prefs, "LOD3renderDistance")

            col_lod_2 = row_lod.column()
            col_lod_2.prop(addon_prefs, "useUnderwaterLOD")
            col_lod_2.prop(addon_prefs, "isLODAutoCenter")
            if not addon_prefs.isLODAutoCenter:
                col_lod_2.prop(addon_prefs, "LODCenterX")
                col_lod_2.prop(addon_prefs, "LODCenterZ")
        # 无版本隔离选择
        if self.version == "":
            layout.label(text="Versions")
            row_undivided = layout.row()
            row_undivided.template_list("VIEW3D_UL_CrafterDividedVersions","",addon_prefs,"Undivided_Vsersions_List",addon_prefs,"Undivided_Vsersions_List_index",rows=1,)

        # 资源包列表
        if len(addon_prefs.Game_Resources_List) + len(addon_prefs.Game_unuse_Resources_List) > 0:
            layout.label(text="Resources List")
            row_resources_use = layout.row()
            col_use_list = row_resources_use.column()
            col_use_list.template_list("VIEW3D_UL_CrafterGameResources", "", addon_prefs, "Game_Resources_List", addon_prefs, "Game_Resources_List_index", rows=1)
            col_use_ops = row_resources_use.column(align=True)
            if len (addon_prefs.Game_Resources_List) > 0:
                col_use_ops.operator("crafter.ban_game_resource", text="", icon="REMOVE")
            if len (addon_prefs.Game_Resources_List) > 1:
                col_use_ops.separator()
                col_use_ops.operator("crafter.up_game_resource", text="", icon="TRIA_UP")
                col_use_ops.operator("crafter.down_game_resource", text="", icon="TRIA_DOWN")

            row_resources_unuse = layout.row()
            col_unuse_list = row_resources_unuse.column()
            col_unuse_list.template_list("VIEW3D_UL_CrafterGameUnuseResources", "", addon_prefs, "Game_unuse_Resources_List", addon_prefs, "Game_unuse_Resources_List_index", rows=1)
            col_unuse_ops = row_resources_unuse.column()
            if len (addon_prefs.Game_unuse_Resources_List) > 0:
                col_unuse_ops.operator("crafter.use_game_resource", text="", icon="ADD")

    def invoke(self, context, event):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        # 获取世界路径，检测路径合法性
        worldPath = os.path.normpath(addon_prefs.World_Path)
        dir_saves = os.path.dirname(worldPath)
        dir_level_dat = os.path.join(worldPath, "level.dat")
        if not os.path.exists(dir_level_dat):
            self.report({'ERROR'}, "It's not a world path!")
            return {"CANCELLED"}
        
        #初始化路径
        jarPath = ""
        versionName = ""
        # 计算游戏文件路径
        dir_saves = os.path.dirname(worldPath)
        dir_back_saves = os.path.dirname(dir_saves)

        if os.path.basename(dir_back_saves) == ".minecraft":
            dir_dot_minecraft = dir_back_saves
            dir_versions = os.path.join(dir_dot_minecraft,"versions")
            list_versions = os.listdir(dir_versions)
            if len(list_versions) == 0:
                self.report({'ERROR'}, "Can't find any versions!")
                return {"CANCELLED"}
            reload_Undivided_Vsersions(context=context,dir_versions=dir_versions)
            dir_resourcepacks = os.path.join(dir_dot_minecraft,"resourcepacks")
            dir_mods = os.path.join(dir_dot_minecraft,"mods")

        else:
            versionPath = dir_back_saves
            versionName = os.path.basename(versionPath)
            jarPath = os.path.join(versionPath, versionName+".jar")
            if not os.path.exists(jarPath):
                self.report({'ERROR'}, "Please set the save file into the Minecraft game folder!")
                return {"CANCELLED"}
            dir_mods = os.path.join(versionPath, "mods")
            dir_resourcepacks = os.path.join(versionPath, "resourcepacks")
            dir_versions = os.path.dirname(versionPath)
            dir_dot_minecraft = os.path.dirname(dir_versions)
        #储存信息到self
        self.worldPath = worldPath
        self.jarPath = jarPath
        self.modsPath = dir_mods

        self.save = os.path.basename(worldPath)
        self.version = versionName
        self.dot_minecraftPath = dir_dot_minecraft
        # 获取资源包
        list_resourcepacks = os.listdir(dir_resourcepacks)
        dir_json_resourcepacks = os.path.join(dir_cafter_data, "resourcepacks.json")
        
        if os.path.exists(dir_json_resourcepacks):
            with open(dir_json_resourcepacks, "r", encoding="utf-8") as file:
                json_resourcepacks = json.load(file)
        else:
            json_resourcepacks = {}
        for dir_saves_old in list(json_resourcepacks):
            if not os.path.exists(dir_saves_old):
                del json_resourcepacks[dir_saves_old]
        json_resourcepacks.setdefault(dir_saves,[[],[]])
        resourcepacks_use_copy = json_resourcepacks[dir_saves][0].copy()
        resourcepacks_unuse_copy = json_resourcepacks[dir_saves][1].copy()
        json_resourcepacks[dir_saves][0] =[]
        json_resourcepacks[dir_saves][1] =[]

        for resourcepack in list_resourcepacks:
            dir_resourcepack = os.path.join(dir_resourcepacks, resourcepack)
            if (not dir_resourcepack in resourcepacks_use_copy) and (not dir_resourcepack in resourcepacks_unuse_copy):
                if dir_resourcepack.endswith(".zip"):
                    json_resourcepacks[dir_saves][0].append(dir_resourcepack)
        for dir_resourcepack in resourcepacks_use_copy:
            if os.path.exists(dir_resourcepack):
                json_resourcepacks[dir_saves][0].append(dir_resourcepack)
        for dir_resourcepack in resourcepacks_unuse_copy:
            if os.path.exists(dir_resourcepack):
                json_resourcepacks[dir_saves][1].append(dir_resourcepack)
    
        with open (dir_json_resourcepacks, "w", encoding="utf-8") as file:
            json.dump(json_resourcepacks, file, indent=4)
        
        bpy.ops.crafter.reload_game_resources()
        
        return context.window_manager.invoke_props_dialog(self)
    
    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        if len(context.selected_objects) != 0:
            bpy.ops.object.mode_set(mode='OBJECT')
        if self.version == "":
            undivided = True
            dir_version = addon_prefs.Undivided_Vsersions_List[addon_prefs.Undivided_Vsersions_List_index].name
            version = os.path.basename(dir_version)
            jarPath = os.path.join(dir_version, version + ".jar")

        else:
            undivided = False
            jarPath = self.jarPath
            version = self.version
        worldPath = self.worldPath
        modsPath = self.modsPath
        dir_version = os.path.dirname(jarPath)
        versionJsonPath = os.path.join(dir_version, version + ".json")
        resourcepacksPaths = []

        save = self.save
        dot_minecraftPath = self.dot_minecraftPath

        for resourcepacksPath in addon_prefs.Game_Resources_List:
            resourcepacksPaths.append(resourcepacksPath.name)
        
        #写入conifg.json
        point_cloud_mode = addon_prefs.Point_Cloud_Mode
        if point_cloud_mode:
            status = 2
        else:
            status = 1
        
        worldconfig = {
            "worldPath": worldPath,
            "jarPath": jarPath,
            "versionJsonPath": versionJsonPath,
            "modsPath": modsPath,
            "resourcepacksPaths":resourcepacksPaths,
            "minX": min(addon_prefs.XYZ_1[0], addon_prefs.XYZ_2[0]),
            "maxX": max(addon_prefs.XYZ_1[0], addon_prefs.XYZ_2[0]),
            "minY": min(addon_prefs.XYZ_1[1], addon_prefs.XYZ_2[1]),
            "maxY": max(addon_prefs.XYZ_1[1], addon_prefs.XYZ_2[1]),
            "minZ": min(addon_prefs.XYZ_1[2], addon_prefs.XYZ_2[2]),
            "maxZ": max(addon_prefs.XYZ_1[2], addon_prefs.XYZ_2[2]),
            "status": status,
            "useChunkPrecision":addon_prefs.useChunkPrecision,
            "keepBoundary":addon_prefs.keepBoundary,
            "strictDeduplication":addon_prefs.strictDeduplication,
            "cullCave":addon_prefs.cullCave,
            "exportLightBlock":addon_prefs.exportLightBlock,
            "exportLightBlockOnly":addon_prefs.exportLightBlockOnly,
            "lightBlockSize":addon_prefs.lightBlockSize,
            "allowDoubleFace":addon_prefs.allowDoubleFace,
            "exportFullModel":not addon_prefs.exportFullModel,
            "partitionSize":addon_prefs.partitionSize,
            "activeLOD":addon_prefs.activeLOD,
            "useUnderwaterLOD":addon_prefs.useUnderwaterLOD,
            "isLODAutoCenter":addon_prefs.isLODAutoCenter,
            "LODCenterX":addon_prefs.LODCenterX,
            "LODCenterZ":addon_prefs.LODCenterZ,
            "LOD0renderDistance":addon_prefs.LOD0renderDistance,
            "LOD1renderDistance":addon_prefs.LOD1renderDistance,
            "LOD2renderDistance":addon_prefs.LOD2renderDistance,
            "LOD3renderDistance":addon_prefs.LOD3renderDistance,
            "solid": 0,
        }

        dir_importer = os.path.join(dir_init_main, "importer")
        dir_config = os.path.join(dir_importer, "config")
        dir_json_config = os.path.join(dir_config, "config.json")

        with open(dir_json_config, 'w', encoding='utf-8') as config:
            # for key, value in worldconfig.items():
            #     config.write(f"{key} = {value}\n")
            json.dump(worldconfig, config, indent=4)

        # 删去之前导出的obj
        dir_importer = os.path.join(dir_init_main, "importer")
        dir_exe_importer = os.path.join(dir_importer, "WorldImporter.exe")
        for file in os.listdir(dir_importer):
            if file.endswith(".obj"):
                os.remove(os.path.join(dir_importer, file))

        #生成obj
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
                    shell=addon_prefs.shell
                )
                self.report({'INFO'}, f"WorldImporter.exe started in a new process")
                #等待进程结束
                process.wait()
            except Exception as e:
                self.report({'ERROR'}, f"Error: {e}")
                return {"CANCELLED"}
            used_time = time.perf_counter() - start_time
            self.report({'INFO'}, i18n("At") + " " + str(used_time)[:6] + "s,"+ i18n("WorldImporter.exe finished"))

            #导入obj
            have_obj = False
            real_name_dic = {}
            material_should_delete = []
            for file in os.listdir(dir_importer):
                if file.endswith(".obj"):
                    pre_import_objects = set(bpy.data.objects)#纪录当前场景中的所有对象
                    
                    bpy.ops.wm.obj_import(filepath=os.path.join(dir_importer, file))
                    bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
                    have_obj = True
                    
                    post_import_objects = set(bpy.data.objects)
                    new_objects = post_import_objects - pre_import_objects# 计算新增对象
                    imported_objects = list(new_objects)
                    for obj in imported_objects:
                        for i in range(len(obj.data.materials)):
                            material = obj.data.materials[i]
                            real_material_name = fuq_bl_dot_number(material.name)
                            if real_material_name in real_name_dic:
                                obj.data.materials[i] = bpy.data.materials[real_name_dic[real_material_name]]
                                material_should_delete.append(material.name)
                            else:
                                real_name_dic[real_material_name] = material.name
                        add_to_mcmts_collection(object=obj,context=context)
                        add_C_time(obj=obj)
                    used_time = time.perf_counter() - start_time
                    self.report({'INFO'}, i18n("At") + " " + str(used_time)[:6] + "s,"+ file + i18n("imported"))
            if not have_obj:
                self.report({'ERROR'}, "WorldImporter didn't export obj!")
                return {"CANCELLED"}
            
            for material in material_should_delete:
                bpy.data.materials.remove(bpy.data.materials[material])
            
        #完成导入
        used_time = time.perf_counter() - start_time
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
        # 读取json，若不存在则创建一个空的json文件
        if os.path.exists(dir_json_history_worlds):
            with open(dir_json_history_worlds, 'r', encoding='utf-8') as file:
                json_history_worlds = json.load(file)
        else:
            json_history_worlds = {}
        # 根据是否隔离，按不同方式保存历史记录
        if undivided:
            json_history_worlds.setdefault(dot_minecraftPath, [{}])
            undivided_list = json_history_worlds[dot_minecraftPath][0].setdefault(save,{})
            json_history_settings = undivided_list.setdefault("settings", [])
            undivided_list["version"] = version
        else:
            json_history_worlds.setdefault(dot_minecraftPath, {})
            json_history_worlds[dot_minecraftPath].setdefault(version, {})
            json_history_worlds[dot_minecraftPath][version].setdefault(save, [])
            json_history_settings = json_history_worlds[dot_minecraftPath][version][save]
        world_settings_now = [list(addon_prefs.XYZ_1), list(addon_prefs.XYZ_2)]
        if len(json_history_settings) == 0:
            json_history_settings.append(None)
            json_history_settings[0] = world_settings_now
        else:
            for i in range (len(json_history_settings)):
                if json_history_settings[i] == world_settings_now:
                    for j in range (i,0,-1):
                        json_history_settings[j] = json_history_settings[j - 1]
                    json_history_settings[0] = world_settings_now
                    break
            else:
                if len(json_history_settings) < 10:
                    json_history_settings.append(None)
                for i in range (len(json_history_settings) - 1,0,-1):
                    json_history_settings[i] = json_history_settings[i - 1]
                json_history_settings[0] = world_settings_now
                
        # 保存到json文件
        with open(dir_json_history_worlds, 'w', encoding='utf-8') as file:
            json.dump(json_history_worlds, file, indent=4)

        # 保存最近世界
        world_now = f"{save}|{version}|{dot_minecraftPath}"
        dir_json_latest_worlds = os.path.join(dir_cafter_data, "latest_worlds.json")
        if os.path.exists(dir_json_latest_worlds):
            with open(dir_json_latest_worlds, 'r', encoding='utf-8') as file:
                json_latest_worlds = json.load(file)
        else:
            json_latest_worlds = []
        if len(json_latest_worlds) == 0:
            json_latest_worlds.append(None)
            json_latest_worlds[0] = world_now
        else:
            for i in range (len(json_latest_worlds)):
                if json_latest_worlds[i] == world_now:
                    for j in range (i,0,-1):
                        json_latest_worlds[j] = json_latest_worlds[j - 1]
                    json_latest_worlds[0] = world_now
                    break
            else:
                if len(json_latest_worlds) < 5:
                    json_latest_worlds.append(None)
                for i in range (len(json_latest_worlds) - 1,0,-1):
                    json_latest_worlds[i] = json_latest_worlds[i - 1]
                json_latest_worlds[0] = world_now
        # 保存到json文件
        with open(dir_json_latest_worlds, 'w', encoding='utf-8') as file:
            json.dump(json_latest_worlds, file, indent=4)
        # 归零index
        addon_prefs.Latest_World_List_index = 0
        addon_prefs.History_World_Settings_List_index = 0

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

#==========替换资源包操作==========
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
        layout.label(text=item.name)

class VIEW3D_UL_CrafterResourcesInfo(bpy.types.UIList):
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        dir_resourcepacks = os.path.join(dir_resourcepacks_plans, addon_prefs.Resources_Plans_List[addon_prefs.Resources_Plans_List_index].name)
        dir_resourcepack = os.path.join(dir_resourcepacks, item.name)
        
        item_name = ""
        i = 0
        while i < len(item.name):
            if item.name[i] == "§":
                i+=1
            elif item.name[i] != "!":
                item_name += item.name[i]
            i+=1
        # if "pack.png" in os.listdir(dir_resourcepack):
        #     layout.label(text=item_name,icon="crafter_resources" + item.name)
        # else:
        #     layout.label(text=item_name)
        layout.label(text=item_name[:-4])

class VIEW3D_OT_CrafterReplaceResources(bpy.types.Operator):#替换资源包
    bl_label = "Replace Resources"
    bl_idname = "crafter.replace_resources"
    bl_description = "Replace resources,but can only replace textures with the same name"
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
            if object.name == "Crafter Materials Settings":
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
                        load_normal_and_PBR_and_link(node_tex_base=node_tex_base, group_CI=group_COn, nodes=nodes, links=links)

        return {'FINISHED'}
    def invoke(self, context, event):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        bpy.ops.crafter.reload_all()
        return context.window_manager.invoke_props_dialog(self)

    def draw(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        layout = self.layout
        
        layout.label(text=i18n("Resources"))
        row_Plans_List = layout.row()
        row_Plans_List.template_list("VIEW3D_UL_CrafterResources", "", addon_prefs, "Resources_Plans_List", addon_prefs, "Resources_Plans_List_index", rows=1)
        col_Plans_List_ops = row_Plans_List.column()
        col_Plans_List_ops.operator("crafter.open_resources_plans",icon="FILE_FOLDER",text="")
        col_Plans_List_ops.operator("crafter.reload_all",icon="FILE_REFRESH",text="")

        if len(addon_prefs.Resources_List) > 0:
            layout.label(text=i18n("Resource"))
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

        node_C_PBR_Parser = bpy.data.node_groups["C-PBR_Parser"]
        nodes = node_C_PBR_Parser.nodes
        links = node_C_PBR_Parser.links
        for node in nodes:
            if node.type == "GROUP_OUTPUT":
                node_output = node
            elif node.type == "GROUP_INPUT":
                node_input = node
            elif node.type == "GROUP":
                node_Parser = node
        node_Parser.node_tree = bpy.data.node_groups["C-" + addon_prefs.PBR_Parser]
        for input in node_Parser.inputs:
            links.new(input, node_output.outputs[input.name])
        for input in node_Parser.inputs:
            links.new(input, node_input.outputs[input.name])
        PBR_value = [0.291769,0.039546,0,1]
        if addon_prefs.PBR_Parser == "old_continuum":
            PBR_value = [0.291769,0,0,1]
        elif addon_prefs.PBR_Parser == "old_BSL":
            PBR_value = [0.5,0,0,1]
        elif addon_prefs.PBR_Parser == "SEUS_PBR":
            PBR_value = [0.5,0,0,1]
        for name_material in context.scene.Crafter_mcmts:
            material = bpy.data.materials[name_material.name]
            node_tree_material = material.node_tree
            for node in node_tree_material.nodes:
                if node.type == "GROUP":
                    if node.node_tree.name != None:
                        if node.node_tree.name == "C-PBR_Parser":
                            node.inputs["PBR"].default_value = PBR_value
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

class VIEW3D_OT_CrafterSetParsedNormalStrength(bpy.types.Operator):#应用解析法向强度
    bl_label = "Set Parsed Normal Strength"
    bl_idname = "crafter.set_parsed_normal_strength"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
    
    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        if "C-Parsed_Normal_Strength" in bpy.data.node_groups:
            node_group_C_Parsed_Normal_Strength = bpy.data.node_groups["C-Parsed_Normal_Strength"]
            for node in node_group_C_Parsed_Normal_Strength.nodes:
                if node.type == "GROUP_OUTPUT":
                    node_output = node
                    break
            node_output.inputs[0].default_value = addon_prefs.Parsed_Normal_Strength

        return {'FINISHED'}

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
        if len(context.selected_objects) != 0:
            bpy.ops.object.mode_set(mode='OBJECT')
        bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
        # 删除startswith(CO-)、startswith(CI-)节点组、startswith(C-)节点组
        for node in bpy.data.node_groups:
            if node.name.startswith("CO-") or node.name.startswith("CI-") or node.name.startswith("C-"):
                bpy.data.node_groups.remove(node)
        # 删除Crafter Materials Settings物体、材质
        try:
            bpy.data.objects.remove(bpy.data.objects["Crafter Materials Settings"])
        except:
            pass
        try:
            bpy.data.materials.remove(bpy.data.materials["Crafter Materials Settings"], do_unlink=True)
        except:
            pass
        # 导入C-节点组
        node_groups_use_fake_user = ["C-PBR_Parser","C-Moving_texture","C-lab_PBR_1.3","C-old_continuum","C-old_BSL","C-SEUS_PBR"]
        with bpy.data.libraries.load(dir_blend_append, link=False) as (data_from, data_to):
            data_to.node_groups = [name for name in data_from.node_groups if name in node_groups_use_fake_user]
        for node_group in bpy.data.node_groups:
            if node_group.name in node_groups_use_fake_user:
                node_group.use_fake_user = True
        # 导入Crafter Materials Settings物体、材质、startswith(CI-)
        blend_material_dir = os.path.join(dir_materials, addon_prefs.Materials_List[addon_prefs.Materials_List_index].name + ".blend")
        with bpy.data.libraries.load(blend_material_dir, link=False) as (data_from, data_to):
            data_to.objects = [name for name in data_from.objects if name == "Crafter Materials Settings"]
        if "Crafter Materials Settings"  in bpy.data.collections:
            collection_Crafter_Materials_Settings = bpy.data.collections["Crafter Materials Settings"]
        else:
            collection_Crafter_Materials_Settings = bpy.data.collections.new(name="Crafter Materials Settings")
            bpy.context.scene.collection.children.link(collection_Crafter_Materials_Settings)
        collection_Crafter_Materials_Settings.objects.link(bpy.data.objects["Crafter Materials Settings"])
        bpy.data.objects["Crafter Materials Settings"].hide_viewport = True
        bpy.data.objects["Crafter Materials Settings"].hide_render = True
        # 获取分类依据地址
        classification_folder_name = addon_prefs.Classification_Basis_List[addon_prefs.Classification_Basis_List_index].name
        classification_folder_dir = os.path.join(dir_classification_basis, classification_folder_name)
        # 初始化 COs,classification_list,banlist, ban_keyw
        classification_list = {}
        banlist = []
        ban_keyw = []
        # 获取classification_list
        for filename in os.listdir(classification_folder_dir):
            file_path = os.path.join(classification_folder_dir, filename)
            if filename.endswith(".json"):
                try:
                    with open(file_path, 'r', encoding='utf-8') as file:
                        data = json.load(file)
                        classification_list = make_json_together(classification_list, data)
                        if "ban" in data:
                            banlist.extend(data["ban"])
                        if "ban_keyw" in data:
                            ban_keyw.extend(data["ban_keyw"])
                except:
                    pass
        # 应用 Parsed_Normal_Strength
        bpy.ops.crafter.set_parsed_normal_strength()

        # 添加选中物体的材质到合集
        for obj in context.selected_objects:
            add_to_mcmts_collection(object=obj,context=context)
            add_C_time(obj=obj)
        # 遍历材质合集
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
                nodes_wait_remove = []
                material.displacement_method = "BOTH"
                for node in nodes:
                    if node.type == "OUTPUT_MATERIAL":
                        if node.target == "EEVEE":
                            node_output_EEVEE = node
                        if node.target == "ALL":
                            node.target = "EEVEE"
                            node_output_EEVEE = node
                        if node.target == "CYCLES":
                            nodes_wait_remove.append(node)
                    if (node.type == "GROUP" and node.node_tree == None) or (node.type == "BSDF_PRINCIPLED"):
                            nodes_wait_remove.append(node)
                for node in nodes_wait_remove:
                    nodes.remove(node)

                # 添加Cycles输出节点
                node_output_Cycles = nodes.new(type="ShaderNodeOutputMaterial")
                node_output_Cycles.target = "CYCLES"
                node_output_Cycles.location = (node_output_EEVEE.location.x, node_output_EEVEE.location.y - 160)
                
                # 添加startswith(CI-)节点组
                group_CI = nodes.new(type="ShaderNodeGroup")
                group_CI.location = (node_output_EEVEE.location.x - 200, node_output_EEVEE.location.y)
                real_name = fuq_bl_dot_number(name_material.name)
                if len(real_name) > 24:
                    last_mao_index = real_name.rfind(':')
                    real_block_name = real_name[last_mao_index+1:]
                    find_CI_group(group_CI=group_CI, real_block_name=real_block_name,classification_list=classification_list)
                else:
                    group_CI.node_tree = bpy.data.node_groups["CI-"]
                if "Base Color" in group_CI.inputs:
                    group_CI.inputs["Base Color"].default_value = [float(material.name[6:10]),float(material.name[11:15]),float(material.name[16:20]),1]
                # 连接CI节点
                Displacement = link_CI_output(group_CI=group_CI, node_output_EEVEE=node_output_EEVEE, node_output_Cycles=node_output_Cycles,links=links)
                if Displacement:# 查看是否需要开启置换
                    # 设置材质置换方式为仅置换
                    material.displacement_method = "BOTH"
                else:
                    material.displacement_method = "BUMP"
                add_node_parser(group_CI=group_CI,nodes=nodes,links=links)
                continue
            #获取基础贴图节点
            nodes_wait_remove = []
            real_block_name = None
            for node in nodes:
                if node.type == "TEX_IMAGE" and node.image != None:
                    name_image = fuq_bl_dot_number(node.image.name)
                    if name_image.endswith("_n.png") or name_image.endswith("_s.png") or name_image.endswith("_a.png"):
                        bpy.data.images.remove(node.image)
                        nodes_wait_remove.append(node)
                    elif node_tex_base != None:
                        nodes_wait_remove.append(node)
                    elif name_image.endswith(".png"):
                        node.interpolation = "Closest"
                        node_tex_base = node
                        block_name = fuq_bl_dot_number(node_tex_base.image.name)
                        real_block_name = block_name[:-4]
            if real_block_name == None:
                continue
            for node in nodes_wait_remove:
                nodes.remove(node)
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
            for ban_key in ban_keyw:
                if real_block_name in ban_key:
                    ban = True
                    break
            if ban or real_block_name in banlist:
                continue
            #获得node_output 并 删去无内容节点组
            nodes_wait_remove = []
            for node in nodes:
                if node.type == "OUTPUT_MATERIAL":
                    if node.target == "EEVEE":
                        node_output_EEVEE = node
                    if node.target == "ALL":
                        node.target = "EEVEE"
                        node_output_EEVEE = node
                    if node.target == "CYCLES":
                        nodes_wait_remove.append(node)
                if node.type == "GROUP" and node.node_tree == None:
                        nodes_wait_remove.append(node)
            for node in nodes_wait_remove:
                nodes.remove(node)
            # 添加Cycles输出节点
            node_output_Cycles = nodes.new(type="ShaderNodeOutputMaterial")
            node_output_Cycles.target = "CYCLES"
            node_output_Cycles.location = (node_output_EEVEE.location.x, node_output_EEVEE.location.y - 160)
            # 删去原有着色器
            try:
                from_node = node_output_EEVEE.inputs[0].links[0].from_node
                if from_node.type == "BSDF_PRINCIPLED" and material.name != "Crafter Materials Settings":
                    nodes.remove(from_node)
            except:
                pass
            # 添加startswith(CI-)节点组
            group_CI = nodes.new(type="ShaderNodeGroup")
            group_CI.location = (node_output_EEVEE.location.x - 200, node_output_EEVEE.location.y)
            find_CI_group(group_CI=group_CI, real_block_name=real_block_name,classification_list=classification_list)
            # 连接CI节点
            Displacement = link_CI_output(group_CI=group_CI, node_output_EEVEE=node_output_EEVEE, node_output_Cycles=node_output_Cycles,links=links)
            if Displacement:# 查看是否需要开启置换
                # 设置材质置换方式为仅置换
                material.displacement_method = "BOTH"
            else:
                material.displacement_method = "BUMP"
            node_C_PBR_Parser = add_node_parser(group_CI=group_CI,nodes=nodes,links=links)
            load_normal_and_PBR_and_link(node_tex_base=node_tex_base, group_CI=group_CI, nodes=nodes, links=links, node_C_PBR_Parser = node_C_PBR_Parser)
        bpy.ops.crafter.set_pbr_parser
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

        row_Parsed_Normal_Strength = layout.row()
        row_Parsed_Normal_Strength.prop(addon_prefs, "Parsed_Normal_Strength")

        layout.label(text="Materials")
        row_Materials_List = layout.row()
        row_Materials_List.template_list("VIEW3D_UL_CrafterMaterials", "", addon_prefs, "Materials_List", addon_prefs, "Materials_List_index", rows=1)
        col_Materials_List_ops = row_Materials_List.column()
        col_Materials_List_ops.operator("crafter.open_materials",icon="FILE_FOLDER",text="")
        col_Materials_List_ops.operator("crafter.reload_all",icon="FILE_REFRESH",text="")

        layout.label(text="Classification Basis")
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
        col_Background_List.template_list("VIEW3D_UL_CrafterBackgroundsList", "", addon_prefs, "Backgrounds_List", addon_prefs, "Backgrounds_List_index", rows=1)
        col_Background_List_ops = row_Backgrounds.column()
        col_Background_List_ops.operator("crafter.open_backgrounds",icon="FILE_FOLDER",text="")
        col_Background_List_ops.operator("crafter.reload_all",icon="FILE_REFRESH",text="")
        
