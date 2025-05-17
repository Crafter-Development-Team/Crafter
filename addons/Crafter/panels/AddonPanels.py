import bpy
import os

from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from ....common.types.framework import reg_order
from ..__init__ import dir_resourcepacks_plans

# @reg_order(0)#==========加载预设面板==========
# class VIEW3D_PT_CrafterPlans(bpy.types.Panel):
#     bl_label = "Plans"
#     bl_space_type = "VIEW_3D"
#     bl_region_type = "UI"
#     bl_category = "Crafter"
#     def draw(self, context: bpy.types.Context):
        
#         layout = self.layout
#         addon_prefs = context.preferences.addons[__addon_name__].preferences

#         # layout.label(text="此版本为测试版本，请勿用于已编")
#         # layout.label(text="辑的工程或作品工程。")


#     @classmethod
#     def poll(cls, context: bpy.types.Context):
#             return True
    
@reg_order(1)#==========导入世界面板==========
class VIEW3D_PT_CrafterImportWorld(bpy.types.Panel):
    bl_label = "Import World"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Crafter"
    def draw(self, context: bpy.types.Context):
        
        layout = self.layout
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        layout.prop(addon_prefs, "World_Path")

        row_XYZ1 = layout.row()
        row_XYZ1.prop(addon_prefs, "XYZ_1")
        row_XYZ2 = layout.row()
        row_XYZ2.prop(addon_prefs, "XYZ_2")
        
        row_setting = layout.row()
        # row_setting.prop(addon_prefs, "Point_Cloud_Mode")
        # row_setting.operator("crafter.use_history_worlds",icon="TIME",text="")
        row_setting.operator("crafter.use_history_worlds",icon="TIME",text="History")
        
        row_ImportWorld = layout.row()
        row_ImportWorld.operator("crafter.import_surface_world",text="Import World")
        if addon_prefs.Point_Cloud_Mode:
            row_ImportWorld.operator("crafter.import_solid_area",text="Import Editable Area")

    @classmethod
    def poll(cls, context: bpy.types.Context):
            return True


@reg_order(2)#==========加载面板==========
class VIEW3D_PT_CrafterImport(bpy.types.Panel):
    bl_label = "Load"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Crafter"

    def draw(self, context: bpy.types.Context):
        
        layout = self.layout
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        #==========加载资源包面板==========
        row_Resources = layout.row()
        if -1 < addon_prefs.Resources_Plans_List_index and addon_prefs.Resources_Plans_List_index < len(addon_prefs.Resources_Plans_List):
            resource = addon_prefs.Resources_Plans_List[addon_prefs.Resources_Plans_List_index].name
        else:
            resource = ""
        row_Resources.label(text=i18n("Resources") + ":" + resource)
        row_Resources.operator("crafter.replace_resources",text="Load")
        #==========加载材质面板==========
        row_Resources = layout.row()
        if -1 < addon_prefs.Materials_List_index and addon_prefs.Materials_List_index < len(addon_prefs.Materials_List):
            material = addon_prefs.Materials_List[addon_prefs.Materials_List_index].name
        else:
            material = ""
        row_Resources.label(text=i18n("Materials") + ":" + material)
        row_Resources.operator("crafter.load_material",text="Load")
        #==========加载环境面板==========
        row_Environments = layout.row()
        if -1 < addon_prefs.Environments_List_index and addon_prefs.Environments_List_index < len(addon_prefs.Environments_List):
            environment = addon_prefs.Environments_List[addon_prefs.Environments_List_index].name
        else:
            environment = ""
        row_Environments.label(text=i18n("Environment") + ":" + environment)
        row_Environments.operator("crafter.load_environment",text="Load")
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

