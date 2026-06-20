import bpy
import os
import time
import subprocess
import json
import shutil
import sys
import ctypes
import platform

# 只在Windows上导入wintypes
if platform.system() == "Windows":
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
        col_1.prop(addon_prefs, "useRandomBlockModels")
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

        # 进入导入流程，重置日志状态
        clear_import_log()
        log_stage_begin("准备导入", "检查世界路径")

        #获取世界路径，检测路径合法性
        bpy.ops.crafter.reload_all()
        bpy.ops.crafter.reload_resources()
        worldPath = os.path.normpath(addon_prefs.World_Path)
        dir_saves = os.path.dirname(worldPath)
        dir_level_dat = os.path.join(worldPath, "level.dat")
        log_step(f"世界路径: {worldPath}")
        if not os.path.exists(dir_level_dat):
            error_log(f"未找到 level.dat，不是有效的世界路径: {dir_level_dat}")
            log_stage_end("准备导入", "失败")
            self.report({'ERROR'}, "It's not a world path!")
            return {"CANCELLED"}
        log_step("level.dat 检查通过")
        
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
            log_step(f"检测到 .minecraft（未版本隔离）: {dir_dot_minecraft}")
            log_step(f"发现 {len(list_versions)} 个版本")
            if len(list_versions) == 0:
                error_log("versions 目录为空，未找到任何版本")
                log_stage_end("准备导入", "失败")
                self.report({'ERROR'}, "Can't find any versions!")
                return {"CANCELLED"}
            reload_Undivided_Vsersions(context=context,dir_versions=dir_versions)
            dir_resourcepacks = os.path.join(dir_dot_minecraft,"resourcepacks")
            dir_mods = os.path.join(dir_dot_minecraft,"mods")
        else:
            dir_version = dir_back_saves_2_dir_version(dir_back_saves)
            name_version = os.path.basename(dir_version)
            jarPath = dir_version_2_dir_jar(dir_version)
            log_step(f"版本隔离模式，版本: {name_version}")
            log_step(f"jar 路径: {jarPath}")
            if not os.path.exists(jarPath):
                warn_log(f"jar 不存在，需要手动选择: {jarPath}")
                addon_prefs.is_Game_Path = False
                self.worldPath = worldPath
                log_stage_end("准备导入", "需手动指定 jar")
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
        log_step(f"存档名: {self.save} | 版本: {self.version or '(未隔离)'}")
        #获取资源包
        if os.path.exists(dir_resourcepacks):
            list_resourcepacks = os.listdir(dir_resourcepacks)
            log_step(f"资源包目录: {dir_resourcepacks}（{len(list_resourcepacks)} 项）")
        else:
            list_resourcepacks = []
            warn_log(f"资源包目录不存在: {dir_resourcepacks}")
        dir_json_resourcepacks = os.path.join(dir_cafter_data, "resourcepacks.json")
        log_stage_end("准备导入", "路径与版本检查完成")

        log_stage_begin("扫描资源包", dir_saves)
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

        new_zip_count = 0
        for resourcepack in list_resourcepacks:
            dir_resourcepack = os.path.join(dir_resourcepacks, resourcepack)
            if (not dir_resourcepack in resourcepacks_use_copy) and (not dir_resourcepack in resourcepacks_unuse_copy):
                if dir_resourcepack.endswith(".zip"):
                    json_resourcepacks[dir_saves][0].append(dir_resourcepack)
                    new_zip_count += 1
        for dir_resourcepack in resourcepacks_use_copy:
            if os.path.exists(dir_resourcepack):
                json_resourcepacks[dir_saves][0].append(dir_resourcepack)
        for dir_resourcepack in resourcepacks_unuse_copy:
            if os.path.exists(dir_resourcepack):
                json_resourcepacks[dir_saves][1].append(dir_resourcepack)
    
        with open (dir_json_resourcepacks, "w", encoding="utf-8") as file:
            json.dump(json_resourcepacks, file, indent=4)
        log_step(f"新增资源包 {new_zip_count} 个，启用 {len(json_resourcepacks[dir_saves][0])} 个，禁用 {len(json_resourcepacks[dir_saves][1])} 个")
        log_step(f"已写入: {dir_json_resourcepacks}")
        log_stage_end("扫描资源包", "资源包列表更新完成")
        
        bpy.ops.crafter.reload_game_resources()
        
        return context.window_manager.invoke_props_dialog(self)
    
    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        imported_time = str(context.scene.Crafter_import_time)
        log_stage_begin("构建导入配置", f"import_time={imported_time}")
        if context.active_object:
            bpy.ops.object.mode_set(mode='OBJECT')
        if (not addon_prefs.is_Game_Path) or addon_prefs.Custom_Path:
            log_step("使用自定义路径模式")
            addon_prefs.Game_Resources_List.clear()
            worldPath = self.worldPath
            if not os.path.exists(addon_prefs.Custom_Jar_Path):
                error_log(f"自定义 jar 路径不存在: {addon_prefs.Custom_Jar_Path}")
                log_stage_end("构建导入配置", "失败")
                self.report({'ERROR'}, "Path not found!")
                return {"CANCELLED"}
            elif not addon_prefs.Custom_Jar_Path.endswith(".jar"):
                error_log(f"自定义路径不是 jar 文件: {addon_prefs.Custom_Jar_Path}")
                log_stage_end("构建导入配置", "失败")
                self.report({'ERROR'}, "It's not a jar file!")
                return {"CANCELLED"}
            jarPath = addon_prefs.Custom_Jar_Path
            versionJsonPath = jarPath[:-3]+"json"
            log_step(f"jar: {jarPath}")
            if addon_prefs.use_Custom_mods_Path:
                if not os.path.exists(addon_prefs.Custom_mods_Path):
                    error_log(f"自定义 mods 路径不存在: {addon_prefs.Custom_mods_Path}")
                    log_stage_end("构建导入配置", "失败")
                    self.report({'ERROR'}, "Path not found!")
                    return {"CANCELLED"}
                if not os.path.isdir(addon_prefs.Custom_mods_Path):
                    error_log(f"自定义 mods 路径不是文件夹: {addon_prefs.Custom_mods_Path}")
                    log_stage_end("构建导入配置", "失败")
                    self.report({'ERROR'}, "It's not a folder!")
                    return {"CANCELLED"}
                modsPath = addon_prefs.Custom_mods_Path
                log_step(f"mods: {modsPath}")
            else:
                modsPath = "None"
                log_step("未启用自定义 mods")
        else:
            if self.version == "":
                undivided = True
                dir_version = addon_prefs.Undivided_Vsersions_List[addon_prefs.Undivided_Vsersions_List_index].name
                version = os.path.basename(dir_version)
                jarPath = os.path.join(dir_version, version + ".jar")
                log_step(f"未版本隔离，选中版本: {version}")
            else:
                undivided = False
                jarPath = self.jarPath
                version = self.version
                log_step(f"版本隔离，版本: {version}")
            worldPath = self.worldPath
            modsPath = self.modsPath
            if not os.path.exists(modsPath):
                modsPath = "None"
                log_step("mods 目录不存在，设为 None")
            dir_version = os.path.dirname(jarPath)
            versionJsonPath = os.path.join(dir_version, version + ".json")
            log_step(f"versionJson: {versionJsonPath}")

            save = self.save
            dot_minecraftPath = self.dot_minecraftPath
        # 获取资源包列表
        log_stage_begin("加载资源包路径")
        resourcepacksPaths = []
        if addon_prefs.Game_Resources:
            for resourcepacksPath in addon_prefs.Game_Resources_List:
                resourcepacksPaths.append(resourcepacksPath.name)
            log_step(f"使用游戏资源包，共 {len(resourcepacksPaths)} 个")
        else:
            dir_resourcepacks = os.path.join(dir_resourcepacks_plans, addon_prefs.Resources_Plans_List[addon_prefs.Resources_Plans_List_index].name)
            dir_crafter_json = os.path.join(dir_resourcepacks, "crafter.json")

            addon_prefs.Resources_List.clear()
            with open(dir_crafter_json, "r", encoding="utf-8") as file:
                json_crafter = json.load(file)
            for resource in json_crafter:
                resourcepacksPaths.append(os.path.join(dir_resourcepacks, resource + ".zip"))
            log_step(f"使用资源方案，共 {len(resourcepacksPaths)} 个")
        log_stage_end("加载资源包路径", f"{len(resourcepacksPaths)} 个资源包")
        # 获取无lod方块列表
        log_stage_begin("加载无LOD方块表")
        list_no_lod_blocks = []
        if addon_prefs.no_lod_blocks:
            list_files = os.listdir(dir_no_lod_blocks)
            json_count = 0
            for file in list_files:
                if file.endswith(".json"):
                    json_count += 1
                    with open(os.path.join(dir_no_lod_blocks, file), "r", encoding="utf-8") as file:
                        json_no_lod_blocks = json.load(file)
                        for block in json_no_lod_blocks:
                            if block not in list_no_lod_blocks:
                                list_no_lod_blocks.append(block)
            log_step(f"读取 {json_count} 个 json，合计 {len(list_no_lod_blocks)} 个无LOD方块")
        else:
            log_step("未启用无LOD方块表")
        log_stage_end("加载无LOD方块表")

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
            "useRandomBlockModels":addon_prefs.useRandomBlockModels,
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

        # 根据平台选择配置目录和可执行文件
        current_platform = platform.system()
        if current_platform == "Darwin":  # macOS
            dir_config = os.path.join(dir_importer, "config_macos")
            dir_exe_importer = os.path.join(dir_importer, "WorldImporter")
        else:  # Windows和其他平台
            dir_config = os.path.join(dir_importer, "config")
            dir_exe_importer = os.path.join(dir_importer, "WorldImporter.exe")
        log_step(f"平台: {current_platform} | exe: {dir_exe_importer}")

        # 输出关键配置摘要
        log_step(f"区域 X[{worldconfig['minX']},{worldconfig['maxX']}] "
                 f"Y[{worldconfig['minY']},{worldconfig['maxY']}] "
                 f"Z[{worldconfig['minZ']},{worldconfig['maxZ']}]")
        log_step(f"维度: {worldconfig['selectedDimension']} | status={status} "
                 f"| LOD={worldconfig['activeLOD']} | GreedyMesh={worldconfig['useGreedyMesh']}")
        log_stage_end("构建导入配置", "worldconfig 构建完成")

        # 确保配置目录存在
        log_stage_begin("写入配置")
        if not os.path.exists(dir_config):
            os.makedirs(dir_config)
            log_step(f"创建配置目录: {dir_config}")

        dir_json_config = os.path.join(dir_config, "config.json")

        with open(dir_json_config, 'w', encoding='utf-8') as config:
            json.dump(worldconfig, config, indent=4)
        log_step(f"已写入 config.json")
        log_stage_end("写入配置", dir_json_config)

        # 清理旧的 obj 文件
        log_stage_begin("清理旧导出")
        removed_obj = 0
        for file in os.listdir(dir_importer):
            if file.endswith(".obj"):
                try:
                    os.remove(os.path.join(dir_importer, file))
                    removed_obj += 1
                except:
                    pass
        log_step(f"删除 {removed_obj} 个旧 obj 文件")
        log_stage_end("清理旧导出")
            
        prepared_time = time.perf_counter()
        #生成obj

        global import_running, import_progress
        import_running = True; import_progress = 0.0
        log_stage_begin("运行 WorldImporter", os.path.basename(dir_exe_importer))
        push_log("WorldImporter 已启动")
        debug_log(f"exe: {dir_exe_importer}")
        debug_log(f"cwd: {dir_importer}")

        poll_fn = start_importer_async(dir_exe_importer, dir_importer)
        if not poll_fn:
            import_running = False
            error_log("启动 WorldImporter 失败（poll_fn 为空）")
            log_stage_end("运行 WorldImporter", "启动失败")
            self.report({'ERROR'}, '启动 WorldImporter 失败')
            return {"CANCELLED"}

        _ctx = context
        _ctx_window = context.window
        _ctx_area = context.area
        _prefs = addon_prefs
        _imp_time = imported_time
        _config = dict(worldconfig)
        _prep_time = prepared_time
        _save = self.save if hasattr(self, "save") else ""
        _version = self.version if hasattr(self, "version") else ""
        _dot_mc = self.dot_minecraftPath if hasattr(self, "dot_minecraftPath") else ""
        _undivided = "undivided" in dir() and undivided
        _is_custom = (not addon_prefs.is_Game_Path) or addon_prefs.Custom_Path
        import time as _time
        wm = context.window_manager
        wm.progress_begin(0, 100)

        def _classify_importer_line(line):
            """根据 WorldImporter 输出内容粗略判断日志级别"""
            low = line.lower()
            if line.startswith("■") or line.startswith("▶"):
                return LOG_LEVEL_INFO
            if "error" in low or "错误" in line or "failed" in low:
                return LOG_LEVEL_ERROR
            if "warn" in low or "警告" in line:
                return LOG_LEVEL_WARN
            return LOG_LEVEL_INFO

        def _continue_import():
            global import_running, import_progress
            r = poll_fn()
            if isinstance(r, str):
                for line in r.split(chr(10)):
                    if not line: continue
                    push_log(line, _classify_importer_line(line), "运行 WorldImporter")
                    p = parse_progress(line)
                    if p is not None: import_progress = p
                wm.progress_update(import_progress)
                for w in bpy.context.window_manager.windows:
                    for a in w.screen.areas:
                        a.tag_redraw()
                return 0.3
            if r is None: return 0.3
            import_running = False
            wm.progress_end()
            if r:
                import_progress = 100.0
                log_stage_end("运行 WorldImporter", "进程成功退出")
                def _do():
                    try:
                        finish_import(
                            _ctx,_prefs,_imp_time,_config,_prep_time,
                            _save,_version,_dot_mc,"",
                            _undivided,_is_custom,_ctx_window,_ctx_area)
                    except Exception as _ex:
                        error_log(f"finish_import 异常: {_ex}")
                        import traceback; traceback.print_exc()
                    return None
                bpy.app.timers.register(_do)
            else:
                error_log("WorldImporter 进程返回失败（退出码非 0）")
                log_stage_end("运行 WorldImporter", "进程失败")
            for w in bpy.context.window_manager.windows:
                for a in w.screen.areas: a.tag_redraw()
            return None

        bpy.app.timers.register(_continue_import)
        self.report({'INFO'}, 'WorldImporter running...')
        return {'FINISHED'}


# ==================== 续传函数（定时器主线程调用） ====================

def finish_import(ctx, prefs, imported_time, worldconfig, prepared_time,
                  save, version, dot_minecraftPath, worldPath, undivided, is_custom,
                  _ctx_win=None, _ctx_area=None):
    """Import OBJ, process materials, save history"""
    from ..__init__ import dir_cafter_data
    log_stage_begin("后处理导入", f"import_time={imported_time}")
    log_stage_begin("加载节点组")
    add_node_group_if_not_exists(names_Crafter_Moving_texture)
    add_node_group_if_not_exists(["Crafter-biomeTex"])
    log_step("Crafter-动态纹理 / Crafter-biomeTex 节点组已就绪")
    log_stage_end("加载节点组")
    debug_log(f"finish_import start: t={imported_time}")

    def _run_ops(fn, **kw):
        """遍历窗口找 VIEW_3D 执行 bpy.ops，回退裸调"""
        if bpy.context.window:
            try:
                with bpy.context.temp_override():
                    return fn(**kw)
            except:
                pass
        for w in bpy.context.window_manager.windows:
            for a in w.screen.areas:
                if a.type == 'VIEW_3D':
                    for r in a.regions:
                        if r.type == 'WINDOW':
                            try:
                                with bpy.context.temp_override(window=w, area=a, region=r):
                                    return fn(**kw)
                            except:
                                continue
        return fn(**kw)

    have_obj = False
    real_name_dic = {}
    before_objects = set(bpy.data.objects)
    log_stage_begin("列举导出文件")
    try:
        importer_files = os.listdir(dir_importer)
    except (FileNotFoundError, PermissionError):
        importer_files = []
    obj_files = [f for f in importer_files if f.endswith('.obj')]
    log_step(f"发现 {len(obj_files)} 个 obj 文件")
    log_stage_end("列举导出文件")

    log_stage_begin("导入 OBJ", f"{len(obj_files)} 个文件")
    obj_idx = 0
    for file in importer_files:
        if not file.endswith('.obj'): continue
        obj_idx += 1
        _obj_t0 = time.perf_counter()
        debug_log(f"Importing OBJ: {file}")
        pre_set = set(bpy.data.objects)
        try:
            _run_ops(bpy.ops.wm.obj_import, filepath=os.path.join(dir_importer, file))
            _run_ops(bpy.ops.object.transform_apply, location=False, rotation=True, scale=True)
            have_obj = True
        except Exception as ex:
            error_log(f"OBJ 导入失败 [{obj_idx}/{len(obj_files)}]: {file} - {ex}")
            continue
        new_objs = list(set(bpy.data.objects) - pre_set)
        log_step(f"[{obj_idx}/{len(obj_files)}] {file} -> {len(new_objs)} 对象 ({(time.perf_counter()-_obj_t0)*1000:.0f}ms)")
        for obj in new_objs:
            for i in range(len(obj.data.materials)):
                mat = obj.data.materials[i]
                n = mat.name
                if n.startswith("color#") and len(n) <= len_color_jin:
                    pass
                else:
                    n = fuq_bl_dot_number(n)
                if n in real_name_dic:
                    obj.data.materials[i] = bpy.data.materials[real_name_dic[n]]
                else:
                    real_name_dic[n] = mat.name
            add_to_mcmts_collection(object=obj, context=ctx)
            add_to_crafter_mcmts_collection(object=obj, context=ctx)
            add_Crafter_time(obj=obj)
            view_2_active_object(ctx)
    log_stage_end("导入 OBJ", f"{len(real_name_dic)} 个唯一材质")

    debug_log(f"Materials: {len(real_name_dic)} unique")

    if not have_obj:
        error_log("WorldImporter 未导出任何 obj 文件")
        log_stage_end("后处理导入", "无 obj")
        return

    # Copy biomeTex
    log_stage_begin("复制 biomeTex")
    dir_bt = os.path.join(dir_importer, "biomeTex")
    dir_btn = os.path.join(dir_bt, imported_time)
    os.makedirs(dir_btn, exist_ok=True)
    try:
        bt_files = [f for f in os.listdir(dir_bt) if f.endswith('.png')]
        for f in bt_files:
            shutil.copy(os.path.join(dir_bt, f), os.path.join(dir_btn, f))
        log_step(f"复制 {len(bt_files)} 张群系纹理到 {dir_btn}")
    except Exception as ex:
        warn_log(f"biomeTex 复制异常: {ex}")
    log_stage_end("复制 biomeTex")

    # Clone biomeTex node group
    log_stage_begin("克隆 biomeTex 节点组")
    try:
        ng_src = bpy.data.node_groups["Crafter-biomeTex"]
        ng = ng_src.copy()
        ng.name = "Crafter-biomeTex_" + imported_time
        loaded_imgs = 0
        for node in ng.nodes:
            if node.type == "TEX_IMAGE":
                try:
                    fn = fuq_bl_dot_number(node.image.name)
                    node.image = bpy.data.images.load(os.path.join(dir_btn, fn))
                    loaded_imgs += 1
                    debug_log(f"  Loaded: {fn}")
                except Exception as ex:
                    debug_log(f"  Image load: {ex}")
            elif node.type == "GROUP":
                node.inputs["min X"].default_value = worldconfig["minX"]
                node.inputs["min Y"].default_value = -worldconfig["maxZ"] -1
                node.inputs["max X"].default_value = 1 + worldconfig["maxX"]
                node.inputs["max Y"].default_value = -worldconfig["minZ"]
        log_step(f"节点组 {ng.name} 就绪，加载 {loaded_imgs} 张群系图")
        debug_log("biomeTex node group ready")
    except Exception as ex:
        warn_log(f"biomeTex 节点组克隆失败: {ex}")
        ng = None
    log_stage_end("克隆 biomeTex 节点组")

    # Apply node group to materials
    log_stage_begin("应用 biomeTex 到材质")
    apply_list = [nm for nm in real_name_dic.values() if not nm.startswith('color#') and nm in bpy.data.materials]
    log_step(f"待处理材质: {len(apply_list)} 个")
    for nm in apply_list:
        mat = bpy.data.materials[nm]
        nds = mat.node_tree.nodes
        lks = mat.node_tree.links
        ntb = None; out_ev = None; pri = None; todel = []
        for nd in nds:
            if nd.type == "OUTPUT_MATERIAL" and nd.target in ("EEVEE", "ALL"):
                out_ev = nd
            elif nd.type == "TEX_IMAGE":
                if ntb is None:
                    ntb = nd; nd.interpolation = 'Closest'
                else:
                    todel.append(nd)
            elif nd.type == "BSDF_PRINCIPLED":
                pri = nd
        for nd in todel: nds.remove(nd)
        if ntb and pri:
            lks.new(ntb.outputs["Alpha"], pri.inputs["Alpha"])
        if out_ev and ng:
            nbn = nds.new("ShaderNodeGroup")
            nbn.location = (out_ev.location.x - 400, out_ev.location.y - 550)
            nbn.node_tree = ng
        if ntb:
            load_normal_and_PBR(node_tex_base=ntb, nodes=nds, links=lks)
            nds.active = ntb
    log_stage_end("应用 biomeTex 到材质", f"{len(apply_list)} 个材质")

    log_stage_begin("打包纹理")
    try:
        _run_ops(bpy.ops.file.pack_all)
        log_step("pack_all 完成")
    except Exception as ex:
        warn_log(f'pack_all 失败: {ex}')
    log_stage_end("打包纹理")

    if prefs.Auto_Load_Material:
        log_stage_begin("自动加载材质")
        ms = time.perf_counter()
        _run_ops(bpy.ops.crafter.load_material)
        mu = time.perf_counter() - ms
        log_stage_end("自动加载材质", f"耗时 {mu:.2f}s")
        debug_log(f"Auto material: {mu:.2f}s")
    else:
        mu = 0.0

    rt = "Import: " + str(time.perf_counter() - prepared_time)[:6] + "s"
    if prefs.Auto_Load_Material:
        rt += ", Mat: " + str(mu)[:6] + "s"

    for o in (set(bpy.data.objects) - before_objects):
        if o.type == "MESH": o.select_set(True)
    view_2_active_object(ctx)
    push_log(rt, "INFO")
    debug_log("finish_import done")

    # Save history
    log_stage_begin("保存历史记录")
    jp = os.path.join(dir_cafter_data, "history_worlds.json")
    if os.path.exists(jp):
        with open(jp, "r", encoding="utf-8") as f:
            jh = json.load(f)
    else:
        jh = {}
    if not is_custom:
        if undivided:
            jh.setdefault(dot_minecraftPath, [{}])
            ul = jh[dot_minecraftPath][0].setdefault(save, {})
            js = ul.setdefault("settings", [])
            ul["version"] = version
        else:
            jh.setdefault(dot_minecraftPath, {})
            jh[dot_minecraftPath].setdefault(version, {})
            jh[dot_minecraftPath][version].setdefault(save, [])
            js = jh[dot_minecraftPath][version][save]
        wsn = [list(prefs.XYZ_1), list(prefs.XYZ_2)]
        if not js:
            js.append(None); js[0] = wsn
        else:
            for i in range(len(js)):
                if js[i] == wsn:
                    for j in range(i, 0, -1): js[j] = js[j-1]
                    js[0] = wsn; break
            else:
                if len(js) < 10: js.append(None)
                for i in range(len(js)-1, 0, -1): js[i] = js[i-1]
                js[0] = wsn
        with open(jp, "w", encoding="utf-8") as f:
            json.dump(jh, f, indent=4)
        wn = f"{save}|{version}|{dot_minecraftPath}"
        lp = os.path.join(dir_cafter_data, "latest_worlds.json")
        if os.path.exists(lp):
            with open(lp, "r", encoding="utf-8") as f:
                jl = json.load(f)
        else:
            jl = []
        if not jl:
            jl.append(None); jl[0] = wn
        else:
            for i in range(len(jl)):
                if jl[i] == wn:
                    for j in range(i, 0, -1): jl[j] = jl[j-1]
                    jl[0] = wn; break
            else:
                if len(jl) < 5: jl.append(None)
                for i in range(len(jl)-1, 0, -1): jl[i] = jl[i-1]
                jl[0] = wn
        with open(lp, "w", encoding="utf-8") as f:
            json.dump(jl, f, indent=4)
        debug_log(f"History saved: {save}")
    log_stage_end("保存历史记录")
    log_stage_end("后处理导入", "全部完成")

    prefs.Latest_World_List_index = 0
    prefs.History_World_Settings_List_index = 0
    ctx.scene.Crafter_import_time += 1

class VIEW3D_OT_CrafterReimportSurfaceWorld(bpy.types.Operator):# 重导入表层世界
    bl_label = "Reimport World"
    bl_idname = "crafter.reimport_surface_world"
    bl_description = "Reimport the surface world"
    bl_options = {'REGISTER', 'UNDO'}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        prepared_time = time.perf_counter()
        clear_import_log()
        log_stage_begin("重新导入", "读取已有 config.json")

        # 根据平台选择配置目录和可执行文件
        current_platform = platform.system()
        if current_platform == "Darwin":  # macOS
            dir_config = os.path.join(dir_importer, "config_macos")
        else:  # Windows和其他平台
            dir_config = os.path.join(dir_importer, "config")
        dir_json_config = os.path.join(dir_config, "config.json")
        log_step(f"配置文件: {dir_json_config}")

        if not os.path.exists(dir_json_config):
            error_log("config.json 不存在，请先执行完整导入")
            log_stage_end("重新导入", "配置缺失")
            self.report({'ERROR'}, "Config file not found!")
            return {'CANCELLED'}
        try:
            with open(dir_json_config, 'r', encoding='utf-8') as config:
                worldconfig = json.load(config)
        except Exception as ce:
            error_log(f"config.json 读取失败: {ce}")
            log_stage_end("重新导入", "配置读取失败")
            self.report({'ERROR'}, "Config file not found!")
            return {'CANCELLED'}
        log_step("config.json 读取成功，跳过 WorldImporter，直接后处理")
        log_stage_end("重新导入")

        push_log("Reimport: running finish_import")
        try:
            finish_import(
                context, addon_prefs,
                str(context.scene.Crafter_import_time),
                worldconfig, prepared_time,
                "", "", "", "",
                False,
                (not addon_prefs.is_Game_Path) or addon_prefs.Custom_Path)
        except Exception as e:
            error_log(f"Reimport 异常: {e}")
            self.report({'ERROR'}, f'Reimport error: {e}')
            return {"CANCELLED"}
        view_2_active_object(context)
        return {"FINISHED"}


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
        if not os.path.exists(dir_json_latest_worlds):
            return {"FINISHED"}
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
        if not os.path.exists(dir_json_history_worlds):
            return {"FINISHED"}
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
                    dir_icon = os.path.join(get_dir_saves(context), save, "icon.png")
                    icons_world.load("world_icon_" + str(world_index), dir_icon, 'IMAGE')
                    world_index += 1
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


# ==================== 日志操作算子 ====================

class VIEW3D_OT_CrafterClearImportLog(bpy.types.Operator):
    bl_label = "Clear Import Log"
    bl_idname = "crafter.clear_import_log"
    bl_description = "Clear all import log entries"

    def execute(self, context):
        clear_import_log()
        self.report({'INFO'}, "日志已清空")
        return {'FINISHED'}


class VIEW3D_OT_CrafterCopyImportLog(bpy.types.Operator):
    bl_label = "Copy Import Log"
    bl_idname = "crafter.copy_import_log"
    bl_description = "Copy all import log to clipboard"

    def execute(self, context):
        text = format_log_text(include_meta=True)
        if not text:
            self.report({'INFO'}, "No log to copy")
            return {'CANCELLED'}
        context.window_manager.clipboard = text
        self.report({'INFO'}, f"Copied {len(import_log)} lines")
        return {'FINISHED'}


class VIEW3D_OT_CrafterExportImportLog(bpy.types.Operator):
    bl_label = "Export Import Log"
    bl_idname = "crafter.export_import_log"
    bl_description = "Save import log to a text file"

    filepath: bpy.props.StringProperty(subtype="FILE_PATH")  # type: ignore

    def invoke(self, context, event):
        import datetime
        default = f"crafter_import_{datetime.datetime.now():%Y%m%d_%H%M%S}.log"
        self.filepath = os.path.join(os.path.expanduser("~"), "Desktop", default)
        context.window_manager.fileselect_add(self)
        return {'RUNNING_MODAL'}

    def execute(self, context):
        text = format_log_text(include_meta=True)
        if not text:
            self.report({'INFO'}, "No log to export")
            return {'CANCELLED'}
        try:
            with open(self.filepath, 'w', encoding='utf-8') as f:
                f.write(text + "\n")
            self.report({'INFO'}, f"Log saved: {self.filepath}")
        except Exception as e:
            self.report({'ERROR'}, f"Save failed: {e}")
        return {'FINISHED'}


class VIEW3D_OT_CrafterExportSessionLog(bpy.types.Operator):
    bl_label = "Export Session Log"
    bl_idname = "crafter.export_session_log"
    bl_description = "Export full session log including all import logs to a file"

    filepath: bpy.props.StringProperty(subtype="FILE_PATH")  # type: ignore

    def invoke(self, context, event):
        import datetime
        default = f"crafter_session_{datetime.datetime.now():%Y%m%d_%H%M%S}.log"
        self.filepath = os.path.join(os.path.expanduser("~"), "Desktop", default)
        context.window_manager.fileselect_add(self)
        return {'RUNNING_MODAL'}

    def execute(self, context):
        import datetime
        lines = []
        lines.append("=== Crafter Session Log ===")
        lines.append(f"Generated: {datetime.datetime.now():%Y-%m-%d %H:%M:%S}")
        lines.append(f"Plugin Version: 0.8.0")
        lines.append(f"Import Count: {context.scene.Crafter_import_time}")
        lines.append("")
        lines.append("--- Import Log ---")
        body = format_log_text(include_meta=True)
        if body:
            lines.append(body)
        lines.append("")
        lines.append(f"Total log entries: {len(import_log)}")
        text = "\n".join(lines)
        if not text.strip():
            self.report({'INFO'}, "No log to export")
            return {'CANCELLED'}
        try:
            with open(self.filepath, 'w', encoding='utf-8') as f:
                f.write(text + "\n")
            self.report({'INFO'}, f"Session log saved: {self.filepath}")
        except Exception as e:
            self.report({'ERROR'}, f"Save failed: {e}")
        return {'FINISHED'}

