import bpy
import os

from .config import __addon_name__
from .i18n.dictionary import dictionary
from ...common.class_loader import auto_load
from ...common.class_loader.auto_load import add_properties, remove_properties
from ...common.i18n.dictionary import common_dictionary
from ...common.i18n.i18n import load_dictionary
from bpy.props import StringProperty, IntProperty, BoolProperty, IntVectorProperty, EnumProperty, CollectionProperty
from addons.Crafter.properties import ResourcePlans, ResourcePlansInfo


# Add-on info
bl_info = {
    "name": "Crafter",
    "author": "Crafter Production Team [ 白给 若有来生 少年忠城 WangXinRui ]",
    "blender": (4, 2, 0),
    "version": (0, 0, 1),
    "description": "目标是成为从Minecraft到Blender全流程的Blender插件",
    "warning": "",
    "doc_url": "https://github.com/Crafter-Production-Team/Crafter?tab=readme-ov-file#crafter",
    "tracker_url": "https://github.com/Crafter-Production-Team/Crafter/issues",
    "support": "COMMUNITY",
    "category": "3D View"
}

_addon_properties = {
    bpy.types.Scene: {
#==========导入纹理属性==========
        "Resources_Plans_List": CollectionProperty(name="Resources Plans",type=ResourcePlans),
        "Resources_Plans_Info_List": CollectionProperty(name="Resources Plans Info",type=ResourcePlansInfo),
    }
}


# You may declare properties like following, framework will automatically add and remove them.
# Do not define your own property group class in the __init__.py file. Define it in a separate file and import it here.
# 注意不要在__init__.py文件中自定义PropertyGroup类。请在单独的文件中定义它们并在此处导入。
# _addon_properties = {
#     bpy.types.Scene: {
#         "property_name": bpy.props.StringProperty(name="property_name"),
#     },
# }

#==========初始化cafter_data地址==========
extension_dir = os.path.dirname(os.path.abspath(__file__))
extensions_dir = os.path.dirname(extension_dir)
cafter_data_dir = os.path.join(extensions_dir, "cafter_data")
resourcepacks_dir = os.path.join(cafter_data_dir, "resourcepacks")
original_dir = os.path.join(resourcepacks_dir, "original")
materials_dir = os.path.join(cafter_data_dir, "materials")

#==========注册==========
def register():
    # Register classes
    auto_load.init()
    auto_load.register()
    add_properties(_addon_properties)

    # Internationalization
    load_dictionary(dictionary)
    bpy.app.translations.register(__addon_name__, common_dictionary)

    #==========初始化cafter_data文件夹==========
    os.makedirs(cafter_data_dir, exist_ok=True)
    os.makedirs(resourcepacks_dir, exist_ok=True)
    os.makedirs(original_dir, exist_ok=True)
    os.makedirs(materials_dir, exist_ok=True)
    print("cafter_data文件夹初始化完成,地址：" + cafter_data_dir)

    #==========刷新UIList==========
    bpy.ops.crafter.reload_resources_plans

    print("{} addon is installed.".format(__addon_name__))

#==========注销==========
def unregister():
    # Internationalization
    bpy.app.translations.unregister(__addon_name__)
    # unRegister classes
    auto_load.unregister()
    remove_properties(_addon_properties)
    print("{} addon is uninstalled.".format(__addon_name__))
