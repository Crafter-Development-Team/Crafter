import os

import bpy
from bpy.props import StringProperty, IntProperty, BoolProperty, IntVectorProperty, EnumProperty, CollectionProperty, FloatProperty
from bpy.types import AddonPreferences
from ..config import __addon_name__
from ..properties import ResourcePlan, ResourcePlansInfo, Material, ClassificationBasisl,Background,HistoryWorldRoot,HistoryWorldVersion,HistoryWorldSave,HistoryWorldSetting,LatestWorld,Resource


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
    Load_Resources: BoolProperty(name="Load Resources",
                                   default=True,)# type: ignore
    Load_Materials: BoolProperty(name="Load Materials",
                                 default=True,)# type: ignore
#==========导入世界属性==========
    World_Path: StringProperty(name="World path",
                               default="World path",
                               subtype="DIR_PATH",
                               update=lambda self, context: context.area.tag_redraw()) # type: ignore
    XYZ_1: IntVectorProperty(name="XYZ-1",
                             default=(0,0,0),
                             description="Starting coordinates",
                             update=lambda self, context: context.area.tag_redraw())# type: ignore
    XYZ_2: IntVectorProperty(name="XYZ-2",
                             default=(0,0,0),
                             description="Ending coordinates",
                             update=lambda self, context: context.area.tag_redraw())# type: ignore
    Point_Cloud_Mode: BoolProperty(name="Point Cloud Mode",
                                   default=False,)# type: ignore
    Latest_World_List: CollectionProperty(name="Latest Worlds List",
                                            type=LatestWorld)#type: ignore
    Latest_World_List_index: IntProperty(name="Latest Worlds",
                                           default=0,
                                           update=lambda self, context: self.reload_latest_worlds_list(context))# type: ignore
    History_World_Roots_List: CollectionProperty(name="History Worlds Roots List",
                                            type=HistoryWorldRoot)#type: ignore
    History_World_Roots_List_index: IntProperty(name="History World Roots",
                                           default=0,
                                           update=lambda self, context: self.reload_history_worlds_list(context))# type: ignore
    History_World_Versions_List: CollectionProperty(name="History Versions List",
                                                    type=HistoryWorldVersion)#type: ignore
    History_World_Versions_List_index: IntProperty(name="History Versions",
                                                   default=0,
                                                   update=lambda self, context: self.reload_history_worlds_list(context))# type: ignore
    History_World_Saves_List: CollectionProperty(name="History Saves List",
                                                 type=HistoryWorldSave)#type: ignore
    History_World_Saves_List_index: IntProperty(name="History Saves",
                                                default=0,
                                                update=lambda self, context: self.reload_history_worlds_list(context))# type: ignore
    History_World_Settings_List: CollectionProperty(name="History Settings List",
                                                    type=HistoryWorldSetting)#type: ignore
    History_World_Settings_List_index: IntProperty(name="History Settings",
                                                   default=0)# type: ignore
    Game_Resources_List: CollectionProperty(name="Resources",
                                            type=Resource)# type: ignore
    Game_Resources_List_index: IntProperty(name="Resource",
                                      default=0,)# type: ignore
    Game_unuse_Resources_List: CollectionProperty(name="Resources",
                                                  type=Resource)# type: ignore
    Game_unuse_Resources_List_index: IntProperty(name="Resource",
                                      default=0,)# type: ignore
    solid: IntProperty(name="Solid",
                       default=0,)# type: ignore
    useChunkPrecision: BoolProperty(name="Chunk Precision",
                                    default=True,)# type: ignore
    keepBoundary: BoolProperty(name="Keep Boundary",
                               default=False,)# type: ignore
    strictDeduplication: BoolProperty(name="Strict Surface Pruning",
                                      default=True,)# type: ignore
    cullCave: BoolProperty(name="Cull Cave",
                           default=True,)# type: ignore
    exportLightBlock: BoolProperty(name="Export Light Block",
                                   default=True,)# type: ignore
    allowDoubleFace: BoolProperty(name="Allow Double Face",
                                  default=False,)# type: ignore
    exportFullModel: BoolProperty(name="As Chunk",
                                  default=False,)# type: ignore
    partitionSize: IntProperty(name="Chunk Size",
                               default=4,
                               min=1)#type: ignore
    activeLOD: BoolProperty(name="LOD",
                            default=True,)# type: ignore
    useUnderwaterLOD: BoolProperty(name="Underwater LOD",
                                   default=False,)# type: ignore
    isLODAutoCenter: BoolProperty(name="LOD Auto Center",
                                  default=True,)# type: ignore
    LODCenterX: IntProperty(name="LOD Center X",
                            default=0)# type: ignore
    LODCenterZ: IntProperty(name="LOD Center Z",
                            default=0)# type: ignore
    LOD0renderDistance: IntProperty(name="LOD0 Distance",
                                   default=6,
                                   min=0)# type: ignore
    LOD1renderDistance: IntProperty(name="LOD1 Distance",
                                   default=6,
                                   min=0)# type: ignore
    LOD2renderDistance: IntProperty(name="LOD2 Distance",
                                   default=6,
                                   min=0)# type: ignore
    LOD3renderDistance: IntProperty(name="LOD3 Distance",
                                   default=6,
                                   min=0)# type: ignore
#==========加载资源包属性==========
    Resources_Plans_List: CollectionProperty(name="Resources Plans",type=ResourcePlan)#type: ignore
    Resources_Plans_List_index: IntProperty(name="Resources",
                                            default=0,
                                            update=lambda self, context: self.reload_all(context))# type: ignore
    Resources_List: CollectionProperty(name="Resources",type=ResourcePlansInfo)# type: ignore
    Resources_List_index: IntProperty(name="Resource",
                                      default=0,
                                      update=lambda self, context: self.reload_all(context))# type: ignore
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
    Parsed_Normal_Strength: FloatProperty(name="Parsed Normal Strength",
                                          default=1.0,
                                          min=0.0,
                                          max=100.0,
                                          description="Parsed Normal strength",
                                          update=lambda self,context: self.set_parsed_normal_strength(context))# type: ignore
    Materials_List: CollectionProperty(name="Materials",type=Material)#type: ignore
    Materials_List_index: IntProperty(name="Material",default=0)# type: ignore
    Classification_Basis_List: CollectionProperty(name="Classification Basis",type=ClassificationBasisl)# type: ignore
    Classification_Basis_List_index: IntProperty(name="Classification Basis",default=0)# type: ignore

#==========加载背景属性==========
    Backgrounds_List: CollectionProperty(name="Backgrounds",type=Background)# type: ignore
    Backgrounds_List_index: IntProperty(name="Background",
                                      default=0,
                                      update=lambda self, context: self.reload_all(context))# type: ignore
#==========偏好设置面板==========
    def draw(self, context: bpy.types.Context):
        layout = self.layout
        layout.prop(self, "Plans")
        layout.prop(self, "Import_World")
        layout.prop(self, "Load_Resources")
        layout.prop(self, "Load_Materials")
#==========修改变量操作==========
    def update_texture_interpolation(self, context):
        bpy.ops.crafter.set_texture_interpolation()
        return None
    
    def reload_all(self, context):
        bpy.ops.crafter.reload_all()
        return None
    
    def update_PBR_Parser(self, context):
        bpy.ops.crafter.set_pbr_parser()
        return None
    def reload_latest_worlds_list(self, context):
        bpy.ops.crafter.reload_latest_worlds_list()
        return None
    def reload_history_worlds_list(self, context):
        bpy.ops.crafter.reload_history_worlds_list()
        return None
    def set_parsed_normal_strength(self, context):
        bpy.ops.crafter.set_parsed_normal_strength()
        return None
