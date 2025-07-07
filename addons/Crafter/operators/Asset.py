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
from .dfs import *

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
