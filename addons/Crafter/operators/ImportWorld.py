import bpy
import os
import time
import subprocess
import json
import shutil
import ctypes

from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from bpy.props import StringProperty, IntProperty, BoolProperty, IntVectorProperty, EnumProperty, CollectionProperty, FloatProperty
from ..__init__ import dir_cafter_data, dir_resourcepacks_plans, dir_materials, dir_classification_basis, dir_blend_append, dir_init_main, dir_backgrounds
from .Defs import *

# ==================== 导入世界 ====================

class VIEW3D_OT_CrafterImportSurfaceWorld(bpy.types.Operator):#导入表层世界
    bl_label = "Import World"
    bl_idname = "crafter.import_surface_world"
    bl_description = "Import the surface world"
    bl_options = {'REGISTER', 'UNDO'}
    
    worldPath = StringProperty(name="World path")#type: ignore
    jarPath: StringProperty(name="Jar path")#type: ignore
    modsPath = StringProperty(name="Mods path")#type: ignore

    save: StringProperty(name="Save")#type: ignore
    version: StringProperty(name="Version")#type: ignore
    dot_minecraftPath: StringProperty(name=".minecraft path")#type: ignore

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
    def draw(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        layout = self.layout

        row = layout.row()

        col_1 = row.column()
        col_1.prop(addon_prefs, "useChunkPrecision")
        col_1.prop(addon_prefs, "strictDeduplication")
        col_1.prop(addon_prefs, "allowDoubleFace")
        col_1.prop(addon_prefs, "exportLightBlock")

        col_2 = row.column()
        col_2.prop(addon_prefs, "keepBoundary")
        col_2.prop(addon_prefs, "cullCave")
        col_2.prop(addon_prefs, "shell")

        if addon_prefs.exportLightBlock:
            row_Light_Block = layout.row()
            row_Light_Block.prop(addon_prefs, "exportLightBlockOnly")
            row_Light_Block.prop(addon_prefs, "lightBlockSize")


        row_exportFullModel = layout.row()
        row_exportFullModel.prop(addon_prefs, "exportFullModel")
        if addon_prefs.exportFullModel:
            row_exportFullModel.prop(addon_prefs, "partitionSize")
        
        row_if_lod = layout.row()
        row_if_lod.prop(addon_prefs, "activeLOD")
        if addon_prefs.activeLOD:
            row_lod = layout.row()
            
            col_lod_1 = row_lod.column()
            col_lod_1.prop(addon_prefs, "LOD0renderDistance")
            col_lod_1.prop(addon_prefs, "LOD1renderDistance")
            col_lod_1.prop(addon_prefs, "LOD2renderDistance")
            col_lod_1.prop(addon_prefs, "LOD3renderDistance")

            col_lod_2 = row_lod.column()
            col_lod_2.prop(addon_prefs, "useUnderwaterLOD")
            col_lod_2.prop(addon_prefs, "isLODAutoCenter")
            if not addon_prefs.isLODAutoCenter:
                col_lod_2.prop(addon_prefs, "LODCenterX")
                col_lod_2.prop(addon_prefs, "LODCenterZ")
        # 无版本隔离选择
        if self.version == "":
            layout.label(text="Versions")
            row_undivided = layout.row()
            row_undivided.template_list("VIEW3D_UL_CrafterUndividedVersions","",addon_prefs,"Undivided_Vsersions_List",addon_prefs,"Undivided_Vsersions_List_index",rows=1,)

        # 资源包列表
        row_resources = layout.row()
        row_resources.prop(addon_prefs, "Game_Resources")
        if addon_prefs.Game_Resources:
            if len(addon_prefs.Game_Resources_List) + len(addon_prefs.Game_unuse_Resources_List) > 0:
                layout.label(text="Resources List")
                row_resources_use = layout.row()
                col_use_list = row_resources_use.column()
                col_use_list.template_list("VIEW3D_UL_CrafterGameResources", "", addon_prefs, "Game_Resources_List", addon_prefs, "Game_Resources_List_index", rows=1)
                col_use_ops = row_resources_use.column(align=True)
                if len (addon_prefs.Game_Resources_List) > 0:
                    col_use_ops.operator("crafter.ban_game_resource", text="", icon="REMOVE")
                if len (addon_prefs.Game_Resources_List) > 1:
                    col_use_ops.separator()
                    col_use_ops.operator("crafter.up_game_resource", text="", icon="TRIA_UP")
                    col_use_ops.operator("crafter.down_game_resource", text="", icon="TRIA_DOWN")

                row_resources_unuse = layout.row()
                col_unuse_list = row_resources_unuse.column()
                col_unuse_list.template_list("VIEW3D_UL_CrafterGameUnuseResources", "", addon_prefs, "Game_unuse_Resources_List", addon_prefs, "Game_unuse_Resources_List_index", rows=1)
                col_unuse_ops = row_resources_unuse.column()
                if len (addon_prefs.Game_unuse_Resources_List) > 0:
                    col_unuse_ops.operator("crafter.use_game_resource", text="", icon="ADD")
        else:
            layout.label(text="Resources List")
            row_Plans_List = layout.row()
            row_Plans_List.template_list("VIEW3D_UL_CrafterResources", "", addon_prefs, "Resources_Plans_List", addon_prefs, "Resources_Plans_List_index", rows=1)
            col_Plans_List_ops = row_Plans_List.column()
            col_Plans_List_ops.operator("crafter.open_resources_plans",icon="FILE_FOLDER",text="")
            col_Plans_List_ops.operator("crafter.reload_all",icon="FILE_REFRESH",text="")

            if len(addon_prefs.Resources_List) > 0:
                layout.label(text=i18n("Resource"))
                row_Resources_List = layout.row()
                row_Resources_List.template_list("VIEW3D_UL_CrafterResourcesInfo", "", addon_prefs, "Resources_List", addon_prefs, "Resources_List_index", rows=1)
                if len(addon_prefs.Resources_List) > 1:
                    col_Resources_List_ops = row_Resources_List.column(align=True)
                    col_Resources_List_ops.operator("crafter.up_resource",icon="TRIA_UP",text="")
                    col_Resources_List_ops.operator("crafter.down_resource",icon="TRIA_DOWN",text="")


    def invoke(self, context, event):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        # 获取世界路径，检测路径合法性
        bpy.ops.crafter.reload_all()
        worldPath = os.path.normpath(addon_prefs.World_Path)
        dir_saves = os.path.dirname(worldPath)
        dir_level_dat = os.path.join(worldPath, "level.dat")
        if not os.path.exists(dir_level_dat):
            self.report({'ERROR'}, "It's not a world path!")
            return {"CANCELLED"}
        
        #初始化路径
        jarPath = ""
        versionName = ""
        # 计算游戏文件路径
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
            versionPath = dir_back_saves
            versionName = os.path.basename(versionPath)
            jarPath = os.path.join(versionPath, versionName+".jar")
            if not os.path.exists(jarPath):
                self.report({'ERROR'}, "Please set the save file into the Minecraft game folder!")
                return {"CANCELLED"}
            dir_mods = os.path.join(versionPath, "mods")
            dir_resourcepacks = os.path.join(versionPath, "resourcepacks")
            dir_versions = os.path.dirname(versionPath)
            dir_dot_minecraft = os.path.dirname(dir_versions)
        #储存信息到self
        self.worldPath = worldPath
        self.jarPath = jarPath
        self.modsPath = dir_mods

        self.save = os.path.basename(worldPath)
        self.version = versionName
        self.dot_minecraftPath = dir_dot_minecraft
        # 获取资源包
        list_resourcepacks = os.listdir(dir_resourcepacks)
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

        start_time = time.perf_counter()#记录开始时间

        imported_time = str(context.scene.Crafter_import_time)
        bpy.ops.object.mode_set(mode='OBJECT')
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
        dir_version = os.path.dirname(jarPath)
        versionJsonPath = os.path.join(dir_version, version + ".json")
        resourcepacksPaths = []

        save = self.save
        dot_minecraftPath = self.dot_minecraftPath

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
        #写入conifg.json
        point_cloud_mode = addon_prefs.Point_Cloud_Mode
        if point_cloud_mode:
            status = 2
        else:
            status = 1
        
        worldconfig = {
            "worldPath": worldPath,
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
            "activeLOD":addon_prefs.activeLOD,
            "useUnderwaterLOD":addon_prefs.useUnderwaterLOD,
            "isLODAutoCenter":addon_prefs.isLODAutoCenter,
            "LODCenterX":addon_prefs.LODCenterX,
            "LODCenterZ":addon_prefs.LODCenterZ,
            "LOD0renderDistance":addon_prefs.LOD0renderDistance,
            "LOD1renderDistance":addon_prefs.LOD1renderDistance,
            "LOD2renderDistance":addon_prefs.LOD2renderDistance,
            "LOD3renderDistance":addon_prefs.LOD3renderDistance,
            "solid": 0,
        }

        dir_importer = os.path.join(dir_init_main, "importer")
        dir_config = os.path.join(dir_importer, "config")
        dir_json_config = os.path.join(dir_config, "config.json")

        with open(dir_json_config, 'w', encoding='utf-8') as config:
            # for key, value in worldconfig.items():
            #     config.write(f"{key} = {value}\n")
            json.dump(worldconfig, config, indent=4)

        # 删去之前导出的obj
        dir_importer = os.path.join(dir_init_main, "importer")
        dir_exe_importer = os.path.join(dir_importer, "WorldImporter.exe")
        for file in os.listdir(dir_importer):
            if file.endswith(".obj"):
                os.remove(os.path.join(dir_importer, file))
        #生成obj

# ==================================================================================
        #旧的exe唤起命令，在exe从cpp14升级到cpp20后无效

        # try:
        #     # 在新的进程中运行WorldImporter.exe
        #     CREATE_NEW_PROCESS_GROUP = 0x00000200
        #     DETACHED_PROCESS = 0x00000008
        #     process = subprocess.Popen(
        #         [dir_exe_importer],
        #         cwd=dir_importer,
        #         creationflags=CREATE_NEW_PROCESS_GROUP | DETACHED_PROCESS,
        #         shell=addon_prefs.shell
        #     )
        #     self.report({'INFO'}, f"WorldImporter.exe started in a new process")
        #     #等待进程结束
        #     process.wait()
        # except Exception as e:
        #     self.report({'ERROR'}, f"Error: {e}")
        #     return {"CANCELLED"}

# ==================================================================================
        # 定义SHELLEXECUTEINFO结构体
        # class SHELLEXECUTEINFO(ctypes.Structure):
        #     _fields_ = [
        #         ('cbSize', wintypes.DWORD),
        #         ('fMask', wintypes.ULONG),
        #         ('hwnd', wintypes.HWND),
        #         ('lpVerb', wintypes.LPCWSTR),
        #         ('lpFile', wintypes.LPCWSTR),
        #         ('lpParameters', wintypes.LPCWSTR),
        #         ('lpDirectory', wintypes.LPCWSTR),
        #         ('nShow', ctypes.c_int),
        #         ('hInstApp', wintypes.HINSTANCE),
        #         ('lpIDList', ctypes.c_void_p),
        #         ('lpClass', wintypes.LPCWSTR),
        #         ('hKeyClass', wintypes.HKEY),
        #         ('dwHotKey', wintypes.DWORD),
        #         ('hMonitor', wintypes.HANDLE),
        #         ('hProcess', wintypes.HANDLE)
        #     ]


        # # 配置结构体参数
        # sei = SHELLEXECUTEINFO()
        # sei.cbSize = ctypes.sizeof(SHELLEXECUTEINFO)
        # sei.fMask = 0x00000040  # SEE_MASK_NOCLOSEPROCESS
        # sei.lpVerb = 'runas'
        # sei.lpFile = dir_exe_importer
        # sei.lpDirectory = dir_importer
        # sei.nShow = addon_prefs.shell  # 隐藏窗口

        # # 调用ShellExecuteEx
        # if not ctypes.windll.shell32.ShellExecuteExW(ctypes.byref(sei)):
        #     self.report({'ERROR'}, "Failed to start process")
        #     return {"CANCELLED"}

        # # 等待进程结束
        # ctypes.windll.kernel32.WaitForSingleObject(sei.hProcess, 0xFFFFFFFF)
        # ctypes.windll.kernel32.CloseHandle(sei.hProcess)

        # # 继续执行后续代码...

# ==================================================================================

        class SHELLEXECUTEINFOW(ctypes.Structure):
            _fields_ = [
                ("cbSize", ctypes.c_ulong),
                ("fMask", ctypes.c_ulong),
                ("hwnd", ctypes.c_void_p),
                ("lpVerb", ctypes.c_wchar_p),
                ("lpFile", ctypes.c_wchar_p),
                ("lpParameters", ctypes.c_wchar_p),
                ("lpDirectory", ctypes.c_wchar_p),
                ("nShow", ctypes.c_int),
                ("hInstApp", ctypes.c_void_p),
                ("lpIDList", ctypes.c_void_p),
                ("lpClass", ctypes.c_wchar_p),
                ("hKeyClass", ctypes.c_void_p),
                ("dwHotKey", ctypes.c_ulong),
                ("hIcon", ctypes.c_void_p),
                ("hProcess", ctypes.c_void_p)
            ]

        sei = SHELLEXECUTEINFOW()
        sei.cbSize = ctypes.sizeof(SHELLEXECUTEINFOW)
        sei.fMask = 0x00000040  # SEE_MASK_NOCLOSEPROCESS
        sei.lpVerb = 'runas'
        sei.lpFile = dir_exe_importer
        sei.lpDirectory = dir_importer
        sei.nShow = addon_prefs.shell

        # 执行并等待
        if ctypes.windll.shell32.ShellExecuteExW(ctypes.byref(sei)):
            ctypes.windll.kernel32.WaitForSingleObject(sei.hProcess, -1)
            ctypes.windll.kernel32.CloseHandle(sei.hProcess)
        else:
            self.report({'ERROR'}, "Failed to start WorldImporter.exe")
            return {"CANCELLED"}


        used_time = time.perf_counter() - start_time
        self.report({'INFO'}, i18n("At") + " " + str(used_time)[:6] + "s,"+ i18n("WorldImporter.exe finished"))

        #导入obj
        have_obj = False
        real_name_dic = {}
        material_should_delete = []
        for file in os.listdir(dir_importer):
            if file.endswith(".obj"):
                pre_import_objects = set(bpy.data.objects)#纪录当前场景中的所有对象
                
                bpy.ops.wm.obj_import(filepath=os.path.join(dir_importer, file))
                bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
                have_obj = True
                
                post_import_objects = set(bpy.data.objects)
                new_objects = post_import_objects - pre_import_objects# 计算新增对象
                imported_objects = list(new_objects)
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
                    add_C_time(obj=obj)
                # 统计时间
                used_time = time.perf_counter() - start_time
                self.report({'INFO'}, i18n("At") + " " + str(used_time)[:6] + "s,"+ file + i18n("imported"))
        for material in material_should_delete:
            bpy.data.materials.remove(bpy.data.materials[material])
        if not have_obj:
            self.report({'ERROR'}, "WorldImporter didn't export obj!")
            return {"CANCELLED"}
        
        # 若不存在，则导入Crafter-Moving_texture节点组
        if not "Crafter-Moving_texture" in bpy.data.node_groups:
            with bpy.data.libraries.load(dir_blend_append, link=False) as (data_from, data_to):
                data_to.node_groups = ["Crafter-Moving_texture"]
            bpy.data.node_groups["Crafter-Moving_texture"].use_fake_user = True
        # 若不存在，则导入群系着色纹理节点
        if not "Crafter-biomeTex" in bpy.data.node_groups:
            node_groups_use_fake_user = ["Crafter-biomeTex"]
            with bpy.data.libraries.load(dir_blend_append, link=False) as (data_from, data_to):
                data_to.node_groups = [name for name in data_from.node_groups if name in node_groups_use_fake_user]
            for node_group in node_groups_use_fake_user:
                bpy.data.node_groups[node_group].use_fake_user = True
        # 复制并修改Crafter-biomeTex
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

        # 查找所需节点
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
            # 添加群系着色纹理,PBR、法线纹理
            node_liomeTex = nodes.new("ShaderNodeGroup")
            node_liomeTex.location = (node_output_EEVEE.location.x - 400, node_output_EEVEE.location.y - 550)
            node_liomeTex.node_tree = node_group_biomeTex_copy
            if node_tex_base != None:
                load_normal_and_PBR(node_tex_base=node_tex_base, nodes=nodes, links=links,)
                nodes.active = node_tex_base
        bpy.ops.file.pack_all()
            
        #完成导入
        used_time = time.perf_counter() - start_time
        self.report({'INFO'}, i18n("Importing finished.Time used:") + str(used_time)[:6] + "s")
        # 自动定位到视图
        for window in context.window_manager.windows:
            for area in window.screen.areas:
                if area.type == 'VIEW_3D':
                    # 需要同时覆盖window/area/region三个上下文参数
                    for region in area.regions:
                        if region.type == 'WINDOW':  # 只处理主区域
                            try:
                                with context.temp_override(window=window, area=area, region=region):
                                    bpy.ops.view3d.view_selected()
                            except:
                                pass
                            break
                            
        # 保存历史世界
        dir_json_history_worlds = os.path.join(dir_cafter_data, "history_worlds.json")
        # 读取json，若不存在则创建一个空的json文件
        if os.path.exists(dir_json_history_worlds):
            with open(dir_json_history_worlds, 'r', encoding='utf-8') as file:
                json_history_worlds = json.load(file)
        else:
            json_history_worlds = {}
        # 根据是否隔离，按不同方式保存历史记录
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
                
        # 保存到json文件
        with open(dir_json_history_worlds, 'w', encoding='utf-8') as file:
            json.dump(json_history_worlds, file, indent=4)

        # 保存最近世界
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
        # 保存到json文件
        with open(dir_json_latest_worlds, 'w', encoding='utf-8') as file:
            json.dump(json_latest_worlds, file, indent=4)
        # 归零index
        addon_prefs.Latest_World_List_index = 0
        addon_prefs.History_World_Settings_List_index = 0
        #增加Crafter_import_time计数
        context.scene.Crafter_import_time += 1
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
        
        with open(dir_json_history_worlds, 'r', encoding='utf-8') as file:
            json_history_worlds = json.load(file)
        if type(json_history_worlds) == list:
            json_history_worlds = {}
        # 整理json
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
                dir_versions = os.path.join(root, "versions")
                # 添加版本
                for version in os.listdir(dir_versions):
                    if os.path.exists(os.path.join(dir_versions, version, version + ".jar")):
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
        # 清理最近世界历史记录
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
            dir_root = addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name
            dir_undivided_saves = os.path.join(dir_root,"saves")
            if os.path.exists(dir_undivided_saves):
                dir_save = os.path.join(dir_undivided_saves,addon_prefs.History_World_Saves_List[addon_prefs.History_World_Saves_List_index].name)
            else:
                dir_version = os.path.join(dir_root,"versions",addon_prefs.History_World_Versions_List[addon_prefs.History_World_Versions_List_index].name)
                dir_save = os.path.join(dir_version,"saves",addon_prefs.History_World_Saves_List[addon_prefs.History_World_Saves_List_index].name)
            addon_prefs.World_Path = dir_save
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

        worldPath = addon_prefs.World_Path
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

        worldPath = addon_prefs.World_Path
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

        worldPath = addon_prefs.World_Path
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

        worldPath = addon_prefs.World_Path
        dir_saves = os.path.dirname(worldPath)
        target_name = addon_prefs.Game_Resources_List[addon_prefs.Game_Resources_List_index].name
        for i in range(len(json_resourcepacks[dir_saves][0])):
            print(i)
            print(json_resourcepacks[dir_saves][0][i])
            print(target_name)
            if json_resourcepacks[dir_saves][0][i] == target_name:
                print('=')
                if i < len(addon_prefs.Game_Resources_List) - 1:
                    print("down")
                    json_resourcepacks[dir_saves][0][i], json_resourcepacks[dir_saves][0][i + 1] = json_resourcepacks[dir_saves][0][i + 1], json_resourcepacks[dir_saves][0][i]
                    addon_prefs.Game_Resources_List_index += 1
                    break

        with open(dir_json_resourcepacks, 'w', encoding='utf-8') as file:
            json.dump(json_resourcepacks, file, indent=4)

        bpy.ops.crafter.reload_game_resources()

        return {'FINISHED'}
    
# ==================== 刷新 ====================

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
        resourcepacks = json_resourcepacks[dir_saves]
        for resourcepack in resourcepacks[0]:
            resourcepack_use = addon_prefs.Game_Resources_List.add()
            resourcepack_use.name = resourcepack
        for resourcepack in resourcepacks[1]:
            resourcepack_unuse = addon_prefs.Game_unuse_Resources_List.add()
            resourcepack_unuse.name = resourcepack


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
                    if addon_prefs.is_Undivided:# 是否开启版本隔离
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
                    if len(addon_prefs.History_World_Saves_List) > 0:
                        if addon_prefs.History_World_Saves_List_index < 0 or addon_prefs.History_World_Saves_List_index >= len(addon_prefs.History_World_Saves_List):
                            addon_prefs.History_World_Saves_List_index = 0
                        for settings in json_history_worlds[addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name][addon_prefs.History_World_Versions_List[addon_prefs.History_World_Versions_List_index].name][addon_prefs.History_World_Saves_List[addon_prefs.History_World_Saves_List_index].name]:
                            history_world_setting = addon_prefs.History_World_Settings_List.add()
                            history_world_setting.name = f"{settings[0][0]} {settings[0][1]} {settings[0][2]} {settings[1][0]} {settings[1][1]} {settings[1][2]}" 

        return {'FINISHED'}

# ==================== UIList ====================

class VIEW3D_UL_CrafterUndividedVersions(bpy.types.UIList):# 无隔离 版本
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        text = item.name
        index_na = text.rfind("\\")
        text = text[index_na+1:]
        layout.label(text=text)

class VIEW3D_UL_CrafterGameResources(bpy.types.UIList):# 游戏 资源包
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
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
        layout.label(text=true_text)

class VIEW3D_UL_CrafterGameUnuseResources(bpy.types.UIList):# 游戏 未使用 资源包
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
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
        layout.label(text=true_text)

class VIEW3D_UL_CrafterLatestWorldList(bpy.types.UIList):# 最近世界 列表
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
       world = item.name.split("|")
       text = f"{world[0]}     {world[1]}     {world[2]}"
       layout.label(text=text)

class VIEW3D_UL_CrafterHistoryWorldRootsList(bpy.types.UIList):# 历史世界 根 列表
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        layout.label(text=item.name)

class VIEW3D_UL_CrafterHistoryWorldVersionsList(bpy.types.UIList):# 历史世界 版本 列表
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        layout.label(text=item.name)

class VIEW3D_UL_CrafterHistoryWorldSavesList(bpy.types.UIList):# 历史世界 存档 列表
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        layout.label(text=item.name)

class VIEW3D_UL_CrafterHistoryWorldSettingsList(bpy.types.UIList):# 历史世界 设置 列表
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        settings=item.name.split(" ")
        layout.label(text=f"{settings[0]}     {settings[1]}     {settings[2]}   |   {settings[3]}     {settings[4]}     {settings[5]}")
