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
from ..__init__ import dir_default_Asset
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
        dir_asset = os.path.normpath(addon_prefs.Asset_Path)
        if not os.path.exists(dir_asset):
            self.report({'ERROR'}, "Path does not exist")
            return {'CANCELLED'}
        lib_crafter = libraries.new(name=name_library, directory=dir_asset)
        list_asset_files = os.listdir(dir_asset)

        for file in os.listdir(dir_default_Asset):
            if os.path.isfile(os.path.join(dir_default_Asset, file)):
                if file.endswith(".blend"):
                    shutil.copy(os.path.join(dir_default_Asset, file), os.path.join(dir_asset, file))
        
        if len(list_asset_files) == 0:
            for file in os.listdir(dir_default_Asset):
                if os.path.isfile(os.path.join(dir_default_Asset, file)):
                    if file.endswith(".txt"):
                        shutil.copy(os.path.join(dir_default_Asset, file), os.path.join(dir_asset, file))
        
            

            
        return {'FINISHED'}