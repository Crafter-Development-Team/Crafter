import bpy

from ..config import __addon_name__

# ==================== UI切换至日志 ====================

class VIEW3D_OT_CrafterUILog(bpy.types.Operator):
    bl_label = "UI-Log"
    bl_idname = "crafter.ui_log"
    bl_description = " "
    bl_options = {'REGISTER', 'UNDO'}
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        addon_prefs.Other_index = 2
        bpy.ops.crafter.reload_all()

        return {'FINISHED'}
