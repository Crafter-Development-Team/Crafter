import bpy
import os

from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from ....common.types.framework import reg_order
from ..__init__ import dir_resourcepacks_plans

@reg_order(0)#==========加载预设面板==========
class VIEW3D_PT_CrafterPlans(bpy.types.Panel):
    bl_label = "Plans"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Crafter"
    def draw(self, context: bpy.types.Context):
        
        layout = self.layout
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        # layout.label(text="此版本为测试版本，请勿用于已编")
        # layout.label(text="辑的工程或作品工程。")
        # layout.separator()
        # layout.label(text="可以使用的仅有 修改插值类型 和")
        # layout.label(text="加载材质 功能，其余功能请勿使")
        # layout.label(text="用，以免造成破坏。")
        # layout.separator()
        # layout.label(text="使用方法：在导出obj后将tex中")
        # layout.label(text="的纹理替换为材质包的纹理。")
        # layout.separator()
        # layout.label(text="制作预设请复制后修改，否则会被")
        # layout.label(text="覆盖。")
        # layout.separator()
        # layout.label(text="导入obj世界。")
        # layout.separator()
        # layout.label(text="在选择了全部世界后点击 加载材")
        # layout.label(text="质。")
        # layout.separator()
        # layout.label(text="如果 雨 值修改后没效果请重复上")
        # layout.label(text="一个操作。")
        # layout.separator()
        # layout.label(text="由于不知道岩浆的纹理如何映射，所")
        # layout.label(text="以岩浆材质是有问题的。")
        # layout.separator()
        # layout.label(text="如有问题请在群里联系 少年忠城。")



    @classmethod
    def poll(cls, context: bpy.types.Context):
            return context.preferences.addons[__addon_name__].preferences.Plans
    
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
            return context.preferences.addons[__addon_name__].preferences.Import_World


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
        if -1 < addon_prefs.Backgrounds_List_index and addon_prefs.Backgrounds_List_index < len(addon_prefs.Backgrounds_List):
            resource = addon_prefs.Resources_Plans_List[addon_prefs.Resources_Plans_List_index].name
        else:
            resource = ""
        row_Resources.label(text=i18n("Resources") + ":" + resource)
        row_Resources.operator("crafter.load_resources",text="Load")
        #==========加载材质面板==========
        row_Resources = layout.row()
        if -1 < addon_prefs.Materials_List_index and addon_prefs.Materials_List_index < len(addon_prefs.Backgrounds_List):
            material = addon_prefs.Materials_List[addon_prefs.Materials_List_index].name
        else:
            material = ""
        row_Resources.label(text=i18n("Materials") + ":" + material)
        row_Resources.operator("crafter.load_material",text="Load")
        #==========加载背景面板==========
        row_Backgrounds = layout.row()
        if -1 < addon_prefs.Backgrounds_List_index and addon_prefs.Backgrounds_List_index < len(addon_prefs.Backgrounds_List):
            background = addon_prefs.Backgrounds_List[addon_prefs.Backgrounds_List_index].name
        else:
            background = ""
        row_Backgrounds.label(text=i18n("Background") + ":" + background)
        row_Backgrounds.operator("crafter.load_background",text="Load")
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return context.preferences.addons[__addon_name__].preferences.Load_Resources

