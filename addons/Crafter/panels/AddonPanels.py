import bpy
import os

from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from ....common.types.framework import reg_order
from ..__init__ import dir_resourcepacks_plans
from ..operators.Defs import *
# @reg_order(0)# ==========加载预设面板==========
# class VIEW3D_PT_CrafterPlans(bpy.types.Panel):
#     bl_label = "Plans"
#     bl_space_type = "VIEW_3D"
#     bl_region_type = "UI"
#     bl_category = "Crafter"
#     def draw(self, context: bpy.types.Context):
        
#         layout = self.layout
#         addon_prefs = context.preferences.addons[__addon_name__].preferences

#         # layout.label(text="此版本为测试版本，请勿用于已编")
#         # layout.label(text="辑的工程或作品工程。")


#     @classmethod
#     def poll(cls, context: bpy.types.Context):
#             return True
    
@reg_order(1)# ========== 导入世界面板 ==========
class VIEW3D_PT_CrafterImportWorld(bpy.types.Panel):
    bl_label = "Import World"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Crafter"
    def draw_header(self, context):
        layout = self.layout
        layout.label(text="", icon="FILE_3D")
    def draw(self, context: bpy.types.Context):
        
        layout = self.layout
        box = layout.box()
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        row_world = box.row(align=True)
        row_world.prop(addon_prefs, "World_Path")
        row_world.operator("crafter.browse_world_or_replay", text="", icon="FILE_FOLDER")

        box.template_list("VIEW3D_UL_CrafterDimensionsList", "", addon_prefs, "Dimensions_List", addon_prefs, "Dimensions_List_index",rows=1)

        box.label(text="XYZ",icon="ORIENTATION_GLOBAL")
        row_XYZ1 = box.row()
        row_XYZ1.prop(addon_prefs, "XYZ_1",text="")
        row_XYZ2 = box.row()
        row_XYZ2.prop(addon_prefs, "XYZ_2",text="")

        row_History = box.row()
        row_History.label(icon="TIME")
        row_History.operator("crafter.use_history_worlds",text="History")

        row_map_selector = box.row()
        row_map_selector.label(icon="WORLD")
        row_map_selector_main = row_map_selector.row(align=True)
        row_map_selector_main.operator("crafter.map_selector", text="Area Selector")
        row_map_selector_main.operator("crafter.java_settings", text="", icon="PREFERENCES")

        row_ImportWorld = box.row()
        row_ImportWorld.label(icon="MOD_BUILD")
        row_ImportWorld_main = row_ImportWorld.row(align=True)
        row_ImportWorld_main.operator("crafter.import_surface_world",text="Import World")
        row_ImportWorld_main.operator("crafter.reimport_surface_world",text="",icon="FILE_REFRESH")
        row_ImportWorld.operator("crafter.open_worldimporter_folder",text="",icon="FILE_FOLDER")
    @classmethod
    def poll(cls, context: bpy.types.Context):
            return True


@reg_order(2)# ========== 材质面板 ==========
class VIEW3D_PT_CrafterMaterials(bpy.types.Panel):
    bl_label = "Material"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Crafter"

    def draw_header(self, context):
        layout = self.layout
        layout.label(text="", icon="MATERIAL")
    def draw(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        layout = self.layout
        box = layout.box()
        
        box_main = box.box()
        box_main.label(text="Materials",icon="SHADING_TEXTURE")
        row_Materials_List = box_main.row()
        row_Materials_List.template_list("VIEW3D_UL_CrafterMaterials", "", addon_prefs, "Materials_List", addon_prefs, "Materials_List_index", rows=1)
        col_Materials_List_ops = row_Materials_List.column()
        col_Materials_List_ops.operator("crafter.open_materials",icon="FILE_FOLDER",text="")
        col_Materials_List_ops.operator("crafter.reload_all",icon="FILE_REFRESH",text="")

        row_load_material = box_main.row(align=True)
        row_load_material.operator("crafter.load_material",text="Load")
        op_reload_material = row_load_material.operator("crafter.load_material",text="Reload",icon="FILE_REFRESH")
        op_reload_material.force_reload = True
        box_main.prop(addon_prefs, "Search_PBR_On_Load")
        
        box_other = box.box()
        row_PBR_Parser = box_other.row()
        row_PBR_Parser.label(icon="SHADERFX")
        row_PBR_Parser.prop(addon_prefs, "PBR_Parser",text="Parser")

        row_Parsed_Normal_Strength = box_other.row()
        row_Parsed_Normal_Strength.label(icon="NODE_TEXTURE")
        row_Parsed_Normal_Strength.prop(addon_prefs, "Parsed_Normal_Strength")

        # 时间驱动：面板只留动作，细项（导入时添加 / Cycles 刷新）收进齿轮弹窗
        row_Crafter_time = box_other.row()
        row_Crafter_time.label(icon="TIME")
        row_Crafter_time_ops = row_Crafter_time.row(align=True)
        row_Crafter_time_ops.operator("crafter.add_craftertime",icon="LINKED",text="Add")
        row_Crafter_time_ops.operator("crafter.remove_craftertime",icon="UNLINKED",text="Remove")
        row_Crafter_time_ops.operator("crafter.time_settings",icon="PREFERENCES",text="")

        row_parallax = box_other.row()
        col1_parallax = row_parallax.column()
        col1_parallax.label(icon="TEXTURE")
        col2_parallax = row_parallax.column()
        row_col2_parallax = col2_parallax.row(align=True)
        row_col2_parallax.operator("crafter.load_parallax",icon="ADD",text="Load Parallax")
        row_col2_parallax.operator("crafter.remove_parallax",icon="X",text="Remove Parallax")

        box_material_panel = box.box()
        row_material_panel = box_material_panel.row()
        row_material_panel.label(icon="NODE")
        row_material_panel.operator("crafter.material_panel",text="Material Panel")

        box_classification = box.box()
        box_classification.label(text="Classification Basis",icon="PACKAGE")
        row_Classification_Basis = box_classification.row()
        row_Classification_Basis.template_list("VIEW3D_UL_CrafterClassificationBasis", "", addon_prefs, "Classification_Basis_List", addon_prefs, "Classification_Basis_List_index", rows=1)
        row_Classification_Basis_ops = row_Classification_Basis.column()
        row_Classification_Basis_ops.operator("crafter.open_classification_basis",icon="FILE_FOLDER",text="")
        row_Classification_Basis_ops.operator("crafter.reload_all",icon="FILE_REFRESH",text="")

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
    
@reg_order(3)# ========== 其他面板 ==========
class VIEW3D_PT_CrafterOthers(bpy.types.Panel):
    bl_label = "Others"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Crafter"
    def draw_header(self, context):
        layout = self.layout
        layout.label(text="", icon="OUTLINER")
    def draw(self, context: bpy.types.Context):
        
        layout = self.layout
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        # ========== 功能选择面板 ==========
        row_function1 = layout.row()
        row_function1.operator("crafter.ui_asset",text="Asset",icon="ASSET_MANAGER",depress=addon_prefs.Other_index == 0)
        row_function1.operator("crafter.ui_replace_resources",text="Replace Resources",icon="NODE_COMPOSITING",depress=addon_prefs.Other_index == 1)
        
        row_function2 = layout.row()
        row_function2.operator("crafter.ui_log",text="Log",icon="TEXT",depress=addon_prefs.Other_index == 2)
        
        if addon_prefs.Other_index == 0:
            
            libraries = bpy.context.preferences.filepaths.asset_libraries
            have_assets = False
            for lib in libraries:
                if lib.name == name_library:
                    have_assets = True
            # if not have_assets:
            #     row_Asset1 = layout.row()
            #     row_Asset1.label(text="Select an path to build asset library")

            #     row_Asset2 = layout.row()
            #     row_Asset2.prop(addon_prefs,"Asset_Path",text="")

            #     row_Asset3 = layout.row()
            #     row_Asset3.operator("crafter.build_asset_library",icon="NODE_COMPOSITING")
            # else:
            #     row_Asset4 = layout.row()
            #     row_Asset4.label(text="Asset is ready")
            
        # ========== 加载资源包面板 ==========
        if addon_prefs.Other_index == 1:
            layout = self.layout
            
            box_resources = layout.box()
            box_resources.label(text="Resources",icon="PACKAGE")
            row_Plans_List = box_resources.row()
            row_Plans_List.template_list("VIEW3D_UL_CrafterResources", "", addon_prefs, "Resources_Plans_List", addon_prefs, "Resources_Plans_List_index", rows=1)
            col_Plans_List_ops = row_Plans_List.column()
            col_Plans_List_ops.operator("crafter.open_resources_plans",icon="FILE_FOLDER",text="")
            col_Plans_List_ops.operator("crafter.reload_all",icon="FILE_REFRESH",text="")

            box_resource = layout.box()
            if len(addon_prefs.Resources_List) > 0:
                box_resource.label(text="Resource",icon="NODE_COMPOSITING")
                row_Resources_List = box_resource.row()
                row_Resources_List.template_list("VIEW3D_UL_CrafterResourcesInfo", "", addon_prefs, "Resources_List", addon_prefs, "Resources_List_index", rows=1)
                if len(addon_prefs.Resources_List) > 1:
                    col_Resources_List_ops = row_Resources_List.column(align=True)
                    col_Resources_List_ops.operator("crafter.up_resource",icon="TRIA_UP",text="")
                    col_Resources_List_ops.operator("crafter.down_resource",icon="TRIA_DOWN",text="")

            row_Resources = layout.row()
            row_Resources.operator("crafter.replace_resources",text="Replace")

        # ========== 日志面板 ==========
        if addon_prefs.Other_index == 2:
            if import_running or len(import_log) > 0:
                log_box = layout.box()
                head_row = log_box.row(align=True)
                if import_running:
                    head_row.label(text=f"Running: {import_stage}" if import_stage else "Running...", icon="TIME")
                else:
                    head_row.label(text=f"Log ({len(import_log)} entries)", icon="INFO")
                head_row.label(text=f"{len(import_log)}", icon="LINENUMBERS_ON")

                if import_running:
                    pct = min(int(import_progress), 100)
                    barlen = 24
                    filled = int(pct / 100 * barlen)
                    log_box.label(text=f"\u2588" * filled + "\u2591" * (barlen - filled) + f"  {pct}%")

                _level_icon = {
                    "ERROR": "CANCEL",
                    "WARN": "ERROR",
                    "INFO": "INFO",
                    "DEBUG": "SETTINGS",
                }
                for entry in import_log[-14:]:
                    lv = entry.get("level", "INFO")
                    icon = _level_icon.get(lv, "INFO")
                    t = entry.get("time", "")
                    txt = entry["text"]
                    if t:
                        txt = f"{t} {txt}"
                    log_box.label(text=txt[:140], icon=icon)

                row_ops = log_box.row(align=True)
                row_ops.operator("crafter.copy_import_log", text="Copy Log", icon="COPYDOWN")
                row_ops.operator("crafter.export_import_log", text="Export Log", icon="TEXT")
                if not import_running and len(import_log) > 0:
                    row_ops.operator("crafter.clear_import_log", text="Clear", icon="TRASH")
            else:
                layout.label(text="No Log", icon="INFO")


    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
# ==================== UIList ====================

class VIEW3D_UL_CrafterDimensionsList(bpy.types.UIList):# 历史世界 存档 列表
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        layout.label(text=item.name)


# ==================== 材质节点：切换 CI 节点 ====================

def _repair_overlay_links(tree, old_node, new_node, node_output_EEVEE, node_output_Cycles):
    '''切换 CI 后恢复 overlay Mix 链：链头 A 输入改接新 CI，链尾重新接回输出/剔除组。'''
    mix_nodes = [n for n in tree.nodes
                 if n.bl_idname == "ShaderNodeMixShader" and n.get("Crafter_overlay_mix")]
    if not mix_nodes:
        return
    # 链头 A 输入来自旧 CI 的，改接新 CI 同名输出（旧节点删除后链接会自动断）
    for mix in mix_nodes:
        a_input = mix.inputs[1]
        if not a_input.is_linked:
            continue
        from_socket = a_input.links[0].from_socket
        if from_socket.node == old_node and from_socket.name in new_node.outputs:
            tree.links.new(new_node.outputs[from_socket.name], a_input)
    # 按 B 输入（overlay 层 CI 的 Surface）分组，每组最后一个 Mix 是链尾
    groups = {}
    for mix in mix_nodes:
        b_input = mix.inputs[2]
        surface = b_input.links[0].from_socket.name if b_input.is_linked else None
        groups.setdefault(surface, []).append(mix)
    cull_input = None
    for cull in tree.nodes:
        if cull.get(CULL_GROUP_PROP):
            cull_input = next((s for s in cull.inputs if s.type == 'SHADER' and s.enabled), None)
            break
    for surface, mixes in groups.items():
        # 链尾 = 输出还没接任何节点的那个（被 link_CI_output 直连断开的就是它）；
        # 已连的要保持链内连接，不能覆盖。
        tail = None
        for mix in mixes:
            if not mix.outputs[0].is_linked:
                tail = mix
                break
        if tail is None:
            continue
        if surface == "Cycles-Surface":
            if cull_input is not None:
                tree.links.new(tail.outputs[0], cull_input)
            elif node_output_Cycles is not None:
                tree.links.new(tail.outputs[0], node_output_Cycles.inputs["Surface"])
        elif surface == "EEVEE-Surface" and node_output_EEVEE is not None:
            tree.links.new(tail.outputs[0], node_output_EEVEE.inputs["Surface"])


def _selected_ci_node(context):
    '''只认唯一目标：活动节点是 CI 就用它；否则仅在恰好选中一个 CI 时才认，多选返回 None。'''
    node = getattr(context, "active_node", None)
    if (node is not None and node.type == "GROUP" and node.node_tree is not None and
            node.node_tree.name.startswith("CI-")):
        return node
    ci_nodes = [n for n in (getattr(context, "selected_nodes", None) or [])
                if n.type == "GROUP" and n.node_tree is not None and
                n.node_tree.name.startswith("CI-")]
    return ci_nodes[0] if len(ci_nodes) == 1 else None


def _ci_group_items(self, context):
    '''枚举当前文件里可切换的 CI- 组（排除当前节点正在用的那个）。'''
    node = _selected_ci_node(context)
    current = node.node_tree.name if node is not None else None
    items = []
    for group in sorted(bpy.data.node_groups, key=lambda g: g.name):
        if group.name.startswith("CI-") and group.name != current:
            items.append((group.name, group.name, ""))
    return items or [("NONE", "没有其他 CI 组", "")]


class VIEW3D_OT_CrafterSwitchCINode(bpy.types.Operator):
    bl_label = "Switch CI Node"
    bl_idname = "crafter.switch_ci_node"
    bl_description = "把选中的 CI 节点换成另一个 CI 组，并按接口名重连 PBR 解析器等上下游节点"
    bl_options = {'REGISTER', 'UNDO'}

    target_group: EnumProperty(name="CI Group", items=_ci_group_items)  # type: ignore

    @classmethod
    def poll(cls, context):
        return _selected_ci_node(context) is not None

    def execute(self, context):
        node = _selected_ci_node(context)
        if node is None or node.node_tree is None:
            return {'CANCELLED'}
        target = bpy.data.node_groups.get(self.target_group)
        if target is None or not target.name.startswith("CI-"):
            self.report({'ERROR'}, "目标 CI 组不存在")
            return {'CANCELLED'}
        tree = node.id_data
        if tree is None or getattr(tree, "is_editmode", False):
            return {'CANCELLED'}
        # node.id_data 是 ShaderNodeTree，tree.id_data 也指向自己，不是 Material；
        # sync_CI_culling 需要真正的 Material，所以按节点树名字反查。
        material = None
        for mat in bpy.data.materials:
            node_tree = getattr(mat, "node_tree", None)
            if node_tree is not None and node_tree.name == tree.name:
                material = mat
                break

        # 材质输出节点：加载材质时建好的 EEVEE/Cycles 输出，输出侧直接复用 link_CI_output。
        node_output_EEVEE = None
        node_output_Cycles = None
        for other in tree.nodes:
            if other.type == "OUTPUT_MATERIAL":
                if other.target == "EEVEE":
                    node_output_EEVEE = other
                elif other.target == "CYCLES":
                    node_output_Cycles = other

        # 记录旧 CI 的输入连接（按 socket 名），换节点后按名字重连。
        input_links = {}
        for socket in node.inputs:
            if socket.is_linked:
                link = socket.links[0]
                input_links[socket.name] = (link.from_node, link.from_socket)

        # 记录旧布局：link_CI_output 会为了插入剔除组把 CI 及左侧节点左移，切换后要还原。
        positions = {other.name: (other.location.x, other.location.y) for other in tree.nodes}

        new_node = tree.nodes.new("ShaderNodeGroup")
        new_node.node_tree = target
        new_node.location = node.location
        new_node.width = node.width
        new_node.height = node.height
        new_node.label = node.label
        new_node.name = target.name

        # 输出：和加载材质一样走 link_CI_output（EEVEE/Cycles 全部接口 + 剔除组插入）。
        if node_output_EEVEE is not None and node_output_Cycles is not None:
            link_CI_output(group_CI=new_node, node_output_EEVEE=node_output_EEVEE,
                           node_output_Cycles=node_output_Cycles, links=tree.links,
                           material=material)

        # 输入：按 socket 名字把 PBR 解析器/贴图/群系颜色接回新 CI。
        for name, (from_node, from_socket) in input_links.items():
            if name in new_node.inputs and from_node.name in tree.nodes:
                tree.links.new(from_socket, new_node.inputs[name])

        # 距离按世界坐标算：帧内节点的 location 是相对帧的，直接和帧外节点相减不可靠。
        def _world_location(target):
            loc = target.location.copy()
            parent = target.parent
            while parent is not None:
                loc = loc + parent.location
                parent = parent.parent
            return loc

        # 再按“距离最近的节点 + 同名接口”补一轮：纹理连 Color/Alpha，PBR/biome 按输出名同名连。
        def _nearest(predicate):
            found = []
            origin = _world_location(node)
            for other in tree.nodes:
                if other == new_node or other == node or not predicate(other):
                    continue
                found.append(((_world_location(other) - origin).length, other))
            found.sort(key=lambda item: item[0])
            return [item[1] for item in found]

        # 只找基础层（帧外）的贴图，否则 overlay 帧内的贴图更近会抢先接上。
        tex_nodes = _nearest(lambda n: n.type == "TEX_IMAGE" and n.parent is None)
        tex_roles = texture_node_roles(tex_nodes)
        base_tex = next((n for n in tex_nodes if tex_roles.get(n) == TEX_ROLE_BASE), None)
        if base_tex is not None:
            if "Color" in base_tex.outputs:
                for in_name in ("Base Color", "基础色"):
                    if in_name in new_node.inputs:
                        tree.links.new(base_tex.outputs["Color"], new_node.inputs[in_name])
                        break
            if "Alpha" in base_tex.outputs and "Alpha" in new_node.inputs:
                tree.links.new(base_tex.outputs["Alpha"], new_node.inputs["Alpha"])

        for prefix in ("C-PBR_Parser", "Crafter-biomeTex"):
            for source in _nearest(
                    lambda n, p=prefix: n.type == "GROUP" and n.parent is None and
                    n.node_tree is not None and n.node_tree.name.startswith(p))[:1]:
                for output in source.outputs:
                    if output.name in new_node.inputs:
                        tree.links.new(output, new_node.inputs[output.name])

        # 和加载材质一样：按 tint 元数据补群系颜色，再按新 CI 的接口同步剔除。
        # 注意：不能用 `material.node_tree is tree` 判断——Blender RNA 每次访问可能返回不同 wrapper。
        if material is not None:
            tint = get_material_tint(material)
            try:
                # tint 为 None 也要调用：apply_tint 会先把 CI 暴露的命名群系输入接上 biomeTex。
                apply_tint(new_node, tree.nodes, tree.links, tint)
            except Exception as ex:
                warn_log(f"apply_tint 失败 [{material.name}]: {ex}")
            sync_CI_culling(material)
            # overlay 材质：link_CI_output 会把新 CI 直连剔除组/输出，这里改回经 Mix 链。
            _repair_overlay_links(tree, node, new_node, node_output_EEVEE, node_output_Cycles)

        # 还原旧布局后交给统一布局函数（和加载材质同一套规则）。
        for other in tree.nodes:
            if other.name in positions:
                other.location = positions[other.name]
        new_node.location = (node.location.x, node.location.y)
        if material is not None:
            layout_material_nodes(material, tree.nodes, new_node, node_output_Cycles)

        tree.nodes.remove(node)
        for other in tree.nodes:
            other.select = False
        new_node.select = True
        # 和加载材质一样：把基础色贴图设为活动节点，实体模式预览才正常。
        if base_tex is not None:
            tree.nodes.active = base_tex
        else:
            tree.nodes.active = new_node
        if context.area is not None:
            context.area.tag_redraw()
        return {'FINISHED'}


class VIEW3D_PT_CrafterCINode(bpy.types.Panel):
    bl_label = "CI Node"
    bl_space_type = "NODE_EDITOR"
    bl_region_type = "UI"
    bl_category = "Crafter"

    @classmethod
    def poll(cls, context):
        space = context.space_data
        return (space is not None and space.type == "NODE_EDITOR" and
                space.tree_type == "ShaderNodeTree")

    def draw(self, context):
        layout = self.layout
        node = _selected_ci_node(context)
        if node is None:
            layout.label(text="选中一个 CI 节点", icon="INFO")
            return
        layout.label(text="当前: " + node.node_tree.name)
        layout.operator_menu_enum("crafter.switch_ci_node", "target_group", text="切换到…", icon="NODETREE")
