import bpy
import os
import time
import subprocess
import threading
import platform
import json
import zipfile
from pathlib import Path
import shutil

from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from bpy.props import StringProperty, IntProperty, BoolProperty, IntVectorProperty, EnumProperty, CollectionProperty, FloatProperty
from .. import dir_cafter_data, dir_resourcepacks_plans, dir_materials, dir_classification_basis, dir_blend_append, dir_init_main, dir_backgrounds

donot = ["Crafter Materials Settings"]
len_color_jin = 20

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
    dir_image = os.path.dirname(node_tex.image.filepath)
    dir_mcmeta = os.path.join(bpy.path.abspath(dir_image), node_tex.image.name + ".mcmeta")
    if os.path.exists(dir_mcmeta):
        node_Moving_texture = nodes.new(type="ShaderNodeGroup")
        node_Moving_texture.location = (node_tex.location.x - 200, node_tex.location.y)
        node_Moving_texture.node_tree = bpy.data.node_groups["Crafter-Moving_texture"]
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
    return: 是否置换
    '''
    Displacement = False
    if "EEVEE-Surface" in group_CI.outputs:
        links.new(group_CI.outputs["EEVEE-Surface"], node_output_EEVEE.inputs["Surface"])
    if "EEVEE-Volume" in group_CI.outputs: 
        links.new(group_CI.outputs["EEVEE-Volume"], node_output_EEVEE.inputs["Volume"])
    if "EEVEE-Displacement" in group_CI.outputs: 
        links.new(group_CI.outputs["EEVEE-Displacement"], node_output_EEVEE.inputs["Displacement"])
        Displacement = True
    if "EEVEE-Thickness" in group_CI.outputs: 
        if "Thickness" in node_output_EEVEE.inputs:
            links.new(group_CI.outputs["EEVEE-Thickness"], node_output_EEVEE.inputs["Thickness"])
        
    if "Cycles-Surface" in group_CI.outputs: 
        links.new(group_CI.outputs["Cycles-Surface"], node_output_Cycles.inputs["Surface"])
    if "Cycles-Volume" in group_CI.outputs: 
        links.new(group_CI.outputs["Cycles-Volume"], node_output_Cycles.inputs["Volume"])
    if "Cycles-Displacement" in group_CI.outputs: 
        links.new(group_CI.outputs["Cycles-Displacement"], node_output_Cycles.inputs["Displacement"])
        Displacement = True
    if "Cycles-Thickness" in group_CI.outputs: 
        if "Thickness" in node_output_Cycles.inputs:
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

def load_normal_and_PBR(node_tex_base, nodes, links):
    '''
    以基础色节点添加法向贴图节点和PBR贴图节点、连接并添加动态纹理节点
    node_tex_base: 基础色节点
    nodes: 目标材质节点组
    links:目标材质连接组
    '''
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

def link_base_normal_and_PBR(node_tex_base, group_CI, links, node_C_PBR_Parser, node_tex_normal, node_tex_PBR):

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

def add_C_time(obj):
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
    