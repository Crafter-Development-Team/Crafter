import bpy
import os
import bmesh
import struct
# 在上方定义，避免循环import
def ui_item(self, context: bpy.types.Context):
    addon_prefs = context.preferences.addons[__addon_name__].preferences
    layout = self.layout
    layout.operator("crafter.import_item",icon="FILE_3D")

from ..__init__ import dir_cafter_data, dir_resourcepacks_plans, dir_materials, dir_classification_basis, dir_blend_append, dir_init_main, dir_no_lod_blocks
from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from bpy.props import *
from .Defs import warn_log, info_log, debug_log, trace_operation


def _run_load_material(from_import=True):
    """Allow older installed operators that lack the from_import property."""
    try:
        return bpy.ops.crafter.load_material(from_import=from_import)
    except TypeError as ex:
        message = str(ex).lower()
        if "from_import" not in message or not (
                "unrecognized" in message or "unexpected keyword" in message):
            raise
        warn_log("crafter.load_material does not recognize from_import (stale operator); retrying without it")
        return bpy.ops.crafter.load_material()

# ==================== 物品贴图尺寸 ====================

def read_image_size(path):
    '''
    只读文件头拿图片的像素尺寸（PNG / JPEG），失败返回 (0, 0)。
    目的是在文件浏览器里刚选中文件时就能显示尺寸，不必真把图加载进 Blender
    （bpy.data.images.load 会留下数据块，且 HD 贴图很慢）。
    '''
    if not path or not os.path.isfile(path):
        return (0, 0)
    try:
        with open(path, "rb") as f:
            head = f.read(32)
            # PNG: 8 字节签名 + 4 字节长度 + "IHDR" + 宽4 + 高4（大端）
            if head[:8] == b"\x89PNG\r\n\x1a\n" and head[12:16] == b"IHDR":
                width, height = struct.unpack(">II", head[16:24])
                return (int(width), int(height))
            # JPEG: 扫段找到 SOFn，段内是 精度1 + 高2 + 宽2（大端）
            if head[:2] == b"\xff\xd8":
                f.seek(2)
                while True:
                    marker = f.read(2)
                    if len(marker) < 2 or marker[0] != 0xFF:
                        return (0, 0)
                    code = marker[1]
                    if code == 0xFF:            # 填充字节，回退一步重读
                        f.seek(-1, 1)
                        continue
                    if code == 0x01 or 0xD0 <= code <= 0xD9:
                        continue                # 无长度字段的标记
                    size_bytes = f.read(2)
                    if len(size_bytes) < 2:
                        return (0, 0)
                    seg_len = struct.unpack(">H", size_bytes)[0]
                    if code in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
                                0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                        body = f.read(5)
                        if len(body) < 5:
                            return (0, 0)
                        height, width = struct.unpack(">HH", body[1:5])
                        return (int(width), int(height))
                    f.seek(seg_len - 2, 1)
    except Exception:
        return (0, 0)
    return (0, 0)


def common_divisors(width, height):
    '''
    宽高公约数里大于 1 的那些（从小到大）；宽或高 <= 1 时返回空
    '''
    try:
        width = int(width)
        height = int(height)
    except Exception:
        return []
    if width <= 1 or height <= 1:
        return []
    return [d for d in range(2, min(width, height) + 1)
            if width % d == 0 and height % d == 0]


def item_image_size(op):
    '''
    操作符当前选中文件的实际像素尺寸（只读文件头，不把图加载进 Blender）；读不出返回 (0, 0)
    '''
    return read_image_size(getattr(op, "filepath", ""))


def item_base_size(op):
    '''
    参与细分的基准尺寸：一律取所选图片的实际像素尺寸（只读文件头）。
    读不出就返回 (0, 0)，execute 那边会退回已加载图片的 size。
    注意：属性回调里的 self 是 RNA 包装、没有实例方法，所以尺寸口径都写在这里。
    '''
    return item_image_size(op)


def item_current_divisor(op):
    '''
    当前生效的约数：选的值不是当前尺寸的公约数（或 <= 1）时按"不除"处理
    '''
    divisor = item_selected_divisor()
    if divisor <= 1:
        return 1
    base_width, base_height = item_base_size(op)
    if base_width <= 0 or base_height <= 0:
        return divisor          # 拿不到基准尺寸就不拦，execute 那边会按图片尺寸除
    if base_width % divisor or base_height % divisor:
        return 1
    return divisor


# "可选的约数"菜单里最多列几个
max_divisor_hint = 8

# 约数的选择状态（模块级）：文件浏览器里点菜单项没法直接改"正在等待的那个操作符实例"的
# 属性，所以菜单项调用 crafter.set_item_divisor 写这里，操作符的 draw/execute 都读它。
# valid 是当前图片的可选约数 [(约数, 结果宽, 结果高)]，导入操作符每次重绘时刷新。
item_divisor_state = {"value": 1, "valid": []}


def item_selected_divisor():
    '''
    当前选中的原始约数（未校验），读模块级状态
    '''
    try:
        return int(item_divisor_state["value"])
    except Exception:
        return 1


def item_divisor_label():
    '''
    面板上显示的当前约数文字
    '''
    divisor = item_selected_divisor()
    return "1（不除）" if divisor <= 1 else str(divisor)


def update_item_file(op, context):
    '''
    文件浏览器里选中文件后：把约数退回"不除"，免得留下除不尽的选项。
    细分尺寸一律按图片实际尺寸走，所以这里不用再写任何尺寸属性。
    '''
    item_divisor_state["value"] = 1


# ==================== 约数下拉菜单 ====================

class VIEW3D_OT_CrafterSetItemDivisor(bpy.types.Operator):
    '''把物品导入的约数设成 divisor（由约数菜单的条目调用）'''
    bl_idname = "crafter.set_item_divisor"
    bl_label = "Set Item Divisor"
    bl_description = "设置物品导入时 X / Y 要除以的约数"
    bl_options = {'INTERNAL'}

    divisor: IntProperty(name="约数",
                         default=1,
                         min=1)#type: ignore

    def execute(self, context):
        item_divisor_state["value"] = max(1, int(self.divisor))
        return {'FINISHED'}


class CRAFTER_MT_item_divisor(bpy.types.Menu):
    '''
    约数下拉：条目按当前图片真正的公约数动态列出，条目本身是按钮。
    （不能用动态枚举：Blender 会把 items=回调 的条目名显示成乱码，按钮文字一直正常）
    '''
    bl_idname = "CRAFTER_MT_item_divisor"
    bl_label = "约数"

    def draw(self, context):
        layout = self.layout
        current = item_selected_divisor()

        def add_entry(value, text):
            kwargs = {"text": text}
            if value == current:
                kwargs["icon"] = 'CHECKMARK'
            layout.operator("crafter.set_item_divisor", **kwargs).divisor = value

        add_entry(1, "1（不除）")
        for divisor, width, height in item_divisor_state.get("valid", []):
            # 约数与尺寸之间空两格（约数位数不同，两格看着更分得开）
            add_entry(divisor, f"{divisor}  {width} x {height}")

# ==================== 挤出厚度（C-item 节点组输入） ====================

# C-item 里两个数值输入的名字。模板里 "Pxel Min" 是既有拼写（少一个 i），
# 这里两种写法都认，免得以后模板把名字改回来就失效。
offset_scale_names = ("Offset Scale",)
pixel_min_names = ("Pxel Min", "Pixel Min")


def _find_group_input(node_group, names):
    '''
    在节点组接口里找一个数值输入，返回它的 identifier（修改器写入时要的就是这个）。
    只认非几何的 INPUT 插槽；找不到返回 None。
    '''
    for item in node_group.interface.items_tree:
        if getattr(item, "item_type", None) != 'SOCKET':
            continue
        if getattr(item, "in_out", None) != 'INPUT':
            continue
        if getattr(item, "socket_type", None) == 'NodeSocketGeometry':
            continue
        if item.name in names:
            return item.identifier
    return None


def _modifier_input_owner(modifier, identifier):
    '''
    取几何节点修改器某个输入的 RNA 包装（写 .value 用的那个）。
    4.x～5.1 的输入是 ID 属性，直接 modifier[identifier]；5.2 起改成了
    modifier.properties.inputs.<identifier>，而 ID 属性接口被移除
    （modifier[key] = val 会抛 "id properties not supported for this type"）。
    老版本 / 找不到该输入时返回 None，调用方再回退到 ID 属性。
    '''
    props = getattr(modifier, "properties", None)
    inputs = getattr(props, "inputs", None)
    if inputs is None:
        return None
    return getattr(inputs, identifier, None)


def _set_modifier_input(modifier, identifier, value):
    '''
    写一个几何节点修改器输入，成功返回 True。
    先走 5.2+ 的 properties.inputs，再回退到老版本的 ID 属性。
    '''
    input_owner = _modifier_input_owner(modifier, identifier)
    if input_owner is not None:
        input_owner.value = value
        return True
    try:
        modifier[identifier] = value
    except (KeyError, TypeError):
        return False
    return True


def set_item_thickness_inputs(object_item, thickness_px, pixel_min):
    '''
    把厚度写进 C-item 节点组：
      Offset Scale ← thickness_px
      Pxel Min     ← pixel_min（被约数除过后的短边像素量）
    节点组内部是 Offset Scale × 0.5 ÷ Pxel Min，所以每边挤出 = 厚度 ÷ (2 × 短边像素量)，
    镜像后总厚 = 厚度 ÷ 短边像素量。
    模板缺插槽时跳过并记一条警告，不让导入整个失败。
    '''
    for modifier in object_item.modifiers:
        if modifier.type != 'NODES':
            continue
        node_group = modifier.node_group
        if node_group is None or node_group.name != "C-item":
            continue
        offset_key = _find_group_input(node_group, offset_scale_names)
        pixel_key = _find_group_input(node_group, pixel_min_names)
        if offset_key is None or pixel_key is None:
            warn_log("C-item node group is missing the 'Offset Scale' / 'Pxel Min' inputs; "
                     "item thickness falls back to the template default")
            return
        if not _set_modifier_input(modifier, offset_key, float(thickness_px)) or \
                not _set_modifier_input(modifier, pixel_key, float(pixel_min)):
            warn_log("could not write the 'Offset Scale' / 'Pxel Min' inputs of the C-item "
                     "modifier; item thickness falls back to the template default")
            return
        return
    warn_log("no C-item geometry-node modifier found; item thickness was not applied")


# ==================== 导入物体 ====================


class VIEW3D_OT_CrafterImportItem(bpy.types.Operator):#导入物体
    bl_label = "Minecraft Item"
    bl_idname = "crafter.import_item"
    bl_description = "Import Minecraft item"
    bl_options = {'REGISTER', 'UNDO'}
    
    # 注意：属性回调（update）拿到的 self 是 RNA 包装、没有实例方法，所以一律挂模块级函数
    filepath: StringProperty(name="dir item",
                              subtype='FILE_PATH',
                              update=update_item_file)#type: ignore
    load_material: BoolProperty(name="加载材质",
                                default=True)#type: ignore
    # 厚度：单位是像素。节点组内部是 Offset Scale × 0.5 ÷ Pxel Min → 挤出网格，
    # 所以每边挤出量 = 厚度 ÷ (2 × 短边像素量)，镜像后总厚度 = 厚度 ÷ 短边像素量。
    thickness: FloatProperty(name="厚度",
                             description="挤出厚度，单位是像素（1 格 = 1 米）：总厚 = 厚度 ÷ 短边像素量",
                             default=1.0,
                             min=0.001)#type: ignore


    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
    
    def invoke(self, context, event):
        # 手动弹出文件选择器
        context.window_manager.fileselect_add(self)
        return {'RUNNING_MODAL'}

    def draw(self, context):
        layout = self.layout
        layout.prop(self, "load_material")
        base_width, base_height = item_base_size(self)
        divisor = item_current_divisor(self)
        # 图片实际尺寸放在约数上面（选中文件时自动读出来）
        image_width, image_height = item_image_size(self)
        if image_width > 0 and image_height > 0:
            layout.label(text=f"图片实际尺寸: {image_width} x {image_height}", icon="IMAGE_DATA")
        # 约数是下拉菜单（Menu + 按钮条目）：条目按当前图片真正的公约数动态生成。
        # 顺带把可选约数刷进模块级状态，菜单照着列。
        valid = common_divisors(base_width, base_height)[:max_divisor_hint]
        item_divisor_state["valid"] = [(d, base_width // d, base_height // d) for d in valid]
        layout.menu("CRAFTER_MT_item_divisor", text=f"约数: {item_divisor_label()}")
        if base_width > 0 and base_height > 0:
            layout.label(text=f"细分尺寸: {base_width // divisor} x {base_height // divisor}")
        # 厚度（像素）放在约数下面：它的最终厚度取决于细分后的短边像素量
        layout.prop(self, "thickness")
    @trace_operation("导入物品", detail=lambda self, context: dict(path=self.filepath, thickness=self.thickness, load_material=self.load_material))
    def execute(self, context: bpy.types.Context):
        dir_item = self.filepath
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        if context.active_object:
            bpy.ops.object.mode_set(mode='OBJECT')
        if not os.path.exists(dir_item):
            warn_log('物品贴图不存在', '导入物品', path=dir_item)
            return {'CANCELLED'}
        pre_import_objects = set(bpy.data.objects)#记录当前场景中的所有对象

        with bpy.data.libraries.load(dir_blend_append, link=False) as (data_from, data_to):
            data_to.objects = [name for name in data_from.objects if name == "item"]
        
        imported_objects = list(set(bpy.data.objects) - pre_import_objects)#计算新增对象
        object_item = imported_objects[0]
        context.collection.objects.link(object_item)
        bpy.ops.object.select_all(action='DESELECT')
        context.view_layer.objects.active = object_item
        context.view_layer.update()
        # ========== 加载贴图 ==========
        image_item = bpy.data.images.load(dir_item)# 从用户选择的文件加载贴图
        debug_log("物品贴图已加载", "导入物品", path=dir_item, width=image_item.size[0], height=image_item.size[1])
        # 赋值给修改器中的 IMAGE_TEXTURE 节点（C-item 节点组内的占位贴图）
        for mod in object_item.modifiers:
            if mod.type == 'NODES':
                node_group = mod.node_group
                for node in node_group.nodes:
                    if node.type == 'IMAGE_TEXTURE':
                        node.inputs['Image'].default_value = image_item
                        break
                break
        # 赋值给材质本身的 TEX_IMAGE 节点
        for node in object_item.data.materials[0].node_tree.nodes:
            if node.type == 'TEX_IMAGE':
                node.image = image_item
                break
        # 细分网络
        mesh = object_item.data
        bm = bmesh.new()
        bm.from_mesh(mesh)

        # 细分尺寸：按图片实际像素尺寸，约数在这里统一生效（64x64 除 4 → 16x16）
        base_width, base_height = item_base_size(self)
        if base_width <= 0 or base_height <= 0:
            base_width, base_height = image_item.size
        divisor = item_current_divisor(self)
        width = max(1, int(base_width) // divisor)
        height = max(1, int(base_height) // divisor)
        info_log("物品网格计划", "导入物品", width=width, height=height, divisor=divisor, base_width=base_width, base_height=base_height)
        cuts_x = width - 1
        cuts_y = height - 1
        # 挤出厚度：厚度（像素）交给 Offset Scale，短边像素量交给 Pxel Min，
        # 节点组内部算 Offset Scale × 0.5 ÷ Pxel Min，即每边挤出 厚度 ÷ (2 × 短边)。
        set_item_thickness_inputs(object_item, self.thickness, min(width, height))


        # 使用示例
        horizontal_edges, vertical_edges = get_boundary_edges_sorted(bm)
        bmesh.ops.subdivide_edges(bm, edges=horizontal_edges, cuts=cuts_x, use_grid_fill=True)
        bm.edges.ensure_lookup_table()
        horizontal_edges, vertical_edges = get_boundary_edges_sorted(bm)
        bmesh.ops.subdivide_edges(bm, edges=vertical_edges, cuts=cuts_y, use_grid_fill=True)

        # 模板是 XY 平面上的 1x1 方片（X 对应图片宽、Y 对应图片高，UV 铺满 0..1）。
        # 长宽比不为 1:1 时按图片比例拉伸网格：短边保持原尺寸，长边拉长，
        # 否则 16:9 之类的贴图会被压成正方形。
        if width > 0 and height > 0 and width != height:
            stretch = max(width, height) / min(width, height)
            for vert in bm.verts:
                if width > height:
                    vert.co.x *= stretch
                else:
                    vert.co.y *= stretch

        # 更新网格并释放资源
        bm.to_mesh(mesh)
        bm.free()
        mesh.update()
        bpy.ops.crafter.scale_uv()
        # 应用第一个修改器
        name_modifier = object_item.modifiers[0].name
        bpy.ops.object.modifier_apply(modifier=name_modifier)
        # 删去该节点组
        bpy.data.node_groups.remove(bpy.data.node_groups["C-item"])

        if self.load_material:
            # 选中导入的物体，确保后续操作作用在正确对象上
            bpy.ops.object.select_all(action='DESELECT')
            object_item.select_set(True)
            context.view_layer.objects.active = object_item
            # 工程里已有一套同库的材质节点组 → 直接复用，只给这个物品挂材质
            # （load_material_for_object 会在同目录查找 _n.png / _s.png / _a.png 作为 PBR 贴图）
            from .LoadMaterial import can_reuse_material_setup, load_material_for_object
            reusable, _reason = can_reuse_material_setup(context)
            info_log("物品材质策略", "导入物品", reuse=reusable, reason=_reason, object=object_item.name)
            if reusable:
                load_material_for_object(context, object_item)
            else:
                # 没加载过 / 换了材质库 / 缺件 → 走操作符，那里重加载后再统一加载
                _run_load_material(from_import=True)

        info_log("物品导入结果", "导入物品", object=object_item.name, vertices=len(object_item.data.vertices), polygons=len(object_item.data.polygons))
        return  {'FINISHED'}
        


# ==================== 缩放UV ====================

# 非常好uv缩放，感谢MCprep
class VIEW3D_OT_CrafterScaleUV(bpy.types.Operator):
    bl_idname = "crafter.scale_uv"
    bl_label = "Scale UV Faces"
    bl_description = "Scale all selected UV faces. See F6 or redo-last panel to adjust factor"
    bl_options = {'REGISTER', 'UNDO'}

    scale: bpy.props.FloatProperty(default=0.999, name="Scale")# type: ignore
    selected_only: bpy.props.BoolProperty(default=False, name="Seleced only") # type: ignore
    skipUsage: bpy.props.BoolProperty(default=False, options={'HIDDEN'}) # type: ignore

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return context.mode == 'EDIT_MESH' or (
            context.mode == 'OBJECT' and context.object)

    track_function = "scale_uv"
    def execute(self, context: bpy.types.Context):

        # 检查是否存在活动对象
        if not context.object:
            self.report({'ERROR'}, "No active object found")
            return {'CANCELLED'}

        # 检查对象类型是否为网格
        elif context.object.type != 'MESH':
            self.report({'ERROR'}, "Active object must be a mesh")
            return {'CANCELLED'}

        # 检查网格是否有面数据
        elif not context.object.data.polygons:
            self.report({'WARNING'}, "Active object has no faces")
            return {'CANCELLED'}

        # 检查是否存在激活的UV贴图
        if not context.object.data.uv_layers.active:
            self.report({'ERROR'}, "No active UV map found")
            return {'CANCELLED'}

        # 保存初始模式并切换到对象模式
        mode_initial = context.mode
        bpy.ops.object.mode_set(mode="OBJECT")
        
        # 执行UV缩放操作
        ret = self.scale_uv_faces(context.object, self.scale)
        
        # 恢复原始编辑模式（如果需要）
        if mode_initial != 'OBJECT':
            bpy.ops.object.mode_set(mode="EDIT")

        # 处理错误返回值
        if ret is not None:
            self.report({'ERROR'}, ret)
            return {'CANCELLED'}

        # 返回正常结束状态
        return {'FINISHED'}
    def scale_uv_faces(self, ob, factor):
        # 调整缩放因子：原始因子乘以-1后加1，用于控制缩放方向和中心点
        factor *= -1
        factor += 1
        modified = False  # 标记是否修改过UV坐标

        uv = ob.data.uv_layers.active  # 获取当前活动的UV层
        is_new_uv_api = bpy.app.version >= (5, 0, 0)

        # 仅在 selected_only 为 True 时才需要读取 UV 选择状态
        uv_select_list = []
        if self.selected_only:
            if is_new_uv_api:
                # Blender 5.0+：UV 选择存储在 .uv_select_vert 属性中
                uv_select_attr = ob.data.attributes.get(".uv_select_vert")
                if uv_select_attr is not None:
                    uv_select_list = [0.0] * len(uv.data)
                    uv_select_attr.data.foreach_get("value", uv_select_list)
            else:
                # Blender 4.x：UV 选择存储在 uv.data.select 中
                try:
                    uv_select_list = [0.0] * len(uv.data)
                    uv.data.foreach_get("select", uv_select_list)
                except:
                    uv_select_list = []

        # 批量读取所有UV坐标到扁平列表 [u0, v0, u1, v1, ...]
        n_loops = len(uv.data)
        flat_uvs = [0.0] * (n_loops * 2)
        uv.data.foreach_get("uv", flat_uvs)

        # 在纯Python中处理所有面
        for f in ob.data.polygons:
            # 如果面未被选中且selected_only为True，则跳过
            if not f.select and self.selected_only is True:
                continue  # 若未选中，不在UV编辑器显示

            # 初始化面内UV坐标的平均中心点计算参数
            x = y = n = 0  # x,y为UV坐标总和，n为顶点数量

            # 第一次循环：计算当前面内所有选中UV点的平均中心点
            for loop_ind in f.loop_indices:
                if self.selected_only:
                    if loop_ind >= len(uv_select_list) or not uv_select_list[loop_ind]:
                        continue  # 跳过未选中的UV点
                i = loop_ind * 2
                x += flat_uvs[i]  # 累加x坐标
                y += flat_uvs[i + 1]  # 累加y坐标
                n += 1  # 计数器+1

            if n == 0:
                continue  # 如果没有选中点，跳过此面

            # 第二次循环：根据平均中心点缩放每个UV点的坐标
            for loop_ind in f.loop_indices:
                if self.selected_only:
                    if loop_ind >= len(uv_select_list) or not uv_select_list[loop_ind]:
                        continue  # 跳过未选中的UV点
                i = loop_ind * 2
                # 使用线性插值进行缩放：
                # 原坐标乘以(1-factor) + 中心点坐标乘以factor
                flat_uvs[i] = flat_uvs[i] * (1 - factor) + x / n * factor
                flat_uvs[i + 1] = flat_uvs[i + 1] * (1 - factor) + y / n * factor
                modified = True  # 标记为已修改

        # 批量写回所有UV坐标
        uv.data.foreach_set("uv", flat_uvs)
        # 强制更新依赖图，确保 foreach_set 的修改在4.x中也能生效
        ob.data.update_tag()

        # 如果没有修改过任何UV坐标，返回警告信息
        if not modified:
            return "No UV faces selected"

        return None  # 成功执行返回None

# 确保网格为单个四边形面
def get_boundary_edges_sorted(bm):
    """根据边的方向（水平/垂直）分类边界边"""
    horizontal_edges = []
    vertical_edges = []

    for edge in bm.edges:
        v1, v2 = edge.verts
        delta = v2.co - v1.co
        if abs(delta.x) > abs(delta.y):  # 水平方向
            horizontal_edges.append(edge)
        else:  # 垂直方向
            vertical_edges.append(edge)

    return horizontal_edges, vertical_edges