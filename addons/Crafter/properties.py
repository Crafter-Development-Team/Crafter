import bpy
from bpy.props import StringProperty

#==========导入纹理属性==========
class ResourcePlans(bpy.types.PropertyGroup):
    Resources_Plans: StringProperty(name="Resources_Plans_Name",
                                    # subtype="DIR_PATH"
                                    )# type: ignore
class ResourcePlansInfo(bpy.types.PropertyGroup):
    Resources: StringProperty(name="Resources_Plans_Name",
                            #   subtype="DIR_PATH"
                              )# type: ignore
