import bpy
import os
import json
import math

from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from bpy.props import *
from ..__init__ import dir_cafter_data, dir_resourcepacks_plans, dir_materials, dir_classification_basis, dir_blend_append, dir_init_main
from .Defs import *

lang = bpy.context.preferences.view.language
is_chinese = lang in ("zh_HANS", "zh_HANT")

# WorldImporter 输出目录（tint.json / overlay.json / doubleSided.json 都落在这里）
dir_importer = os.path.join(dir_init_main, "importer")

# ==================== 材质节点组复用 ====================
# 一套 Crafter 材质节点组（CO-/CI-/C- 节点组 + 材质设置物体）由材质库 .blend 导入而来。
# 工程里已经有一套、且用的还是同一个材质库时，Load Material 不再拆了重加载，直接复用：
# 用户对 CI- 组的改动、材质设置面板上的开关都不会被冲掉，重复导入也快得多。
# 已加载的库名记在场景上（随 .blend 保存）；库名对不上、或关键节点组缺件，就退回全量重加载。
material_library_prop = "Crafter_loaded_material_library"
node_groups_use_fake_user = ["C-PBR_Parser", "C-lab_PBR_1.3", "C-old_PBR"]
name_material_settings = "材质设置/Material Settings"


def get_material_library_name(context):
    '''
    当前偏好里选中的材质库名；没有有效选择时返回 None
    '''
    try:
        addon_prefs = context.preferences.addons[__addon_name__].preferences
    except Exception:
        return None
    index = addon_prefs.Materials_List_index
    if not (-1 < index < len(addon_prefs.Materials_List)):
        return None
    return addon_prefs.Materials_List[index].name


def get_loaded_material_library(context):
    '''
    工程里上一次加载材质用的库名（从没加载过则返回空串）
    '''
    try:
        return str(context.scene.get(material_library_prop, "") or "")
    except Exception:
        return ""


def set_loaded_material_library(context, library_name):
    '''
    记录本次加载用的材质库名，供下次判定能否复用
    '''
    try:
        context.scene[material_library_prop] = str(library_name)
    except Exception as ex:
        warn_log(f"记录材质库名失败: {ex}")


def material_setup_missing(context):
    '''
    列出复用所需的东西里缺哪些（返回空列表 = 可以复用）。
    只查后续流程真正会取用的：C-PBR_Parser、当前 PBR 解析器组、至少一个 CI- 分类组。
    材质设置物体（面板载体）不在必查项里：它缺失时由 Load Material 在同一次 load 中补上。
    '''
    missing = []
    for name in node_groups_use_fake_user:
        if name not in bpy.data.node_groups:
            missing.append(name)
    try:
        parser_name = "C-" + context.preferences.addons[__addon_name__].preferences.PBR_Parser
        if parser_name not in bpy.data.node_groups and parser_name not in missing:
            missing.append(parser_name)
    except Exception:
        pass
    if not any(node_group.name.startswith("CI-") for node_group in bpy.data.node_groups):
        missing.append("CI-*")
    return missing


def can_reuse_material_setup(context):
    '''
    工程里是否已有一套能直接复用的材质节点组：库名与上次加载一致，且关键节点组齐全。
    return: (是否可复用, 说明) —— 说明可直接进日志
    '''
    library_name = get_material_library_name(context)
    if library_name is None:
        return (False, "没有选中材质库")
    loaded_name = get_loaded_material_library(context)
    if loaded_name != library_name:
        return (False, f"材质库已切换（{loaded_name or '未记录'} → {library_name}）")
    missing = material_setup_missing(context)
    if missing:
        return (False, "缺少 " + "、".join(missing))
    return (True, f"复用工程内已有的材质节点组（{library_name}）")


def ensure_material_settings_object(context):
    '''
    把材质设置物体挂进它自己的集合（已在集合里时只刷新隐藏状态）
    '''
    obj = bpy.data.objects.get(name_material_settings)
    if obj is None:
        return
    if name_material_settings in bpy.data.collections:
        collection = bpy.data.collections[name_material_settings]
    else:
        collection = bpy.data.collections.new(name=name_material_settings)
        context.scene.collection.children.link(collection)
    if collection not in obj.users_collection:
        collection.objects.link(obj)
    obj.hide_viewport = True
    obj.hide_render = True


def ensure_material_library_settings_object(context, library_name):
    '''Return the settings object, appending it only if its groups are absent.

    Callers with CI/CO groups already loaded must accept a full, force_reload
    Load Material instead: appending the object afterwards duplicates CI groups.
    None tells panel callers to take that full reload path.
    '''
    obj = bpy.data.objects.get(name_material_settings)
    if obj is None:
        if library_name is None or any(
                group.name.startswith(("CI-", "CO-", "C-")) for group in bpy.data.node_groups):
            return None
        with bpy.data.libraries.load(os.path.join(dir_materials, library_name + ".blend"),
                                     link=False) as (data_from, data_to):
            data_to.objects = [name for name in data_from.objects if name == name_material_settings]
        obj = bpy.data.objects.get(name_material_settings)
    ensure_material_settings_object(context)
    return obj


def material_settings_object_for_panel(context):
    obj = ensure_material_library_settings_object(context, get_material_library_name(context))
    if obj is None:
        # CI-only imports omit this object. Rebuild materials and append the
        # object with CI groups in one load, rather than creating CI-*.001.
        if bpy.ops.crafter.load_material(force_reload=True) != {'FINISHED'}:
            return None
        obj = bpy.data.objects.get(name_material_settings)
    return obj


@trace_operation("重载材质库", detail=lambda context, library_name, **kwargs: dict(library=library_name, directory=dir_materials))
def reload_material_setup(context, library_name, include_settings=False):
    '''
    全量重加载材质节点组：删掉工程里旧的 CO-/CI-/C- 节点组与材质设置物体，
    再从 append.blend 与指定材质库 .blend 直接导入 CI/CO，嵌套 C- 组随依赖导入。
    include_settings: 面板需要的设置物体必须与 CI/CO 在同一次 load 中加载，避免重名组。
    library_name: 材质库名（dir_materials 下去掉 .blend 的文件名）
    '''
    # 删除startswith(CO-)、startswith(CI-)节点组、startswith(C-)节点组
    node_delete = []
    for node_group in bpy.data.node_groups:
        if node_group.name.startswith("CO-") or node_group.name.startswith("CI-") or node_group.name.startswith("C-"):
            node_delete.append(node_group)
    for node_group in node_delete:
        bpy.data.node_groups.remove(node_group)
    # 删除材质设置/Material Settings物体、材质
    try:
        bpy.data.objects.remove(bpy.data.objects[name_material_settings])
    except:
        pass
    try:
        bpy.data.materials.remove(bpy.data.materials[name_material_settings], do_unlink=True)
    except:
        pass
    # 导入C-节点组（已存在的跳过）
    node_groups_missing = [name for name in node_groups_use_fake_user if name not in bpy.data.node_groups]
    if node_groups_missing:
        with bpy.data.libraries.load(dir_blend_append, link=False) as (data_from, data_to):
            data_to.node_groups = [name for name in data_from.node_groups if name in node_groups_missing]
    for name in node_groups_use_fake_user:
        if name in bpy.data.node_groups:
            bpy.data.node_groups[name].use_fake_user = True
    # 导入Crafter-Moving_texture节点组
    add_node_group_if_not_exists(names_Crafter_Moving_texture)
    # Cycles 剔除组（CI 的 Cycles 输出与最终输出之间那层，默认静音）
    add_node_group_if_not_exists([CULL_GROUP_NAME])
    # Import only the shader groups on the automatic path; Blender loads their
    # nested helpers. Panel loads request the settings object in this same pass.
    blend_material_dir = os.path.join(dir_materials, library_name + ".blend")
    with bpy.data.libraries.load(blend_material_dir, link=False) as (data_from, data_to):
        data_to.node_groups = [name for name in data_from.node_groups
                               if name.startswith(("CI-", "CO-"))]
        if include_settings:
            data_to.objects = [name for name in data_from.objects if name == name_material_settings]
    if not include_settings:
        # The settings material normally keeps unused classifications alive.
        # CI-only loads need their own users so save/reopen preserves the library.
        for group in data_to.node_groups:
            if group is not None:
                group.use_fake_user = True
    ensure_material_settings_object(context)

# ==================== 加载材质 ====================

def get_double_sided_cull_setting(context):
    '''
    双面几何背面剔除的开关与目录。
    开关关闭时返回 (False, "")，调用方据此跳过 doubleSided.json 的读取。
    return: (是否启用, WorldImporter 输出目录)
    '''
    try:
        addon_prefs = context.preferences.addons[__addon_name__].preferences
    except Exception:
        return (False, "")
    return (bool(getattr(addon_prefs, "doubleSidedBackfaceCull", True)), dir_importer)

# ========== 提取的分类数据加载函数 ==========
@trace_operation("load_classification_data")
def load_classification_data(addon_prefs):
    '''
    从分类基础目录加载所有 JSON 分类文件，规范化合并并编译为结构化分类规则。
    组的值可以是字典（单条目）或字典列表（条目间 OR）；多文件同名组的条目按加载顺序拼接。
    addon_prefs: 插件偏好设置，从中获取当前选中的分类基础文件夹名称
    return: 编译后的规则列表（见 Defs.compile_classification_list）
    '''
    classification_folder_name = addon_prefs.Classification_Basis_List[addon_prefs.Classification_Basis_List_index].name
    classification_folder_dir = os.path.join(dir_classification_basis, classification_folder_name)
    classification_raw = {}   # 组名 -> [条目, ...]
    for filename in os.listdir(classification_folder_dir):
        file_path = os.path.join(classification_folder_dir, filename)
        if filename.endswith(".json"):
            try:
                with open(file_path, 'r', encoding='utf-8') as file:
                    data = json.load(file)
                    for group_name, group in data.items():
                        entries = group if isinstance(group, list) else [group]
                        for entry in entries:
                            if isinstance(entry, dict):
                                classification_raw.setdefault(group_name, []).append(entry)
            except Exception:
                log_exception("分类规则读取失败，跳过文件", "加载分类规则", path=file_path)
    debug_log("分类规则加载完成", "加载分类规则", directory=classification_folder_dir, groups=len(classification_raw))
    return compile_classification_list(classification_raw)

# ========== 提取的单材质处理函数 ==========
@trace_operation("处理单个材质", level="DEBUG", detail=lambda material, *args, **kwargs: dict(material=material.name))
def process_single_material(material, context, classification_list, imported_by_crafter=False, folder_search=None,
                            addon_prefs=None):
    '''
    处理单个材质：扫描 TEX_IMAGE 节点识别基础色/PBR贴图，构建 CI- 节点组，
    装配 PBR 解析器，连接法向和 PBR 贴图。
    material:             要处理的材质
    context:             Blender 上下文
    classification_list: 分类规则列表（来自 load_classification_data）
    imported_by_crafter: 是否由 Crafter 导入触发（True=保留已有贴图节点不重新从文件查找PBR）
    folder_search:       面板按钮路径的"去基础色贴图文件夹找 PBR/法向"开关：
                         None  = 导入路径，按 imported_by_crafter 的既定判据走
                                 （非 Crafter 材质会先删掉已有的后缀节点，再从文件夹整体重找）；
                         True/False = 面板路径，材质里已有的法向/PBR 节点一律保留不动，
                                 只在这个值为 True 时去文件夹补上缺的那张。
    '''
    node_tree_material = material.node_tree
    if node_tree_material is None:
        return
    if addon_prefs is None:
        addon_prefs = context.preferences.addons[__addon_name__].preferences
    nodes = node_tree_material.nodes
    links = node_tree_material.links
    # 面板路径（folder_search 非 None）：已有的法向/PBR 一律保留，开关只决定缺的那张要不要去找
    manual = folder_search is not None
    keep_existing = True if manual else imported_by_crafter

    if bpy.app.version >= (4, 2, 0):
        material.volume_intersection_method = 'ACCURATE'
        material.displacement_method = "BOTH"

    # 双面几何背面剔除：开启时按 doubleSided.json 判定该材质是否使用双面几何，
    # 命中则打开 EEVEE 剔除（渲染 + 阴影）并把 CI 组的 Cycles 背面剔除输入写 1。
    # 注意：必须等下面的 CI- 组建好之后再调用（由 _apply_ds_cull 在两个 return 前触发）。
    _ds_enabled, _ds_dir = get_double_sided_cull_setting(context)

    def _apply_ds_cull():
        '''CI- 组建好后按 doubleSided.json 应用背面剔除（两条分支各自触发）'''
        if not _ds_enabled:
            return
        try:
            apply_double_sided_backface_cull(material, _ds_dir)
        except Exception as ex:
            warn_log(f"双面几何背面剔除失败 [{material.name}]: {ex}")

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
        link_CI_output(group_CI=group_CI, node_output_EEVEE=node_output_EEVEE, node_output_Cycles=node_output_Cycles, links=links, material=material)
        link_biome_tex(node_biomeTex=node_biomeTex, group_CI=group_CI, links=links)
        tint = get_material_tint(material)
        try:
            apply_tint(group_CI=group_CI, nodes=nodes, links=links, tint=tint)
        except Exception as ex:
            warn_log(f"apply_tint 失败 [{material.name}]: {ex}")
        add_node_parser(group_CI=group_CI, nodes=nodes, links=links, addon_prefs=addon_prefs,
                        has_pbr_texture=False)
        _apply_ds_cull()
        return

    nodes_wait_remove = []
    real_block_name = None
    node_tex_normal = None
    node_tex_PBR = None
    node_biomeTex = None
    node_output_EEVEE = None
    # 先收齐本材质(不含 overlay 帧)的贴图候选，再整份一起判角色：
    # 只有存在同名的无后缀贴图时，_n/_s/_a 才算 PBR/法向贴图；孤立的后缀贴图
    # （如资源包里只有 polaroids_a.png）本身就是基础色，不能按后缀吃成 PBR。
    texture_candidates = [node for node in nodes
                          if node.type == "TEX_IMAGE" and node.image is not None
                          and not in_overlay_frame(node)]
    candidate_roles = texture_node_roles(texture_candidates)
    for node in nodes:
        # overlay 帧内的贴图/节点属于叠加层，不参与基础色扫描（也不能被删）
        if in_overlay_frame(node):
            continue
        role_texture = candidate_roles.get(node)
        if role_texture is not None:
            if role_texture == TEX_ROLE_NORMAL:
                if keep_existing:
                    node_tex_normal = node
                else:
                    bpy.data.images.remove(node.image)
                    nodes_wait_remove.append(node)
            elif role_texture == TEX_ROLE_PBR:
                if keep_existing:
                    # 有 _a 用 _a，否则用 _s（原实现 _a 覆盖 _s，这里按文件名择优，与扫描顺序无关）
                    if node_tex_PBR is None:
                        node_tex_PBR = node
                    elif fuq_bl_dot_number(node.image.name).endswith("_a.png"):
                        node_tex_PBR = node
                else:
                    bpy.data.images.remove(node.image)
                    nodes_wait_remove.append(node)
            else:
                # 基础色：第一张当基础色并统一为最近邻采样，其余候选节点删掉
                if node_tex_base is not None:
                    nodes_wait_remove.append(node)
                else:
                    node.interpolation = "Closest"
                    # 孤立后缀贴图当基础色时，它之前可能已按 PBR 被设成 Non-Color
                    # （add_node_pbr 会改），这里纠正回 sRGB，否则基础色会发暗
                    try:
                        if node.image.colorspace_settings.name == "Non-Color":
                            node.image.colorspace_settings.name = "sRGB"
                    except Exception:
                        pass
                    node_tex_base = node
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
    # 分类取值：优先材质名的完整名（如 minecraft:block/oak_leaves；CTM 材质去掉 @ 后缀后
    # 得到原材质名）。名字超过 63 字节时 Blender 侧是截断名，这里从节点帧里读回完整名
    # （见 Defs.store_full_material_name），否则贴图名等尾部信息会丢。
    full_material_name = get_full_material_name(material) or material.name
    if ":" in material.name:
        real_block_name = material_base_name(full_material_name)
    elif node_tex_base is not None:
        real_block_name = texture_base_name(fuq_bl_dot_number(node_tex_base.image.name))
    else:
        real_block_name = material_base_name(full_material_name)
    if real_block_name is None:
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
    # 给输出前面的 overlay Mix 链留位置: 有 overlay 时基础 CI 与 Mix 链按同一步长左移
    # (CI -> Mix1 -> ... -> 输出的左边缘间距统一为 overlay_mix_step)
    overlay_frames = find_overlay_frames(nodes, material)
    overlay_count = len(overlay_frames)
    ci_offset_x = overlay_mix_step * (overlay_count + 1) if overlay_count > 0 else 200.0
    group_CI.location = (node_output_EEVEE.location.x - ci_offset_x,
                         node_output_EEVEE.location.y)
    find_CI_group(group_CI=group_CI, real_block_name=real_block_name, classification_list=classification_list)
    link_CI_output(group_CI=group_CI, node_output_EEVEE=node_output_EEVEE, node_output_Cycles=node_output_Cycles, links=links, material=material)
    # 按 CI- 组的输出接口名决定这个材质的剔除（见 Defs.CI_CULL_OUTPUT_*）：有 → 开，没有 → 关
    sync_CI_culling(material, count=True)
    link_biome_tex(node_biomeTex=node_biomeTex, group_CI=group_CI, links=links)
    tint = get_material_tint(material)
    try:
        apply_tint(group_CI=group_CI, nodes=nodes, links=links, tint=tint)
    except Exception as ex:
        warn_log(f"apply_tint 失败 [{material.name}]: {ex}")
    if manual:
        # 只补缺：已有哪一类就保留哪一类，开关开着的才去文件夹找缺的那张
        want_normal = bool(folder_search) and node_tex_normal is None
        want_pbr = bool(folder_search) and node_tex_PBR is None
        if node_tex_base is not None and (want_normal or want_pbr):
            node_new_normal, node_new_PBR = load_normal_and_PBR(
                node_tex_base=node_tex_base, nodes=nodes, links=links,
                want_normal=want_normal, want_pbr=want_pbr, keep_moving_texture=True)
            if node_tex_normal is None:
                node_tex_normal = node_new_normal
            if node_tex_PBR is None:
                node_tex_PBR = node_new_PBR
    elif not imported_by_crafter:
        node_tex_normal, node_tex_PBR = load_normal_and_PBR(node_tex_base=node_tex_base, nodes=nodes, links=links)
    # 贴图确定后再建解析器：没有法向/PBR 贴图且偏好没开"始终添加"时跳过，改写成默认常量
    has_pbr_texture = node_tex_normal is not None or node_tex_PBR is not None
    node_C_PBR_Parser = add_node_parser(group_CI=group_CI, nodes=nodes, links=links,
                                      addon_prefs=addon_prefs,
                                      has_pbr_texture=has_pbr_texture)
    link_base_normal_PBR(node_tex_base=node_tex_base, group_CI=group_CI, links=links, node_C_PBR_Parser=node_C_PBR_Parser, node_tex_normal=node_tex_normal, node_tex_PBR=node_tex_PBR)
    if node_tex_base is not None:
        nodes.active = node_tex_base
    # overlay 图层：帧内建 CI/Parser，并用 Mix Shader 按层盖到基础表面上
    try:
        process_overlay_frames(material, nodes, links, group_CI,
                               node_output_EEVEE, node_output_Cycles, classification_list,
                               frames=overlay_frames, addon_prefs=addon_prefs)
    except Exception as ex:
        warn_log(f"overlay 处理失败 [{material.name}]: {ex}")

    # 最后一步：CI- 组与 overlay 帧里的 CI 节点都已就位，再按双面几何清单应用剔除
    _apply_ds_cull()


# ========== overlay 图层加载 ==========

def _clear_overlay_generated_nodes(nodes, frame):
    '''
    清掉 overlay 帧内上一次 Load Material 生成的节点（CI/Parser/Mix Shader），
    保留贴图/动态纹理/biomeTex，保证重复加载材质幂等。
    '''
    wait_remove = []
    for node in nodes:
        if node.parent != frame:
            continue
        if node.type == "GROUP":
            tree = node.node_tree
            if tree is None or tree.name.startswith("CI-") or tree.name.startswith("C-PBR_Parser"):
                wait_remove.append(node)
        elif node.bl_idname == "ShaderNodeMixShader":
            wait_remove.append(node)
    for node in wait_remove:
        try:
            nodes.remove(node)
        except:
            pass

def _overlay_frame_pbr_textures(nodes, frame):
    '''
    取 overlay 帧内的法向/PBR 贴图节点（角色判定同基础层：整帧贴图一起判，
    只有存在同名的无后缀贴图时 _n/_s/_a 才算 PBR/法向）
    '''
    texture_nodes = [node for node in nodes
                     if node.parent == frame and node.type == "TEX_IMAGE" and node.image is not None]
    return (texture_node_by_role(texture_nodes, TEX_ROLE_NORMAL),
            texture_node_by_role(texture_nodes, TEX_ROLE_PBR))

def _remove_overlay_mix_nodes(nodes):
    '''
    清掉上一轮建在输出节点前面的 overlay Mix 链（按自定义属性标记识别）
    '''
    wait_remove = []
    for node in nodes:
        if node.bl_idname != "ShaderNodeMixShader":
            continue
        try:
            if node.get("Crafter_overlay_mix"):
                wait_remove.append(node)
        except Exception:
            pass
    for node in wait_remove:
        try:
            nodes.remove(node)
        except:
            pass

def _apply_overlay_layout_shift(material, nodes, new_shift):
    '''
    根据 overlay 图层数把"其他节点"(贴图/帧/群组等; 输出节点与链上的 CI/Parser/Mix、
    biomeTex 除外)左移 new_shift，给输出节点前面的 Mix 链让位。位移量记在材质属性
    Crafter_overlay_shift 上，重复加载时按差值位移，不会越移越偏。
    '''
    try:
        old_shift = float(material.get("Crafter_overlay_shift", 0.0) or 0.0)
    except Exception:
        old_shift = 0.0
    delta = new_shift - old_shift
    if abs(delta) > 1e-6:
        for node in nodes:
            if node.parent is not None:
                continue
            if node.type == "OUTPUT_MATERIAL":
                continue
            if node.type == "GROUP" and node.node_tree is not None and (
                    node.node_tree.name.startswith("CI-") or
                    node.node_tree.name.startswith("C-PBR_Parser") or
                    node.node_tree.name.startswith("Crafter-biomeTex")):
                continue
            if node.bl_idname == "ShaderNodeMixShader":
                continue
            node.location = (node.location.x - delta, node.location.y)
    material["Crafter_overlay_shift"] = new_shift

def process_overlay_frames(material, nodes, links, group_CI,
                           node_output_EEVEE, node_output_Cycles, classification_list,
                           frames=None, addon_prefs=None):
    '''
    overlay 图层加载：
    - 每个 overlay 帧按 label 里的完整材质名分类建 CI（挂在帧内），帧内贴图接 CI 的
      Base Color 与 C-PBR_Parser。
    - 再用 Mix Shader 按层序把各层 CI 的 Surface 输出叠到基础 CI 表面上：
        A = 上一层结果（首层为基础 CI 输出），B = 本层 CI 输出，Fac = 本层基础色贴图 Alpha。
    - overlay CI 的 Alpha 输入故意不接（保持默认 1）：CI 内部已经用 Alpha 把 Transparent
      混进 Surface 输出，若再接一遍会二次乘 alpha（颜色 a²、还会透出背景）；透明度统一由
      外层 Mix 控制即为标准 alpha-over。
    - 帧内 CI 与基础 CI 同一 x 列（parser 随之对齐），各层 CI/Parser 各成一条竖列。
    - Mix 链建在输出节点前面（最上层离输出最近，其余依次向左，纵向微调对齐插座）；
      其他节点按层数左移让位，位移量记录在材质属性上，重复加载按差值位移不会漂移。
    - 可重复调用：先清掉上一轮的 Mix 链与帧内 CI/Parser 再重建。
    '''
    if frames is None:
        frames = find_overlay_frames(nodes, material)
    if not frames:
        # Most imported materials have no overlay. Only undo layout/mix state
        # when overlay frames from a previous load have actually been removed.
        if material.get("Crafter_overlay_shift"):
            _remove_overlay_mix_nodes(nodes)
            _apply_overlay_layout_shift(material, nodes, 0.0)
        return
    if group_CI is None:
        return
    _remove_overlay_mix_nodes(nodes)
    # 按 overlay 数量把其他节点左移, 给输出节点前面的 Mix 链让位(记录差值, 重复加载不漂移)
    _apply_overlay_layout_shift(material, nodes, overlay_layout_shift(nodes, frames=frames))

    # 每个输出目标一条链: (CI 输出名, 输出节点, overlay 终点插座)
    # Cycles 有剔除组时，Mix 链接到剔除组的输入（剔除组放在整条 Mix 链之后）
    targets = []
    if node_output_EEVEE is not None and "EEVEE-Surface" in group_CI.outputs:
        targets.append(("EEVEE-Surface", node_output_EEVEE, node_output_EEVEE.inputs["Surface"]))
    if node_output_Cycles is not None and "Cycles-Surface" in group_CI.outputs:
        cull_input = overlay_cull_input(material)
        end_socket = cull_input if cull_input is not None else node_output_Cycles.inputs["Surface"]
        targets.append(("Cycles-Surface", node_output_Cycles, end_socket))
    if not targets:
        return
    chain = {name: group_CI.outputs[name] for name, _, _ in targets}

    for seq, (index, name, frame) in enumerate(frames, start=1):
        _clear_overlay_generated_nodes(nodes, frame)
        node_tex = overlay_frame_base_texture(nodes, frame)
        if node_tex is None:
            continue

        node_CI = nodes.new(type="ShaderNodeGroup")
        node_CI.parent = frame
        # 与基础 CI 同 x 列(帧无父帧, 直接相减得到帧内相对 x), parser 随之对齐
        node_CI.location = (group_CI.location.x - frame.location.x, node_tex.location.y)
        find_CI_group(group_CI=node_CI,
                      real_block_name=material_base_name(name),
                      classification_list=classification_list)
        tint = get_overlay_tint(material, index)
        if "Base Color" in node_CI.inputs:
            links.new(node_tex.outputs["Color"], node_CI.inputs["Base Color"])

        try:
            apply_tint(group_CI=node_CI, nodes=nodes, links=links, tint=tint)
        except Exception as ex:
            warn_log(f"apply_tint 失败 [overlay {name}]: {ex}")

        node_tex_normal, node_tex_PBR = _overlay_frame_pbr_textures(nodes, frame)
        node_parser = add_node_parser(group_CI=node_CI, nodes=nodes, links=links, parent=frame,
                                      addon_prefs=addon_prefs,
                                      has_pbr_texture=(node_tex_normal is not None or node_tex_PBR is not None))
        link_base_normal_PBR(node_tex_base=None, group_CI=node_CI, links=links,
                             node_C_PBR_Parser=node_parser,
                             node_tex_normal=node_tex_normal, node_tex_PBR=node_tex_PBR)

        for surface_name, output_node, end_socket in targets:
            if surface_name not in node_CI.outputs:
                continue
            mix_node = nodes.new(type="ShaderNodeMixShader")
            mix_node["Crafter_overlay_mix"] = 1   # 标记: 便于重复加载时清理
            mix_node.label = "Crafter-overlay"
            # 输出节点前面: 最上层离输出最近, 其余依次向左(其他节点已按层数左移让位);
            # 纵向略微下移, 让 Mix 的输出插槽与输出节点的 Surface 插槽齐平
            mix_node.location = (output_node.location.x - overlay_mix_step * (len(frames) - seq + 1),
                                 output_node.location.y - overlay_mix_y_offset)
            links.new(node_tex.outputs["Alpha"], mix_node.inputs[0])
            links.new(chain[surface_name], mix_node.inputs[1])
            links.new(node_CI.outputs[surface_name], mix_node.inputs[2])
            for old_link in list(end_socket.links):
                links.remove(old_link)
            links.new(mix_node.outputs[0], end_socket)
            chain[surface_name] = mix_node.outputs[0]

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
    classification_list = load_classification_data(addon_prefs)
    for mat in obj.data.materials:
        process_single_material(mat, context, classification_list, imported_by_crafter=False,
                                addon_prefs=addon_prefs)
    add_to_mcmts_collection(object=obj, context=context)
    add_to_crafter_mcmts_collection(object=obj, context=context)
    if addon_prefs.Add_Crafter_time_On_Import:
        add_Crafter_time(obj=obj)
    bpy.ops.crafter.set_pbr_parser()

class VIEW3D_OT_CrafterLoadMaterial(bpy.types.Operator):
    bl_label = "Load Material"
    bl_idname = "crafter.load_material"
    bl_description = "Load Material"
    bl_options = {"REGISTER", "UNDO"}

    # 忽略工程里已有的材质节点组，强制从材质库重加载（面板上的 Reload 按钮）
    force_reload: BoolProperty(name="Force Reload",
                               description="Ignore the material node groups already in this project and reload them from the material library",
                               default=False)# type: ignore
    # 由导入流程调用（世界导入的自动加载 / 导入物品）：不理会面板上的"查找PBR贴图"开关
    from_import: BoolProperty(name="From Import",
                              description="Called by the import flow: ignore the Search PBR preference, import behaviour stays unchanged",
                              default=False,
                              options={'HIDDEN'})# type: ignore

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
        return any(obj.type == "MESH" for obj in context.selected_objects)

    @trace_operation("加载材质", detail=lambda self, context: dict(from_import=self.from_import, force_reload=self.force_reload, selected_objects=len(context.selected_objects)))
    def execute(self, context: bpy.types.Context):

        info_log("准备加载材质", "加载材质")
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        reset_CI_cull_stats()

        bpy.ops.crafter.reload_all()
        if not (-1 < addon_prefs.Materials_List_index and addon_prefs.Materials_List_index < len(addon_prefs.Materials_List)):
            warn_log("取消加载：未选择有效材质库", "加载材质", library_index=addon_prefs.Materials_List_index)
            return {"CANCELLED"}
        make_true_object_mode(context)
        # finish_import already applies world transforms. Item/manual imports
        # can still have rotation or scale, so retain the operator when needed.
        if not self.from_import or any(
                tuple(obj.rotation_euler) != (0.0, 0.0, 0.0) or tuple(obj.scale) != (1.0, 1.0, 1.0)
                for obj in context.selected_objects if obj.type == "MESH"):
            bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
        # 工程里已有一套同库的材质节点组就直接复用，只有换库/缺件/强制重加载才拆了重来。
        # 材质设置物体（面板载体）缺失时也要补：随 CI/CO 在同一次 load 里加载，否则导入后
        # 第一次打开材质面板会触发一次全量重载（工程材质越多越慢）。
        library_name = get_material_library_name(context)
        reusable, reason = can_reuse_material_setup(context)
        needs_settings = name_material_settings not in bpy.data.objects
        if self.force_reload or not reusable or needs_settings:
            if self.force_reload:
                reason = f"强制重加载材质节点组（{library_name}）"
            elif needs_settings and reusable:
                reason = f"补载材质设置物体（{library_name}）"
            info_log(f'重加载材质节点组：{reason}', '加载材质', library=library_name, action='reload')
            # include_settings 恒为 True：设置物体必须和 CI/CO 同一次 load，避免生成 CI-*.001
            reload_material_setup(context, library_name, include_settings=True)
            set_loaded_material_library(context, library_name)
        else:
            info_log(reason, '加载材质', library=library_name, action='reuse')
            ensure_material_settings_object(context)
        # 获取分类依据规则
        classification_list = load_classification_data(addon_prefs)
        # 应用 Parsed_Normal_Strength
        bpy.ops.crafter.set_parsed_normal_strength()

        for_collection = []
        for mcmt in context.scene.Crafter_mcmts:
            for_collection.append(mcmt.name)
        # 添加选中物体的材质到遍历合集
        for obj in context.selected_objects:
            if obj.type == "MESH":
                for mat in obj.data.materials:
                    if mat is not None and mat.name not in for_collection:
                        for_collection.append(mat.name)
        # 面板按钮路径按"查找PBR贴图"开关决定要不要去文件夹补缺；导入路径的判据保持不变
        folder_search = None if self.from_import else bool(addon_prefs.Search_PBR_On_Load)
        info_log("材质处理计划", "加载材质", count=len(for_collection), library=library_name, folder_search=folder_search)
        # 遍历材质合集
        for name_material in for_collection:
            imported_by_crafter = False
            if name_material in context.scene.Crafter_crafter_mcmts:
                imported_by_crafter = True
            material = bpy.data.materials[name_material]
            process_single_material(material, context, classification_list, imported_by_crafter, folder_search,
                                    addon_prefs=addon_prefs)
        # 添加选中物体的材质到合集
        for obj in context.selected_objects:
            if obj.type == "MESH":
                add_to_mcmts_collection(object=obj,context=context)
                add_to_crafter_mcmts_collection(object=obj,context=context)
                if addon_prefs.Add_Crafter_time_On_Import:
                    add_Crafter_time(obj=obj)
                
        if self.from_import:
            # Each new base/overlay parser already has its defaults; only the
            # shared child parser needs rewiring, without another material walk.
            ensure_pbr_parser_group(addon_prefs)
        else:
            bpy.ops.crafter.set_pbr_parser()

        # 逐材质在加载时就同步过了，这里只汇总日志（不再全量扫描）
        cull_stats = get_CI_cull_stats()
        push_log(f'[接口驱动剔除] 本次处理 {cull_stats["scanned"]} 个材质: '
                 f'{CI_CULL_OUTPUT_RENDER} → {cull_stats["render"]} 个开背面剔除, '
                 f'{CI_CULL_OUTPUT_SHADOW} → {cull_stats["shadow"]} 个开阴影剔除', 'INFO')

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

        info_log('加载视差', '加载视差')
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
            # 贴图角色：整份材质(不含 overlay 帧)一起判，只有存在同名的无后缀贴图时
            # _n/_s/_a 才算 PBR/法向（孤立的后缀贴图是基础色）
            texture_candidates = [tex_node for tex_node in nodes
                                  if tex_node.type == "TEX_IMAGE" and tex_node.image is not None
                                  and not in_overlay_frame(tex_node)]
            candidate_roles = texture_node_roles(texture_candidates)
            for node in nodes:
                # overlay 帧内的节点属于叠加层，不参与视差的基底/贴图查找
                if in_overlay_frame(node):
                    continue
                role_texture = candidate_roles.get(node)
                if role_texture == TEX_ROLE_NORMAL:
                    node_tex_normal = node
                elif role_texture == TEX_ROLE_PBR:
                    # 有 _a 用 _a，否则用 _s（与扫描顺序无关）
                    if node_tex_PBR is None:
                        node_tex_PBR = node
                    elif fuq_bl_dot_number(node.image.name).endswith("_a.png"):
                        node_tex_PBR = node
                elif role_texture == TEX_ROLE_BASE:
                    if node_tex_base is not None:
                        nodes_wait_remove.append(node)
                    else:
                        node_tex_base = node
                if node.type == "GROUP":
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
                        if addon_prefs.Parallax_Based_on_Parsed_Normal and node_C_PBR_Parser is not None:
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

        info_log('移除视差', '移除视差')
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
                if in_overlay_frame(node):
                    continue
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
                if in_overlay_frame(node):
                    continue
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
                # overlay 帧内的贴图有自己的动态纹理节点，不能被基础层的动态纹理重连
                if in_overlay_frame(node):
                    continue
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

        ensure_pbr_parser_group(addon_prefs)

        for name_material in context.scene.Crafter_mcmts:
            material = bpy.data.materials[name_material.name]
            node_tree_material = material.node_tree
            if node_tree_material is None:
                continue
            for node in node_tree_material.nodes:
                if node.type == "GROUP" and node.node_tree is not None:
                    if node.node_tree.name == "C-PBR_Parser":
                        apply_default_pbr_to_parser_node(node, addon_prefs)
        return {'FINISHED'}

class VIEW3D_OT_CrafterMaterialPanel(bpy.types.Operator):
    bl_label = "Material Panel"
    bl_idname = "crafter.material_panel"
    bl_description = "Show Material Panel"
    bl_options = {'REGISTER', 'UNDO'}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return name_material_settings in bpy.data.objects or can_reuse_material_setup(context)[0]

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

    def get_float_interface_range(self, node_tree, output_index):
        '''从节点组接口读取第 output_index 个输出项（FLOAT 类型）的自定义范围与子类型。

        Blender 4.2+ 的接口定义在 node_tree.interface.items_tree 中；
        未设置范围时 Blender 返回 ±FLT_MAX（约 3.4e38），视为无范围。
        返回 (min_value, max_value, subtype)，无有效范围时前两个为 None。
        '''
        try:
            if node_tree is None or node_tree.interface is None:
                return None, None, None
            out_index = 0
            for item in node_tree.interface.items_tree:
                if getattr(item, 'item_type', None) != 'SOCKET':
                    continue
                if getattr(item, 'in_out', None) != 'OUTPUT':
                    continue
                if out_index != output_index:
                    out_index += 1
                    continue
                if getattr(item, 'socket_type', '') in ('FLOAT', 'VALUE', 'NodeSocketFloat'):
                    subtype = getattr(item, 'subtype', 'NONE') or 'NONE'
                    min_value = getattr(item, 'min_value', None)
                    max_value = getattr(item, 'max_value', None)
                    if min_value is None or max_value is None:
                        return None, None, subtype
                    if not (math.isfinite(min_value) and math.isfinite(max_value)):
                        return None, None, subtype
                    # 接口未设置范围时的默认 ±FLT_MAX，视为无范围
                    if abs(min_value) > 1e30 or abs(max_value) > 1e30:
                        return None, None, subtype
                    if min_value >= max_value:
                        return None, None, subtype
                    return float(min_value), float(max_value), subtype
                break
        except Exception:
            pass
        return None, None, None

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
                                            min_v, max_v, subtype = self.get_float_interface_range(node_tree, i)
                                            output_data['float_subtype'] = subtype or 'NONE'
                                            if min_v is not None and max_v is not None:
                                                output_data['float_min'] = min_v
                                                output_data['float_max'] = max_v
                                                output_data['has_float_range'] = True
                                        elif input_socket.type in ('COLOR', 'RGBA'):
                                            output_data['color_value'] = list(input_socket.default_value)
                                        elif input_socket.type == 'VECTOR':
                                            output_data['vector_value'] = list(input_socket.default_value)
                                    panel_data['outputs'].append(output_data)
                                if panel_data['outputs']:
                                    panels.append(panel_data)
        return panels

    def invoke(self, context, event):
        obj = material_settings_object_for_panel(context)
        if obj is None:
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
                on_index = output_data.get('on_index', -1)
                output_item.on_index = on_index if on_index is not None else -1
                off_index = output_data.get('off_index', -1)
                output_item.off_index = off_index if off_index is not None else -1
                output_item.output_index = output_data.get('output_index', -1)
                output_item.use_on = output_data.get('use_on', True)
                if 'float_value' in output_data:
                    # float_value 是动态 IDProperty，范围由 apply_float_ui_range 经 id_properties_ui 设置
                    output_item["float_value"] = output_data['float_value']
                output_item.float_subtype = output_data.get('float_subtype', 'NONE')
                if 'float_min' in output_data:
                    output_item.float_min = output_data['float_min']
                if 'float_max' in output_data:
                    output_item.float_max = output_data['float_max']
                if 'has_float_range' in output_data:
                    output_item.has_float_range = output_data['has_float_range']
                if 'float_value' in output_data:
                    # 范围/步进只写一次；draw 每帧不再重写动态 IDProperty 的 UI 数据
                    self.apply_float_ui_range(output_item)
                # 开关项缺少 On/Off 接口：不渲染开关控件，面板顶部显示错误提示
                if output_item.is_switch and (output_item.on_index < 0 or output_item.off_index < 0):
                    panel_item.on_off_missing = True
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
            if panel.on_off_missing:
                layout.label(text="No On/Off interface found", icon="ERROR")
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
                    # Alpha 开关列（缺少 On/Off 接口时不渲染开关）
                    if output.is_switch and not (output.on_index < 0 or output.off_index < 0):
                        split2.prop(output, "switch_state", text="")
                    else:
                        split2.label(text="")
                else:
                    # 普通模式：两列布局（名称 + 数值）
                    split2 = row.split(factor=0.4)
                    split2.label(text=display_name)
                
                # 第三列（高级模式）或第二列（普通模式）：混合系数、On/Off 开关或数值
                if output.is_switch:
                    if output.on_index < 0 or output.off_index < 0:
                        pass  # 不显示开关控件，面板顶部已有错误提示
                    elif output.switch_state:
                        split2.prop(output, "mix_factor", text="", slider=True)
                    else:
                        split2.prop(output, "use_on", text="")
                else:
                    if output.socket_type in ('FLOAT', 'VALUE'):
                        # float_value 是动态 IDProperty，prop 须用 '["..."]' 自定义属性语法；
                        # 与原节点组显示一致：FACTOR 类接口显示滑块，其余保持数字框
                        split2.prop(output, '["float_value"]', text="", slider=(output.float_subtype == 'FACTOR'))
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
                        output_socket.default_value = output["float_value"] if "float_value" in output else 0.0
                    elif output.socket_type in ('COLOR', 'RGBA'):
                        output_socket.default_value = output.color_value
                    elif output.socket_type == 'VECTOR':
                        output_socket.default_value = output.vector_value

        self.report({'INFO'}, "Material panel settings applied")
        return {'FINISHED'}

    def apply_float_ui_range(self, output):
        '''把接口自定义范围写入 float_value（动态 IDProperty）的 UI 数据。

        只写 soft_min/soft_max 而不写 min/max（硬范围）：
        - 滑块拖动按 soft 范围限制（与原节点组拖动条行为一致）；
        - 点击输入框不受钳制，可输入任意值（与原节点组输入行为一致）。
        接口无范围时不写入，保持无限制数字框。
        '''
        if not output.has_float_range:
            return
        try:
            if "float_value" not in output:
                output["float_value"] = 0.0
            output.id_properties_ui("float_value").update(
                soft_min=output.float_min,
                soft_max=output.float_max,
                step=3,
                precision=3,
            )
        except Exception:
            pass


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
        return name_material_settings in bpy.data.objects or can_reuse_material_setup(context)[0]

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        obj = material_settings_object_for_panel(context)
        if obj is None:
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

# ==================== 时间驱动（Time Driver，旧名 Crafter-time）====================
# 时间写在物体颜色属性的 alpha 通道上（驱动器 frame * fps_base / fps = 当前秒数），
# 材质里用 Object Info 节点的 Alpha 输出读取。一次驱动器求值，与几何量无关，可以常开。
# 内部 id 保持 add_craftertime/remove_craftertime，避免破坏已有的快捷键与脚本。

class VIEW3D_OT_CrafterAddCrafterTime(bpy.types.Operator):
    bl_label = "Add Time Driver"
    bl_idname = "crafter.add_craftertime"
    bl_description = "Write the current second (frame * fps_base / fps) into the object's color alpha with a driver. Material nodes read it from the Object Info node's Alpha output. The cost is one driver evaluation per object and no geometry work, so it can stay on while you work"
    
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
    bl_label = "Remove Time Driver"
    bl_idname = "crafter.remove_craftertime"
    bl_description = "Remove the time driver from the object color alpha and set the alpha back to 1. The Crafter-time geometry node modifier left by older versions is cleaned up as well"
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
    def execute(self, context: bpy.types.Context):
        for object in bpy.data.objects:
            remove_Crafter_time(object)
        return {'FINISHED'}

class VIEW3D_OT_CrafterTimeSettings(bpy.types.Operator):
    '''时间驱动的设置弹窗（和坐标选择器的齿轮一样，细项不占主面板）'''
    bl_label = "Time Driver Settings"
    bl_idname = "crafter.time_settings"
    bl_description = "Settings for the time driver: add it on import, and keep it refreshing while Cycles is the render engine"

    def draw(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        layout = self.layout
        layout.prop(addon_prefs, "Add_Crafter_time_On_Import")
        if hasattr(addon_prefs, "Cycles_Time_Refresh"):
            layout.prop(addon_prefs, "Cycles_Time_Refresh")

    def invoke(self, context, event):
        return context.window_manager.invoke_props_dialog(self)

    def execute(self, context):
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
