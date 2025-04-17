import bpy

# ==================== 刷新全部 ====================

class VIEW3D_OT_CrafterReloadAll(bpy.types.Operator):
    bl_label = "Reload"
    bl_idname = "crafter.reload_all"
    bl_description = " "
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        bpy.ops.crafter.reload_resources_plans()
        bpy.ops.crafter.reload_resources()
        bpy.ops.crafter.reload_materials()
        bpy.ops.crafter.reload_classification_basis()
        bpy.ops.crafter.reload_background()
        return {'FINISHED'}
