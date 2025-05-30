import os

import bpy
from bpy.props import *
from bpy.types import AddonPreferences
from ..config import __addon_name__
from ..properties import *


class CrafterAddonPreferences(AddonPreferences):
    # this must match the add-on name (the folder name of the unzipped file)
    bl_idname = __addon_name__

    # https://docs.blender.org/api/current/bpy.props.html
    # The name can't be dynamically translated during blender programming running as they are defined
    # when the class is registered, i.e. we need to restart blender for the property name to be correctly translated.

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
    Undivided_Vsersions_List: CollectionProperty(name="Undivided Versions List",
                                                type=UndividedVersion)#type: ignore
    Undivided_Vsersions_List_index: IntProperty(name="Undivided Versions",
                                                default=0)# type: ignore
    is_Undivided: BoolProperty(name="Undivided",
                               default=False,)# type: ignore
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
                                    default=False,)# type: ignore
    keepBoundary: BoolProperty(name="Keep Boundary",
                               default=False,)# type: ignore
    strictDeduplication: BoolProperty(name="Strict Surface Pruning",
                                      default=True,)# type: ignore
    cullCave: BoolProperty(name="Cull Cave",
                           default=False,)# type: ignore
    exportLightBlock: BoolProperty(name="Export Light Block",
                                   default=True,)# type: ignore
    exportLightBlockOnly: BoolProperty(name="Only Export Light Block",
                                       default=False,)# type: ignore
    lightBlockSize: FloatProperty(name="Light Block Size",
                                   default=0.05,
                                   min=0.0001,
                                   max=1.0)# type: ignore
    allowDoubleFace: BoolProperty(name="Allow Double Face",
                                  default=False,)# type: ignore
    exportFullModel: BoolProperty(name="As Chunk",
                                  default=False,)# type: ignore
    partitionSize: IntProperty(name="Chunk Size",
                               default=4,
                               min=1)#type: ignore
    maxTasksPerBatch: IntProperty(name="Chunk Number to Release",
                                  default=32768,
                                  min=1)# type: ignore
    activeLOD: BoolProperty(name="LOD",
                            default=False,)# type: ignore
    useUnderwaterLOD: BoolProperty(name="Underwater LOD",
                                   default=False,)# type: ignore
    useGreedyMesh: BoolProperty(name="Greedy Mesh",
                                default=True,)# type: ignore
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
    shell: BoolProperty(name="Shell ",
                        description="Enable this option when reporting a bug and include the shell output content",
                        default=False,)# type: ignore
    Game_Resources: BoolProperty(name="Game Resources",
                                  default=True,)# type: ignore
    Auto_Load_Material: BoolProperty(name="Load Material",
                                      default=True,)# type: ignore
    Custom_Path: BoolProperty(name="Custom Path",
                                  default=False,)# type: ignore
    is_Game_Path: BoolProperty(name="Game Path",
                                  default=False,)# type: ignore
    Custom_Jar_Path: StringProperty(name="Jar Path",
                                        subtype="FILE_PATH",
                                        default="Jar Path")# type: ignore
    use_Custom_mods_Path: BoolProperty(name="Custom Mods",
                                        default=False,)# type: ignore
    Custom_mods_Path: StringProperty(name="Mods Path",
                                        subtype="DIR_PATH",
                                        default="Mods Path")# type: ignore
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
    Default_Metallic: FloatProperty(name="Metallic",
                                    subtype="FACTOR",
                                    default=0.0,
                                    min=0.0,
                                    max=1.0,)# type: ignore
    Default_Roughness: FloatProperty(name="Roughness",
                                    subtype="FACTOR",
                                    default=0.5,
                                    min=0.0,
                                    max=1.0,)# type: ignore
    Default_IOR: FloatProperty(name="IOR",
                                subtype="FACTOR",
                                default=1.5,
                                min=1.0,
                                max=10.0,)# type: ignore
    Default_Emission_Strength: FloatProperty(name="Emission Strength",
                                          subtype="FACTOR",
                                          default=0.0,
                                          min=0.0,
                                          max=1.0,)# type: ignore

#==========加载环境属性==========
    Environments_List: CollectionProperty(name="Environments",type=Environment)# type: ignore
    Environments_List_index: IntProperty(name="Environment",
                                      default=0,
                                      update=lambda self, context: self.reload_all(context))# type: ignore
#==========偏好设置面板==========
    def draw(self, context: bpy.types.Context):
        layout = self.layout
        col_default_PBR  = layout.column()
        col_default_PBR.prop(self, "Default_Metallic")
        col_default_PBR.prop(self, "Default_Roughness")
        col_default_PBR.prop(self, "Default_IOR")
        col_default_PBR.prop(self, "Default_Emission_Strength")
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
