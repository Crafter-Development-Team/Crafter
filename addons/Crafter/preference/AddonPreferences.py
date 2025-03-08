import os

import bpy
from bpy.props import StringProperty, IntProperty, BoolProperty, IntVectorProperty, EnumProperty, CollectionProperty
from bpy.types import AddonPreferences
from ..config import __addon_name__
from ..properties import ResourcePlan, ResourcePlansInfo, Material, ClassificationBasisl,HistoryWorld


class CrafterAddonPreferences(AddonPreferences):
    # this must match the add-on name (the folder name of the unzipped file)
    bl_idname = __addon_name__

    # https://docs.blender.org/api/current/bpy.props.html
    # The name can't be dynamically translated during blender programming running as they are defined
    # when the class is registered, i.e. we need to restart blender for the property name to be correctly translated.
#====================希望用户再次打开还能保留的属性【要开自动保存】====================
#==========功能显示属性==========
    Plans: BoolProperty(name="Plans",
                        default=True,)# type: ignore
    Import_World: BoolProperty(name="Import World",
                               default=True,)# type: ignore
    Import_Resources: BoolProperty(name="Import Resources",
                                   default=True,)# type: ignore
    Load_Materials: BoolProperty(name="Load Materials",
                                 default=True,)# type: ignore
#==========导入世界属性==========
    World_Path: StringProperty(name="World path",
                               default="World path",
                               subtype="DIR_PATH",) # type: ignore
    XYZ_1: IntVectorProperty(name="XYZ-1",
                             default=(0,0,0),
                             description="Starting coordinates")# type: ignore
    XYZ_2: IntVectorProperty(name="XYZ-2",
                             default=(0,0,0),
                             description="Ending coordinates")# type: ignore
    Point_Cloud_Mode: BoolProperty(name="Point Cloud Mode",
                                   default=False,)# type: ignore
    History_Worlds_List: CollectionProperty(name="History Worlds",
                                            type=HistoryWorld)#type: ignore
    History_Worlds_List_index: IntProperty(name="History Worlds",
                                           default=0)# type: ignore
    solid: IntProperty(name="Solid",
                       default=0,)# type: ignore
#==========导入资源包属性==========
    Resources_Plans_List: CollectionProperty(name="Resources Plans",type=ResourcePlan)#type: ignore
    Resources_Plans_List_index: IntProperty(name="Resources",
                                            default=0,
                                            update=lambda self, context: self.update_resources_plans_list_index(context))# type: ignore
    Resources_List: CollectionProperty(name="Resources",type=ResourcePlansInfo)# type: ignore
    Resources_List_index: IntProperty(name="Resource",
                                      default=0,
                                      update=lambda self, context: self.update_resources_list_index(context))# type: ignore
    Texture_Interpolation: EnumProperty(name="Texture Interpolation",
                                        items=[("Linear","Linear","Linear interpolation"),
                                               ("Closest","Closest","No interpolation (sample closest texel)"),
                                               ("Cubic","Cubic","Cubic interpolation"),
                                               ("Smart","Smart","Bicubic when magnifying, else bilinear (OSL only)")],
                                        default="Closest",
                                        description="Texture interpolation method",
                                        update=lambda self, context: self.update_texture_interpolation(context))# type: ignore
#==========加载材质属性==========
    PBR_Parser: EnumProperty(name="PBR Parser",
                              items=[("lab_PBR_1.3","lab PBR 1.3","(1-R)**2,G as F0,Emission in Alpha"),
                                     ("old_continuum","old continuum","(1-R)**2,G as Metallic,Emission in Alpha"),
                                     ("old_BSL","old BSL","1-R,G as Metallic,Emission in B"),
                                     ("SEUS_PBR","SEUS PBR","1-R,G as Metallic,No Emission")],
                              default="lab_PBR_1.3",
                              description="How to parse PBR texture(and normal texture)",
                              update=lambda self, context: self.update_PBR_Parser(context))# type: ignore
    Materials_List: CollectionProperty(name="Materials",type=Material)#type: ignore
    Materials_List_index: IntProperty(name="Material",default=0)# type: ignore
    Classification_Basis_List: CollectionProperty(name="Classification Basis",type=ClassificationBasisl)# type: ignore
    Classification_Basis_List_index: IntProperty(name="Classification Basis",default=0)# type: ignore
#==========偏好设置面板==========
    def draw(self, context: bpy.types.Context):
        layout = self.layout
        layout.prop(self, "Plans")
        layout.prop(self, "Import_World")
        layout.prop(self, "Import_Resources")
        layout.prop(self, "Load_Materials")
#==========修改变量操作==========
    def update_texture_interpolation(self, context):
        bpy.ops.crafter.set_texture_interpolation()
        return None
    
    def update_resources_plans_list_index(self, context):
        bpy.ops.crafter.reload_all()
        return None
    
    def update_resources_list_index(self, context):
        bpy.ops.crafter.reload_all()
        return None
    
    def update_PBR_Parser(self, context):
        bpy.ops.crafter.set_pbr_parser()
        return None
