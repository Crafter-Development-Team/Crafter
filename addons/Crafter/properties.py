import bpy
from bpy.props import StringProperty

#==========导入世界属性==========
class HistoryWorld(bpy.types.PropertyGroup):
    History_World: StringProperty(name="History World",
                                    # subtype="DIR_PATH"
                                    )# type: ignore
#==========导入资源属性==========
class ResourcePlan(bpy.types.PropertyGroup):
    Resources_Plan: StringProperty(name="Resources Plan",
                                    # subtype="DIR_PATH"
                                    )# type: ignore
class ResourcePlansInfo(bpy.types.PropertyGroup):
    Resource: StringProperty(name="Resource",
                            #   subtype="DIR_PATH"
                              )# type: ignore
#==========导入材质属性==========
class Material(bpy.types.PropertyGroup):
    Material: StringProperty(name="Material",
                            #   subtype="DIR_PATH"
                              )# type: ignore
class ClassificationBasisl(bpy.types.PropertyGroup):
    Classification_Basisl: StringProperty(name="Classification Basisl",
                            #   subtype="DIR_PATH"
                              )# type: ignore
