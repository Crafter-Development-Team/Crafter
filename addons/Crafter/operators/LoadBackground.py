import bpy
import os
import json

from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from bpy.props import StringProperty, IntProperty, BoolProperty, IntVectorProperty, EnumProperty, CollectionProperty, FloatProperty
from .. import dir_cafter_data, dir_resourcepacks_plans, dir_materials, dir_classification_basis, dir_blend_append, dir_init_main, dir_backgrounds
from .Defs import *

# ==================== 加载背景 ====================

class VIEW3D_OT_CrafterLoadBackground(bpy.types.Operator):
    bl_label = "Load Background"
    bl_idname = "crafter.load_background"
    bl_description = "Load Background"
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        bpy.ops.crafter.reload_all()
        if not (-1 < addon_prefs.Backgrounds_List_index and addon_prefs.Backgrounds_List_index < len(addon_prefs.Backgrounds_List)):
            self.report({'ERROR'}, "No Selected Background!")
            return {'FINISHED'}
            
        dir_background = os.path.join(dir_backgrounds, addon_prefs.Backgrounds_List[addon_prefs.Backgrounds_List_index].name + ".blend")
        if context.scene.world:
            bpy.data.worlds.remove(context.scene.world)
        with bpy.data.libraries.load(dir_background) as (data_from, data_to):
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

        row_Backgrounds = layout.row()
        col_Background_List = row_Backgrounds.column()
        col_Background_List.template_list("VIEW3D_UL_CrafterBackgroundsList", "", addon_prefs, "Backgrounds_List", addon_prefs, "Backgrounds_List_index", rows=1)
        col_Background_List_ops = row_Backgrounds.column()
        col_Background_List_ops.operator("crafter.open_backgrounds",icon="FILE_FOLDER",text="")
        col_Background_List_ops.operator("crafter.reload_all",icon="FILE_REFRESH",text="")

# ==================== 打开背景列表文件夹 ====================

class VIEW3D_OT_CrafterOpenBackgrounds(bpy.types.Operator):
    bl_label = "Open Backgrounds"
    bl_idname = "crafter.open_backgrounds"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        folder_path = dir_backgrounds
        open_folder(folder_path)

        return {'FINISHED'}



# ==================== 刷新 ====================

class VIEW3D_OT_CrafterReloadBackgrounds(bpy.types.Operator):# 刷新 背景 列表
    bl_label = "Reload Backgrounds"
    bl_idname = "crafter.reload_background"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        addon_prefs.Backgrounds_List.clear()
        for folder in os.listdir(dir_backgrounds):
            base, extension = os.path.splitext(folder)
            if extension == ".blend":
                material_name = addon_prefs.Backgrounds_List.add()
                material_name.name = base
        return {'FINISHED'}

# ==================== UIList ====================

class VIEW3D_UL_CrafterBackgroundsList(bpy.types.UIList):# 背景 列表
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        layout.label(text=item.name)
