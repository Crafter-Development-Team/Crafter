import bpy
import os
import json

from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from bpy.props import StringProperty, IntProperty, BoolProperty, IntVectorProperty, EnumProperty, CollectionProperty, FloatProperty
from .. import dir_cafter_data, dir_resourcepacks_plans, dir_materials, dir_classification_basis, dir_blend_append, dir_init_main, dir_environments
from .Defs import *

# ==================== 加载环境 ====================

class VIEW3D_OT_CrafterLoadEnvironment(bpy.types.Operator):
    bl_label = "Load Environment"
    bl_idname = "crafter.load_environment"
    bl_description = "Load Environment"
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        bpy.ops.crafter.reload_all()
        if -1 < addon_prefs.Environments_List_index and addon_prefs.Environments_List_index < len(addon_prefs.Environments_List):
            return  {'CANCELLED'}
        if not (-1 < addon_prefs.Environments_List_index and addon_prefs.Environments_List_index < len(addon_prefs.Environments_List)):
            self.report({'ERROR'}, "No Selected Environment!")
            return {'FINISHED'}
            
        dir_environment = os.path.join(dir_environments, addon_prefs.Environments_List[addon_prefs.Environments_List_index].name + ".blend")
        if context.scene.world:
            bpy.data.worlds.remove(context.scene.world)
        with bpy.data.libraries.load(dir_environment) as (data_from, data_to):
            data_to.worlds = data_from.worlds
        context.scene.world = data_to.worlds[0]
        
        return {'FINISHED'}
    def invoke(self, context, event):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        bpy.ops.crafter.reload_all()
        return context.window_manager.invoke_props_dialog(self)

    def draw(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        layout = self.layout

        row_Environments = layout.row()
        col_Environment_List = row_Environments.column()
        col_Environment_List.template_list("VIEW3D_UL_CrafterEnvironmentsList", "", addon_prefs, "Environments_List", addon_prefs, "Environments_List_index", rows=1)
        col_Environment_List_ops = row_Environments.column()
        col_Environment_List_ops.operator("crafter.open_environments",icon="FILE_FOLDER",text="")
        col_Environment_List_ops.operator("crafter.reload_all",icon="FILE_REFRESH",text="")

# ==================== 打开环境列表文件夹 ====================

class VIEW3D_OT_CrafterOpenEnvironments(bpy.types.Operator):
    bl_label = "Open Environments"
    bl_idname = "crafter.open_environments"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        folder_path = dir_environments
        open_folder(folder_path)

        return {'FINISHED'}



# ==================== 刷新 ====================

class VIEW3D_OT_CrafterReloadEnvironments(bpy.types.Operator):# 刷新 环境 列表
    bl_label = "Reload Environments"
    bl_idname = "crafter.reload_environment"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        addon_prefs.Environments_List.clear()
        for folder in os.listdir(dir_environments):
            base, extension = os.path.splitext(folder)
            if extension == ".blend":
                material_name = addon_prefs.Environments_List.add()
                material_name.name = base
        return {'FINISHED'}

# ==================== UIList ====================

class VIEW3D_UL_CrafterEnvironmentsList(bpy.types.UIList):# 环境 列表
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        layout.label(text=item.name)
