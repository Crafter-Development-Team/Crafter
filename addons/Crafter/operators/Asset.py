import bpy
import os
import time
import subprocess
import json
import shutil
import sys
import ctypes
from ctypes import wintypes
from ..nbt import nbt

from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from bpy.props import *
from .. import dir_cafter_data, dir_resourcepacks_plans, dir_blend_append, dir_init_main, dir_no_lod_blocks
from .. import icons_world, icons_game_resource, icons_game_unuse_resource
from .Defs import *

# ==================== UI切换至资产 ====================

class VIEW3D_OT_CrafterUIAsset(bpy.types.Operator):
    bl_label = "UI-Asset"
    bl_idname = "crafter.ui_asset"
    bl_description = " "
    bl_options = {'REGISTER', 'UNDO'}
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        addon_prefs.Other_index = 0
        bpy.ops.crafter.reload_all()


        return {'FINISHED'}

# ==================== 注册资产库 ====================

class VIEW3D_OT_CrafterBuildAssetLibrary(bpy.types.Operator):
    bl_label = "Build Asset Library"
    bl_idname = "crafter.build_asset_library"
    bl_description = " "
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        libraries = bpy.context.preferences.filepaths.asset_libraries

        for lib in libraries:
            if lib.name == name_library:
                return {'FINISHED'}
        for lib in libraries:
            print(lib.name)
        lib_crafter = libraries.new(name=name_library)
        # lib_crafter.path = os.path.join(get_dir_save(context), "Crafter_Library")
            
        return {'FINISHED'}