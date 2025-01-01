import bpy
import os

from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from ....common.types.framework import reg_order
from ..__init__ import resourcepacks_dir, materials_dir

@reg_order(0)#==========导入预设面板==========
class VIEW3D_PT_CrafterPlans(bpy.types.Panel):
    bl_idname = "VIEW3D_PT_CrafterPlans"
    bl_label = "Plans"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Crafter"
    def draw(self, context: bpy.types.Context):
        
        layout = self.layout
        addon_prefs = context.preferences.addons[__addon_name__].preferences

    @classmethod
    def poll(cls, context: bpy.types.Context):
            return context.preferences.addons[__addon_name__].preferences.Plans
    
@reg_order(1)#==========导入世界面板==========
class VIEW3D_PT_CrafterImportWorld(bpy.types.Panel):
    bl_idname = "VIEW3D_PT_CrafterImportWorld"
    bl_label = "Import World"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Crafter"
    def draw(self, context: bpy.types.Context):
        
        layout = self.layout
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        layout.prop(addon_prefs, "World_Path")

        cul_XYZ = layout.column(align=True)
        row_XYZ1 = cul_XYZ.row()
        row_XYZ1.prop(addon_prefs, "XYZ_1")
        row_XYZ2 = cul_XYZ.row()
        row_XYZ2.prop(addon_prefs, "XYZ_2")
        
        row_ImportWorld = layout.row()
        row_ImportWorld.operator("crafter.import_surface_world",text=i18n("Import World"))
        row_ImportWorld.operator("crafter.import_solid_area",text=i18n("Import Editable Area"))

    @classmethod
    def poll(cls, context: bpy.types.Context):
            return context.preferences.addons[__addon_name__].preferences.Import_World

#==========导入纹理列表==========
class VIEW3D_UL_CrafterResources(bpy.types.UIList):
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        if self.layout_type in {"DEFAULT","COMPACT"}:
            layout.label(text=item.name)
class VIEW3D_UL_CrafterResourcesInfo(bpy.types.UIList):
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        if self.layout_type in {"DEFAULT","COMPACT"}:
            layout.label(text=item.name)

@reg_order(2)#==========导入纹理面板==========
class VIEW3D_PT_CrafterImportResources(bpy.types.Panel):
    bl_idname = "VIEW3D_PT_CrafterImportResources"
    bl_label = "Import Resources"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Crafter"
    def draw(self, context: bpy.types.Context):
        
        layout = self.layout
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        # context.scene.Resources_Plans_List = [entry for entry in os.listdir(resourcepacks_dir) if os.path.isdir(os.path.join(resourcepacks_dir, entry))]
        layout.template_list("VIEW3D_UL_CrafterResources", "", context.scene, "Resources_Plans_List", addon_prefs, "Resources_Plans_List_index", rows=1)
        layout.template_list("VIEW3D_UL_CrafterResourcesInfo", "", context.scene, "Resources_Plans_Info_List", addon_prefs, "Resources_Plans_Info_List_index", rows=1)
        #layout.operator()
        row_Texture_Interpolation = layout.row(align=True)
        row_Texture_Interpolation.prop(addon_prefs,"Texture_Interpolation")
        row_Texture_Interpolation.operator("crafter.set_texture_interpolation")

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return context.preferences.addons[__addon_name__].preferences.Import_Resources

@reg_order(3)#==========加载材质面板==========
class VIEW3D_PT_Materials(bpy.types.Panel):
    bl_label = "Load Materials"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Crafter"

    def draw(self, context: bpy.types.Context):

        layout = self.layout

        layout.label(text=i18n("Plans"))

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return context.preferences.addons[__addon_name__].preferences.Load_Materials
