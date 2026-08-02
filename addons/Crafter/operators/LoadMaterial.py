import bpy
import os
import json

from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from bpy.props import *
from ..__init__ import dir_cafter_data, dir_resourcepacks_plans, dir_materials, dir_classification_basis, dir_blend_append, dir_init_main
from .Defs import *

lang = bpy.context.preferences.view.language
is_chinese = lang in ("zh_HANS", "zh_HANT")

# ==================== 加载材质 ====================

# ========== 提取的分类数据加载函数 ==========
def load_classification_data(addon_prefs):
    '''
    从分类基础目录加载所有 JSON 分类文件，合并成统一的分类列表、封禁列表和封禁关键词列表。
    addon_prefs: 插件偏好设置，从中获取当前选中的分类基础文件夹名称
    return: (classification_list, banlist, ban_keyw) 三元组
    '''
    classification_folder_name = addon_prefs.Classification_Basis_List[addon_prefs.Classification_Basis_List_index].name
    classification_folder_dir = os.path.join(dir_classification_basis, classification_folder_name)
    classification_list = {}
    banlist = []
    ban_keyw = []
    for filename in os.listdir(classification_folder_dir):
        file_path = os.path.join(classification_folder_dir, filename)
        if filename.endswith(".json"):
            try:
                with open(file_path, 'r', encoding='utf-8') as file:
                    data = json.load(file)
                    classification_list = make_dict_together(classification_list, data)
                    if "ban" in data:
                        banlist.extend(data["ban"])
                    if "ban_keyw" in data:
                        ban_keyw.extend(data["ban_keyw"])
            except:
                pass
    return classification_list, banlist, ban_keyw

# ========== 提取的单材质处理函数 ==========
def process_single_material(material, context, classification_list, banlist, ban_keyw, imported_by_crafter=False):
    '''
    处理单个材质：扫描 TEX_IMAGE 节点识别基础色/PBR贴图，构建 CI- 节点组，
    装配 PBR 解析器，连接法向和 PBR 贴图。
    material:             要处理的材质
    context:             Blender 上下文
    classification_list: 分类列表（来自 load_classification_data）
    banlist:             封禁方块名列表
    ban_keyw:            封禁关键词列表
    imported_by_crafter: 是否由 Crafter 导入触发（True=保留已有贴图节点不重新从文件查找PBR）
    '''
    node_tree_material = material.node_tree
    if node_tree_material is None:
        return
    nodes = node_tree_material.nodes
    links = node_tree_material.links

    if bpy.app.version >= (4, 2, 0):
        material.volume_intersection_method = 'ACCURATE'
        material.displacement_method = "BOTH"

    node_tex_base = None
    if material.name.startswith("color#"):
        node_biomeTex = None
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
            if node.type == "BSDF_PRINCIPLED":
                nodes_wait_remove.append(node)
            if node.type == "GROUP":
                if node.node_tree is None:
                    nodes_wait_remove.append(node)
                else:
                    if node.node_tree.name.startswith("Crafter-biomeTex"):
                        node_biomeTex = node
        for node in nodes_wait_remove:
            nodes.remove(node)

        node_output_Cycles = nodes.new(type="ShaderNodeOutputMaterial")
        node_output_Cycles.target = "CYCLES"
        node_output_Cycles.location = (node_output_EEVEE.location.x, node_output_EEVEE.location.y - 160)

        group_CI = nodes.new(type="ShaderNodeGroup")
        group_CI.location = (node_output_EEVEE.location.x - 200, node_output_EEVEE.location.y)
        real_name = fuq_bl_dot_number(material.name)
        if len(real_name) > len_color_jin:
            last_mao_index = real_name.rfind(':')
            real_block_name = real_name[last_mao_index + 1:]
            find_CI_group(group_CI=group_CI, real_block_name=real_block_name, classification_list=classification_list)
        else:
            group_CI.node_tree = bpy.data.node_groups["CI-"]
        if "Base Color" in group_CI.inputs:
            group_CI.inputs["Base Color"].default_value = [float(material.name[6:10]), float(material.name[11:15]), float(material.name[16:20]), 1]
        link_CI_output(group_CI=group_CI, node_output_EEVEE=node_output_EEVEE, node_output_Cycles=node_output_Cycles, links=links)
        link_biome_tex(node_biomeTex=node_biomeTex, group_CI=group_CI, links=links)
        add_node_parser(group_CI=group_CI, nodes=nodes, links=links)
        return

    nodes_wait_remove = []
    real_block_name = None
    node_tex_normal = None
    node_tex_PBR = None
    node_biomeTex = None
    node_output_EEVEE = None
    for node in nodes:
        if node.type == "TEX_IMAGE" and node.image is not None:
            name_image = fuq_bl_dot_number(node.image.name)
            if name_image.endswith("_n.png") or name_image.endswith("_s.png") or name_image.endswith("_a.png"):
                if imported_by_crafter:
                    if name_image.endswith("_n.png"):
                        node_tex_normal = node
                    if name_image.endswith("_s.png") or name_image.endswith("_a.png"):
                        node_tex_PBR = node
                else:
                    bpy.data.images.remove(node.image)
                    nodes_wait_remove.append(node)
            elif node_tex_base is not None:
                nodes_wait_remove.append(node)
            else:
                node.interpolation = "Closest"
                node_tex_base = node
                block_name = fuq_bl_dot_number(node_tex_base.image.name)
                real_block_name = block_name[:-4]
        if node.type == "OUTPUT_MATERIAL":
            if node.target == "EEVEE":
                node_output_EEVEE = node
            if node.target == "ALL":
                node.target = "EEVEE"
                node_output_EEVEE = node
            if node.target == "CYCLES":
                nodes_wait_remove.append(node)
        if node.type == "GROUP":
            if node.node_tree is None:
                nodes_wait_remove.append(node)
            else:
                if node.node_tree.name.startswith("Crafter-biomeTex"):
                    node_biomeTex = node
    if node_tex_base is None:
        name_material_real = fuq_bl_dot_number(material.name)
        last_gang_index = name_material_real.rfind('/')
        real_block_name = name_material_real[last_gang_index + 1:]
    if real_block_name is None:
        return
    ban = False
    for ban_key in ban_keyw:
        if real_block_name in ban_key:
            ban = True
            break
    if ban or real_block_name in banlist:
        return
    for node in nodes_wait_remove:
        nodes.remove(node)
    node_output_Cycles = nodes.new(type="ShaderNodeOutputMaterial")
    node_output_Cycles.target = "CYCLES"
    node_output_Cycles.location = (node_output_EEVEE.location.x, node_output_EEVEE.location.y - 160)
    try:
        from_node = node_output_EEVEE.inputs[0].links[0].from_node
        if from_node.type == "BSDF_PRINCIPLED" and material.name not in donot:
            nodes.remove(from_node)
    except:
        pass
    group_CI = nodes.new(type="ShaderNodeGroup")
    group_CI.location = (node_output_EEVEE.location.x - 200, node_output_EEVEE.location.y)
    find_CI_group(group_CI=group_CI, real_block_name=real_block_name, classification_list=classification_list)
    link_CI_output(group_CI=group_CI, node_output_EEVEE=node_output_EEVEE, node_output_Cycles=node_output_Cycles, links=links)
    link_biome_tex(node_biomeTex=node_biomeTex, group_CI=group_CI, links=links)
    node_C_PBR_Parser = add_node_parser(group_CI=group_CI, nodes=nodes, links=links)
    if not imported_by_crafter:
        node_tex_normal, node_tex_PBR = load_normal_and_PBR(node_tex_base=node_tex_base, nodes=nodes, links=links)
    link_base_normal_PBR(node_tex_base=node_tex_base, group_CI=group_CI, links=links, node_C_PBR_Parser=node_C_PBR_Parser, node_tex_normal=node_tex_normal, node_tex_PBR=node_tex_PBR)
    if node_tex_base is not None:
        nodes.active = node_tex_base

# ========== 提取的为物体加载材质的函数（供外部调用，假设 C-PBR_Parser 已存在）==========
def load_material_for_object(context, obj):
    '''
    为指定物体加载材质：遍历物体的所有材质调用 process_single_material，
    然后将物体加入 MCMTS/Crafter MCMTS 集合、记录 Crafter 时间戳、设置 PBR 解析器。
    与 bpy.ops.crafter.load_material() 操作符不同，此函数跳过 reload_all 和用户交互检查，
    专为程序化调用设计（如 Item 导入完成后自动加载材质）。
    context: Blender 上下文
    obj:     目标物体
    '''
    addon_prefs = context.preferences.addons[__addon_name__].preferences
    classification_list, banlist, ban_keyw = load_classification_data(addon_prefs)
    for mat in obj.data.materials:
        process_single_material(mat, context, classification_list, banlist, ban_keyw, imported_by_crafter=False)
    add_to_mcmts_collection(object=obj, context=context)
    add_to_crafter_mcmts_collection(object=obj, context=context)
    add_Crafter_time(obj=obj)
    bpy.ops.crafter.set_pbr_parser()

class VIEW3D_OT_CrafterLoadMaterial(bpy.types.Operator):
    bl_label = "Load Material"
    bl_idname = "crafter.load_material"
    bl_description = "Load Material"
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
        return any(obj.type == "MESH" for obj in context.selected_objects)

    def execute(self, context: bpy.types.Context):

        push_log('[unknown] 加载材质', 'INFO')
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        bpy.ops.crafter.reload_all()
        if not (-1 < addon_prefs.Materials_List_index and addon_prefs.Materials_List_index < len(addon_prefs.Materials_List)):
            return {"CANCELLED"}
        make_true_object_mode(context)
        bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
        # 删除startswith(CO-)、startswith(CI-)节点组、startswith(C-)节点组
        node_delete = []
        for node in bpy.data.node_groups:
            if node.name.startswith("CO-") or node.name.startswith("CI-") or node.name.startswith("C-"):
                node_delete.append(node)
        for node in node_delete:
            bpy.data.node_groups.remove(node)
        # 删除材质设置/Material Settings物体、材质
        try:
            bpy.data.objects.remove(bpy.data.objects["材质设置/Material Settings"])
        except:
            pass
        try:
            bpy.data.materials.remove(bpy.data.materials["材质设置/Material Settings"], do_unlink=True)
        except:
            pass
        # 导入C-节点组
        node_groups_use_fake_user = ["C-PBR_Parser", "C-lab_PBR_1.3", "C-old_PBR"]
        with bpy.data.libraries.load(dir_blend_append, link=False) as (data_from, data_to):
            data_to.node_groups = [name for name in data_from.node_groups if name in node_groups_use_fake_user]
        for node_group in node_groups_use_fake_user:
            bpy.data.node_groups[node_group].use_fake_user = True
        # 导入Crafter-Moving_texture节点组
        add_node_group_if_not_exists(names_Crafter_Moving_texture)
        # 导入材质设置/Material Settings物体、材质、startswith(CI-)
        blend_material_dir = os.path.join(dir_materials, addon_prefs.Materials_List[addon_prefs.Materials_List_index].name + ".blend")
        with bpy.data.libraries.load(blend_material_dir, link=False) as (data_from, data_to):
            data_to.objects = [name for name in data_from.objects if name == "材质设置/Material Settings"]
        if "材质设置/Material Settings"  in bpy.data.collections:
            collection_Crafter_Materials_Settings = bpy.data.collections["材质设置/Material Settings"]
        else:
            collection_Crafter_Materials_Settings = bpy.data.collections.new(name="材质设置/Material Settings")
            bpy.context.scene.collection.children.link(collection_Crafter_Materials_Settings)
        collection_Crafter_Materials_Settings.objects.link(bpy.data.objects["材质设置/Material Settings"])
        bpy.data.objects["材质设置/Material Settings"].hide_viewport = True
        bpy.data.objects["材质设置/Material Settings"].hide_render = True
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
                        classification_list = make_dict_together(classification_list, data)
                        if "ban" in data:
                            banlist.extend(data["ban"])
                        if "ban_keyw" in data:
                            ban_keyw.extend(data["ban_keyw"])
                except:
                    pass
        # 应用 Parsed_Normal_Strength
        bpy.ops.crafter.set_parsed_normal_strength()

        for_collection = []
        for mcmt in context.scene.Crafter_mcmts:
            for_collection.append(mcmt.name)
        # 添加选中物体的材质到遍历合集
        for obj in context.selected_objects:
            if obj.type == "MESH":
                for mat in obj.data.materials:
                    if mat.name not in for_collection:
                        for_collection.append(mat.name)
        # 遍历材质合集
        for name_material in for_collection:
            imported_by_crafter = False
            if name_material in context.scene.Crafter_crafter_mcmts:
                imported_by_crafter = True
            material = bpy.data.materials[name_material]
            process_single_material(material, context, classification_list, banlist, ban_keyw, imported_by_crafter)
        # 添加选中物体的材质到合集
        for obj in context.selected_objects:
            if obj.type == "MESH":
                add_to_mcmts_collection(object=obj,context=context)
                add_to_crafter_mcmts_collection(object=obj,context=context)
                add_Crafter_time(obj=obj)
                
        bpy.ops.crafter.set_pbr_parser()

        return {'FINISHED'}


# ==================== 加载视差 ====================

class VIEW3D_OT_CrafterLoadParallax(bpy.types.Operator):
    bl_label = "Load Parallax"
    bl_idname = "crafter.load_parallax"
    bl_description = " "
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def invoke(self, context, event):
        return context.window_manager.invoke_props_dialog(self)
    
    def draw(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        layout = self.layout

        row1 = layout.row()
        row1.prop(addon_prefs,"Parallax_Iterations",text="Iterations")

        row2 = layout.row()
        row2.prop(addon_prefs,"Parallax_Depth",text="Depth")

        row3 = layout.row()
        row3.prop(addon_prefs,"Parallax_Smooth",text="Smooth")

        row4 = layout.row()
        row4.prop(addon_prefs,"Parallax_Calculate_Normal")
        if addon_prefs.Parallax_Calculate_Normal:
            row4.prop(addon_prefs,"Parallax_Based_on_Parsed_Normal")
        
        row5 = layout.row()
        row5.prop(addon_prefs,"Parallax_Guess_Height")
        if addon_prefs.Parallax_Guess_Height:
            row5.prop(addon_prefs,"Parallax_Guess_Height_Scale")
    def execute(self, context: bpy.types.Context):

        push_log('[unknown] 加载视差', 'INFO')
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        bpy.ops.crafter.remove_parallax()
        if context.active_object:
            bpy.ops.object.mode_set(mode='OBJECT')
        bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
        # 导入CP-节点组
        add_node_group_if_not_exists(["CP-视差","CP-视差转换"])
        # 开始遍历
        for_collection = []
        for mcmt in context.scene.Crafter_mcmts:
            for_collection.append(mcmt.name)
        # 遍历材质合集
        for name_material in for_collection:
            material = bpy.data.materials[name_material]
            node_tree_material = material.node_tree
            if node_tree_material == None:
                continue
            if material.name.startswith("color#"):
                continue
            nodes = node_tree_material.nodes
            links = node_tree_material.links
            have_loaded_material = False
            # 遍历并寻找关键节点
            nodes_wait_remove = []
            node_tex_base = None
            node_tex_normal = None
            node_tex_PBR = None
            node_output = None
            node_CI = None
            node_C_PBR_Parser = None
            for node in nodes:
                if node.type == "TEX_IMAGE" and node.image != None:
                    name_image = fuq_bl_dot_number(node.image.name)
                    if name_image.endswith("_n.png") or name_image.endswith("_s.png") or name_image.endswith("_a.png"):
                        if name_image.endswith("_n.png"):
                            node_tex_normal = node
                        if name_image.endswith("_s.png") or name_image.endswith("_a.png"):
                            node_tex_PBR = node
                    elif node_tex_base != None:
                        nodes_wait_remove.append(node)
                    else:
                        node_tex_base = node
                elif node.type == "GROUP":
                    if node.node_tree != None:
                        if node.node_tree.name.startswith("CI-"):
                            node_CI = node
                        if node.node_tree.name == "C-PBR_Parser":
                            node_C_PBR_Parser = node
                    have_loaded_material = True
                elif node.type == "OUTPUT_MATERIAL" and node.target == "EEVEE":
                    node_output = node
            if not have_loaded_material:
                continue
            if node_tex_base == None or node_tex_normal == None:# 若没有基础纹理或没有法向纹理，则跳过
                continue
            # 查看法线贴图alpha是否存在视差信息
            not_parallax = is_alpha_channel_all_one(node_tex_normal)
            if not_parallax:
                if addon_prefs.Parallax_Guess_Height:
                    base_as_height = True
                else:
                    continue
            else:
                base_as_height = False

            iterations = addon_prefs.Parallax_Iterations
            smooth = addon_prefs.Parallax_Smooth

            info_base = node_moving_tex_info(node_tex_base)
            info_normal = node_moving_tex_info(node_tex_normal)
            info_PBR = node_moving_tex_info(node_tex_PBR)

            if base_as_height:
                node_tex_height = node_tex_base
                node_tex = node_tex_normal
                info_height = info_base
                info_tex = info_normal
                node_final_depth, node_frame = creat_parallax_node(node_tex_height=node_tex_height, iterations=iterations, smooth=smooth, info_moving_normal=info_normal, nodes=nodes, links=links, height_output="Color", gusess_scale=addon_prefs.Parallax_Guess_Height_Scale)
            else:
                node_tex_height = node_tex_normal
                node_tex = node_tex_base
                info_height = info_normal
                info_tex = info_base
                node_final_depth, node_frame = creat_parallax_node(node_tex_height=node_tex_height, iterations=iterations, smooth=smooth, info_moving_normal=info_normal, nodes=nodes, links=links, height_output="Alpha", gusess_scale=1)

            node_final = None
            if is_moving_same(info_tex=info_tex, info_height=info_height):
                links.new(node_final_depth.outputs["UV"], node_tex.inputs["Vector"])
            else:
                node_final = create_parallax_final(node=node_tex, node_final_depth=node_final_depth, info_height=info_height, info_moving=info_tex, nodes=nodes, links=links, node_frame=node_frame)
            if node_tex_PBR != None:
                if is_moving_same(info_tex=info_tex, info_height=info_height):
                    links.new(node_final_depth.outputs["UV"], node_tex_PBR.inputs["Vector"])
                else:
                    create_pbr_fianl = True
                    if node_final != None:
                        create_pbr_fianl = not is_moving_same(info_tex=info_PBR, info_height=info_tex)
                    if create_pbr_fianl:
                        create_parallax_final(node=node_tex_PBR, node_final_depth=node_final_depth, info_height=info_height, info_moving=info_PBR, nodes=nodes, links=links, node_frame=node_frame)
                    else:
                        links.new(node_final.outputs["UV"], node_tex_PBR.inputs["Vector"])

            if addon_prefs.Parallax_Calculate_Normal and node_output != None and node_CI !=None:
                for input in node_CI.inputs:
                    if input.name == "Parsed Normal":
                        node_frame_Bump = nodes.new(type="NodeFrame")
                        node_frame_Bump.label = "Crafter-凹凸"

                        node_bump = nodes.new(type="ShaderNodeBump")
                        node_bump.location = (node_output.location.x - 400, node_output.location.y + 100)
                        node_bump.invert = True
                        node_bump.parent = node_frame_Bump
                        links.new(node_bump.outputs["Normal"], input)
                        links.new(node_final_depth.outputs["Current Depth"], node_bump.inputs["Height"])
                        for input in node_bump.inputs:
                            if input.name == "Filter Width":
                                input.default_value = 1.2
                                node_bump.inputs["Distance"].default_value = 1
                        if addon_prefs.Parallax_Based_on_Parsed_Normal:
                            links.new(node_C_PBR_Parser.outputs["Parsed Normal"], node_bump.inputs["Normal"])
                        break
                
            bpy.ops.crafter.set_parallax_depth()
        for node in bpy.data.node_groups["CP-1 / Iterations"].nodes:
            if node.type == "GROUP_OUTPUT":
                node_output = node
        node_output.inputs["1 / Iterations"].default_value = 1 / addon_prefs.Parallax_Iterations

        return {'FINISHED'}

# ==================== 去除视差 ====================

class VIEW3D_OT_CrafterRemoveParallax(bpy.types.Operator):
    bl_label = "Remove Parallax"
    bl_idname = "crafter.remove_parallax"
    bl_description = " "
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):

        push_log('[unknown] 移除视差', 'INFO')
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        bpy.ops.crafter.reload_all()
        if context.active_object:
            bpy.ops.object.mode_set(mode='OBJECT')
        bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
        # 删除startswith(CP-)节点组
        node_delete = []
        for node in bpy.data.node_groups:
            if node.name.startswith("CP-"):
                node_delete.append(node)
        for node in node_delete:
            bpy.data.node_groups.remove(node)
        # 开始遍历
        for_collection = []
        for mcmt in context.scene.Crafter_mcmts:
            for_collection.append(mcmt.name)
        # 遍历材质合集
        for name_material in for_collection:
            try:
                material = bpy.data.materials[name_material]
            except:
                continue
            node_tree_material = material.node_tree
            if node_tree_material == None:
                continue
            if material.name.startswith("color#"):
                continue
            nodes = node_tree_material.nodes
            links = node_tree_material.links

            node_frame = None
            node_frame_Bump = None
            node_CI = None
            node_C_PBR_Parser = None
            nodes_wait_delete = []
            for node in nodes:
                if node.type == "FRAME":
                    if node.label == "Crafter-视差":
                        node_frame = node
                        nodes_wait_delete.append(node)
                    elif node.label == "Crafter-凹凸":
                        node_frame_Bump = node
                        nodes_wait_delete.append(node)
                elif node.type == "GROUP":
                    if node.node_tree != None:
                        if node.node_tree.name.startswith("CI-"):
                            node_CI = node
                        if node.node_tree.name == "C-PBR_Parser":
                            node_C_PBR_Parser = node
            for node in nodes:
                if node_frame != None:
                    if node.parent == node_frame:
                        nodes_wait_delete.append(node)
                        continue
                if node_frame_Bump != None:
                    if node.parent == node_frame_Bump:
                        nodes_wait_delete.append(node)
                        continue
                    
                if node.type == "GROUP":
                    if node.node_tree == None:
                        nodes_wait_delete.append(node)
            for node in nodes_wait_delete:
                nodes.remove(node)

            # nodes_tex_coord = []
            # nodes_moving = []
            # for node in nodes:
            #     if node.type == "TEX_COORD":
            #         nodes_tex_coord.append(node)
            #     if node.type == "GROUP":
            #         if node.node_tree.name == "Crafter-动态纹理_尾":
            #             nodes_moving.append(node)
            # for node_moving in nodes_moving:
            #     if len(nodes_tex_coord) == 0:
            #         continue
            #     xy = [node_moving.location.x, node_moving.location.y]
            #     list_closest = [(((nodes_tex_coord[0].location.x - xy[0]) ** 2) + ((nodes_tex_coord[0].location.y - xy[1]) ** 2)) ** 0.5,nodes_tex_coord[0]]
            #     for i in range(1,len(nodes_tex_coord)):
            #         distance = (((nodes_tex_coord[i].location.x - xy[0]) ** 2) + ((nodes_tex_coord[i].location.y - xy[1]) ** 2)) ** 0.5
            #         if list_closest[0] > distance:
            #             list_closest = [distance,nodes_tex_coord[i]]
            #     links.new(list_closest[1].outputs["UV"], node_moving.inputs["UV"])

            nodes_moving_end = []
            nodes_tex = []
            for node in nodes:
                if node.type == "TEX_IMAGE":
                    nodes_tex.append(node)
                if node.type == "GROUP":
                    if node.node_tree.name == "Crafter-动态纹理_尾":
                        nodes_moving_end.append(node)
            for node_tex in nodes_tex:
                if len(nodes_moving_end) == 0:
                    continue
                list_closest = [nodes_distance(node_tex, nodes_moving_end[0]),nodes_moving_end[0]]
                for i in range(1,len(nodes_moving_end)):
                    distance = nodes_distance(node_tex, nodes_moving_end[i])
                    if list_closest[0] > distance:
                        list_closest = [distance,nodes_moving_end[i]]
                links.new(list_closest[1].outputs["Vector"], node_tex.inputs["Vector"])

            if node_CI != None and node_C_PBR_Parser !=None:
                for inpupt in node_CI.inputs:
                    if inpupt.name == "Parsed Normal":
                        links.new(node_C_PBR_Parser.outputs["Parsed Normal"], inpupt)
            
        return {'FINISHED'}
    
# ==================== 设置视差深度 ====================

class VIEW3D_OT_CrafterSetParallaxDepth(bpy.types.Operator):
    bl_label = "Set Parallax Depth"
    bl_idname = "crafter.set_parallax_depth"
    bl_description = " "
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        for node in bpy.data.node_groups["CP-1 / Depth"].nodes:
            if node.type == "GROUP_OUTPUT":
                node_output = node
        node_output.inputs["1 / Depth"].default_value = 1 / addon_prefs.Parallax_Depth
        
        return {'FINISHED'}

# ==================== 设置PBR解析器 ====================

class VIEW3D_OT_CrafterSetPBRParser(bpy.types.Operator):
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
        for output in node_Parser.outputs:
            links.new(output, node_output.inputs[output.name])
        for input in node_Parser.inputs:
            links.new(input, node_input.outputs[input.name])

        metallic = addon_prefs.Default_Metallic
        roughness = addon_prefs.Default_Roughness
        IOR = addon_prefs.Default_IOR
        f0 = ((IOR - 1) / (IOR + 1)) ** 2
        emission_strength = addon_prefs.Default_Emission_Strength

        PBR_value = [1 - roughness ** 0.5, min(229/255, f0), 0, emission_strength * 254 / 255]
        if addon_prefs.PBR_Parser == "old_continuum":
            PBR_value = [1 - roughness ** 0.5, metallic, 0, emission_strength * 254 / 255]
        elif addon_prefs.PBR_Parser == "old_BSL":
            PBR_value = [1 - roughness, metallic, emission_strength,1]
        elif addon_prefs.PBR_Parser == "SEUS_PBR":
            PBR_value = [1 - roughness, metallic, 0,1]

        for name_material in context.scene.Crafter_mcmts:
            material = bpy.data.materials[name_material.name]
            node_tree_material = material.node_tree
            for node in node_tree_material.nodes:
                if node.type == "GROUP":
                    if node.node_tree.name != None:
                        if node.node_tree.name == "C-PBR_Parser":
                            node.inputs["PBR"].default_value = PBR_value
        return {'FINISHED'}

class VIEW3D_OT_CrafterMaterialPanel(bpy.types.Operator):
    bl_label = "Material Panel"
    bl_idname = "crafter.material_panel"
    bl_description = "Show Material Panel"
    bl_options = {'REGISTER', 'UNDO'}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        for obj in bpy.data.objects:
            if obj.name == "材质设置/Material Settings":
                return True
        return False

    def parse_panel_name(self, node_name):
        prefix = "C-Panel-"
        if not node_name.startswith(prefix):
            return None
        name_part = node_name[len(prefix):]
        if "/" in name_part:
            cn_name, en_name = name_part.split("/", 1)
            return cn_name if is_chinese else en_name
        return name_part

    def find_on_off_inputs(self, node_tree, socket_type):
        on_output = None
        off_output = None
        on_index = None
        off_index = None
        for inner_node in node_tree.nodes:
            if inner_node.type == 'GROUP_INPUT':
                for idx, output in enumerate(inner_node.outputs):
                    if output.type == socket_type:
                        if output.name == "On":
                            on_output = output
                            on_index = idx
                        elif output.name == "Off":
                            off_output = output
                            off_index = idx
                break
        return on_output, off_output, on_index, off_index

    def scan_panels(self, obj):
        panels = []
        for mat in obj.data.materials:
            if mat.use_nodes:
                for node in mat.node_tree.nodes:
                    has_node_tree = hasattr(node, 'node_tree') and node.node_tree is not None
                    if node.type == 'GROUP' and has_node_tree:
                        node_tree = node.node_tree
                        if node_tree.name.startswith("C-Panel-"):
                            panel_name = self.parse_panel_name(node_tree.name)
                            if panel_name:
                                panel_data = {
                                    'name': panel_name,
                                    'node_tree_name': node_tree.name,
                                    'outputs': []
                                }
                                group_output_node = None
                                for inner_node in node_tree.nodes:
                                    if inner_node.type == 'GROUP_OUTPUT':
                                        group_output_node = inner_node
                                        break
                                if group_output_node is None:
                                    continue
                                for i, input_socket in enumerate(group_output_node.inputs):
                                    if input_socket.type == 'INT':
                                        continue
                                    if input_socket.type == 'SHADER' and not input_socket.links:
                                        continue
                                    if i >= len(node.outputs):
                                        continue
                                    on_output, off_output, on_index, off_index = self.find_on_off_inputs(node_tree, input_socket.type)
                                    output_data = {
                                        'name': input_socket.name,
                                        'socket_type': input_socket.type,
                                        'on_index': on_index,
                                        'off_index': off_index,
                                        'output_index': i
                                    }
                                    if input_socket.links:
                                        link = input_socket.links[0]
                                        from_socket = link.from_socket
                                        if from_socket.node.type == 'MIX' and from_socket.node.bl_idname in ['ShaderNodeMixShader', 'ShaderNodeMix']:
                                            mix_node = from_socket.node
                                            output_data['is_switch'] = True
                                            output_data['switch_state'] = True
                                            output_data['use_on'] = True
                                            if hasattr(mix_node, 'inputs') and len(mix_node.inputs) > 0:
                                                factor_input = mix_node.inputs[0]
                                                if hasattr(factor_input, 'default_value'):
                                                    output_data['mix_factor'] = factor_input.default_value
                                                else:
                                                    output_data['mix_factor'] = 0.0
                                            else:
                                                output_data['mix_factor'] = 0.0
                                        else:
                                            output_data['is_switch'] = True
                                            output_data['switch_state'] = False
                                            output_data['mix_factor'] = 0.0
                                            if from_socket == on_output:
                                                output_data['use_on'] = True
                                            elif from_socket == off_output:
                                                output_data['use_on'] = False
                                            else:
                                                output_data['use_on'] = True
                                    else:
                                        output_data['is_switch'] = False
                                        output_data['switch_state'] = False
                                        output_data['mix_factor'] = 0.0
                                        output_data['use_on'] = True
                                        if input_socket.type in ('FLOAT', 'VALUE'):
                                            output_data['float_value'] = input_socket.default_value
                                        elif input_socket.type in ('COLOR', 'RGBA'):
                                            output_data['color_value'] = list(input_socket.default_value)
                                        elif input_socket.type == 'VECTOR':
                                            output_data['vector_value'] = list(input_socket.default_value)
                                    panel_data['outputs'].append(output_data)
                                if panel_data['outputs']:
                                    panels.append(panel_data)
        return panels

    def invoke(self, context, event):
        for obj in bpy.data.objects:
            if obj.name == "材质设置/Material Settings":
                break
        else:
            self.report({'ERROR'}, "Material Settings object not found")
            return {'CANCELLED'}

        panels = self.scan_panels(obj)
        if not panels:
            self.report({'INFO'}, "No Crafter-Panel nodes found")
            return {'CANCELLED'}

        scene = context.scene
        scene.Crafter_panels.clear()
        scene.Crafter_panels_index = 0
        for panel_data in panels:
            panel_item = scene.Crafter_panels.add()
            panel_item.name = panel_data['name']
            panel_item.node_tree_name = panel_data['node_tree_name']
            for output_data in panel_data['outputs']:
                output_item = panel_item.outputs.add()
                output_item.name = output_data['name']
                output_item.socket_type = output_data['socket_type']
                output_item.is_switch = output_data['is_switch']
                output_item.switch_state = output_data['switch_state']
                output_item.mix_factor = output_data['mix_factor']
                output_item.on_index = output_data.get('on_index', -1)
                output_item.off_index = output_data.get('off_index', -1)
                output_item.output_index = output_data.get('output_index', -1)
                output_item.use_on = output_data.get('use_on', True)
                if 'float_value' in output_data:
                    output_item.float_value = output_data['float_value']
                if 'color_value' in output_data:
                    output_item.color_value = output_data['color_value']
                if 'vector_value' in output_data:
                    output_item.vector_value = output_data['vector_value']
        
        return context.window_manager.invoke_props_dialog(self)

    def draw(self, context):
        from ..config import __addon_name__
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        layout = self.layout
        scene = context.scene
        panels = scene.Crafter_panels
        
        # 页码和名称（最上面，居中）
        if len(panels) > 0:
            current_panel = panels[scene.Crafter_panels_index]
            panel_name = current_panel.name
            if "/" in panel_name:
                cn_name, en_name = panel_name.split("/", 1)
                panel_name = cn_name if is_chinese else en_name
            
            row = layout.row()
            row.alignment = 'CENTER'
            row.label(text=f"{panel_name} [{scene.Crafter_panels_index + 1}/{len(panels)}]", translate=False)
        
        # 翻页按钮和 operator（左右分开，中间有间隔）
        if len(panels) > 0:
            row = layout.row()
            # 左翻页 25%
            split = row.split(factor=1/3)
            split.operator("crafter.panel_prev", text="", icon='TRIA_LEFT')
            split2 = split.split(factor=1/2)
            split2.operator("crafter.material_shader_panel", text="", icon='MATERIAL')
            split2.operator("crafter.panel_next", text="", icon='TRIA_RIGHT')
        
        # 高级模式开关
        row = layout.row()
        row.prop(addon_prefs, "Advanced_Switch_Mode", text="Advanced Switch Mode")
        
        # 面板内容
        if len(panels) > 0:
            layout.separator()
            
            # 当前面板内容
            panel = current_panel
            for output in panel.outputs:
                row = layout.row(align=True)
                display_name = output.name
                if "/" in display_name:
                    cn_name, en_name = display_name.split("/", 1)
                    display_name = cn_name if is_chinese else en_name
                
                # 使用 split 创建布局
                if addon_prefs.Advanced_Switch_Mode:
                    # 高级模式：三列布局（名称 + Alpha开关 + 数值）
                    split = row.split(factor=0.4)
                    split.label(text=display_name)
                    
                    split2 = split.split(factor=0.15)
                    # Alpha 开关列
                    if output.is_switch:
                        split2.prop(output, "switch_state", text="")
                    else:
                        split2.label(text="")
                else:
                    # 普通模式：两列布局（名称 + 数值）
                    split2 = row.split(factor=0.4)
                    split2.label(text=display_name)
                
                # 第三列（高级模式）或第二列（普通模式）：混合系数、On/Off 开关或数值
                if output.is_switch:
                    if output.switch_state:
                        split2.prop(output, "mix_factor", text="", slider=True)
                    else:
                        split2.prop(output, "use_on", text="")
                else:
                    if output.socket_type in ('FLOAT', 'VALUE'):
                        split2.prop(output, "float_value", text="")
                    elif output.socket_type in ('COLOR', 'RGBA'):
                        split2.prop(output, "color_value", text="")
                    elif output.socket_type == 'VECTOR':
                        split2.prop(output, "vector_value", text="")

    def execute(self, context: bpy.types.Context):
        scene = context.scene
        panels = scene.Crafter_panels

        for panel in panels:
            # 通过名称直接获取节点树
            if panel.node_tree_name not in bpy.data.node_groups:
                continue
            node_tree = bpy.data.node_groups[panel.node_tree_name]
            
            # 找到输入节点和输出节点
            group_input = None
            group_output = None
            for node in node_tree.nodes:
                if node.type == 'GROUP_INPUT':
                    group_input = node
                elif node.type == 'GROUP_OUTPUT':
                    group_output = node
            
            if group_input is None or group_output is None:
                continue
            
            links = node_tree.links
            
            # 删除除输入/输出节点外的所有节点
            nodes_to_remove = [node for node in node_tree.nodes if node not in (group_input, group_output)]
            for node in nodes_to_remove:
                node_tree.nodes.remove(node)
            
            # 根据用户编辑结果重新连接
            for output in panel.outputs:
                if output.output_index < 0 or output.output_index >= len(group_output.inputs):
                    continue
                
                output_socket = group_output.inputs[output.output_index]
                
                if output.is_switch:
                    # 开关类型
                    if output.on_index < 0 or output.off_index < 0:
                        continue
                    if output.on_index >= len(group_input.outputs) or output.off_index >= len(group_input.outputs):
                        continue
                    
                    on_socket = group_input.outputs[output.on_index]
                    off_socket = group_input.outputs[output.off_index]
                    socket_type = output.socket_type
                    
                    if output.switch_state:
                        # Alpha 开：添加混合节点
                        if socket_type == 'SHADER':
                            # Shader 类型使用专门的混合着色器
                            mix_node = node_tree.nodes.new('ShaderNodeMixShader')
                            mix_node.location = (group_output.location.x - 200, group_output.location.y - output.output_index * 100)
                            links.new(off_socket, mix_node.inputs[1])
                            links.new(on_socket, mix_node.inputs[2])
                            mix_node.inputs[0].default_value = output.mix_factor
                            links.new(mix_node.outputs[0], output_socket)
                        else:
                            # 其他类型使用通用混合节点
                            mix_node = node_tree.nodes.new('ShaderNodeMix')
                            mix_node.location = (group_output.location.x - 200, group_output.location.y - output.output_index * 100)
                            
                            # 先设置数据类型
                            if socket_type in ('FLOAT', 'VALUE'):
                                mix_node.data_type = 'FLOAT'
                            elif socket_type in ('COLOR', 'RGBA'):
                                mix_node.data_type = 'RGBA'
                            elif socket_type == 'VECTOR':
                                mix_node.data_type = 'VECTOR'
                            
                            # 使用接口名称连接（Blender 会自动映射到正确的索引）
                            links.new(off_socket, mix_node.inputs['A'])
                            links.new(on_socket, mix_node.inputs['B'])
                            mix_node.inputs['Factor'].default_value = output.mix_factor
                            links.new(mix_node.outputs['Result'], output_socket)
                    else:
                        # Alpha 关：直接连接 On 或 Off
                        target_socket = on_socket if output.use_on else off_socket
                        links.new(target_socket, output_socket)
                else:
                    # 非开关类型：设置默认值
                    if output.socket_type in ('FLOAT', 'VALUE'):
                        output_socket.default_value = output.float_value
                    elif output.socket_type in ('COLOR', 'RGBA'):
                        output_socket.default_value = output.color_value
                    elif output.socket_type == 'VECTOR':
                        output_socket.default_value = output.vector_value

        self.report({'INFO'}, "Material panel settings applied")
        return {'FINISHED'}


class VIEW3D_OT_CrafterPanelPrev(bpy.types.Operator):
    bl_idname = "crafter.panel_prev"
    bl_label = "Previous Panel"
    bl_description = " "
    
    def execute(self, context):
        scene = context.scene
        total = len(scene.Crafter_panels)
        if total > 0:
            if scene.Crafter_panels_index > 0:
                scene.Crafter_panels_index -= 1
            else:
                scene.Crafter_panels_index = total - 1  # 循环到最后一页
        return {'FINISHED'}


class VIEW3D_OT_CrafterPanelNext(bpy.types.Operator):
    bl_idname = "crafter.panel_next"
    bl_label = "Next Panel"
    bl_description = " "
    
    def execute(self, context):
        scene = context.scene
        total = len(scene.Crafter_panels)
        if total > 0:
            if scene.Crafter_panels_index < total - 1:
                scene.Crafter_panels_index += 1
            else:
                scene.Crafter_panels_index = 0  # 循环到第一页
        return {'FINISHED'}


class VIEW3D_OT_CrafterMaterialShaderPanel(bpy.types.Operator):
    bl_label = "Material Shader Panel"
    bl_idname = "crafter.material_shader_panel"
    bl_description = "Show Material Shader Panel"

    @classmethod
    def poll(cls, context: bpy.types.Context):

        for obj in bpy.data.objects:
            if obj.name == "材质设置/Material Settings":
                return True
        else:
            return False

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        for obj in bpy.data.objects:
            if obj.name == "材质设置/Material Settings":
                break
        else:
            return {'FINISHED'}


        make_true_object_mode(context)

        obj.hide_select = False
        obj.hide_viewport = False
        context.view_layer.objects.active = obj

        bpy.ops.wm.window_new()
        window = bpy.context.window_manager.windows[-1]
        area = window.screen.areas[0]
        area.ui_type = 'ShaderNodeTree'
        
        for mat in obj.data.materials:
            if mat.use_nodes == True:
                nodes = mat.node_tree.nodes
                for node in nodes:
                    if node.type == "FRAME":
                        frame_node = node
                        nodes.active = node
                    
        obj.hide_viewport = True

        return {'FINISHED'}


# ==================== 打开材质列表文件夹 ====================

class VIEW3D_OT_CrafterOpenMaterials(bpy.types.Operator):
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

# ==================== 打开分类依据文件夹 ====================

class VIEW3D_OT_CrafterOpenClassificationBasis(bpy.types.Operator):
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

# ==================== 应用解析法向强度 ====================

class VIEW3D_OT_CrafterSetParsedNormalStrength(bpy.types.Operator):
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

# ==================== Crafter-time设置 ====================

class VIEW3D_OT_CrafterAddCrafterTime(bpy.types.Operator):
    bl_label = "Add Crafter-time"
    bl_idname = "crafter.add_craftertime"
    bl_description = "The Crafter-time node can provide the current second count to material nodes (dynamic textures and water flowing), but it will reduce the preview frame rate. It is recommended to remove it during previews and add it back before rendering"
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        selected = False
        for obj in context.selected_objects:
            if obj.type == 'MESH':
                selected = True
                break
        mode_object = context.mode == 'OBJECT'
        return selected and mode_object
    def execute(self, context: bpy.types.Context):
        for object in context.selected_objects:
            if object.type == 'MESH':
                add_Crafter_time(object)

        return {'FINISHED'}

class VIEW3D_OT_CrafterRemoveCrafterTime(bpy.types.Operator):
    bl_label = "Remove Crafter-time"
    bl_idname = "crafter.remove_craftertime"
    bl_description = "The Crafter-time node can provide the current second count to material nodes (dynamic textures and water flowing), but it will reduce the preview frame rate. It is recommended to remove it during previews and add it back before rendering"
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
    def execute(self, context: bpy.types.Context):
        for object in bpy.data.objects:
            if object.type == 'MESH':
                for modifier in object.modifiers:
                    if modifier.type == 'NODES':
                        if modifier.node_group.name == "Crafter-time":
                            object.modifiers.remove(modifier)
                            break
        return {'FINISHED'}

# ==================== 刷新 ====================

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
        if (addon_prefs.Materials_List_index < 0 or addon_prefs.Materials_List_index >= len(addon_prefs.Materials_List)) and addon_prefs.Materials_List_index != 0:
            addon_prefs.Materials_List_index = 0
            
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
        if (addon_prefs.Classification_Basis_List_index < 0 or addon_prefs.Classification_Basis_List_index >= len(addon_prefs.Classification_Basis_List)) and addon_prefs.Classification_Basis_List_index != 0:
            addon_prefs.Classification_Basis_List_index = 0

        return {'FINISHED'}

# ==================== UIList ====================

class VIEW3D_UL_CrafterMaterials(bpy.types.UIList):
    def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        if self.layout_type in {"DEFAULT","COMPACT"}:
            layout.label(text=item.name)

class VIEW3D_UL_CrafterClassificationBasis(bpy.types.UIList):
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        if self.layout_type in {"DEFAULT","COMPACT"}:
            layout.label(text=item.name)
