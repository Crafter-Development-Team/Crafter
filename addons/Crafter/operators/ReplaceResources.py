import bpy
import os
import json

from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from bpy.props import *
from .. import dir_cafter_data, dir_resourcepacks_plans, dir_materials, dir_classification_basis, dir_blend_append, dir_init_main, dir_environments
from .Defs import *

# ==================== 替换资源包 ====================

class VIEW3D_OT_CrafterReplaceResources(bpy.types.Operator):
    bl_label = "Replace Resources"
    bl_idname = "crafter.replace_resources"
    bl_description = "Replace resources,but can only replace textures with the same name"
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True
        return any(obj.type == "MESH" for obj in context.selected_objects)

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        bpy.ops.crafter.reload_all()
        if not (-1 < addon_prefs.Resources_Plans_List_index and addon_prefs.Resources_Plans_List_index < len(addon_prefs.Resources_Plans_List)):
            return {'CANCELLED'}
        bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
        dir_resourcepacks = os.path.join(dir_resourcepacks_plans, addon_prefs.Resources_Plans_List[addon_prefs.Resources_Plans_List_index].name)
        dir_crafter_json = os.path.join(dir_resourcepacks, "crafter.json")
        # 加载json
        with open(dir_crafter_json, 'r', encoding='utf-8') as file:
            crafter_json = json.load(file)
        images = []
        for resource in crafter_json:
            dir_resourcepack = os.path.join(dir_resourcepacks, resource)
            if not os.path.exists(dir_resourcepack):
                try:
                    unzip(dir_resourcepack + ".zip", dir_resourcepack)
                except Exception as e:
                    print(e)
            dir_assets = os.path.join(dir_resourcepack, "assets")
            files_list = []
            for root, dirs, files in os.walk(dir_assets):
                for file in files:
                    if not root.endswith("colormap"):
                        file_path = os.path.join(root, file)
                        files_list.append((file, file_path))
            images.append(files_list)
        is_Vanilla = False
        if len(crafter_json) == 0:
            is_Vanilla = True
        
        for obj in context.selected_objects:
            if obj.type == "MESH":
                add_to_mcmts_collection(object=obj,context=context)
                add_C_time(obj=obj)
        for name_material in context.scene.Crafter_mcmts:
            material = bpy.data.materials[name_material.name]
            node_tree_material = material.node_tree
            nodes = node_tree_material.nodes
            links = node_tree_material.links
            is_materialed = False
            for node in nodes:
                if node.type == 'TEX_IMAGE':
                    if node.image == None:
                        nodes.remove(node)
                    else:
                        name_image = fuq_bl_dot_number(node.image.name)
                        if name_image.endswith("_n.png") or name_image.endswith("_s.png") or name_image.endswith("_a.png"):
                            # 移除pbr、法向材质节点
                            bpy.data.images.remove(node.image)
                            nodes.remove(node)
                        elif name_image.endswith(".png"):
                            node.interpolation = "Closest"
                            if not is_Vanilla:
                                node_tex_base = node
                                found_texture = False
                                i = 0
                                while i < len(images) and not found_texture:
                                    j = 0
                                    while j < len(images[i]) and not found_texture:
                                        if name_image == images[i][j][0]:
                                            node.image = bpy.data.images.load(images[i][j][1])
                                            found_texture = True
                                        j += 1
                                    i += 1
                elif node.type == 'GROUP':
                    if node.node_tree != None:
                        if node.node_tree.name.startswith("CI-"):
                            is_materialed = True
                            group_CI = node
                        if node.node_tree.name.startswith("C-PBR_Parser"):
                            node_C_PBR_Parser = node
            if is_materialed and (not is_Vanilla):
                node_tex_normal, node_tex_PBR = load_normal_and_PBR(node_tex_base=node_tex_base, nodes=nodes, links=links,)
                link_base_normal_and_PBR(node_tex_base=node_tex_base, group_CI=group_CI, links=links, node_C_PBR_Parser=node_C_PBR_Parser,node_tex_normal=node_tex_normal, node_tex_PBR=node_tex_PBR)
                
        for obj in context.selected_objects:
            if obj.type == "MESH":
                add_to_crafter_mcmts_collection(object=obj,context=context)

        return {'FINISHED'}
    def invoke(self, context, event):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        bpy.ops.crafter.reload_all()
        return context.window_manager.invoke_props_dialog(self)

    def draw(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        layout = self.layout
        
        layout.label(text=i18n("Resources"))
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

# ==================== 打开资源包列表文件夹 ====================

class VIEW3D_OT_CrafterOpenResourcesPlans(bpy.types.Operator):
    bl_label = "Open Resources Plans"
    bl_idname = "crafter.open_resources_plans"
    bl_description = ""
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        folder_path = dir_resourcepacks_plans
        open_folder(folder_path)

        return {'FINISHED'}



# ==================== 资源包优先级 ====================

class VIEW3D_OT_CrafterUpResource(bpy.types.Operator):#提高 资源包 优先级
    bl_label = "Up resource's priority"    
    bl_idname = "crafter.up_resource"
    bl_description = ""

    @classmethod
    def poll(cls, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        return addon_prefs.Resources_List_index > 0

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        dir_resourcepacks = os.path.join(dir_resourcepacks_plans, addon_prefs.Resources_Plans_List[addon_prefs.Resources_Plans_List_index].name)
        dir_crafter_json = os.path.join(dir_resourcepacks, "crafter.json")

        with open(dir_crafter_json, 'r', encoding='utf-8') as file:
            crafter_json = json.load(file)
        target_name = addon_prefs.Resources_List[addon_prefs.Resources_List_index].name
        for i in range(len(crafter_json)):
            if crafter_json[i] == target_name:
                if i > 0:
                    crafter_json[i], crafter_json[i - 1] = crafter_json[i - 1], crafter_json[i]
                    addon_prefs.Resources_List_index -= 1
                    break
        with open(dir_crafter_json, 'w', encoding='utf-8') as file:
            json.dump(crafter_json, file, indent=4)
        bpy.ops.crafter.reload_resources()

        return {'FINISHED'}

class VIEW3D_OT_CrafterDownResource(bpy.types.Operator):#降低 资源包 优先级
    bl_label = "Down resource's priority"    
    bl_idname = "crafter.down_resource"
    bl_description = ""

    @classmethod
    def poll(cls, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        
        return addon_prefs.Resources_List_index < len(addon_prefs.Resources_List) - 1

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        dir_resourcepacks = os.path.join(dir_resourcepacks_plans, addon_prefs.Resources_Plans_List[addon_prefs.Resources_Plans_List_index].name)
        dir_crafter_json = os.path.join(dir_resourcepacks, "crafter.json")

        with open(dir_crafter_json, 'r', encoding='utf-8') as file:
            crafter_json = json.load(file)
        target_name = addon_prefs.Resources_List[addon_prefs.Resources_List_index].name
        for i in range(len(crafter_json)):
            if crafter_json[i] == target_name:
                if i < len(addon_prefs.Resources_List) - 1:
                    crafter_json[i], crafter_json[i + 1] = crafter_json[i + 1], crafter_json[i]
                    addon_prefs.Resources_List_index += 1
                    break
        with open(dir_crafter_json, 'w', encoding='utf-8') as file:
            json.dump(crafter_json, file, indent=4)
        bpy.ops.crafter.reload_resources()

        return {'FINISHED'}

# ==================== 刷新 ====================

class VIEW3D_OT_CrafterReloadResourcesPlans(bpy.types.Operator):#刷新 资源包 预设 列表
    bl_label = "Reload Resources Plans"
    bl_idname = "crafter.reload_resources_plans"
    bl_description = ""
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        addon_prefs.Resources_Plans_List.clear()
        for folder in os.listdir(dir_resourcepacks_plans):
            if os.path.isdir(os.path.join(dir_resourcepacks_plans, folder)):
                plan_name = addon_prefs.Resources_Plans_List.add()
                plan_name.name = folder
        if (addon_prefs.Resources_Plans_List_index < 0 or addon_prefs.Resources_Plans_List_index >= len(addon_prefs.Resources_Plans_List)) and addon_prefs.Resources_Plans_List_index != 0:
            addon_prefs.Resources_Plans_List_index = 0

        return {'FINISHED'}

class VIEW3D_OT_CrafterReloadResources(bpy.types.Operator):#刷新 资源包 列表
    bl_label = "Reload Resources"
    bl_idname = "crafter.reload_resources"
    bl_description = ""
    
    @classmethod
    def poll(cls, context: bpy.types.Context):
        return True

    def execute(self, context: bpy.types.Context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        dir_resourcepacks = os.path.join(dir_resourcepacks_plans, addon_prefs.Resources_Plans_List[addon_prefs.Resources_Plans_List_index].name)
        list_dir_resourcepacks = os.listdir(dir_resourcepacks)
        dir_crafter_json = os.path.join(dir_resourcepacks, "crafter.json")

        addon_prefs.Resources_List.clear()
        json_crafter_copy =[]
        if "crafter.json" in list_dir_resourcepacks:
            try:
                with open(dir_crafter_json, "r", encoding="utf-8") as file:
                    json_crafter = json.load(file)
            except:
                json_crafter = []
            json_crafter_copy =json_crafter.copy()
        json_crafter = []
        for folder in list_dir_resourcepacks:
            if folder.endswith(".zip") and (not folder[:-4] in json_crafter_copy):
                json_crafter.append(folder[:-4])
        for resourcepack in json_crafter_copy:
            if os.path.exists(os.path.join(dir_resourcepacks, resourcepack + ".zip")):
                json_crafter.append(resourcepack)
        for resourcepack in json_crafter:
            resourcepack_name = addon_prefs.Resources_List.add()
            resourcepack_name.name = resourcepack

        with open(dir_crafter_json, "w", encoding="utf-8") as file:
            json.dump(json_crafter, file, ensure_ascii=False, indent=4)

        if (addon_prefs.Resources_List_index < 0 or addon_prefs.Resources_List_index >= len(addon_prefs.Resources_List)) and addon_prefs.Resources_List_index != 0:
            addon_prefs.Resources_List_index = 0

        return {'FINISHED'}

# ==================== UIList ====================

class VIEW3D_UL_CrafterResources(bpy.types.UIList):# 资源包 预设 列表
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        layout.label(text=item.name)

class VIEW3D_UL_CrafterResourcesInfo(bpy.types.UIList):# 资源包 预设详情 列表
     def draw_item(self, context, layout, data, item, icon, active_data, active_propname):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        # dir_resourcepacks = os.path.join(dir_resourcepacks_plans, addon_prefs.Resources_Plans_List[addon_prefs.Resources_Plans_List_index].name)
        # dir_resourcepack = os.path.join(dir_resourcepacks, item.name)
        
        item_name = ""
        i = 0
        while i < len(item.name):
            if item.name[i] == "§":
                i+=1
            elif item.name[i] != "!":
                item_name += item.name[i]
            i+=1
        # if "pack.png" in os.listdir(dir_resourcepack):
        #     layout.label(text=item_name,icon="crafter_resources" + item.name)
        # else:
        #     layout.label(text=item_name)
        layout.label(text=item_name)



# ==================== 弃用 ====================

class VIEW3D_OT_CrafterSetTextureInterpolation(bpy.types.Operator):#设置纹理插值
    bl_label = "Set Texture Interpolation"    
    bl_idname = "crafter.set_texture_interpolation"
    bl_description = "Set Texture Interpolation"
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context):
        return any(obj.type == "MESH" for obj in context.selected_objects)

    def execute(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences

        for object in context.selected_objects:
            if object.type == "MESH":
                for material in object.data.materials:
                    for node in material.node_tree.nodes:
                        if node.type == 'TEX_IMAGE':
                            node.interpolation = addon_prefs.Texture_Interpolation
        return {'FINISHED'}
