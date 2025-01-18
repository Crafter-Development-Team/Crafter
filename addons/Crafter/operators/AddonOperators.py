import bpy
import os
import time
import subprocess
import threading
import platform
import json

from ..config import __addon_name__
from ..__init__ import resourcepacks_dir, materials_dir, classification_basis_dir, blend_append_dir

#==========通用操作==========
def open_folder(folder_path: str):
    if platform.system() == "Windows":
        os.startfile(folder_path)
    elif platform.system() == "Darwin":  # MacOS
        subprocess.run(["open", folder_path])
    else:  # Linux
        subprocess.run(["xdg-open", folder_path])

def make_json_together(dict1, dict2):
    for key, value in dict2.items():
        if key in dict1:
            if isinstance(dict1[key], dict) and isinstance(value, dict):
                make_json_together(dict1[key], value)
            elif isinstance(dict1[key], list) and isinstance(value, list):
                dict1[key] = list(set(dict1[key] + value))
            else:
                dict1[key] = value
        else:
            dict1[key] = value

#==========导入世界操作==========
class VIEW3D_OT_CrafterImportWorld(bpy.types.Operator):#导入世界
    bl_label = "Import World"
    bl_idname = "crafter.import_world"
    bl_description = "Import world"
    bl_options = {'INTERNAL'}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        worldconfig = {
            "worldPath": addon_prefs.World_Path,
            "biomeMappingFile": "config\\mappings\\biomes_mapping.json",
            "solid": addon_prefs.solid,
            "minX": min(addon_prefs.XYZ_1[0], addon_prefs.XYZ_2[0]),
            "maxX": max(addon_prefs.XYZ_1[0], addon_prefs.XYZ_2[0]),
            "minY": min(addon_prefs.XYZ_1[1], addon_prefs.XYZ_2[1]),
            "maxY": max(addon_prefs.XYZ_1[1], addon_prefs.XYZ_2[1]),
            "minZ": min(addon_prefs.XYZ_1[2], addon_prefs.XYZ_2[2]),
            "maxZ": max(addon_prefs.XYZ_1[2], addon_prefs.XYZ_2[2]),
            "status": 0,
        }

        parent_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        importer_dir = os.path.join(parent_dir, "importer")
        config_dir = os.path.join(importer_dir, "config")
        os.makedirs(config_dir, exist_ok=True)

        config_file_path = os.path.join(config_dir, "config.json")

        with open(config_file_path, 'w', encoding='gbk') as config_file:
            for key, value in worldconfig.items():
                config_file.write(f"{key} = {value}\n")

        self.report({'INFO'}, f"World config saved to {config_file_path}")

        importer_exe = os.path.join(importer_dir, "WorldImporter.exe")
        
        if os.path.exists(importer_exe):
            try:
                # 在新的进程中运行WorldImporter.exe
                CREATE_NEW_PROCESS_GROUP = 0x00000200
                DETACHED_PROCESS = 0x00000008
                subprocess.Popen(
                    [importer_exe],
                    cwd=importer_dir,
                    creationflags=CREATE_NEW_PROCESS_GROUP | DETACHED_PROCESS
                )
                self.report({'INFO'}, f"WorldImporter.exe started in a new process")

                # 使用线程监控状态
                threading.Thread(target=self.monitor_status, args=(config_file_path,), daemon=True).start()

            except Exception as e:
                self.report({'ERROR'}, f"Error: {e}")
        else:
            self.report({'ERROR'}, f"WorldImporter.exe not found at {importer_exe}")

        return {'FINISHED'}

    def monitor_status(self, config_file_path):
        while True:
            time.sleep(0.2)  # 每秒检查一次
            try:
                with open(config_file_path, 'r', encoding='gbk') as config_file:
                    content = config_file.read()
                    if "status = 3" in content:
                        content = content.replace("status = 3", "status = 0")
                        with open(config_file_path, 'w', encoding='gbk') as config_file:
                            config_file.write(content)
                        bpy.app.timers.register(self.import_output)
                        break
            except Exception as e:
                print(f"Error reading config file: {e}")
                continue

    def import_output(self):
        parent_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        importer_dir = os.path.join(parent_dir, "importer")
        output_obj = os.path.join(importer_dir, "output.obj")
        if os.path.exists(output_obj):
            bpy.ops.wm.obj_import(filepath=output_obj)
            self.report({'INFO'}, f"Imported {output_obj}")
        else:
            self.report({'WARNING'}, f"output.obj not found at {output_obj}")

class VIEW3D_OT_CrafterImportSurfaceWorld(bpy.types.Operator):#导入表层世界
    bl_label = "Import Surface World"
    bl_idname = "crafter.import_surface_world"
    bl_description = "Import the surface world"
    bl_options = {'REGISTER', 'UNDO'}
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        addon_prefs.solid = 0
        bpy.ops.crafter.improt_world()
        return {'FINISHED'}

class VIEW3D_OT_CrafterImportSolidArea(bpy.types.Operator):#导入可编辑区域==========未完善==========
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
        bpy.ops.crafter.improt_world()
        return {'FINISHED'}

#==========导入资源操作==========
class VIEW3D_OT_CrafterOpenResourcesPlans(bpy.types.Operator):#打开资源包列表文件夹
    bl_label = "Open Resources Plans"
    bl_idname = "crafter.open_resources_plans"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        folder_path = resourcepacks_dir
        open_folder(folder_path)

        return {'FINISHED'}

class VIEW3D_OT_CrafterReloadResourcesPlans(bpy.types.Operator):#刷新资源包列表
    bl_label = "Reload Resources Plans"
    bl_idname = "crafter.reload_resources_plans"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        addon_prefs.Resources_Plans_List.clear()
        for folder in os.listdir(resourcepacks_dir):
            if os.path.isdir(os.path.join(resourcepacks_dir, folder)):
                plan_name = addon_prefs.Resources_Plans_List.add()
                plan_name.name = folder
        return {'FINISHED'}

    bl_label = "Set Texture Interpolation"    
    bl_idname = "crafter.set_texture_interpolation"
    bl_description = "Set Texture Interpolation"
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context):
        return context.selected_objects

    def execute(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        for object in context.selected_objects:
            if object.type == "MESH":
                for material in object.data.materials:
                    for node in material.node_tree.nodes:
                        if node.type == 'TEX_IMAGE':
                            node.interpolation = addon_prefs.Texture_Interpolation
        return {'FINISHED'}

#==========加载材质操作==========
class VIEW3D_OT_CrafterOpenMaterials(bpy.types.Operator):#打开材质列表文件夹
    bl_label = "Open Materials"
    bl_idname = "crafter.open_materials"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        folder_path = materials_dir
        open_folder(folder_path)

        return {'FINISHED'}

class VIEW3D_OT_CrafterReloadMaterials(bpy.types.Operator):#刷新资源包列表
    bl_label = "Reload Materials"
    bl_idname = "crafter.reload_materials"
    bl_description = " "
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        addon_prefs.Materials_List.clear()
        for folder in os.listdir(materials_dir):
            base, extension = os.path.splitext(folder)
            if extension == ".blend":
                material_name = addon_prefs.Materials_List.add()
                material_name.name = base
        return {'FINISHED'}

class VIEW3D_OT_CrafterLoadMaterial(bpy.types.Operator):#加载材质
    bl_label = "Load Material"
    bl_idname = "crafter.load_material"
    bl_description = "Load Material"
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return context.selected_objects

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        bpy.ops.crafter.reload_materials()
        bpy.ops.crafter.reload_classification_basis()
        # 删除startswith(CO-)、startswith(CI-)节点组
        for node in bpy.data.node_groups:
            if node.name.startswith("CO-") or node.name.startswith("CI-"):
                bpy.data.node_groups.remove(node)
        # 删除CrafterIn物体、材质
        try:
            bpy.data.objects.remove(bpy.data.objects["CrafterIn"])
            bpy.data.materials.remove(bpy.data.materials["CrafterIn"], do_unlink=True)
        except:
            pass
        # 导入CO-节点组
        with bpy.data.libraries.load(blend_append_dir, link=False) as (data_from, data_to):
            data_to.node_groups = [name for name in data_from.node_groups if name == "CO-"]
        # 导入CrafterIn物体、材质、startswith(CI-)
        blend_material_dir = os.path.join(materials_dir, addon_prefs.Materials_List[addon_prefs.Materials_List_index].name + ".blend")
        with bpy.data.libraries.load(blend_material_dir, link=False) as (data_from, data_to):
            data_to.objects = [name for name in data_from.objects if name == "CrafterIn"]
        if "CrafterIns"  in bpy.data.collections:
            collection_CrafterIns = bpy.data.collections["CrafterIns"]
        else:
            collection_CrafterIns = bpy.data.collections.new(name="CrafterIns")
            bpy.context.scene.collection.children.link(collection_CrafterIns)
        collection_CrafterIns.objects.link(bpy.data.objects["CrafterIn"])
        # 获取分类依据地址
        classification_folder_name = addon_prefs.Classification_Basis_List[addon_prefs.Classification_Basis_List_index].name
        classification_folder_dir = os.path.join(classification_basis_dir, classification_folder_name)
        # 初始化COs，classification_list,banlist
        COs = ["CO-"]
        classification_list = {}
        banlist = []
        # 获取classification_list
        for filename in os.listdir(classification_folder_dir):
            file_path = os.path.join(classification_folder_dir, filename)
            if filename.endswith(".json"):
                try:
                    with open(file_path, 'r', encoding='utf-8') as file:
                        data = json.load(file)
                        banlist.extend(data["banlist"])
                        make_json_together(classification_list, data)
                except Exception as e:
                    print(e)
        # 创建所有startswith(CO-)节点组
        group_CO = bpy.data.node_groups['CO-']
        for type_name in classification_list:
            if type_name == "banlist":
                continue
            for group_name in classification_list[type_name]:
                group_new = group_CO.copy()
                group_new.name = "CO-" + group_name
                COs.append("CO-" + group_name)
        ## 删去原有着色器 并 重新添加startswith(CO-)节点组
        for object in context.selected_objects:
            if object.type == "MESH":
                for material in object.data.materials:
                    # 获得real_material_name(如果有mod_name,type_name,获得之,但目前好像没用...)
                    real_material_name = material.name
                    last_dot_index = real_material_name.rfind('.')
                    last_hen_index = real_material_name.rfind('-')
                    mod_name = "minecraft"
                    type_name = "block"
                    if not last_dot_index == -1:
                        real_material_name = real_material_name[:last_dot_index]
                    if not last_hen_index == -1:
                        mod_and_type = real_material_name[:last_hen_index]
                        real_material_name = real_material_name[last_hen_index+1:]
                        last____index = mod_and_type.rfind('_')
                        mod_name = real_material_name[:last____index]
                        type_name = real_material_name[last____index+1:last_hen_index]
                    # 如果在banlist里直接跳过
                    if real_material_name in banlist:
                        continue
                    node_tree_material = material.node_tree
                    nodes = node_tree_material.nodes
                    links = node_tree_material.links
                    #获得node_output
                    for node in nodes:
                        if node.type == "OUTPUT_MATERIAL" and node.is_active_output:
                            node_output = node
                    # 删去原有着色器
                    try:
                        from_node = node_output.inputs[0].links[0].from_node
                        if (from_node.type == "BSDF_PRINCIPLED" or from_node.node_tree == None)  and material.name != "CrafterIn":
                            node_tree_material.nodes.remove(from_node)
                    except:
                        pass
                    # 重新添加startswith(CO-)节点组
                    group_COn = nodes.new(type='ShaderNodeGroup')
                    group_COn.location = (node_output.location.x - 200, node_output.location.y)
                    for type_name in classification_list:
                        if type_name == "banlist":
                            continue
                        for group_name in classification_list[type_name]:
                            breakout = False
                            if "banlist" in classification_list[type_name][group_name]:
                                for item in classification_list[type_name][group_name]["banlist"]:
                                    if item in real_material_name:
                                        breakout = True
                                        break
                            if breakout:
                                break
                            if "key_words" in classification_list[type_name][group_name]:
                                for item in classification_list[type_name][group_name]["key_words"]:
                                    if item in real_material_name:
                                        group_COn.node_tree = bpy.data.node_groups["CO-" + group_name]
                                        breakout = True
                                        break
                            if breakout:
                                break
                            if "full_name" in classification_list[type_name][group_name]:
                                for item in classification_list[type_name][group_name]["full_name"]:
                                    if item == real_material_name:
                                        group_COn.node_tree = bpy.data.node_groups["CO-" + group_name]
                                        breakout = True
                                        break
                            if not breakout:
                                group_COn.node_tree = bpy.data.node_groups["CO-"]
                    # 连接外层节点
                    for output in group_COn.outputs:
                        links.new(output, node_output.inputs[output.name])
                    try:
                        for node in nodes:
                            if node.type == "TEX_IMAGE":
                                links.new(node.outputs[0], group_COn.inputs[0])
                                links.new(node.outputs[1], group_COn.inputs[1])
                    except Exception as e:
                        print(e)
        #连接startswith(CO-)、startswith(CI-)节点组
        for aCO in COs:
            group_CO = bpy.data.node_groups[aCO]
            nodes = group_CO.nodes
            links = group_CO.links
            for node in nodes:
                if node.type == "GROUP_OUTPUT" and node.is_active_output:
                    node_output = node
                if node.type == "GROUP_INPUT":
                    node_input = node
            group_CI = nodes.new(type='ShaderNodeGroup')
            group_CI.location = (node_output.location.x - 200, node_output.location.y)
            try:
                group_CI.node_tree = bpy.data.node_groups["CI-" + aCO[3:]]
            except:
                group_CI.node_tree = bpy.data.node_groups["CI-"]
            try:
                for output in group_CI.outputs:
                    links.new(output, node_output.inputs[output.name])
                for input in group_CI.inputs:
                    links.new(input, node_input.outputs[input.name])
            except:
                pass
        return {'FINISHED'}

class VIEW3D_OT_CrafterOpenClassificationBasis(bpy.types.Operator):#打开分类依据文件夹
    bl_label = "Open Classification Basis"
    bl_idname = "crafter.open_classification_basis"
    bl_description = " "
    bl_options = {'REGISTER'}
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        folder_path = classification_basis_dir
        open_folder(folder_path)

        return {'FINISHED'}

class VIEW3D_OT_CrafterReloadClassificationBasis(bpy.types.Operator):#刷新分类依据菜单
    bl_label = "Reload Classification Basis"
    bl_idname = "crafter.reload_classification_basis"
    bl_description = " "
    bl_options = {'REGISTER'}
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        addon_prefs.Classification_Basis_List.clear()
        for folder in os.listdir(classification_basis_dir):
            if os.path.isdir(os.path.join(classification_basis_dir, folder)):
                plan_name = addon_prefs.Classification_Basis_List.add()
                plan_name.name = folder

        return {'FINISHED'}


