import bpy
from bpy.props import StringProperty, PointerProperty, BoolProperty, FloatProperty, FloatVectorProperty, IntProperty, CollectionProperty, EnumProperty
dirs_temp = []
# ==========导入世界属性==========
class LatestWorld(bpy.types.PropertyGroup):
    Latest_World: StringProperty(name="Latest World",)# type: ignore
class HistoryWorldRoot(bpy.types.PropertyGroup):
    History_World_Root: StringProperty(name="History World Root",)# type: ignore
class HistoryWorldVersion(bpy.types.PropertyGroup):
    History_World_Version: StringProperty(name="History World Version",)# type: ignore
class HistoryWorldSave(bpy.types.PropertyGroup):
    History_World_Save: StringProperty(name="History World Save",)# type: ignore
class HistoryWorldSetting(bpy.types.PropertyGroup):
    History_World_Setting: StringProperty(name="History World Setting",)# type: ignore
class UndividedVersion(bpy.types.PropertyGroup):
    Undivided_Version: StringProperty(name="Undivided Version",)# type: ignore
class Resource(bpy.types.PropertyGroup):
    Resource: StringProperty(name="Resource",)# type: ignore
# ==========加载资源属性==========
class ResourcePlan(bpy.types.PropertyGroup):
    Resources_Plan: StringProperty(name="Resources Plan",)# type: ignore
class ResourcePlansInfo(bpy.types.PropertyGroup):
    Resource: StringProperty(name="Resource",)# type: ignore
# ==========加载材质属性==========
class McMt(bpy.types.PropertyGroup):
    mcmt: StringProperty(name="mcmt",)# type: ignore
class Material(bpy.types.PropertyGroup):
    Material: StringProperty(name="Material",)# type: ignore
class ClassificationBasisl(bpy.types.PropertyGroup):
    Classification_Basisl: StringProperty(name="Classification Basisl",)# type: ignore
# ==========加载背景属性==========
class Environment(bpy.types.PropertyGroup):
    Environment: StringProperty(name="Environment",)# type: ignore
# ==========材质面板属性==========
class PanelOutputItem(bpy.types.PropertyGroup):
    name: StringProperty(name="Name")# type: ignore
    socket_type: StringProperty(name="Socket Type")# type: ignore
    is_switch: BoolProperty(name="Is Switch", default=False)# type: ignore
    switch_state: BoolProperty(name="Switch State", default=False)# type: ignore
    mix_factor: FloatProperty(name="Mix Factor", default=0.0, min=0.0, max=1.0)# type: ignore
    float_value: FloatProperty(name="Float Value", default=0.0)# type: ignore
    color_value: FloatVectorProperty(name="Color Value", size=4, default=(1.0, 1.0, 1.0, 1.0), subtype='COLOR')# type: ignore
    vector_value: FloatVectorProperty(name="Vector Value", size=3, default=(0.0, 0.0, 0.0))# type: ignore
    on_index: IntProperty(name="On Index", default=-1)# type: ignore
    off_index: IntProperty(name="Off Index", default=-1)# type: ignore
    output_index: IntProperty(name="Output Index", default=-1)# type: ignore
    use_on: BoolProperty(name="Use On", default=True)# type: ignore
class PanelItem(bpy.types.PropertyGroup):
    name: StringProperty(name="Panel Name")# type: ignore
    node_tree_name: StringProperty(name="Node Tree Name")# type: ignore
    outputs: CollectionProperty(type=PanelOutputItem, name="Outputs")# type: ignore
    outputs_index: IntProperty(name="Outputs Index", default=0)# type: ignore
