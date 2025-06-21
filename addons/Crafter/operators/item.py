import bpy
import os
import bmesh
# 在上方定义，避免循环import
def ui_item(self, context: bpy.types.Context):
    addon_prefs = context.preferences.addons[__addon_name__].preferences
    layout = self.layout
    layout.operator("crafter.import_item",icon="FILE_3D")

from ..__init__ import dir_cafter_data, dir_resourcepacks_plans, dir_materials, dir_classification_basis, dir_blend_append, dir_init_main, dir_no_lod_blocks
from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from bpy.props import *

# ==================== 导入物体 ====================


class VIEW3D_OT_CrafterImportItem(bpy.types.Operator):#导入物体
    bl_label = "Minecraft Item"
    bl_idname = "crafter.import_item"
    bl_description = "Import Minecraft item"
    bl_options = {'REGISTER', 'UNDO'}
    
    filepath: StringProperty(name="dir item",
                              subtype='FILE_PATH')#type: ignore
    custome_size: BoolProperty(name="Custome Size",
                               default=False)#type: ignore
    width: IntProperty(name="X",
                       default=16,
                       min=1)#type: ignore
    height: IntProperty(name="Y",
                        default=16,
                        min=1)#type: ignore


    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
    
    def invoke(self, context, event):
        # 手动弹出文件选择器
        context.window_manager.fileselect_add(self)
        return {'RUNNING_MODAL'}
    def draw(self, context):
        layout = self.layout
        layout.prop(self, "custome_size")
        if self.custome_size:
            layout.prop(self, "width")
            layout.prop(self, "height")
    def execute(self, context: bpy.types.Context):
        dir_item = self.filepath
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        if context.active_object:
            bpy.ops.object.mode_set(mode='OBJECT')
        if not os.path.exists(dir_item):
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
        # 添加图片
        image_item = bpy.data.images.load(dir_item)
        for mod in object_item.modifiers:
            if mod.type == 'NODES':
                node_group = mod.node_group
                for node in node_group.nodes:
                    if node.type == 'IMAGE_TEXTURE':
                        node.inputs['Image'].default_value = image_item
                        break
                break
        for node in object_item.data.materials[0].node_tree.nodes:
            if node.type == 'TEX_IMAGE':
                node.image = image_item
                break

        # 细分网络
        mesh = object_item.data
        bm = bmesh.new()
        bm.from_mesh(mesh)

        # 获取图像尺寸
        if self.custome_size:
            width = self.width
            height = self.height
        else:
            width, height = image_item.size
        cuts_x = width - 1
        cuts_y = height - 1


        # 使用示例
        horizontal_edges, vertical_edges = get_boundary_edges_sorted(bm)
        bmesh.ops.subdivide_edges(bm, edges=horizontal_edges, cuts=cuts_x, use_grid_fill=True)
        bm.edges.ensure_lookup_table()
        horizontal_edges, vertical_edges = get_boundary_edges_sorted(bm)
        bmesh.ops.subdivide_edges(bm, edges=vertical_edges, cuts=cuts_y, use_grid_fill=True)

        # 更新网格并释放资源
        bm.to_mesh(mesh)
        bm.free()
        mesh.update()
        bpy.ops.crafter.scale_uv()
        # 应用第一个修改器
        name_modifier = object_item.modifiers[0].name
        bpy.ops.object.modifier_apply(modifier=name_modifier)
        return  {'FINISHED'}
        


# ==================== 缩放UV ====================

# 非常好uv缩放，感谢MCprep
class VIEW3D_OT_CrafterScaleUV(bpy.types.Operator):
    bl_idname = "crafter.scale_uv"
    bl_label = "Scale UV Faces"
    bl_description = "Scale all selected UV faces. See F6 or redo-last panel to adjust factor"
    bl_options = {'REGISTER', 'UNDO'}

    scale: bpy.props.FloatProperty(default=0.75, name="Scale")# type: ignore
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
        
        # 遍历对象的所有面（polygon）
        for f in ob.data.polygons:
            # 如果面未被选中且selected_only为True，则跳过
            if not f.select and self.selected_only is True:
                continue  # 若未选中，不在UV编辑器显示

            # 初始化面内UV坐标的平均中心点计算参数
            x = y = n = 0  # x,y为UV坐标总和，n为顶点数量
            
            # 第一次循环：计算当前面内所有选中UV点的平均中心点
            for loop_ind in f.loop_indices:
                if not uv.data[loop_ind].select and self.selected_only is True:
                    continue  # 跳过未选中的UV点
                x += uv.data[loop_ind].uv[0]  # 累加x坐标
                y += uv.data[loop_ind].uv[1]  # 累加y坐标
                n += 1  # 计数器+1
            
            # 第二次循环：根据平均中心点缩放每个UV点的坐标
            for loop_ind in f.loop_indices:
                if not uv.data[loop_ind].select and self.selected_only is True:
                    continue  # 跳过未选中的UV点
                
                # 使用线性插值进行缩放：
                # 原坐标乘以(1-factor) + 中心点坐标乘以factor
                uv.data[loop_ind].uv[0] = uv.data[loop_ind].uv[0]*(1-factor) + x/n*(factor)
                uv.data[loop_ind].uv[1] = uv.data[loop_ind].uv[1]*(1-factor) + y/n*(factor)
                modified = True  # 标记为已修改
        
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