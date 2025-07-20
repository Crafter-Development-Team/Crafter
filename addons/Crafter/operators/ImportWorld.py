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
from ..__init__ import dir_cafter_data, dir_resourcepacks_plans, dir_blend_append, dir_init_main, dir_no_lod_blocks
from ..__init__ import icons_world, icons_game_resource, icons_game_unuse_resource
from .Defs import *

dir_importer = os.path.join(dir_init_main, "importer")

# ==================== 导入世界 ====================

class VIEW3D_OT_CrafterImportSurfaceWorld(bpy.types.Operator):#导入表层世界
    bl_label = "Import World"
    bl_idname = "crafter.import_surface_world"
    bl_description = "Import the surface world"
    bl_options = {'REGISTER', 'UNDO'}
    
    worldPath: StringProperty(name="World path")#type: ignore
    jarPath: StringProperty(name="Jar path")#type: ignore
    modsPath: StringProperty(name="Mods path")#type: ignore

    save: StringProperty(name="Save")#type: ignore
    version: StringProperty(name="Version")#type: ignore
    dot_minecraftPath: StringProperty(name=".minecraft path")#type: ignore

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
    def draw(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        layout = self.layout

        box_custom = layout.box()
        col_custom = box_custom.column()
        if not addon_prefs.is_Game_Path:
            col_custom.label(icon="GHOST_DISABLED",text="Your world path is not in game folder,select jar.")
        else:
            col_custom.prop(addon_prefs, "Custom_Path")
        if (not addon_prefs.is_Game_Path) or addon_prefs.Custom_Path:
            col_custom.prop(addon_prefs, "Custom_Jar_Path")
            if not os.path.exists(addon_prefs.Custom_Jar_Path):
                col_custom.label(icon="ERROR",text="Path not found!")
            elif not addon_prefs.Custom_Jar_Path.endswith(".jar"):
                col_custom.label(icon="ERROR",text="It's not a jar file!")
            col_custom.prop(addon_prefs, "use_Custom_mods_Path")
            if addon_prefs.use_Custom_mods_Path:
                col_custom.prop(addon_prefs, "Custom_mods_Path")
                if not os.path.exists(addon_prefs.Custom_mods_Path):
                    col_custom.label(icon="ERROR",text="Path not found!")
                elif not os.path.isdir(addon_prefs.Custom_mods_Path):
                    col_custom.label(icon="ERROR",text="It's not a folder!")

        box_main_settings = layout.box()
        col_cols = box_main_settings.column(align=True)
        row_cols = box_main_settings.row()

        col_1 = row_cols.column()
        col_1.prop(addon_prefs, "useChunkPrecision")
        col_1.prop(addon_prefs, "strictDeduplication")
        col_1.prop(addon_prefs, "allowDoubleFace")
        col_1.prop(addon_prefs, "Auto_Load_Material")
        col_1.prop(addon_prefs, "exportLightBlock")

        col_2 = row_cols.column()
        col_2.prop(addon_prefs, "maxTasksPerBatch")
        col_2.prop(addon_prefs, "keepBoundary")
        col_2.prop(addon_prefs, "cullCave")
        col_2.prop(addon_prefs, "shell")
        col_2.prop(addon_prefs, "useGreedyMesh")

        if addon_prefs.exportLightBlock:
            row_Light_Block = box_main_settings.row()
            row_Light_Block.prop(addon_prefs, "exportLightBlockOnly")
            row_Light_Block.prop(addon_prefs, "lightBlockSize")


        row_exportFullModel = box_main_settings.row()
        row_exportFullModel.prop(addon_prefs, "exportFullModel")
        if addon_prefs.exportFullModel:
            row_exportFullModel.prop(addon_prefs, "partitionSize")
        
        
        box_lod = layout.box()
        box_lod.prop(addon_prefs, "Max_LOD_Level")
        if int(addon_prefs.Max_LOD_Level) > 0:
            split_lod = box_lod.split(factor=0.5)

            col_1_lod = split_lod.column()
            col_1_lod.prop(addon_prefs, "LOD0renderDistance")
            if int(addon_prefs.Max_LOD_Level) > 1:
                col_1_lod.prop(addon_prefs, "LOD1renderDistance")
                if int(addon_prefs.Max_LOD_Level) > 2:
                    col_1_lod.prop(addon_prefs, "LOD2renderDistance")
                    if int(addon_prefs.Max_LOD_Level) > 3:
                        col_1_lod.prop(addon_prefs, "LOD3renderDistance")
            # ===================================================
            col_2_lod = split_lod.column()
            col_2_lod.prop(addon_prefs, "useBiomeColors")
            col_2_lod.prop(addon_prefs, "useUnderwaterLOD")
            if addon_prefs.isLODAutoCenter:
                col_2_lod.prop(addon_prefs, "isLODAutoCenter")
            else:
                box_lodcenter = col_2_lod.box()
                col_lodcenter = box_lodcenter.column()
                col_lodcenter.prop(addon_prefs, "isLODAutoCenter")
                col_lodcenter.prop(addon_prefs, "LODCenterX")
                col_lodcenter.prop(addon_prefs, "LODCenterZ")

            if addon_prefs.no_lod_blocks:
                box_nolod = col_2_lod.box()
                col_nolod = box_nolod.column()
                col_nolod.prop(addon_prefs, "no_lod_blocks")
                col_nolod.operator("crafter.open_no_lod_blocks_folder",icon="FILE_FOLDER")
            else:
                col_2_lod.prop(addon_prefs, "no_lod_blocks")

        #无版本隔离选择
        if self.version == "":
            box_undivided = layout.box()
            box_undivided.label(text="Versions")
            row_undivided = box_undivided.row()
            row_undivided.template_list("VIEW3D_UL_CrafterUndividedVersions","",addon_prefs,"Undivided_Vsersions_List",addon_prefs,"Undivided_Vsersions_List_index",rows=1,)

        #资源包列表
        box_resources_main = layout.box()
        box_resources_main.prop(addon_prefs, "Game_Resources")
        box_resources = box_resources_main.box()
        if addon_prefs.Game_Resources:
            if len(addon_prefs.Game_Resources_List) + len(addon_prefs.Game_unuse_Resources_List) > 0:
                box_resources.label(text="Resources List",icon="NODE_COMPOSITING")
                row_resources_use = box_resources.row()
                col_use_list = row_resources_use.column()
                col_use_list.template_list("VIEW3D_UL_CrafterGameResources", "", addon_prefs, "Game_Resources_List", addon_prefs, "Game_Resources_List_index", rows=1)
                col_use_ops = row_resources_use.column(align=True)
                if len (addon_prefs.Game_Resources_List) > 0:
                    col_use_ops.operator("crafter.ban_game_resource", text="", icon="REMOVE")
                if len (addon_prefs.Game_Resources_List) > 1:
                    col_use_ops.separator()
                    col_use_ops.operator("crafter.up_game_resource", text="", icon="TRIA_UP")
                    col_use_ops.operator("crafter.down_game_resource", text="", icon="TRIA_DOWN")

                if len (addon_prefs.Game_unuse_Resources_List) > 0:
                    row_resources_unuse = box_resources.row()
                    col_unuse_list = row_resources_unuse.column()
                    col_unuse_list.template_list("VIEW3D_UL_CrafterGameUnuseResources", "", addon_prefs, "Game_unuse_Resources_List", addon_prefs, "Game_unuse_Resources_List_index", rows=1)
                    col_unuse_ops = row_resources_unuse.column()
                    col_unuse_ops.operator("crafter.use_game_resource", text="", icon="ADD")
        else:
            box_resources.label(text="Resources List",icon="PACKAGE")
            row_Plans_List = box_resources.row()
            row_Plans_List.template_list("VIEW3D_UL_CrafterResources", "", addon_prefs, "Resources_Plans_List", addon_prefs, "Resources_Plans_List_index", rows=1)
            col_Plans_List_ops = row_Plans_List.column()
            col_Plans_List_ops.operator("crafter.open_resources_plans",icon="FILE_FOLDER",text="")
            col_Plans_List_ops.operator("crafter.reload_all",icon="FILE_REFRESH",text="")

            if len(addon_prefs.Resources_List) > 0:
                box_resource = box_resources_main.box()
                box_resource.label(text="Resource",icon="NODE_COMPOSITING")
                row_Resources_List = box_resource.row()
                row_Resources_List.template_list("VIEW3D_UL_CrafterResourcesInfo", "", addon_prefs, "Resources_List", addon_prefs, "Resources_List_index", rows=1)
                if len(addon_prefs.Resources_List) > 1:
                    col_Resources_List_ops = row_Resources_List.column(align=True)
                    col_Resources_List_ops.operator("crafter.up_resource",icon="TRIA_UP",text="")
                    col_Resources_List_ops.operator("crafter.down_resource",icon="TRIA_DOWN",text="")


    def invoke(self, context, event):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        #获取世界路径，检测路径合法性
        bpy.ops.crafter.reload_all()
        bpy.ops.crafter.reload_resources()
        worldPath = os.path.normpath(addon_prefs.World_Path)
        dir_saves = os.path.dirname(worldPath)
        dir_level_dat = os.path.join(worldPath, "level.dat")
        if not os.path.exists(dir_level_dat):
            self.report({'ERROR'}, "It's not a world path!")
            return {"CANCELLED"}
        
        #初始化路径
        jarPath = ""
        name_version = ""
        addon_prefs.is_Game_Path = True
        #计算游戏文件路径
        dir_saves = os.path.dirname(worldPath)
        dir_back_saves = os.path.dirname(dir_saves)

        if os.path.basename(dir_back_saves) == ".minecraft":
            dir_dot_minecraft = dir_back_saves
            dir_versions = os.path.join(dir_dot_minecraft,"versions")
            list_versions = os.listdir(dir_versions)
            if len(list_versions) == 0:
                self.report({'ERROR'}, "Can't find any versions!")
                return {"CANCELLED"}
            reload_Undivided_Vsersions(context=context,dir_versions=dir_versions)
            dir_resourcepacks = os.path.join(dir_dot_minecraft,"resourcepacks")
            dir_mods = os.path.join(dir_dot_minecraft,"mods")
        else:
            dir_version = dir_back_saves_2_dir_version(dir_back_saves)
            name_version = os.path.basename(dir_version)
            jarPath = dir_version_2_dir_jar(dir_version)
            if not os.path.exists(jarPath):
                addon_prefs.is_Game_Path = False
                self.worldPath = worldPath
                return context.window_manager.invoke_props_dialog(self)
            else:
                dir_mods = os.path.join(dir_back_saves, "mods")
                dir_resourcepacks = os.path.join(dir_back_saves, "resourcepacks")
                dir_versions = os.path.dirname(dir_version)
                dir_dot_minecraft = os.path.dirname(dir_versions)
        #储存信息到self
        self.worldPath = worldPath
        self.jarPath = jarPath
        self.modsPath = dir_mods

        self.save = os.path.basename(worldPath)
        self.version = name_version
        self.dot_minecraftPath = dir_dot_minecraft
        #获取资源包
        if os.path.exists(dir_resourcepacks):
            list_resourcepacks = os.listdir(dir_resourcepacks)
        else:
            list_resourcepacks = []
        dir_json_resourcepacks = os.path.join(dir_cafter_data, "resourcepacks.json")
        
        if os.path.exists(dir_json_resourcepacks):
            with open(dir_json_resourcepacks, "r", encoding="utf-8") as file:
                json_resourcepacks = json.load(file)
        else:
            json_resourcepacks = {}
        for dir_saves_old in list(json_resourcepacks):
            if not os.path.exists(dir_saves_old):
                del json_resourcepacks[dir_saves_old]
        json_resourcepacks.setdefault(dir_saves,[[],[]])
        resourcepacks_use_copy = json_resourcepacks[dir_saves][0].copy()
        resourcepacks_unuse_copy = json_resourcepacks[dir_saves][1].copy()
        json_resourcepacks[dir_saves][0] =[]
        json_resourcepacks[dir_saves][1] =[]

        for resourcepack in list_resourcepacks:
            dir_resourcepack = os.path.join(dir_resourcepacks, resourcepack)
            if (not dir_resourcepack in resourcepacks_use_copy) and (not dir_resourcepack in resourcepacks_unuse_copy):
                if dir_resourcepack.endswith(".zip"):
                    json_resourcepacks[dir_saves][0].append(dir_resourcepack)
        for dir_resourcepack in resourcepacks_use_copy:
            if os.path.exists(dir_resourcepack):
                json_resourcepacks[dir_saves][0].append(dir_resourcepack)
        for dir_resourcepack in resourcepacks_unuse_copy:
            if os.path.exists(dir_resourcepack):
                json_resourcepacks[dir_saves][1].append(dir_resourcepack)
    
        with open (dir_json_resourcepacks, "w", encoding="utf-8") as file:
            json.dump(json_resourcepacks, file, indent=4)
        
        bpy.ops.crafter.reload_game_resources()
        
        return context.window_manager.invoke_props_dialog(self)
    
    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        imported_time = str(context.scene.Crafter_import_time)
        if context.active_object:
            bpy.ops.object.mode_set(mode='OBJECT')
        if (not addon_prefs.is_Game_Path) or addon_prefs.Custom_Path:

            addon_prefs.Game_Resources_List.clear()
            worldPath = self.worldPath
            if not os.path.exists(addon_prefs.Custom_Jar_Path):
                self.report({'ERROR'}, "Path not found!")
                return {"CANCELLED"}
            elif not addon_prefs.Custom_Jar_Path.endswith(".jar"):
                self.report({'ERROR'}, "It's not a jar file!")
                return {"CANCELLED"}
            jarPath = addon_prefs.Custom_Jar_Path
            versionJsonPath = jarPath[:-3]+"json"
            if addon_prefs.use_Custom_mods_Path:
                if not os.path.exists(addon_prefs.Custom_mods_Path):
                    self.report({'ERROR'}, "Path not found!")
                    return {"CANCELLED"}
                if not os.path.isdir(addon_prefs.Custom_mods_Path):
                    self.report({'ERROR'}, "It's not a folder!")
                    return {"CANCELLED"}
                modsPath = addon_prefs.Custom_mods_Path
            else:
                modsPath = "None"
        else:
            if self.version == "":
                undivided = True
                dir_version = addon_prefs.Undivided_Vsersions_List[addon_prefs.Undivided_Vsersions_List_index].name
                version = os.path.basename(dir_version)
                jarPath = os.path.join(dir_version, version + ".jar")
            else:
                undivided = False
                jarPath = self.jarPath
                version = self.version
            worldPath = self.worldPath
            modsPath = self.modsPath
            if not os.path.exists(modsPath):
                modsPath = "None"
            dir_version = os.path.dirname(jarPath)
            versionJsonPath = os.path.join(dir_version, version + ".json")

            save = self.save
            dot_minecraftPath = self.dot_minecraftPath
        # 获取资源包列表
        resourcepacksPaths = []
        if addon_prefs.Game_Resources:
            for resourcepacksPath in addon_prefs.Game_Resources_List:
                resourcepacksPaths.append(resourcepacksPath.name)
        else:
            dir_resourcepacks = os.path.join(dir_resourcepacks_plans, addon_prefs.Resources_Plans_List[addon_prefs.Resources_Plans_List_index].name)
            dir_crafter_json = os.path.join(dir_resourcepacks, "crafter.json")

            addon_prefs.Resources_List.clear()
            with open(dir_crafter_json, "r", encoding="utf-8") as file:
                json_crafter = json.load(file)
            for resource in json_crafter:
                resourcepacksPaths.append(os.path.join(dir_resourcepacks, resource + ".zip"))
        # 获取无lod方块列表
        list_no_lod_blocks = []
        if addon_prefs.no_lod_blocks:
            list_files = os.listdir(dir_no_lod_blocks)
            for file in list_files:
                if file.endswith(".json"):
                    with open(os.path.join(dir_no_lod_blocks, file), "r", encoding="utf-8") as file:
                        json_no_lod_blocks = json.load(file)
                        for block in json_no_lod_blocks:
                            if block not in list_no_lod_blocks:
                                list_no_lod_blocks.append(block)

        #写入conifg.json
        point_cloud_mode = addon_prefs.Point_Cloud_Mode
        if point_cloud_mode:
            status = 2
        else:
            status = 1
            
        worldconfig = {
            "worldPath": worldPath,
            "selectedDimension": addon_prefs.Dimensions_List[addon_prefs.Dimensions_List_index].name,
            "jarPath": jarPath,
            "versionJsonPath": versionJsonPath,
            "modsPath": modsPath,
            "resourcepacksPaths":resourcepacksPaths,
            "minX": min(addon_prefs.XYZ_1[0], addon_prefs.XYZ_2[0]),
            "maxX": max(addon_prefs.XYZ_1[0], addon_prefs.XYZ_2[0]),
            "minY": min(addon_prefs.XYZ_1[1], addon_prefs.XYZ_2[1]),
            "maxY": max(addon_prefs.XYZ_1[1], addon_prefs.XYZ_2[1]),
            "minZ": min(addon_prefs.XYZ_1[2], addon_prefs.XYZ_2[2]),
            "maxZ": max(addon_prefs.XYZ_1[2], addon_prefs.XYZ_2[2]),
            "status": status,
            "useChunkPrecision":addon_prefs.useChunkPrecision,
            "keepBoundary":addon_prefs.keepBoundary,
            "strictDeduplication":addon_prefs.strictDeduplication,
            "cullCave":addon_prefs.cullCave,
            "exportLightBlock":addon_prefs.exportLightBlock,
            "exportLightBlockOnly":addon_prefs.exportLightBlockOnly,
            "lightBlockSize":addon_prefs.lightBlockSize,
            "allowDoubleFace":addon_prefs.allowDoubleFace,
            "exportFullModel":not addon_prefs.exportFullModel,
            "partitionSize":addon_prefs.partitionSize,
            "maxTasksPerBatch":addon_prefs.maxTasksPerBatch,
            "activeLOD":int(addon_prefs.Max_LOD_Level) > 0,
            "activeLOD2":int(addon_prefs.Max_LOD_Level) > 1,
            "activeLOD3":int(addon_prefs.Max_LOD_Level) > 2,
            "activeLOD4":int(addon_prefs.Max_LOD_Level) > 3,
            "useBiomeColors":addon_prefs.useBiomeColors,
            "useUnderwaterLOD":addon_prefs.useUnderwaterLOD,
            "useGreedyMesh":addon_prefs.useGreedyMesh,
            "isLODAutoCenter":addon_prefs.isLODAutoCenter,
            "LODCenterX":addon_prefs.LODCenterX,
            "LODCenterZ":addon_prefs.LODCenterZ,
            "lod1Blocks":list_no_lod_blocks,
            "LOD0renderDistance":addon_prefs.LOD0renderDistance,
            "LOD1renderDistance":addon_prefs.LOD1renderDistance,
            "LOD2renderDistance":addon_prefs.LOD2renderDistance,
            "LOD3renderDistance":addon_prefs.LOD3renderDistance,
            "solid": 0,
        }

        dir_config = os.path.join(dir_importer, "config")
        dir_json_config = os.path.join(dir_config, "config.json")

        with open(dir_json_config, 'w', encoding='utf-8') as config:
            json.dump(worldconfig, config, indent=4)

        #删去之前导出的obj
        dir_exe_importer = os.path.join(dir_importer, "WorldImporter.exe")
        for file in os.listdir(dir_importer):
            if file.endswith(".obj"):
                os.remove(os.path.join(dir_importer, file))
                
        prepared_time = time.perf_counter()
        #生成obj

# ==================================================================================
        ##旧的exe唤起命令

        ##后来白给修好exe的问题后忠城发现新唤起方式的shell模式性能比旧版高，所以改用新的唤起方式

        #try:
        ##在新的进程中运行WorldImporter.exe
        #CREATE_NEW_PROCESS_GROUP = 0x00000200
        #DETACHED_PROCESS = 0x00000008
        #process = subprocess.Popen(
        #[dir_exe_importer],
        #cwd=dir_importer,
        #creationflags=CREATE_NEW_PROCESS_GROUP | DETACHED_PROCESS,
        #shell=addon_prefs.shell
        #)
        ##等待进程结束
        #process.wait()
        #except Exception as e:
        #return {"CANCELLED"}

# ==================================================================================
        run_as_admin_and_wait(dir_exe_importer,dir_importer,shell = addon_prefs.shell)
# ==================================================================================

        #导入obj
        have_obj = False
        real_name_dic = {}
        material_should_delete = []
        before_objects = set(bpy.data.objects)#记录当前场景最初对象
        for file in os.listdir(dir_importer):
            if file.endswith(".obj"):
                pre_import_objects = set(bpy.data.objects)#记录当前场景中的所有对象
                
                bpy.ops.wm.obj_import(filepath=os.path.join(dir_importer, file))
                bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
                have_obj = True
                
                imported_objects = list(set(bpy.data.objects) - pre_import_objects)#计算新增对象
                for obj in imported_objects:
                    for i in range(len(obj.data.materials)):
                        material = obj.data.materials[i]
                        if material.name.startswith("color#"):
                            if len(material.name) > len_color_jin:
                                real_material_name = fuq_bl_dot_number(material.name)
                            else:
                                real_material_name = material.name
                        else:
                            real_material_name = fuq_bl_dot_number(material.name)
                        if real_material_name in real_name_dic:
                            obj.data.materials[i] = bpy.data.materials[real_name_dic[real_material_name]]
                            material_should_delete.append(material.name)
                        else:
                            real_name_dic[real_material_name] = material.name
                    add_to_mcmts_collection(object=obj,context=context)
                    add_to_crafter_mcmts_collection(object=obj,context=context)
                    add_Crafter_time(obj=obj)
                    #定位到视图
                    view_2_active_object(context)

        for material in material_should_delete:
            bpy.data.materials.remove(bpy.data.materials[material])
        if not have_obj:
            self.report({'ERROR'}, "WorldImporter didn't export obj!")
            return {"CANCELLED"}
        
        #若不存在，则导入Crafter-Moving_texture节点组
        if not "Crafter-Moving_texture" in bpy.data.node_groups:
            with bpy.data.libraries.load(dir_blend_append, link=False) as (data_from, data_to):
                data_to.node_groups = ["Crafter-Moving_texture"]
            bpy.data.node_groups["Crafter-Moving_texture"].use_fake_user = True
        #若不存在，则导入群系着色纹理节点
        if not "Crafter-biomeTex" in bpy.data.node_groups:
            node_groups_use_fake_user = ["Crafter-biomeTex"]
            with bpy.data.libraries.load(dir_blend_append, link=False) as (data_from, data_to):
                data_to.node_groups = [name for name in data_from.node_groups if name in node_groups_use_fake_user]
            for node_group in node_groups_use_fake_user:
                bpy.data.node_groups[node_group].use_fake_user = True
        #复制并修改Crafter-biomeTex
        dir_biomeTex = os.path.join(dir_importer, "biomeTex")
        dir_biomeTex_num = os.path.join(dir_biomeTex, imported_time)
        os.makedirs(dir_biomeTex_num)
        for file in os.listdir(dir_biomeTex): #复制群系颜色至新文件夹
            if file.endswith(".png"):
                shutil.copy(os.path.join(dir_biomeTex, file), os.path.join(dir_biomeTex_num, file))
        node_group_biomeTex = bpy.data.node_groups["Crafter-biomeTex"]
        copyname = "Crafter-biomeTex_" + imported_time
        node_group_biomeTex_copy = node_group_biomeTex.copy()
        node_group_biomeTex_copy.name = copyname
        for node in node_group_biomeTex_copy.nodes:
            if node.type == "TEX_IMAGE":
                try:
                    node.image = bpy.data.images.load(os.path.join(dir_biomeTex_num, fuq_bl_dot_number(node.image.name)))
                except:
                    pass
            if node.type == "GROUP":
                node.inputs["min X"].default_value = min(addon_prefs.XYZ_1[0],addon_prefs.XYZ_2[0])
                node.inputs["min Y"].default_value = min(0 - addon_prefs.XYZ_1[2],0 - addon_prefs.XYZ_2[2]) - 1
                node.inputs["max X"].default_value = 1 + max(addon_prefs.XYZ_1[0],addon_prefs.XYZ_2[0])
                node.inputs["max Y"].default_value = max(0 - addon_prefs.XYZ_1[2],0 - addon_prefs.XYZ_2[2])

        #查找所需节点
        for name_material in real_name_dic.values():
            if name_material.startswith("color#"):
                continue
            material = bpy.data.materials[name_material]
            nodes = material.node_tree.nodes
            links = material.node_tree.links
            nodes_wait_remove = []
            node_tex_base = None
            for node in nodes:
                if node.type == "OUTPUT_MATERIAL":
                    if node.target == "EEVEE":
                        node_output_EEVEE = node
                    if node.target == "ALL":
                        node_output_EEVEE = node
                elif node.type == "TEX_IMAGE":
                    if node_tex_base != None:
                        nodes_wait_remove.append(node)
                    else:
                        node_tex_base = node
                        node.interpolation = "Closest"
            for node in nodes_wait_remove:
                nodes.remove(node)
            #添加群系着色纹理,PBR、法线纹理
            node_liomeTex = nodes.new("ShaderNodeGroup")
            node_liomeTex.location = (node_output_EEVEE.location.x - 400, node_output_EEVEE.location.y - 550)
            node_liomeTex.node_tree = node_group_biomeTex_copy
            if node_tex_base != None:
                load_normal_and_PBR(node_tex_base=node_tex_base, nodes=nodes, links=links,)
                nodes.active = node_tex_base
        try:
            bpy.ops.file.pack_all()
        except Exception as e:
            print(e)
            
        #完成导入
        world_imported_time = time.perf_counter()
        #定位到视图
        new_objects = list(set(bpy.data.objects) - before_objects)
        for object in new_objects:
            if object.type == "MESH":
                object.select_set(True)
        view_2_active_object(context)
                            
        #保存历史世界
        dir_json_history_worlds = os.path.join(dir_cafter_data, "history_worlds.json")
        #读取json，若不存在则创建一个空的json文件
        if os.path.exists(dir_json_history_worlds):
            with open(dir_json_history_worlds, 'r', encoding='utf-8') as file:
                json_history_worlds = json.load(file)
        else:
            json_history_worlds = {}
        if (not addon_prefs.is_Game_Path) or addon_prefs.Custom_Path:
            pass
        else:
            #根据是否隔离，按不同方式保存历史记录
            if undivided:
                json_history_worlds.setdefault(dot_minecraftPath, [{}])
                undivided_list = json_history_worlds[dot_minecraftPath][0].setdefault(save,{})
                json_history_settings = undivided_list.setdefault("settings", [])
                undivided_list["version"] = version
            else:
                json_history_worlds.setdefault(dot_minecraftPath, {})
                json_history_worlds[dot_minecraftPath].setdefault(version, {})
                json_history_worlds[dot_minecraftPath][version].setdefault(save, [])
                json_history_settings = json_history_worlds[dot_minecraftPath][version][save]
            world_settings_now = [list(addon_prefs.XYZ_1), list(addon_prefs.XYZ_2)]
            if len(json_history_settings) == 0:
                json_history_settings.append(None)
                json_history_settings[0] = world_settings_now
            else:
                for i in range (len(json_history_settings)):
                    if json_history_settings[i] == world_settings_now:
                        for j in range (i,0,-1):
                            json_history_settings[j] = json_history_settings[j - 1]
                        json_history_settings[0] = world_settings_now
                        break
                else:
                    if len(json_history_settings) < 10:
                        json_history_settings.append(None)
                    for i in range (len(json_history_settings) - 1,0,-1):
                        json_history_settings[i] = json_history_settings[i - 1]
                    json_history_settings[0] = world_settings_now
                    
            #保存到json文件
            with open(dir_json_history_worlds, 'w', encoding='utf-8') as file:
                json.dump(json_history_worlds, file, indent=4)

            #保存最近世界
            world_now = f"{save}|{version}|{dot_minecraftPath}"
            dir_json_latest_worlds = os.path.join(dir_cafter_data, "latest_worlds.json")
            if os.path.exists(dir_json_latest_worlds):
                with open(dir_json_latest_worlds, 'r', encoding='utf-8') as file:
                    json_latest_worlds = json.load(file)
            else:
                json_latest_worlds = []
            if len(json_latest_worlds) == 0:
                json_latest_worlds.append(None)
                json_latest_worlds[0] = world_now
            else:
                for i in range (len(json_latest_worlds)):
                    if json_latest_worlds[i] == world_now:
                        for j in range (i,0,-1):
                            json_latest_worlds[j] = json_latest_worlds[j - 1]
                        json_latest_worlds[0] = world_now
                        break
                else:
                    if len(json_latest_worlds) < 5:
                        json_latest_worlds.append(None)
                    for i in range (len(json_latest_worlds) - 1,0,-1):
                        json_latest_worlds[i] = json_latest_worlds[i - 1]
                    json_latest_worlds[0] = world_now
            #保存到json文件
            with open(dir_json_latest_worlds, 'w', encoding='utf-8') as file:
                json.dump(json_latest_worlds, file, indent=4)
        #归零index
        addon_prefs.Latest_World_List_index = 0
        addon_prefs.History_World_Settings_List_index = 0
        #增加Crafter_import_time计数
        context.scene.Crafter_import_time += 1

        report_text = i18n("Import time: ") + str(world_imported_time - prepared_time)[:6] + "s"

        if addon_prefs.Auto_Load_Material:
            material_start_time = time.perf_counter()
            bpy.ops.crafter.load_material()
            material_used_time = time.perf_counter() - material_start_time
            report_text = report_text + i18n(", Material time: ") + str(material_used_time)[:6] + "s"

        self.report({'INFO'},report_text)

        return {'FINISHED'}

class VIEW3D_OT_CrafterImportSolidArea(bpy.types.Operator):#导入可编辑区域 ==========未完善==========
    bl_label = "Import Solid Area"
    bl_idname = "crafter.import_solid_area"
    bl_description = "Import the solid area"
    bl_options = {'REGISTER', 'UNDO'}
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return False#完善后开启

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        #这里应该还要删除物体在坐标内的顶点，但时间紧任务重，稍后再写
        addon_prefs.solid = 1
        return {'FINISHED'}

# ==================== 使用历史世界 ====================

class VIEW3D_OT_UseCrafterHistoryWorlds(bpy.types.Operator):
    bl_label = "History Worlds"
    bl_idname = "crafter.use_history_worlds"
    bl_description = "To use the history world settings"

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
    
    def invoke(self, context, event):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        #补全history_worlds.json
        dir_json_history_worlds = os.path.join(dir_cafter_data, "history_worlds.json")
        dir_json_latest_worlds = os.path.join(dir_cafter_data, "latest_worlds.json")
        
        if not os.path.exists(dir_json_history_worlds):
            self.report({ 'ERROR' }, "Haven't history worlds")
            return {'CANCELLED'}
        if os.path.exists(dir_json_latest_worlds):
            with open(dir_json_latest_worlds, 'r', encoding='utf-8') as file:
                json_latest_worlds = json.load(file)
        else:
            json_latest_worlds = []
        #补全history_worlds.json
        with open(dir_json_history_worlds, 'r', encoding='utf-8') as file:
            json_history_worlds = json.load(file)
        if type(json_history_worlds) == list:
            json_history_worlds = {}
        #整理json
        for root in list(json_history_worlds):
            #地址不存在则移除该root
            if not os.path.exists(root):
                del json_history_worlds[root]
                continue
            if type(json_history_worlds[root]) == list:#判断是否版本隔离
                for save in list(json_history_worlds[root][0]):
                    #存档不存在则移除该save
                    if not os.path.exists(os.path.join(root, "saves", save)):
                        del json_history_worlds[root][0][save]
                        continue
                    #加入新找到的save
                    for save in os.listdir(os.path.join(root, "saves")):
                        if os.path.isdir(os.path.join(root, "saves", save)):
                            json_history_worlds[root][0].setdefault(save, {})
            else:
                dir_versions = dir_root_2_dir_versions(dir_root=root)
                #添加版本
                for version in os.listdir(dir_versions):
                    if os.path.exists(os.path.join(dir_versions, version, "saves")):
                        json_history_worlds[root].setdefault(version, {})
                for version in list(json_history_worlds[root]):
                    #版本不存在则移除该version
                    if not os.path.exists(os.path.join(dir_versions, version)):
                        del json_history_worlds[root][version]
                        continue
                    #存档不存在则移除该save
                    dir_version = os.path.join(dir_versions, version)
                    dir_saves = os.path.join(dir_version, "saves")
                    for save in list(json_history_worlds[root][version]):
                        if not os.path.exists(os.path.join(dir_saves, save)):
                            del json_history_worlds[root][version][save]
                            continue
                    #加入新找到的save
                    if os.path.exists(dir_saves):
                        for save in os.listdir(dir_saves):
                            if os.path.isdir(os.path.join(dir_saves, save)):
                                json_history_worlds[root][version].setdefault(save, [])
        #清理最近世界历史记录
        for i in range(len(json_latest_worlds)-1,-1,-1):
            world = json_latest_worlds[i].split("|")
            if not world[2] in json_history_worlds:
                del json_latest_worlds[i]
                continue
            if type(json_history_worlds[world[2]]) == list:#判断是否版本隔离
                if not world[0] in json_history_worlds[world[2]][0]:
                    del json_latest_worlds[i]
                    continue
            else:#版本隔离
                if not world[1] in json_history_worlds[world[2]]:
                    del json_latest_worlds[i]
                    continue
                if not world[0] in json_history_worlds[world[2]][world[1]]:
                    del json_latest_worlds[i]
                    continue

        with open(dir_json_history_worlds, 'w', encoding='utf-8') as file:
            json.dump(json_history_worlds, file, indent=4)

        with open(dir_json_latest_worlds, 'w', encoding='utf-8') as file:
            json.dump(json_latest_worlds, file, indent=4)
                
        #刷新list
        if len(json_latest_worlds) > 0:
            bpy.ops.crafter.reload_latest_worlds_list()
        else:
            bpy.ops.crafter.reload_history_worlds_list()
                        
        return context.window_manager.invoke_props_dialog(self)
    
    def draw(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        layout = self.layout

        if len(addon_prefs.Latest_World_List) > 0:
            row_world = min(len(addon_prefs.Latest_World_List),4)
            layout.template_list("VIEW3D_UL_CrafterLatestWorldList", "", addon_prefs, "Latest_World_List", addon_prefs, "Latest_World_List_index", rows=row_world)

        if len(addon_prefs.History_World_Roots_List) > 0:
            layout.label(text="Minecraft Saves")
            row_root = min(len(addon_prefs.History_World_Roots_List),4)
            layout.template_list("VIEW3D_UL_CrafterHistoryWorldRootsList", "", addon_prefs, "History_World_Roots_List", addon_prefs, "History_World_Roots_List_index", rows=row_root)
        if addon_prefs.is_Undivided:
            if len(addon_prefs.Undivided_Vsersions_List) > 0:
                row_version = min(len(addon_prefs.Undivided_Vsersions_List),4)
                layout.template_list("VIEW3D_UL_CrafterUndividedVersions", "", addon_prefs, "Undivided_Vsersions_List", addon_prefs, "Undivided_Vsersions_List_index", rows=row_version)
            pass
        else:
            if len(addon_prefs.History_World_Versions_List) > 0:
                row_version = min(len(addon_prefs.History_World_Versions_List),4)
                layout.template_list("VIEW3D_UL_CrafterHistoryWorldVersionsList", "", addon_prefs, "History_World_Versions_List", addon_prefs, "History_World_Versions_List_index", rows=row_version)
        if len(addon_prefs.History_World_Saves_List) > 0:
            row_save = min(len(addon_prefs.History_World_Saves_List),4)
            layout.template_list("VIEW3D_UL_CrafterHistoryWorldSavesList", "", addon_prefs, "History_World_Saves_List", addon_prefs, "History_World_Saves_List_index", rows=row_save)
        if len(addon_prefs.History_World_Settings_List) > 0:
            layout.template_list("VIEW3D_UL_CrafterHistoryWorldSettingsList", "", addon_prefs, "History_World_Settings_List", addon_prefs, "History_World_Settings_List_index", rows=1,maxrows=10)

    def execute(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        if len(addon_prefs.History_World_Saves_List) > 0:
            addon_prefs.World_Path = get_dir_save(context)
            if len(addon_prefs.History_World_Settings_List) > 0:
                settings = addon_prefs.History_World_Settings_List[addon_prefs.History_World_Settings_List_index].name
                setting = settings.split(" ")
                addon_prefs.XYZ_1 = (int(setting[0]),int(setting[1]),int(setting[2]))
                addon_prefs.XYZ_2 = (int(setting[3]),int(setting[4]),int(setting[5]))
                
        return {'FINISHED'}

# ==================== Ban游戏资源包 ====================

class VIEW3D_OT_CrafterBanGameResource(bpy.types.Operator):
    bl_label = "Ban resource"    
    bl_idname = "crafter.ban_game_resource"
    bl_description = " "

    @classmethod
    def poll(cls, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        dir_json_resourcepacks = os.path.join(dir_cafter_data, "resourcepacks.json")
        with open(dir_json_resourcepacks, "r", encoding="utf-8") as file:
            json_resourcepacks = json.load(file)

        worldPath = os.path.normpath(addon_prefs.World_Path)
        dir_saves = os.path.dirname(worldPath)
        use_list = json_resourcepacks[dir_saves][0].copy()
        unuse_list = json_resourcepacks[dir_saves][1].copy()
        json_resourcepacks[dir_saves][0] = []
        json_resourcepacks[dir_saves][1] = []
        for i in range(len(use_list)):
            if i == addon_prefs.Game_Resources_List_index:
                continue
            json_resourcepacks[dir_saves][0].append(use_list[i])
        json_resourcepacks[dir_saves][1].append(addon_prefs.Game_Resources_List[addon_prefs.Game_Resources_List_index].name)
        for resource in unuse_list:
            json_resourcepacks[dir_saves][1].append(resource)

        with open(dir_json_resourcepacks, 'w', encoding='utf-8') as file:
            json.dump(json_resourcepacks, file, indent=4)

        if (addon_prefs.Game_Resources_List_index > 0) and (addon_prefs.Game_Resources_List_index == len(addon_prefs.Game_Resources_List) - 1):
            addon_prefs.Game_Resources_List_index -= 1

        bpy.ops.crafter.reload_game_resources()

        return {'FINISHED'}

# ==================== 使用游戏资源包 ====================

class VIEW3D_OT_CrafterUseGameResource(bpy.types.Operator):
    bl_label = "Use resource"    
    bl_idname = "crafter.use_game_resource"
    bl_description = " "

    @classmethod
    def poll(cls, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        dir_json_resourcepacks = os.path.join(dir_cafter_data, "resourcepacks.json")
        with open(dir_json_resourcepacks, "r", encoding="utf-8") as file:
            json_resourcepacks = json.load(file)

        worldPath = os.path.normpath(addon_prefs.World_Path)
        dir_saves = os.path.dirname(worldPath)
        use_list = json_resourcepacks[dir_saves][0].copy()
        unuse_list = json_resourcepacks[dir_saves][1].copy()
        json_resourcepacks[dir_saves][0] = []
        json_resourcepacks[dir_saves][1] = []
        json_resourcepacks[dir_saves][0].append(addon_prefs.Game_unuse_Resources_List[addon_prefs.Game_unuse_Resources_List_index].name)
        for resource in use_list:
            json_resourcepacks[dir_saves][0].append(resource)
        for i in range(len(unuse_list)):
            if i == addon_prefs.Game_unuse_Resources_List_index:
                continue
            json_resourcepacks[dir_saves][1].append(unuse_list[i])

        with open(dir_json_resourcepacks, 'w', encoding='utf-8') as file:
            json.dump(json_resourcepacks, file, indent=4)

        if (addon_prefs.Game_unuse_Resources_List_index > 0) and (addon_prefs.Game_unuse_Resources_List_index == len(addon_prefs.Game_unuse_Resources_List) - 1):
            addon_prefs.Game_unuse_Resources_List_index -= 1

        bpy.ops.crafter.reload_game_resources()

        return {'FINISHED'}


# ==================== 游戏资源包优先级 ====================

class VIEW3D_OT_CrafterUpGameResource(bpy.types.Operator):#提高 游戏资源包 优先级
    bl_label = "Up resource's priority"    
    bl_idname = "crafter.up_game_resource"
    bl_description = " "

    @classmethod
    def poll(cls, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        return addon_prefs.Game_Resources_List_index > 0

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        dir_json_resourcepacks = os.path.join(dir_cafter_data, "resourcepacks.json")
        with open(dir_json_resourcepacks, "r", encoding="utf-8") as file:
            json_resourcepacks = json.load(file)

        worldPath = os.path.normpath(addon_prefs.World_Path)
        dir_saves = os.path.dirname(worldPath)
        target_name = addon_prefs.Game_Resources_List[addon_prefs.Game_Resources_List_index].name
        for i in range(len(json_resourcepacks[dir_saves][0])):
            if json_resourcepacks[dir_saves][0][i] == target_name:
                if i > 0:
                    json_resourcepacks[dir_saves][0][i], json_resourcepacks[dir_saves][0][i - 1] = json_resourcepacks[dir_saves][0][i - 1], json_resourcepacks[dir_saves][0][i]
                    addon_prefs.Game_Resources_List_index -= 1
                    break
        with open(dir_json_resourcepacks, 'w', encoding='utf-8') as file:
            json.dump(json_resourcepacks, file, indent=4)
        bpy.ops.crafter.reload_game_resources()

        return {'FINISHED'}

class VIEW3D_OT_CrafterDownGameResource(bpy.types.Operator):#降低 游戏资源包 优先级
    bl_label = "Down resource's priority"    
    bl_idname = "crafter.down_game_resource"
    bl_description = " "

    @classmethod
    def poll(cls, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        return addon_prefs.Game_Resources_List_index < len(addon_prefs.Game_Resources_List) - 1

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        dir_json_resourcepacks = os.path.join(dir_cafter_data, "resourcepacks.json")
        with open(dir_json_resourcepacks, "r", encoding="utf-8") as file:
            json_resourcepacks = json.load(file)

        worldPath = os.path.normpath(addon_prefs.World_Path)
        dir_saves = os.path.dirname(worldPath)
        target_name = addon_prefs.Game_Resources_List[addon_prefs.Game_Resources_List_index].name
        for i in range(len(json_resourcepacks[dir_saves][0])):
            if json_resourcepacks[dir_saves][0][i] == target_name:
                if i < len(addon_prefs.Game_Resources_List) - 1:
                    json_resourcepacks[dir_saves][0][i], json_resourcepacks[dir_saves][0][i + 1] = json_resourcepacks[dir_saves][0][i + 1], json_resourcepacks[dir_saves][0][i]
                    addon_prefs.Game_Resources_List_index += 1
                    break

        with open(dir_json_resourcepacks, 'w', encoding='utf-8') as file:
            json.dump(json_resourcepacks, file, indent=4)

        bpy.ops.crafter.reload_game_resources()

        return {'FINISHED'}

# ==================== 打开WorldImporter文件夹 ====================

class VIEW3D_OT_CrafterOpenWorldImporter(bpy.types.Operator):
    bl_label = "Open WorldImporter folder"
    bl_idname = "crafter.open_worldimporter_folder"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        folder_path = dir_importer
        open_folder(folder_path)

        return {'FINISHED'}

# ==================== 打开no_lod_blocks文件夹 ====================

class VIEW3D_OT_CrafterOpenNoLodBlocks(bpy.types.Operator):
    bl_label = "Open no lod blocks folder"
    bl_idname = "crafter.open_no_lod_blocks_folder"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        folder_path = dir_no_lod_blocks
        open_folder(folder_path)

        return {'FINISHED'}

# ==================== 刷新 ====================

class VIEW3D_OT_CrafterReloadDimensions(bpy.types.Operator):#刷新 维度
    bl_label = "Reload Dimensions"  
    bl_idname = "crafter.reload_dimensions"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        worldPath = os.path.normpath(addon_prefs.World_Path)
        dir_level_dat = os.path.join(worldPath, "level.dat")
        if not os.path.exists(dir_level_dat):
            return { "FINISHED"}
        
        #清空维度列表
        addon_prefs.Dimensions_List.clear()
        
        #添加默认维度
        dim_overworld = addon_prefs.Dimensions_List.add()
        dim_overworld.name = "minecraft:overworld"
        
        #尝试从level.dat读取维度信息
        nbt_file = nbt.NBTFile(dir_level_dat)
        
        #检查维度文件夹
        dimensions_dir = os.path.join(worldPath, "dimensions")
        if os.path.exists(dimensions_dir) and os.path.isdir(dimensions_dir):
            #遍历dimensions目录下的所有folder
            for namespace in os.listdir(dimensions_dir):
                dir_namespace = os.path.join(dimensions_dir, namespace)
                if os.path.isdir(dir_namespace):
                    #遍历空间下的所有dim
                    for dimension in os.listdir(dir_namespace):
                        dir_dimension = os.path.join(dir_namespace, dimension)
                        if os.path.isdir(dir_dimension):
                            #添加到维度列表
                            dim_name = f"{namespace}:{dimension}"
                            #跳过overworld维度
                            if dim_name == "minecraft:overworld":
                                continue
                            dim = addon_prefs.Dimensions_List.add()
                            dim.name = dim_name
        
        #检查DIM文件夹
        for item in os.listdir(worldPath):
            if item.startswith("DIM"):
                #尝试提取维度ID
                if item == "DIM1":
                    dim = addon_prefs.Dimensions_List.add()
                    dim.name = "minecraft:the_end"
                    print("add dim: minecraft:the_end (DIM1)")
                elif item == "DIM-1":
                    dim = addon_prefs.Dimensions_List.add()
                    dim.name = "minecraft:the_nether"
                    print("add dim: minecraft:the_nether (DIM-1)")
                else:
                    #模组维度
                    dim_id = item.replace("DIM", "")
                    try:
                        #try2查找维度名称文件
                        dim_info_path = os.path.join(worldPath, item, "dimension.txt")
                        if os.path.exists(dim_info_path):
                            with open(dim_info_path, "r") as f:
                                dim_name = f.read().strip()
                            dim = addon_prefs.Dimensions_List.add()
                            dim.name = dim_name
                            print(f"add dim: {dim_name} ({item})")
                        else:
                            #没有维度名称文件 使用modid+dim_id
                            dim = addon_prefs.Dimensions_List.add()
                            dim.name = f"mod_dimension:{dim_id}"
                            print(f"add dim: mod_dimension:{dim_id} ({item})")
                    except Exception as e:
                        print(f"something went wrong! when handling dim {item}: {e}")
        
        #如果没有找到下界和末地,直接添加
        dimension_names = [dim.name for dim in addon_prefs.Dimensions_List]
        if "minecraft:the_nether" not in dimension_names:
            dim = addon_prefs.Dimensions_List.add()
            dim.name = "minecraft:the_nether"
        if "minecraft:the_end" not in dimension_names:
            dim = addon_prefs.Dimensions_List.add()
            dim.name = "minecraft:the_end"
        
        if addon_prefs.Dimensions_List_index >= len(addon_prefs.Dimensions_List) or addon_prefs.Dimensions_List_index < 0:
            addon_prefs.Dimensions_List_index = 0
            
        return { "FINISHED"}

class VIEW3D_OT_CrafterReloadGameResources(bpy.types.Operator):#刷新 游戏资源包 列表
    bl_label = "Reload Game Resources"
    bl_idname = "crafter.reload_game_resources"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        dir_json_resourcepacks = os.path.join(dir_cafter_data, "resourcepacks.json")

        worldPath = os.path.normpath(addon_prefs.World_Path)
        dir_saves = os.path.dirname(worldPath)
        
        addon_prefs.Game_Resources_List.clear()
        addon_prefs.Game_unuse_Resources_List.clear()

        if os.path.exists(dir_json_resourcepacks):
            with open(dir_json_resourcepacks, "r", encoding="utf-8") as file:
                json_resourcepacks = json.load(file)
        else:
            return {'FINISHED'}
        if dir_saves in json_resourcepacks:
            resourcepacks = json_resourcepacks[dir_saves]
        else:
            return {'FINISHED'}
        index_game = 0
        index_game_unuse = 0
        try:
            bpy.utils.previews.remove(icons_game_resource)
        except:
            pass
        try:
            bpy.utils.previews.remove(icons_game_unuse_resource)
        except:
            pass
        icons_game_resource.clear()
        icons_game_unuse_resource.clear()

        worldPath = os.path.normpath(addon_prefs.World_Path)
        dir_saves = os.path.dirname(worldPath)
        dir_back_saves = os.path.dirname(dir_saves)
        dir_resourcepacks = os.path.join(dir_back_saves, "resourcepacks")
        
        for resourcepack in resourcepacks[0]:
            resourcepack_use = addon_prefs.Game_Resources_List.add()
            resourcepack_use.name = resourcepack
            dir_resource = os.path.join(dir_resourcepacks, resourcepack)
            load_icon_from_zip(zip_path=dir_resource, icons=icons_game_resource, name_icons="game_resource", index=index_game)
            index_game += 1
        for resourcepack in resourcepacks[1]:
            resourcepack_unuse = addon_prefs.Game_unuse_Resources_List.add()
            resourcepack_unuse.name = resourcepack
            dir_resource = os.path.join(dir_resourcepacks, resourcepack)
            load_icon_from_zip(zip_path=dir_resource, icons=icons_game_unuse_resource, name_icons="game_unuse_resource", index=index_game_unuse)
            index_game_unuse += 1


        return {'FINISHED'}

class VIEW3D_OT_CrafterReloadLatestWorldsList(bpy.types.Operator):#刷新 最近世界 列表
    bl_label = "Reload Latest Worlds List"
    bl_idname = "crafter.reload_latest_worlds_list"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        bpy.ops.crafter.reload_history_worlds_list()

        dir_json_latest_worlds = os.path.join(dir_cafter_data, "latest_worlds.json")
        with open(dir_json_latest_worlds, 'r', encoding='utf-8') as file:
            json_latest_worlds = json.load(file)
        
        addon_prefs.Latest_World_List.clear()
   
        for latest_world in json_latest_worlds:
            latest_world_name = addon_prefs.Latest_World_List.add()
            latest_world_name.name = latest_world
        if len(addon_prefs.Latest_World_List) > 0:
            if addon_prefs.Latest_World_List_index < 0 or addon_prefs.Latest_World_List_index >= len(addon_prefs.Latest_World_List):
                addon_prefs.Latest_World_List_index = 0
            world_now = addon_prefs.Latest_World_List[addon_prefs.Latest_World_List_index].name
            world_now = world_now.split("|")
            for i in range(len(addon_prefs.History_World_Roots_List)):
                if addon_prefs.History_World_Roots_List[i].name == world_now[2]:
                    addon_prefs.History_World_Roots_List_index = i
                    bpy.ops.crafter.reload_history_worlds_list()
                    if addon_prefs.is_Undivided:#判断是否开启版本隔离
                        dir_versions = os.path.join(world_now[2], "versions")
                        reload_Undivided_Vsersions(context=context,dir_versions=dir_versions)
                        for j in range(len(addon_prefs.Undivided_Vsersions_List)):
                            if addon_prefs.Undivided_Vsersions_List[j].name == world_now[1]:
                                addon_prefs.Undivided_Vsersions_List_index = j
                                break
                        for j in range(len(addon_prefs.History_World_Saves_List)):
                                    if addon_prefs.History_World_Saves_List[j].name == world_now[0]:
                                        addon_prefs.History_World_Saves_List_index = j
                                        break
                        bpy.ops.crafter.reload_history_worlds_list()
                        break
                    else:
                        for j in range(len(addon_prefs.History_World_Versions_List)):
                            if addon_prefs.History_World_Versions_List[j].name == world_now[1]:
                                addon_prefs.History_World_Versions_List_index = j
                                bpy.ops.crafter.reload_history_worlds_list()
                                for k in range(len(addon_prefs.History_World_Saves_List)):
                                    if addon_prefs.History_World_Saves_List[k].name == world_now[0]:
                                        addon_prefs.History_World_Saves_List_index = k
                                        bpy.ops.crafter.reload_history_worlds_list()
                                        break
                                break
                        break

        return {'FINISHED'}

class VIEW3D_OT_CrafterReloadHistoryWorldsList(bpy.types.Operator):#刷新 历史世界 列表
    bl_label = "Reload History Worlds List"
    bl_idname = "crafter.reload_history_worlds_list"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        dir_json_history_worlds = os.path.join(dir_cafter_data, "history_worlds.json")
        with open(dir_json_history_worlds, 'r', encoding='utf-8') as file:
            json_history_worlds = json.load(file)

        try:
            bpy.utils.previews.remove(icons_world)
        except:
            pass
        icons_world.clear()
        world_index = 0

        addon_prefs.History_World_Roots_List.clear()
        addon_prefs.History_World_Versions_List.clear()
        addon_prefs.History_World_Saves_List.clear()
        addon_prefs.History_World_Settings_List.clear()

        for root in json_history_worlds:
            history_world_root = addon_prefs.History_World_Roots_List.add()
            history_world_root.name = root
        if len(addon_prefs.History_World_Roots_List) > 0:
            if addon_prefs.History_World_Roots_List_index < 0 or addon_prefs.History_World_Roots_List_index >= len(addon_prefs.History_World_Roots_List):
                addon_prefs.History_World_Roots_List_index = 0
            if type(json_history_worlds[addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name]) == list:
                addon_prefs.is_Undivided = True
                for save in json_history_worlds[addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name][0]:
                    history_world_save = addon_prefs.History_World_Saves_List.add()
                    history_world_save.name = save
                    if len(addon_prefs.History_World_Saves_List) > 0:
                        if addon_prefs.History_World_Saves_List_index < 0 or addon_prefs.History_World_Saves_List_index >= len(addon_prefs.History_World_Saves_List):
                            addon_prefs.History_World_Saves_List_index = 0
                        for settings in json_history_worlds[addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name][0][addon_prefs.History_World_Saves_List[addon_prefs.History_World_Saves_List_index].name]["settings"]:
                            history_world_setting = addon_prefs.History_World_Settings_List.add()
                            history_world_setting.name = f"{settings[0][0]} {settings[0][1]} {settings[0][2]} {settings[1][0]} {settings[1][1]} {settings[1][2]}" 
            else:
                addon_prefs.is_Undivided = False
                for version in json_history_worlds[addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name]:
                    history_world_version = addon_prefs.History_World_Versions_List.add()
                    history_world_version.name = version
                if len(addon_prefs.History_World_Versions_List) > 0:
                    if addon_prefs.History_World_Versions_List_index < 0 or addon_prefs.History_World_Versions_List_index >= len(addon_prefs.History_World_Versions_List):
                        addon_prefs.History_World_Versions_List_index = 0
                    for save in json_history_worlds[addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name][addon_prefs.History_World_Versions_List[addon_prefs.History_World_Versions_List_index].name]:
                        history_world_save = addon_prefs.History_World_Saves_List.add()
                        history_world_save.name = save
                        dir_icon = os.path.join(get_dir_saves(context), save, "icon.png")
                        icons_world.load("world_icon_" + str(world_index), dir_icon, 'IMAGE')
                        world_index += 1
                    if len(addon_prefs.History_World_Saves_List) > 0:
                        if addon_prefs.History_World_Saves_List_index < 0 or addon_prefs.History_World_Saves_List_index >= len(addon_prefs.History_World_Saves_List):
                            addon_prefs.History_World_Saves_List_index = 0
                        for settings in json_history_worlds[addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name][addon_prefs.History_World_Versions_List[addon_prefs.History_World_Versions_List_index].name][addon_prefs.History_World_Saves_List[addon_prefs.History_World_Saves_List_index].name]:
                            history_world_setting = addon_prefs.History_World_Settings_List.add()
                            history_world_setting.name = f"{settings[0][0]} {settings[0][1]} {settings[0][2]} {settings[1][0]} {settings[1][1]} {settings[1][2]}" 

        return {'FINISHED'}

# ==================== UIList ====================

class VIEW3D_UL_CrafterUndividedVersions(bpy.types.UIList):#无隔离 版本
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        text = item.name
        index_na = text.rfind("\\")
        text = text[index_na+1:]
        layout.label(text=text)

class VIEW3D_UL_CrafterGameResources(bpy.types.UIList):#游戏 资源包
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname, index):
        text = item.name
        index_na = text.rfind("\\")
        text = text[index_na+1:-4]
        true_text = ""
        i = 0
        while i < len(text):
            if text[i] == "§":
                i+=1
            elif text[i] != "!":
                true_text += text[i]
            i+=1
        name_icon = "game_resource_icon_"+ str(index)
        icon = icons_game_resource[name_icon]
        layout.label(text=true_text,icon_value=icon.icon_id)

class VIEW3D_UL_CrafterGameUnuseResources(bpy.types.UIList):#游戏 未使用 资源包
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname, index):
        text = item.name
        index_na = text.rfind("\\")
        text = text[index_na+1:-4]
        true_text = ""
        i = 0
        while i < len(text):
            if text[i] == "§":
                i+=1
            elif text[i] != "!":
                true_text += text[i]
            i+=1
        name_icon = "game_unuse_resource_icon_"+ str(index)
        icon = icons_game_unuse_resource[name_icon]
        layout.label(text=true_text,icon_value=icon.icon_id)

class VIEW3D_UL_CrafterLatestWorldList(bpy.types.UIList):#最近世界 列表
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
       world = item.name.split("|")
       text = f"{world[0]}     {world[1]}     {world[2]}"
       layout.label(text=text)

class VIEW3D_UL_CrafterHistoryWorldRootsList(bpy.types.UIList):#历史世界 根 列表
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        layout.label(text=item.name)

class VIEW3D_UL_CrafterHistoryWorldVersionsList(bpy.types.UIList):#历史世界 版本 列表
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        layout.label(text=item.name)

class VIEW3D_UL_CrafterHistoryWorldSavesList(bpy.types.UIList):#历史世界 存档 列表
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname, index):
        row=layout.row()
        name_icon = "world_icon_"+ str(index)
        icon = icons_world[name_icon]
        row.label(text=item.name, icon_value=icon.icon_id)

class VIEW3D_UL_CrafterHistoryWorldSettingsList(bpy.types.UIList):#历史世界 设置 列表
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        settings=item.name.split(" ")
        layout.label(text=f"{settings[0]}     {settings[1]}     {settings[2]}   |   {settings[3]}     {settings[4]}     {settings[5]}")
