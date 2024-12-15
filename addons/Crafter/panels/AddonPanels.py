import bpy

from ..config import __addon_name__
from ..operators.AddonOperators import ExampleOperator
from ....common.i18n.i18n import i18n


class VIEW3D_PT_ImportWorld(bpy.types.Panel):
    bl_label = "Import World"
    bl_space_type = "VIEW_3D"
    bl_region_type = 'UI'
    bl_category = "Crafter"
    def draw(self, context: bpy.types.Context):

        layout = self.layout
        row = layout.row()
        col = row.column()


    @classmethod
    def poll(cls, context: bpy.types.Context):
        return context.preferences.addons[__addon_name__].preferences.Import_World

class VIEW3D_PT_Materials(bpy.types.Panel):
    bl_label = "Materials Loader"
    bl_space_type = "VIEW_3D"
    bl_region_type = 'UI'
    bl_category = "Crafter"

    def draw(self, context: bpy.types.Context):

        layout = self.layout
        row = layout.row()
        col = row.column()

        layout.label(text=i18n("Plan"))
        layout.operator(ExampleOperator.bl_idname)

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return context.preferences.addons[__addon_name__].preferences.Materials_Loader
