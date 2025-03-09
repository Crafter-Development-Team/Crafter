import bpy
from bpy.props import StringProperty

#==========导入世界属性==========
class HistoryWorld(bpy.types.PropertyGroup):
    History_World: StringProperty(name="History World",)# type: ignore
#==========加载资源属性==========
class ResourcePlan(bpy.types.PropertyGroup):
    Resources_Plan: StringProperty(name="Resources Plan",)# type: ignore
class ResourcePlansInfo(bpy.types.PropertyGroup):
    Resource: StringProperty(name="Resource",)# type: ignore
#==========加载材质属性==========
class Material(bpy.types.PropertyGroup):
    Material: StringProperty(name="Material",)# type: ignore
class ClassificationBasisl(bpy.types.PropertyGroup):
    Classification_Basisl: StringProperty(name="Classification Basisl",)# type: ignore
#==========加载背景属性==========
class Background(bpy.types.PropertyGroup):
    Background: StringProperty(name="Background",)# type: ignore
