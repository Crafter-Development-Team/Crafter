import bpy

from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from ....common.types.framework import reg_order

@reg_order(0)#==========导入世界面板==========
class VIEW3D_PT_ImportWorld(bpy.types.Panel):
    bl_idname = "VIEW3D_PT_ImportWorld"
    bl_label = "Import World"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Crafter"
    def draw(self, context: bpy.types.Context):
        
        layout = self.layout
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        layout.prop(addon_prefs, "World_Path")
        layout.prop(addon_prefs, "XYZ_1")
        layout.prop(addon_prefs, "XYZ_2")
        layout.operator("scene.crafter_import_world")


    @classmethod
    def poll(cls, context: bpy.types.Context):
        return context.preferences.addons[__addon_name__].preferences.Import_World

@reg_order(1)#==========加载材质面板==========
class VIEW3D_PT_Materials(bpy.types.Panel):
    bl_label = "Load Materials"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Crafter"

    def draw(self, context: bpy.types.Context):

        layout = self.layout
        row = layout.row()
        col = row.column()

        layout.label(text=i18n("Plans"))
    # def draw_item(self, context, layout, data, item, icon, active_data, active_propname):


    @classmethod
    def poll(cls, context: bpy.types.Context):
        return context.preferences.addons[__addon_name__].preferences.Load_Materials
