import bpy
import os
import shutil

from .config import __addon_name__
from .i18n.dictionary import dictionary
from ...common.class_loader import auto_load
from ...common.class_loader.auto_load import add_properties, remove_properties
from ...common.i18n.dictionary import common_dictionary
from ...common.i18n.i18n import load_dictionary
from bpy.props import StringProperty, IntProperty, BoolProperty, IntVectorProperty, EnumProperty, CollectionProperty
from .properties import ResourcePlan, ResourcePlansInfo, Material


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
extension_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
extensions_dir = os.path.dirname(extension_dir)
defaults_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "defaults")
defaults_materials_dir = os.path.join(defaults_dir, "materials")
defaults_classification_basis_dir = os.path.join(defaults_dir, "classification basis")
blend_append_dir = os.path.join(os.path.join(defaults_dir,"append.blend"))

cafter_data_dir = os.path.join(extensions_dir, "cafter_data")
resourcepacks_dir = os.path.join(cafter_data_dir, "resourcepacks")
original_dir = os.path.join(resourcepacks_dir, "original")
materials_dir = os.path.join(cafter_data_dir, "materials")
classification_basis_dir = os.path.join(cafter_data_dir, "classification basis")
classification_basis_default_dir = os.path.join(classification_basis_dir, "default")

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
    os.makedirs(classification_basis_dir, exist_ok=True)
    os.makedirs(classification_basis_default_dir, exist_ok=True)

    print("cafter_data文件夹初始化完成,地址：" + cafter_data_dir)
    #==========初始化默认方案==========
    for filename in os.listdir(defaults_materials_dir):
        src_file = os.path.join(defaults_materials_dir, filename)
        dest_file = os.path.join(materials_dir, filename)
        shutil.copy(src_file, dest_file)
        print("材质"+filename+"初始化完成")
    for filename in os.listdir(defaults_classification_basis_dir):
        src_file = os.path.join(defaults_classification_basis_dir, filename)
        dest_file = os.path.join(classification_basis_default_dir, filename)
        shutil.copy(src_file, dest_file)
        print("分类依据"+filename+"初始化完成")

    #==========刷新UIList==========
    bpy.ops.crafter.reload_resources_plans
    bpy.ops.crafter.reload_materials

    print("{} addon is installed.".format(__addon_name__))

#==========注销==========
def unregister():
    # Internationalization
    bpy.app.translations.unregister(__addon_name__)
    # unRegister classes
    auto_load.unregister()
    remove_properties(_addon_properties)
    print("{} addon is uninstalled.".format(__addon_name__))
