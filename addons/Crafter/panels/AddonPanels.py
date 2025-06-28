import bpy
import os

from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from ....common.types.framework import reg_order
from ..__init__ import dir_resourcepacks_plans

# @reg_order(0)# ==========加载预设面板==========
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
    
@reg_order(1)# ========== 导入世界面板 ==========
class VIEW3D_PT_CrafterImportWorld(bpy.types.Panel):
    bl_label = "Import World"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Crafter"
    def draw_header(self, context):
        layout = self.layout
        layout.label(text="", icon="FILE_3D")
    def draw(self, context: bpy.types.Context):
        
        layout = self.layout
        box = layout.box()
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        box.prop(addon_prefs, "World_Path")

        box.template_list("VIEW3D_UL_CrafterDimensionsList", "", addon_prefs, "Dimensions_List", addon_prefs, "Dimensions_List_index",rows=1)

        box.label(text="XYZ",icon="ORIENTATION_GLOBAL")
        row_XYZ1 = box.row()
        row_XYZ1.prop(addon_prefs, "XYZ_1",text="")
        row_XYZ2 = box.row()
        row_XYZ2.prop(addon_prefs, "XYZ_2",text="")
        
        row_setting = box.row()
        # row_setting.prop(addon_prefs, "Point_Cloud_Mode")
        # row_setting.operator("crafter.use_history_worlds",icon="TIME",text="")
        row_setting.label(icon="TIME")
        row_setting.operator("crafter.use_history_worlds",text="History")
        
        row_ImportWorld = box.row()
        row_ImportWorld.label(icon="MOD_BUILD")
        row_ImportWorld.operator("crafter.import_surface_world",text="Import World")
        row_ImportWorld.operator("crafter.open_worldimporter_folder",text="",icon="FILE_FOLDER")
        if addon_prefs.Point_Cloud_Mode:
            row_ImportWorld.operator("crafter.import_solid_area",text="Import Editable Area")

    @classmethod
    def poll(cls, context: bpy.types.Context):
            return True


@reg_order(2)# ========== 材质面板 ==========
class VIEW3D_PT_CrafterMaterials(bpy.types.Panel):
    bl_label = "Material"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Crafter"

    def draw_header(self, context):
        layout = self.layout
        layout.label(text="", icon="MATERIAL")
    def draw(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        layout = self.layout
        box = layout.box()
        
        box_main = box.box()
        box_main.label(text="Materials",icon="SHADING_TEXTURE")
        row_Materials_List = box_main.row()
        row_Materials_List.template_list("VIEW3D_UL_CrafterMaterials", "", addon_prefs, "Materials_List", addon_prefs, "Materials_List_index", rows=1)
        col_Materials_List_ops = row_Materials_List.column()
        col_Materials_List_ops.operator("crafter.open_materials",icon="FILE_FOLDER",text="")
        col_Materials_List_ops.operator("crafter.reload_all",icon="FILE_REFRESH",text="")

        box_main.operator("crafter.load_material",text="Load")
        
        box_other = box.box()
        row_PBR_Parser = box_other.row()
        row_PBR_Parser.label(icon="SHADERFX")
        row_PBR_Parser.prop(addon_prefs, "PBR_Parser",text="Parser")

        row_Parsed_Normal_Strength = box_other.row()
        row_Parsed_Normal_Strength.label(icon="NODE_TEXTURE")
        row_Parsed_Normal_Strength.prop(addon_prefs, "Parsed_Normal_Strength")

        row_Crafter_time = box_other.row()
        row_Crafter_time.label(icon="TIME")
        row_Crafter_time_ops = row_Crafter_time.row(align=True)
        row_Crafter_time_ops.operator("crafter.add_craftertime",icon="LINKED",text="Add")
        row_Crafter_time_ops.operator("crafter.remove_craftertime",icon="UNLINKED",text="Remove")

        box_classification = box.box()
        box_classification.label(text="Classification Basis",icon="PACKAGE")
        row_Classification_Basis = box_classification.row()
        row_Classification_Basis.template_list("VIEW3D_UL_CrafterClassificationBasis", "", addon_prefs, "Classification_Basis_List", addon_prefs, "Classification_Basis_List_index", rows=1)
        row_Classification_Basis_ops = row_Classification_Basis.column()
        row_Classification_Basis_ops.operator("crafter.open_classification_basis",icon="FILE_FOLDER",text="")
        row_Classification_Basis_ops.operator("crafter.reload_all",icon="FILE_REFRESH",text="")

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
    
@reg_order(3)# ========== 其他面板 ==========
class VIEW3D_PT_CrafterOthers(bpy.types.Panel):
    bl_label = "Others"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Crafter"
    def draw_header(self, context):
        layout = self.layout
        layout.label(text="", icon="OUTLINER")
    def draw(self, context: bpy.types.Context):
        
        layout = self.layout
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        # ========== 功能选择面板 ==========
        row_function1 = layout.row()
        col_f1_1 = row_function1.column()
        # col_f1_1.alert = addon_prefs.Other_index == 0
        col_f1_1.operator("crafter.ui_asset",text="Asset",icon="ASSET_MANAGER")
        col_f1_2 = row_function1.column()
        # col_f1_2.alert = addon_prefs.Other_index == 1
        col_f1_2.operator("crafter.ui_replace_resources",text="Replace Resources",icon="NODE_COMPOSITING")
        
        if addon_prefs.Other_index == 0:
            row_Asset = layout.row()
            
        # ========== 加载资源包面板 ==========
        if addon_prefs.Other_index == 1:
            layout = self.layout
            
            box_resources = layout.box()
            box_resources.label(text="Resources",icon="PACKAGE")
            row_Plans_List = box_resources.row()
            row_Plans_List.template_list("VIEW3D_UL_CrafterResources", "", addon_prefs, "Resources_Plans_List", addon_prefs, "Resources_Plans_List_index", rows=1)
            col_Plans_List_ops = row_Plans_List.column()
            col_Plans_List_ops.operator("crafter.open_resources_plans",icon="FILE_FOLDER",text="")
            col_Plans_List_ops.operator("crafter.reload_all",icon="FILE_REFRESH",text="")

            box_resource = layout.box()
            if len(addon_prefs.Resources_List) > 0:
                box_resource.label(text="Resource",icon="NODE_COMPOSITING")
                row_Resources_List = box_resource.row()
                row_Resources_List.template_list("VIEW3D_UL_CrafterResourcesInfo", "", addon_prefs, "Resources_List", addon_prefs, "Resources_List_index", rows=1)
                if len(addon_prefs.Resources_List) > 1:
                    col_Resources_List_ops = row_Resources_List.column(align=True)
                    col_Resources_List_ops.operator("crafter.up_resource",icon="TRIA_UP",text="")
                    col_Resources_List_ops.operator("crafter.down_resource",icon="TRIA_DOWN",text="")

            row_Resources = layout.row()
            row_Resources.operator("crafter.replace_resources",text="Replace")

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
# ==================== UIList ====================

class VIEW3D_UL_CrafterDimensionsList(bpy.types.UIList):# 历史世界 存档 列表
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        layout.label(text=item.name)