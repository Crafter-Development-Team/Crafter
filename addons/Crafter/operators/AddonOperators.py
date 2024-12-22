import bpy
import json
from ..config import __addon_name__
from ..preference.AddonPreferences import ExampleAddonPreferences

#==========导入世界操作==========

class Crafter_Import_World(bpy.types.Operator):
    '''Import world'''
    bl_label = "Import World"
    bl_idname = "scene.crafter_import_world"
    bl_options = {'REGISTER', 'UNDO'}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return context.active_object is not None
    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        worldconfig = {
            "world_path": addon_prefs.World_Path,
            "minX": min(addon_prefs.XYZ_1[0],addon_prefs.XYZ_2[0]),
            "maxX": max(addon_prefs.XYZ_1[0],addon_prefs.XYZ_2[0]),
            "minY": min(addon_prefs.XYZ_1[1],addon_prefs.XYZ_2[1]),
            "maxY": max(addon_prefs.XYZ_1[1],addon_prefs.XYZ_2[1]),
            "minz": min(addon_prefs.XYZ_1[2],addon_prefs.XYZ_2[2]),
            "maxz": max(addon_prefs.XYZ_1[2],addon_prefs.XYZ_2[2]),
            "status": 0,
        }
        print(worldconfig)
        # worldconfig.to_json()

        return {'FINISHED'}
    
#==========加载材质操作==========