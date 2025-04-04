import bpy
from bpy.props import StringProperty, PointerProperty

#==========导入世界属性==========
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
#==========加载资源属性==========
class ResourcePlan(bpy.types.PropertyGroup):
    Resources_Plan: StringProperty(name="Resources Plan",)# type: ignore
class ResourcePlansInfo(bpy.types.PropertyGroup):
    Resource: StringProperty(name="Resource",)# type: ignore
#==========加载材质属性==========
class McMt(bpy.types.PropertyGroup):
    mcmt: StringProperty(name="mcmt",)# type: ignore
class Material(bpy.types.PropertyGroup):
    Material: StringProperty(name="Material",)# type: ignore
class ClassificationBasisl(bpy.types.PropertyGroup):
    Classification_Basisl: StringProperty(name="Classification Basisl",)# type: ignore
#==========加载背景属性==========
class Background(bpy.types.PropertyGroup):
    Background: StringProperty(name="Background",)# type: ignore
