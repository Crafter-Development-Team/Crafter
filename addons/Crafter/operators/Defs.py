import bpy
import os
import subprocess
import platform
import json
import zipfile
import sqlite3
import ctypes
import tempfile
import textwrap
import re
import fnmatch
import copy

# 只在Windows上导入wintypes
if platform.system() == "Windows":
    from ctypes import wintypes
    print("[DEBUG] Windows")


from ..config import __addon_name__
from ....common.i18n.i18n import i18n
from bpy.props import *
from ..__init__ import dir_blend_append, dir_init_main
from..properties import dirs_temp

donot = ["材质设置/Material Settings"]
len_color_jin = 21
name_library = "Crafter"
names_Crafter_Moving_texture = ["Crafter-动态纹理_首", "Crafter-动态纹理_首_渐变", "Crafter-动态纹理_尾", "Crafter-UV缩放"]

# ========== 贴图角色判定约定 ==========
# 材质里一张贴图到底算基础色、法向还是 PBR，唯一判据是"有没有同名的无后缀贴图"：
#   有名如 polaroids.png 与 polaroids_a.png 同时存在 → polaroids_a.png 是这套材质的 PBR；
#   只有孤立的 polaroids_a.png                    → 它本身就是基础色。
# 只按文件名后缀认类型会把资源包里名字自带 _a/_s/_n 的物品贴图（如 polaroids_a.png）
# 误判成 PBR/法向，所以这套规则必须整份材质一起判，不能逐节点判。
TEX_ROLE_BASE = "base"
TEX_ROLE_NORMAL = "normal"
TEX_ROLE_PBR = "pbr"
# 后缀 -> 角色；同一个后缀既可能是法向也可能是 PBR（导出器两套命名都用 _n/_s/_a）
TEX_SUFFIX_ROLES = (
    ("_n.png", TEX_ROLE_NORMAL),
    ("_s.png", TEX_ROLE_PBR),
    ("_a.png", TEX_ROLE_PBR),
)


def fuq_bl_dot_number(name: str):
    '''
    去除blender中重复时烦人的.xxx
    name: 待处理的字符串
    return: 处理后的字符串
    '''
    last_dot_index = name.rfind('.')
    if not last_dot_index == -1:
        if all("0" <= i <= "9" for i in name[last_dot_index + 1:]):
            name = name[:last_dot_index]
    return name


def texture_image_name(node):
    '''
    取贴图节点所用图像在节点体系里用的名字（去掉 Blender 的重名 .xxx 后缀）。
    node: 贴图节点；没有图像时返回 None。
    '''
    if node is None or getattr(node, "type", None) != "TEX_IMAGE" or node.image is None:
        return None
    return fuq_bl_dot_number(node.image.name)


def texture_block_name(name):
    '''
    取贴图的"贴图块名"：去掉 _n/_s/_a.png 后缀，同时去掉 .png 扩展名。
    acacia_log.png / acacia_log_n.png / acacia_log_s.png 都得到 acacia_log。
    拼同族文件名（<块名>_n.png 等）用它；判定同族关系也用它当配对键。
    '''
    if not name:
        return None
    for suffix, _role in TEX_SUFFIX_ROLES:
        if name.endswith(suffix):
            return name[:-len(suffix)]
    if name.endswith(".png"):
        return name[:-4]
    return name


def texture_base_name(name):
    '''
    兼容入口：取贴图块名（等价于 texture_block_name，见其说明）。
    '''
    return texture_block_name(name)


def texture_node_role(node, base_names):
    '''
    按整份材质的贴图集合判定单个贴图节点的角色。
    node:       贴图节点
    base_names: 该材质里所有无后缀贴图的块名集合（见 texture_node_roles）
    return:     TEX_ROLE_BASE / TEX_ROLE_NORMAL / TEX_ROLE_PBR；非贴图节点或没有图像时返回 None
    '''
    name = texture_image_name(node)
    if name is None:
        return None
    for suffix, role in TEX_SUFFIX_ROLES:
        if name.endswith(suffix):
            # 块名命中同名无后缀贴图 -> 这张是它的法向/PBR；
            # 没命中 -> 孤立的后缀贴图，本身就是基础色
            return role if texture_block_name(name) in base_names else TEX_ROLE_BASE
    return TEX_ROLE_BASE


def texture_node_roles(texture_nodes):
    '''
    批量判定贴图节点角色（一次算好无后缀贴图的块名集合，避免逐节点各判一遍）。
    return: {节点: 角色}
    '''
    nodes_list = [node for node in texture_nodes if node is not None]
    # 第一遍：把"无后缀贴图"（基础色）的块名登记下来，作为同族 _n/_s/_a 的认亲依据
    base_names = set()
    for node in nodes_list:
        name = texture_image_name(node)
        if name is None or not name.endswith(".png"):
            continue
        if texture_block_name(name) == name[:-4]:   # 没有任何 _n/_s/_a 后缀
            base_names.add(name[:-4])
    # 第二遍：逐张判角色
    result = {}
    for node in nodes_list:
        result[node] = texture_node_role(node, base_names)
    return result


def texture_node_by_role(texture_nodes, roles):
    '''
    在贴图节点里按角色取第一张；roles 是一个或多个角色（判断顺序即优先级）。
    用于 overlay 帧内只有一两张贴图时的取用（帧内标准布局为 基础色 + 法向 + PBR）。
    '''
    roles = (roles,) if isinstance(roles, str) else tuple(roles)
    node_roles = texture_node_roles(texture_nodes)
    for role in roles:
        for node, node_role in node_roles.items():
            if node_role == role:
                return node
    return None

# ========== tint 接口约定 ==========
# tint.json 里的 kind 取值；与 Crafter-biomeTex 的输出名一致（waterFog 为大写 F）
TINT_BIOME_KINDS = ("grass", "foliage", "dryfoliage", "water", "waterFog", "fog", "sky")
tint_interface_enable = "Tint Enable"
tint_interface_color = "Tint Color"

# ========== overlay 帧约定 ==========
# 导入时把导出器给出的共面叠加层(overlay.json)贴图放进材质内的 NodeFrame，
# label 形如 "C#<层序号>|<导出器完整材质名>"（序号从 1 开始，越大越靠上）。
# 旧前缀 "Crafter-overlay#" 仍然兼容识别。
# 分类/合成留到 Load Material 阶段，此处只承载贴图与元数据。
overlay_frame_prefix = "C#"
overlay_frame_prefix_legacy = "Crafter-overlay#"
# 叠加层材质全名：frame.label 也有 63 字节上限，超长会在 Blender 里被截断，
# 分类时就认不出这一层；完整名另存一份到 frame 的自定义属性（无长度限制）。
overlay_name_prop = "Crafter_overlay_name"
# overlay 帧的层序号标记（frame.label 可被用户改名/被 63 字节截断，检测不能只靠 label）
overlay_index_prop = "Crafter_overlay_index"


def overlay_layer_name_prop(index):
    '''叠加层材质全名在材质上的属性名（与 Crafter_overlay<i>_kind/_color 同一套约定）。'''
    return "Crafter_overlay" + str(index) + "_name"
overlay_layer_stride = 1000.0   # 每层 overlay 帧在基础材质下方的纵向间距
overlay_node_offset_x = 50.0    # 帧内贴图相对帧左上角的 x 偏移(与基础材质 x 列对齐)
overlay_mix_step = 250.0        # 输出前 Mix 链每层占用的横向间距(其他节点据此左移)
overlay_extra_shift = 100.0     # 有 overlay 时其他节点额外左移量(给 Parser 让出与贴图列的间隙)
overlay_mix_y_offset = 0.0      # Mix 相对输出节点的纵向偏移(0=顶对齐即插座齐平, 普通节点首个插槽同高)
parser_offset_x = 300.0         # Parser 相对 CI 的 x 偏移(留出 parser 自身宽度, 避免压住 CI)


def overlay_frame_label(index, name):
    '''生成 overlay 帧的 label'''
    return overlay_frame_prefix + str(index) + "|" + str(name)


def parse_overlay_frame_label(label):
    '''
    解析 overlay 帧 label -> (层序号, 完整材质名)；非 overlay 帧返回 (None, None)。
    兼容旧前缀 Crafter-overlay#。
    '''
    if not label:
        return None, None
    if label.startswith(overlay_frame_prefix):
        body = label[len(overlay_frame_prefix):]
    elif label.startswith(overlay_frame_prefix_legacy):
        body = label[len(overlay_frame_prefix_legacy):]
    else:
        return None, None
    sep = body.find("|")
    if sep == -1:
        return None, None
    index_text = body[:sep]
    name = body[sep + 1:]
    if not index_text.isdigit() or not name:
        return None, None
    return int(index_text), name


def overlay_frame_index(node):
    '''
    取 frame 的叠加层序号（从 1 开始；不是 overlay 帧返回 None）。
    优先读 frame 上的 Crafter_overlay_index 属性，label 只是显示用、用户可能改名或
    被 63 字节截断，所以不作为唯一依据；两者都没有才判定"不是 overlay 帧"。
    '''
    if node is None or getattr(node, "type", "") != "FRAME":
        return None
    try:
        index = int(node.get(overlay_index_prop, 0) or 0)
    except (TypeError, ValueError):
        index = 0
    if index > 0:
        return index
    index, _ = parse_overlay_frame_label(node.label)
    return index


def find_overlay_frames(nodes, material=None):
    '''
    收集材质节点里的 overlay 帧，按层序号排序返回 [(index, name, frame), ...]。
    name 取该层材质全名：优先材质属性 Crafter_overlay<i>_name（与 _kind/_color 同一套），
    其次兼容旧版存在 frame 上的属性，最后才用 label（label 只有 63 字节，超长会被截断）。
    '''
    frames = []
    for node in nodes:
        index = overlay_frame_index(node)
        if index is None:
            continue
        _, label_name = parse_overlay_frame_label(node.label)
        name = label_name or ""
        stored_name = ""
        if material is not None:
            stored_name = str(material.get(overlay_layer_name_prop(index), ""))
        if not stored_name:
            stored_name = str(node.get(overlay_name_prop, ""))
        if stored_name:
            name = stored_name
        frames.append((index, name, node))
    frames.sort(key=lambda item: item[0])
    return frames


# ========== 材质全名存储（自定义属性） ==========
# 导出器会把超过 63 字节的材质名截成 "<头部>~!<8位哈希>"，同时输出 materials.json
# （可见名 -> 完整名）。导入时把完整名写进材质自定义属性（IDProperty）：这是 .blend
# 里的数据，跟插件装没装无关——禁用/卸载/删掉插件、重开文件都不会丢，只有材质本身
# 被删除或属性被手动清掉才会没有。
full_name_prop = "Crafter_full_name"
# 已提示过"截断名缺全名"的材质名，避免每个材质刷一遍日志
_truncated_name_warned = set()
# 旧版做法（已废弃，仅保留读取兼容）：用 "CF<序号>#<片段>" 的嵌套 frame 存名字。
legacy_full_name_frame_prop = "crafter_full_name"
legacy_full_name_frame_pattern = re.compile(r"^CF(\d+)#(.*)$", re.S)


def store_full_material_name(material, full_name):
    '''
    把完整材质名写进材质自定义属性。
    return: 是否写入（名字没被截断或材质不可用返回 False）
    '''
    if material is None or not full_name:
        return False
    if fuq_bl_dot_number(material.name) == full_name:
        return False
    material[full_name_prop] = full_name
    return True


def get_full_material_name(material):
    '''读回完整材质名：优先自定义属性，其次兼容旧版 CF 嵌套帧；都没有返回 None。'''
    if material is None:
        return None
    stored = str(material.get(full_name_prop, ""))
    if stored:
        return stored
    if material.node_tree is None:
        return None
    legacy = []
    for node in material.node_tree.nodes:
        if node.type != "FRAME" or not node.get(legacy_full_name_frame_prop):
            continue
        match = legacy_full_name_frame_pattern.match(str(node.label))
        if match is None:
            continue
        legacy.append((int(match.group(1)), match.group(2)))
    if not legacy:
        # 可见名带截断标记（"~!<哈希>"）却拿不到全名：多半是 materials.json 缺失或来自
        # 旧版导出器。此时分类会退回用截断名，贴图名等尾部信息丢失——提示一次，免得
        # 用户只看到"分组怪怪的"却不知道原因。
        name = str(getattr(material, "name", ""))
        if "~!" in name and name not in _truncated_name_warned:
            _truncated_name_warned.add(name)
            warn_log(f"材质名被截断但缺少完整名（materials.json 缺失或旧导出器），"
                     f"分类将按截断名进行: {name}")
        return None
    legacy.sort(key=lambda item: item[0])
    return "".join(chunk for _, chunk in legacy)


def overlay_layout_shift(nodes, frames=None):
    '''
    overlay 图层在输出节点前占用的布局宽度: 层数 x overlay_mix_step;
    有 overlay 时其他节点额外再左移 overlay_extra_shift(给 Parser 让出与贴图列的间隙)。
    '''
    count = len(find_overlay_frames(nodes) if frames is None else frames)
    if count <= 0:
        return 0.0
    return count * overlay_mix_step + overlay_extra_shift


def in_overlay_frame(node):
    '''
    节点是否位于某个 overlay 帧内（沿 parent 链查找）
    '''
    parent = getattr(node, "parent", None)
    while parent is not None:
        if parent.type == "FRAME" and overlay_frame_index(parent) is not None:
            return True
        parent = getattr(parent, "parent", None)
    return False


def overlay_frame_base_texture(nodes, frame):
    '''
    取 overlay 帧内的基础色贴图节点（直接子节点里角色为基础色的第一张贴图）。
    角色按整帧的贴图集合判定（见 texture_node_role）：帧内只有一张 _a.png 时它就是基础色。
    '''
    texture_nodes = [node for node in nodes
                     if node.parent == frame and node.type == "TEX_IMAGE" and node.image is not None]
    return texture_node_by_role(texture_nodes, TEX_ROLE_BASE)


def overlay_frame_ci(nodes, frame):
    '''
    取 overlay 帧内的 CI 节点（Load Material 生成，直接子节点）
    '''
    for node in nodes:
        if node.parent != frame:
            continue
        if node.type == "GROUP" and node.node_tree is not None and node.node_tree.name.startswith("CI-"):
            return node
    return None


def overlay_frame_parser(nodes, frame):
    '''
    取 overlay 帧内的 C-PBR_Parser 节点（Load Material 生成，直接子节点）
    '''
    for node in nodes:
        if node.parent != frame:
            continue
        if node.type == "GROUP" and node.node_tree is not None and node.node_tree.name.startswith("C-PBR_Parser"):
            return node
    return None


def build_overlay_frames(material, overlays, dir_importer):
    '''
    为材质创建 overlay 帧：每层一个 NodeFrame（label 记录导出器给的完整材质名），
    帧按层号排在基础材质正下方同一 x 列（整体一条竖排），帧内放叠加层基础色贴图 +
    法向/PBR 贴图（动态纹理帧保持嵌套在 overlay 帧内），tint 元数据写入材质自定义
    属性（Crafter_overlay<i>_kind/_color）供 Load Material 阶段使用。
    material:     目标 Blender 材质
    overlays:     overlay.json 中该材质的层列表 [{"name","texture","kind"?,"color"?}, ...]
    dir_importer: WorldImporter 输出目录（贴图路径相对该目录）
    '''
    node_tree = material.node_tree
    if node_tree is None:
        return
    nodes = node_tree.nodes
    links = node_tree.links

    base_location = None
    for node in nodes:
        if node.type == "TEX_IMAGE" and node.image is not None:
            base_location = node.location
            break
    if base_location is None:
        base_location = [0.0, 0.0]

    for index, layer in enumerate(overlays, start=1):
        if not isinstance(layer, dict):
            continue
        name = str(layer.get("name", ""))
        texture = str(layer.get("texture", ""))
        if not name or not texture:
            continue
        texture_path = os.path.join(dir_importer, texture.replace("/", os.sep))
        if not os.path.exists(texture_path):
            warn_log(f"overlay 贴图缺失，跳过: {texture}")
            continue

        frame = nodes.new(type="NodeFrame")
        frame.label = overlay_frame_label(index, name)
        # 层序号存属性：它是"这个材质有叠加层"的判定依据，不能只靠 label（用户可改名、
        # 超长会被截断）。完整名同样存材质属性（与 Crafter_overlay<i>_kind/_color 对齐）。
        frame[overlay_index_prop] = index
        material[overlay_layer_name_prop(index)] = name
        # 基础材质正下方同一 x 列, 层号越大越靠下; 帧内贴图 x 与基础材质基础色对齐
        frame.location = (base_location[0] - overlay_node_offset_x,
                          base_location[1] - overlay_layer_stride * index)

        node_tex = nodes.new(type="ShaderNodeTexImage")
        node_tex.image = bpy.data.images.load(texture_path)
        node_tex.interpolation = "Closest"
        # 先挂帧再设坐标 (node.location 是父帧相对坐标)
        node_tex.parent = frame
        node_tex.location = (overlay_node_offset_x, 0.0)

        load_normal_and_PBR(node_tex_base=node_tex, nodes=nodes, links=links, parent=frame)

        # tint 元数据（同 tint.json 约定；color 为线性值）
        material["Crafter_overlay" + str(index) + "_kind"] = str(layer.get("kind", "")) if layer.get("kind") else ""
        color = layer.get("color")
        if color and len(color) >= 3:
            material["Crafter_overlay" + str(index) + "_color"] = (
                float(color[0]), float(color[1]), float(color[2]))


def open_zip_icon(zip_file, member):
    '''仅容忍图标目录项与本地文件头的大小写差异，仍由 zipfile 解压并校验 CRC。'''
    try:
        return zip_file.open(member)
    except zipfile.BadZipFile:
        # ZipFile.open(ZipInfo) 仍会比较 orig_filename；仅换成目录项的名字不足以修复。
        # 读取本地文件头上的真实名字，在副本上归一化，避免修改 ZIP 或共享 ZipInfo。
        with open(zip_file.filename, "rb") as archive:
            archive.seek(member.header_offset)
            header = archive.read(30)
            if len(header) != 30 or header[:4] != b"PK\x03\x04":
                raise
            flags = int.from_bytes(header[6:8], "little")
            name_length = int.from_bytes(header[26:28], "little")
            raw_name = archive.read(name_length)
        encoding = "utf-8" if flags & 0x800 else (getattr(zip_file, "metadata_encoding", None) or "cp437")
        local_name = raw_name.decode(encoding)
        if (len(raw_name) != name_length or local_name == member.orig_filename
                or local_name.casefold() != member.orig_filename.casefold()):
            raise
        normalized = copy.copy(member)
        normalized.orig_filename = local_name
        return zip_file.open(normalized)


def load_icon_from_zip(zip_path, icons, name_icons, index):
    with zipfile.ZipFile(zip_path, "r") as zip_file:
        members = zip_file.infolist()
        for name in ("pack.png", "pack.jpg"):
            member = next((item for item in members if item.filename.casefold() == name), None)
            if member is None:
                continue
            # 固定输出名，兼容 Linux/macOS；不使用压缩包里的路径来创建文件。
            with open_zip_icon(zip_file, member) as source:
                data = source.read()
            dir_temp = tempfile.mkdtemp()
            dirs_temp.append(dir_temp)
            dir_icon = os.path.join(dir_temp, name)
            with open(dir_icon, "wb") as target:
                target.write(data)
            icons.load(name_icons + "_icon_" + str(index), dir_icon, 'IMAGE')
            return True
    return False

def get_dir_saves(context):
    addon_prefs = context.preferences.addons[__addon_name__].preferences

    dir_root = addon_prefs.History_World_Roots_List[addon_prefs.History_World_Roots_List_index].name

    divided = False

    dir_versions = dir_root_2_dir_versions(dir_root)
    try:
        list_folder_versions = os.listdir(dir_versions)
    except (FileNotFoundError, PermissionError):
        list_folder_versions = []
    if len(list_folder_versions) > 0:
        dir_version = os.path.join(dir_versions, list_folder_versions[0])
        dir_saves = os.path.join(dir_version, "saves")
        if os.path.exists(dir_saves):
            divided = True

    if divided:
        dir_verisons = dir_root_2_dir_versions(dir_root)
        dir_version = os.path.join(dir_verisons,addon_prefs.History_World_Versions_List[addon_prefs.History_World_Versions_List_index].name)
        dir_saves = dir_version_2_dir_saves(dir_version)
        
        return dir_saves
    else:
        dir_undivided_saves = os.path.join(dir_root,"saves")
        
        return dir_undivided_saves

def get_dir_save(context):
    addon_prefs = context.preferences.addons[__addon_name__].preferences

    dir_save = os.path.join(get_dir_saves(context), addon_prefs.History_World_Saves_List[addon_prefs.History_World_Saves_List_index].name)
    
    return dir_save

def dir_root_2_dir_versions(dir_root):
    list_folder_minecraft = os.listdir(dir_root)
    if "Instances" in list_folder_minecraft:
        dir_versions = os.path.join(dir_root, "Instances")
    elif "profiles" in list_folder_minecraft:
        dir_versions = os.path.join(dir_root, "profiles")
    else:
        dir_versions = os.path.join(dir_root, "versions")
    return dir_versions

def dir_version_2_dir_saves(dir_version):
    list_floder_version = os.listdir(dir_version)
    if ("instance.cfg" in list_floder_version) and ("mmc-pack.json" in list_floder_version):
        dir_saves = os.path.join(dir_version, "minecraft", "saves")
    else:
        dir_saves = os.path.join(dir_version, "saves")
    return dir_saves

def dir_back_saves_2_dir_version(dir_back_saves):
    dir_back_back_saves = os.path.dirname(dir_back_saves)
    list_floder_back_back_saves = os.listdir(dir_back_back_saves)
    if ("instance.cfg" in list_floder_back_back_saves) and ("mmc-pack.json" in list_floder_back_back_saves):
         dir_versions = dir_back_back_saves
    else:
        dir_versions = dir_back_saves
    return dir_versions

def dir_version_2_dir_jar(dir_version):
    dir_versions = os.path.dirname(dir_version)
    name_versions = os.path.basename(dir_versions)
    if name_versions == "Instances":
        list_floder_version = os.listdir(dir_version)
        if ("instance.cfg" in list_floder_version) and ("mmc-pack.json" in list_floder_version):
            
            dir_jar = ""# 暂未支持prime laucher（似乎与官方启动器使用同样的jar存储方式）
        else:
            dir_json_Instance = os.path.join(dir_version, "minecraftinstance.json")
            with open(dir_json_Instance, "r", encoding="utf-8") as file:
                json_instance = json.load(file)
            json_versionjson = json.loads(json_instance["baseModLoader"]["versionJson"])
            name_folder = json_versionjson["id"]
            name_jar = name_folder + ".jar"
            dir_minecraft = os.path.dirname(dir_versions)
            dir_jar = os.path.join(dir_minecraft, "Install", "versions", name_folder, name_jar)
    elif name_versions == "profiles":
        name_version = os.path.basename(dir_version)
        dir_ModrinthApp = os.path.dirname(dir_versions)
        dir_db_app = os.path.join(dir_ModrinthApp, "app.db")
        dir_meta = os.path.join(dir_ModrinthApp, "meta")
        dir_versions_meta = os.path.join(dir_meta, "versions")

        db_app = sqlite3.connect(dir_db_app)
        cursor = db_app.cursor()
        cursor.execute("SELECT * FROM profiles")
        rows = cursor.fetchall()
        for row in rows:
            if row[0] == name_version:
                if row[6] == None:
                    name_jar = row[4]
                else:
                    name_jar = row[4] + "-" + row[6]
                break
        dir_jar = os.path.join(dir_versions_meta, name_jar, name_jar + ".jar")
    else:
        name_version = os.path.basename(dir_version)
        dir_jar = os.path.join(dir_version, name_version+".jar")
    return dir_jar

def reloadwindow():
    bpy.ops.wm.redraw_timer(type="DRAW_WIN_SWAP")

def view_2_active_object(context, *, redraw=True):
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
    if redraw:
        reloadwindow()

def draw_multiline_label( text, parent,context):
    # 获取当前面板宽度和UI缩放比例
    panel_width = context.region.width
    
    # 计算有效字符宽度（考虑UI缩放对字体大小的影响）
    chars_per_line = max(10, int(panel_width / 50))  # 防下限
    
    # 使用textwrap分割文本
    wrapper = textwrap.TextWrapper(
        width=chars_per_line,
        break_long_words=True,
        replace_whitespace=False
    )
    lines = wrapper.wrap(text=text)
    
    # 动态生成多行标签
    for line in lines:
        parent.label(text=line)

def run_as_admin_and_wait(exe_path, work_dir=None, shell=False):
    """
    跨平台运行可执行文件并等待完成
    Windows: 使用管理员权限运行
    macOS/Linux: 使用普通权限运行
    """
    current_platform = platform.system()
    print(f"[DEBUG] Platform detected: {current_platform}")
    print(f"[DEBUG] Executable path: {exe_path}")
    print(f"[DEBUG] Working directory: {work_dir}")

    if current_platform == "Windows":
        print("[DEBUG] Using Windows execution path")
        return _run_windows_admin(exe_path, work_dir, shell)
    elif current_platform == "Darwin":  # macOS
        print("[DEBUG] Using macOS execution path")
        return _run_macos(exe_path, work_dir, shell)
    else:  # Linux或其他Unix系统
        print(f"[DEBUG] Using Unix execution path for {current_platform}")
        return _run_unix(exe_path, work_dir, shell)


# ==================== 全局日志系统 ====================
# 日志级别常量
LOG_LEVEL_DEBUG = "DEBUG"
LOG_LEVEL_INFO = "INFO"
LOG_LEVEL_WARN = "WARN"
LOG_LEVEL_ERROR = "ERROR"

import_log = []          # [{"text": str, "level": str, "time": str, "stage": str}, ...]
import_running = False
import_progress = 0.0
import_stage = ""        # 当前所处的环节名称
MAX_LOG_LINES = 2000     # 日志上限，超过后从头部裁剪

DEBUG_MODE = True  # 设为 False 可关闭详细调试日志

# 阶段计时表：{stage_name: start_perf_counter}
_stage_timers = {}
import time as _time  # 日志系统专用计时
import datetime as _datetime  # 日志系统专用时间戳

def _now_str():
    """返回当前时间字符串 HH:MM:SS"""
    return _datetime.datetime.now().strftime("%H:%M:%S")

def _format_dur(seconds):
    """格式化耗时：小于1秒显示 ms，否则显示 s"""
    if seconds is None:
        return "?"
    if seconds < 1.0:
        return f"{seconds * 1000:.0f}ms"
    return f"{seconds:.2f}s"

def debug_log(text, stage=None):
    """调试日志，受 DEBUG_MODE 开关控制"""
    if DEBUG_MODE:
        push_log(text, LOG_LEVEL_DEBUG, stage)

def info_log(text, stage=None):
    push_log(text, LOG_LEVEL_INFO, stage)

def warn_log(text, stage=None):
    push_log(text, LOG_LEVEL_WARN, stage)

def error_log(text, stage=None):
    push_log(text, LOG_LEVEL_ERROR, stage)

def push_log(text, level=LOG_LEVEL_INFO, stage=None):
    """追加一条日志。level 为日志级别，stage 为所处环节（缺省取全局 import_stage）。
    向后兼容：text/level 字段保持不变，新增 time/stage 字段。
    """
    import_log.append({
        "text": str(text),
        "level": level,
        "time": _now_str(),
        "stage": stage if stage is not None else import_stage,
    })
    if len(import_log) > MAX_LOG_LINES:
        del import_log[:100]

def log_stage(stage_name):
    """仅设置当前环节名称（不输出日志），后续 push_log 会自动带上该环节"""
    global import_stage
    import_stage = stage_name

def log_stage_begin(stage_name, detail=""):
    """开始一个计时环节：设置当前环节、记录起始时间、输出开始日志"""
    global import_stage, _stage_timers
    import_stage = stage_name
    _stage_timers[stage_name] = _time.perf_counter()
    msg = f"▶ {stage_name}"
    if detail:
        msg += f" - {detail}"
    push_log(msg, LOG_LEVEL_INFO, stage_name)

def log_stage_end(stage_name, extra=""):
    """结束一个计时环节：输出完成日志并附带耗时"""
    global _stage_timers
    t0 = _stage_timers.pop(stage_name, None)
    dur = _format_dur(_time.perf_counter() - t0 if t0 is not None else None)
    msg = f"■ {stage_name} 完成 ({dur})"
    if extra:
        msg += f" - {extra}"
    push_log(msg, LOG_LEVEL_INFO, stage_name)

def log_step(text, stage=None):
    """在当前环节内输出一条步骤日志（不计时）"""
    push_log(f"  · {text}", LOG_LEVEL_INFO, stage)

def clear_import_log():
    """清空日志并重置进度/阶段状态"""
    global import_running, import_progress, import_stage
    import_log.clear()
    import_running = False
    import_progress = 0.0
    import_stage = ""
    _stage_timers.clear()

def format_log_text(include_meta=True):
    """将 import_log 格式化为可读文本。
    include_meta=True 时带 [时间][级别] 前缀，便于复制/导出排查问题。
    """
    if not import_log:
        return ""
    lines = []
    for e in import_log:
        if include_meta:
            stage = e.get("stage", "")
            stage_str = f"[{stage}]" if stage else ""
            lines.append(f"[{e.get('time','')}] [{e.get('level','INFO')}] {stage_str} {e['text']}")
        else:
            lines.append(e["text"])
    return "\n".join(lines)

def parse_progress(text):
    import re as _re
    m = _re.search(r'(\d+)\s*/\s*(\d+)', text)
    if m:
        cur, total = int(m.group(1)), int(m.group(2))
        if total > 0:
            return min(100.0, cur / total * 100)
    return None

def _run_windows_admin(exe_path, work_dir=None, shell=False):
    """Windows平台使用管理员权限运行"""
    # 只在Windows平台上执行
    if platform.system() != "Windows":
        return False

    try:
        # 定义SHELLEXECUTEINFOW结构体
        class SHELLEXECUTEINFOW(ctypes.Structure):
            _fields_ = [
                ("cbSize", wintypes.DWORD),
                ("fMask", ctypes.c_ulong),
                ("hwnd", wintypes.HWND),
                ("lpVerb", wintypes.LPCWSTR),
                ("lpFile", wintypes.LPCWSTR),
                ("lpParameters", wintypes.LPCWSTR),
                ("lpDirectory", wintypes.LPCWSTR),
                ("nShow", ctypes.c_int),
                ("hInstApp", wintypes.HINSTANCE),
                ("lpIDList", ctypes.c_void_p),
                ("lpClass", wintypes.LPCWSTR),
                ("hKeyClass", wintypes.HKEY),
                ("dwHotKey", wintypes.DWORD),
                ("hIcon", wintypes.HANDLE),
                ("hProcess", wintypes.HANDLE)
            ]

        # 配置结构体参数
        sei = SHELLEXECUTEINFOW()
        sei.cbSize = ctypes.sizeof(SHELLEXECUTEINFOW)
        sei.fMask = 0x00000040  # SEE_MASK_NOCLOSEPROCESS
        sei.lpVerb = 'runas'    # 管理员权限
        sei.lpFile = exe_path.replace("\\", "\\\\")  # 处理Windows路径转义
        sei.lpDirectory = work_dir.replace("\\", "\\\\") if work_dir else None
        sei.nShow = shell  # SW_SHOWNORMAL

        # 调用ShellExecuteExW
        if not ctypes.windll.shell32.ShellExecuteExW(ctypes.byref(sei)):
            error_code = ctypes.GetLastError()
            error_msg = ctypes.FormatError(error_code)
            return False

        # 等待进程结束
        WAIT_TIMEOUT = 0x00000102
        WAIT_OBJECT_0 = 0x0
        while True:
            wait_result = ctypes.windll.kernel32.WaitForSingleObject(sei.hProcess, 100)  # 100ms间隔
            if wait_result == WAIT_OBJECT_0:
                break
            elif wait_result == WAIT_TIMEOUT:
                continue
            else:
                ctypes.windll.kernel32.CloseHandle(sei.hProcess)
                return False

        # 获取退出码
        exit_code = wintypes.DWORD()
        ctypes.windll.kernel32.GetExitCodeProcess(sei.hProcess, ctypes.byref(exit_code))
        ctypes.windll.kernel32.CloseHandle(sei.hProcess)

        return exit_code.value == 0

    except Exception as e:
        print(f"Windows execution error: {e}")
        return False

def _run_macos(exe_path, work_dir=None, shell=False):
    """macOS平台运行"""
    try:
        # 检查是否是Windows可执行文件，如果是则替换为macOS版本
        if exe_path.endswith("WorldImporter.exe"):
            macos_exe_path = exe_path.replace("WorldImporter.exe", "WorldImporter")
            if os.path.exists(macos_exe_path):
                exe_path = macos_exe_path
                print("macOS")  # 输出给Blender
            else:
                print(f"macOS executable not found: {macos_exe_path}")
                return False

        print(f"[DEBUG] Final executable path: {exe_path}")
        print(f"[DEBUG] Working directory: {work_dir}")

        # 检查配置文件是否存在
        config_path = os.path.join(work_dir, "config_macos", "config.json")
        print(f"[DEBUG] Expected config path: {config_path}")
        if os.path.exists(config_path):
            print(f"[DEBUG] Config file exists and size: {os.path.getsize(config_path)} bytes")
        else:
            print(f"[DEBUG] Config file does not exist!")

        # 使用subprocess运行程序
        process = subprocess.Popen(
            [exe_path],
            cwd=work_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )

        # 等待进程完成
        stdout, stderr = process.communicate()

        # 打印输出（可选）
        if stdout:
            print("=== STDOUT ===")
            print(stdout.decode('utf-8'))
        if stderr:
            print("=== STDERR ===")
            print(stderr.decode('utf-8'))

        print(f"[DEBUG] Process return code: {process.returncode}")
        return process.returncode == 0

    except Exception as e:
        print(f"macOS execution error: {e}")
        import traceback
        traceback.print_exc()
        return False

def _run_unix(exe_path, work_dir=None, shell=False):
    """Unix/Linux平台运行"""
    try:
        process = subprocess.Popen(
            [exe_path],
            cwd=work_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )

        stdout, stderr = process.communicate()

        if stdout:
            print(stdout.decode('utf-8'))
        if stderr:
            print(stderr.decode('utf-8'))

        return process.returncode == 0

    except Exception as e:
        print(f"Unix execution error: {e}")
        return False


def open_folder(folder_path: str):
    '''
    打开目标文件夹
    folder_path: 文件夹路径
    '''
    if platform.system() == "Windows":
        os.startfile(folder_path)
    elif platform.system() == "Darwin":  # MacOS
        subprocess.run(["open", folder_path])
    else:  # Linux
        subprocess.run(["xdg-open", folder_path])

def unzip(zip_path, extract_to):
    '''
    解压压缩文件
    zip_path: 压缩文件路径
    extract_to: 解压路径
    '''
    os.makedirs(extract_to, exist_ok=True)
    with zipfile.ZipFile(zip_path, 'r') as zip_ref:
        zip_ref.extractall(extract_to)

def add_node_group_if_not_exists(names):
    for name in names:
        if name not in bpy.data.node_groups:
            try:
                with bpy.data.libraries.load(dir_blend_append, link=False) as (data_from, data_to):
                    data_to.node_groups = [name]
                bpy.data.node_groups[name].use_fake_user = True
            except Exception as e:
                print(f"Error loading node group '{name}': {e}")
                continue

def add_node_moving_texture(node_tex, nodes, links, list_info_moving, parent=None):
    '''
    为基础色节点添加动态纹理节点并连接
    node_tex_base: 基础纹理节点
    nodes: 目标材质节点组
    links:目标材质连接组
    list_info_moving: 动态纹理节点信息列表
    row_base: 基础色纹理行数
    parent: 动态纹理帧要挂到的父帧（overlay 帧），None 表示不挂
    return:动态纹理节点信息
    '''
    if node_tex.image.size[0] == 0:
        return None
    else:
        row = node_tex.image.size[1] / node_tex.image.size[0]

    dir_image = os.path.dirname(node_tex.image.filepath)
    dir_mcmeta = os.path.join(bpy.path.abspath(dir_image), fuq_bl_dot_number(node_tex.image.name) + ".mcmeta")

    if len(list_info_moving) > 0:
        # list_info_moving[0][1] 是动画信息 [row, frametime, ...]；为 None 的条目（"复用某个
        # 节点的输出"那种）拿不到 row，退回 1，别当成可下标的对象
        info_base = None if list_info_moving[0] is None else list_info_moving[0][1]
        row_base = info_base[0] if info_base is not None else 1
    else:
        row_base = None

    if os.path.exists(dir_mcmeta):

        with open(dir_mcmeta, 'r', encoding='utf-8') as file:
            mcmeta = json.load(file)
        frametime = 1
        interpolate = False
        frames = row
        list_frames = []
        haveframe = True
        if "animation" in mcmeta:
            if "frametime" in mcmeta["animation"]:
                frametime = mcmeta["animation"]["frametime"]
            if "interpolate" in mcmeta["animation"]:
                interpolate = mcmeta["animation"]["interpolate"]
            if "height" in mcmeta["animation"] and "width" in mcmeta["animation"]:
                row = mcmeta["animation"]["height"] / mcmeta["animation"]["width"]
                frames = row
            if "frames" in mcmeta["animation"]:
                frames = 0
                for frame in mcmeta["animation"]["frames"]:
                    if type(frame) == int:
                        frames += 1
                        list_frames.append([frame,1])
                    else:
                        frames += frame["time"] / frametime
                        list_frames.append([frame["index"],frame["time"] / frametime])
            else:
                haveframe = False
        else:
            haveframe = False
        if not haveframe:
            frames = row
            for i in range(int(frames)):
                list_frames.append([i,1])
        info = [row, frametime, frames, list_frames, interpolate]
        for info_old in list_info_moving:
            if info_old == None or info_old[1] == None:
                continue
            else:
                if info_old[1] == info:
                    links.new(info_old[0].outputs["Vector"], node_tex.inputs["Vector"])
                    return info_old
        # 全新的动态纹理
        
        node_frame = nodes.new(type="NodeFrame")
        node_frame.label = "Crafter-动态纹理"
        if parent is not None:
            node_frame.parent = parent
        # 动态纹理帧要与参考贴图共享同一坐标空间: 挂帧后归零,
        # 否则帧内节点的 location 会被当成父帧相对坐标错位
        node_frame.location = (0.0, 0.0)

        # 注意: node.location 是父帧相对坐标, 必须先挂 parent 再设 location
        node_Moving_texture_end = nodes.new(type="ShaderNodeGroup")
        node_Moving_texture_end.node_tree = bpy.data.node_groups["Crafter-动态纹理_尾"]
        node_Moving_texture_end.parent = node_frame
        node_Moving_texture_end.location = (node_tex.location.x - 200, node_tex.location.y)

        node_Fac = nodes.new(type="ShaderNodeValToRGB")
        node_Fac.color_ramp.interpolation = "CONSTANT"
        node_Fac.parent = node_frame
        node_Fac.location = (node_tex.location.x - 750, node_tex.location.y)

        node_Moving_texture_start = nodes.new(type="ShaderNodeGroup")
        node_Moving_texture_start.parent = node_frame
        node_Moving_texture_start.location = (node_tex.location.x - 900, node_tex.location.y)
        
        if interpolate:
            node_Moving_texture_start.node_tree = bpy.data.node_groups["Crafter-动态纹理_首_渐变"]
            node_Moving_texture_start.inputs["20 / frametime"].default_value = 20 / frametime
            node_Moving_texture_start.inputs["frames"].default_value = frames
        else:
            node_Moving_texture_start.node_tree = bpy.data.node_groups["Crafter-动态纹理_首"]
            node_Moving_texture_start.inputs["20 / frametime / frames"].default_value = 20 / frametime / frames
            
        links.new(node_Moving_texture_end.outputs["Vector"], node_tex.inputs["Vector"])
        links.new(node_Moving_texture_start.outputs["Fac"], node_Fac.inputs["Fac"])

        # 某些资源包的 .mcmeta 可能存在 animation.frames 为空、frames 为 0 等异常情况。
        # 原逻辑在 list_frames 为空时不会创建 node_Mix，但结尾仍可能访问 node_Mix，
        # 从而触发 “cannot access local variable 'node_Mix'” 异常。
        if not list_frames or frames <= 0:
            frames = 1
            list_frames = [[0, 1]]
        node_Mix = None
        n = -1
        adding_frames = 0
        for i in list_frames:
            n += 1
            if (n // 32 > 0) and (n % 32 == 0):
                if n // 32 ==1:
                    last_out_put_alpha = node_Fac.outputs["Alpha"]
                else:
                    last_out_put_alpha = node_Mix.outputs["Result"]
                node_Fac = nodes.new(type="ShaderNodeValToRGB")
                node_Fac.color_ramp.interpolation = "CONSTANT"
                node_Fac.parent = node_frame
                node_Fac.location = (node_tex.location.x - 750, node_tex.location.y + ((n // 32) * 250))
                links.new(node_Moving_texture_start.outputs["Fac"], node_Fac.inputs["Fac"])

                node_Math = nodes.new(type="ShaderNodeMath")
                node_Math.operation = "LESS_THAN"
                node_Math.use_clamp = False
                node_Math.parent = node_frame
                node_Math.location = (node_tex.location.x - 500, node_tex.location.y + ((n // 32) * 250) - 250)
                links.new(node_Moving_texture_start.outputs["Fac"], node_Math.inputs["Value"])
                node_Math.inputs["Value_001"].default_value = adding_frames / frames

                node_Mix = nodes.new(type="ShaderNodeMix")
                node_Mix.data_type = "FLOAT"
                node_Mix.clamp_factor = False
                node_Mix.parent = node_frame
                node_Mix.location = (node_tex.location.x - 350, node_tex.location.y + ((n // 32) * 250) - 250)

                links.new(node_Math.outputs["Value"], node_Mix.inputs["Factor"])
                links.new(node_Fac.outputs["Alpha"], node_Mix.inputs["A"])
                links.new(last_out_put_alpha, node_Mix.inputs["B"])
            if (n % 32) > 1:
                node_Fac.color_ramp.elements.new(1)
                
            frame_chu_row = i[0] / row
            node_Fac.color_ramp.elements[n % 32].position = adding_frames / frames
            node_Fac.color_ramp.elements[n % 32].color = [frame_chu_row, frame_chu_row, frame_chu_row, frame_chu_row]
            adding_frames  += i[1]
        if node_Mix is None:
            last_out_put_alpha = node_Fac.outputs["Alpha"]
        else:
            last_out_put_alpha = node_Mix.outputs["Result"]

        links.new(last_out_put_alpha, node_Moving_texture_end.inputs["frame / row"])
        node_Moving_texture_end.inputs["row"].default_value = row
        if row_base != None:
                node_Moving_texture_end.inputs["row_tex / row"].default_value = row_base / row
        else:
            node_Moving_texture_end.inputs["row_tex / row"].default_value = 1
            
        info_moving = [node_Moving_texture_end, info]
        return info_moving
    else:
        # 不是动态纹理
        if len(list_info_moving) > 0:
            # 不是基础色纹理
            if list_info_moving[0] == None:
                # 基础色纹理 不是 动态纹理
                return None
            else:
                # 基础色纹理 是 动态纹理
                for info_old in list_info_moving:
                    if info_old == None:
                        continue
                    elif info_old[1] == None:
                        links.new(info_old[0].outputs["Vector"], node_tex.inputs["Vector"])
                        return info_old
                node_frame = nodes.new(type="NodeFrame")
                node_frame.label = "Crafter-动态纹理"
                if parent is not None:
                    node_frame.parent = parent
                node_frame.location = (0.0, 0.0)
                node_scall_UV = nodes.new(type="ShaderNodeGroup")
                node_scall_UV.node_tree = bpy.data.node_groups["Crafter-UV缩放"]
                node_scall_UV.parent = node_frame
                node_scall_UV.location = (node_tex.location.x - 200, node_tex.location.y)
                node_scall_UV.inputs["row_tex / row"].default_value = row_base
                links.new(node_scall_UV.outputs["Vector"], node_tex.inputs["Vector"])
                return [node_scall_UV, None]
        else:
            # 是基础色纹理
            return None

def material_base_name(name: str):
    '''
    从材质名取分类用的完整名：去掉 blender 的 .xxx 后缀、去掉第一个 @ 之后的
    tint/CTM 后缀，保留命名空间与路径（如 minecraft:block/oak_leaves）。
    '''
    name = fuq_bl_dot_number(name)
    at = name.find("@")
    if at != -1:
        name = name[:at]
    return name

def add_to_mcmts_collection(object,context):
    '''
    object: 目标对象
    context: 目标上下文
    '''
    if object.type == "MESH":
        list_name_context_material = []
        for context_material in bpy.data.materials:
            list_name_context_material.append(context_material.name)
        list_name_object_material = []
        for material_object in object.data.materials:
            list_name_object_material.append(material_object.name)
        if (object.name not in donot) and object.type == "MESH" and object.data.materials:
            for name_material in list_name_object_material:
                if (name_material not in context.scene.Crafter_mcmts) and (name_material not in donot):
                    new_mcmt = context.scene.Crafter_mcmts.add()
                    new_mcmt.name = name_material
        for i in range(len(context.scene.Crafter_mcmts)-1,-1,-1):
            if context.scene.Crafter_mcmts[i].name not in list_name_context_material:
                context.scene.Crafter_mcmts.remove(i)

def add_to_crafter_mcmts_collection(object,context):
    '''
    object: 目标对象
    context: 目标上下文
    '''
    if object.type == "MESH":
        list_name_context_material = []
        for context_material in bpy.data.materials:
            list_name_context_material.append(context_material.name)
        list_name_object_material = []
        for object_material in object.data.materials:
            list_name_object_material.append(object_material.name)
        if (object.name not in donot) and object.type == "MESH" and object.data.materials:
            for name_material in list_name_object_material:
                if (name_material not in context.scene.Crafter_crafter_mcmts) and (name_material not in donot):
                    new_mcmt = context.scene.Crafter_crafter_mcmts.add()
                    new_mcmt.name = name_material
        for i in range(len(context.scene.Crafter_crafter_mcmts)-1,-1,-1):
            if context.scene.Crafter_crafter_mcmts[i].name not in list_name_context_material:
                context.scene.Crafter_crafter_mcmts.remove(i)
                
# ========== 分类依据：结构化匹配 ==========
# 规则格式：{组名: 条目 或 [条目...]}；条目 = {"block": [...], "key": [...], "texture": [...]}
# 语义：组内条目 OR；条目内维度 AND（出现过的维度都要命中）；维度内元素 OR（支持 fnmatch 通配）。
_CLASSIFICATION_DIMS = ("block", "key", "texture")

def parse_material_name(name: str):
    '''
    解析材质名为结构化字段。
    格式：[namespace:]block[#贴图键][~贴图名]（传入前需已去掉 tint/CTM 的 @ 后缀）
    缺省命名空间为 minecraft；无 ~ 时贴图名回退为方块名。
    color# 等特殊材质返回 None（不参与结构化匹配）。
    return: {"block": "ns:方块id", "key": str|None, "texture": str|None} 或 None
    '''
    if not name:
        return None
    if name.startswith("color#"):
        return None
    left = name
    texture = None
    tilde = left.find("~")
    if tilde != -1:
        texture = left[tilde + 1:]
        left = left[:tilde]
    key = None
    sharp = left.find("#")
    if sharp != -1:
        key = left[sharp + 1:]
        left = left[:sharp]
    if not left:
        return None
    if ":" in left:
        namespace, block = left.split(":", 1)
    else:
        namespace, block = "minecraft", left
    if not namespace:
        namespace = "minecraft"
    if not texture:
        texture = block
    return {"block": namespace + ":" + block, "key": key or None, "texture": texture or None}

def _compile_classification_pattern(pattern: str):
    '''把一条匹配项编译为 ("exact", 字符串) 或 ("wild", 正则)（fnmatch 通配）'''
    if any(ch in pattern for ch in "*?["):
        return ("wild", re.compile(fnmatch.translate(pattern)))
    return ("exact", pattern)

def _compile_classification_entry(entry) -> dict:
    '''编译一条条目为 {维度: (精确集合, 通配正则列表)}'''
    dims = {}
    if not isinstance(entry, dict):
        return dims
    for dim in _CLASSIFICATION_DIMS:
        items = entry.get(dim)
        if not isinstance(items, list) or not items:
            continue
        exact = set()
        wild = []
        for item in items:
            if not isinstance(item, str) or not item:
                continue
            kind, value = _compile_classification_pattern(item)
            if kind == "exact":
                exact.add(value)
            else:
                wild.append(value)
        if exact or wild:
            dims[dim] = (exact, wild)
    return dims

def compile_classification_list(raw_list) -> list:
    '''
    把合并后的分类依据字典编译成有序规则列表：[(组名, [条目, ...]), ...]。
    组的值可以是字典（单条目）或字典列表（条目间 OR）。保留 JSON 顺序作为优先级。
    '''
    rules = []
    if not isinstance(raw_list, dict):
        return rules
    for group_name, group in raw_list.items():
        entries = group if isinstance(group, list) else [group]
        compiled = []
        for entry in entries:
            dims = _compile_classification_entry(entry)
            if dims:
                compiled.append(dims)
        if compiled:
            rules.append((group_name, compiled))
    return rules

def _classification_field_match(value, field) -> bool:
    if value is None:
        return False
    exact, wild = field
    if value in exact:
        return True
    for pattern in wild:
        if pattern.match(value):
            return True
    return False

def match_classification_rule(rules, parsed) -> str:
    '''
    在编译后的规则列表中找到第一个命中的组名；未命中返回 None。
    '''
    if parsed is None:
        return None
    for group_name, entries in rules:
        for dims in entries:
            matched = True
            for dim, field in dims.items():
                if not _classification_field_match(parsed.get(dim), field):
                    matched = False
                    break
            if matched:
                return group_name
    return None

def find_CI_group(classification_list,real_block_name,group_CI):
    '''
    classification_list: 编译后的分类规则列表（见 compile_classification_list）
    real_block_name: 材质全名（已去掉 @ 后缀）
    group_CI: CI节点组
    '''
    group_name = match_classification_rule(classification_list, parse_material_name(real_block_name))
    name_node = "CI-" + group_name if group_name else "CI-"
    if name_node not in bpy.data.node_groups:
        name_node = "CI-"
    group_CI.node_tree = bpy.data.node_groups[name_node]

# ========== CI- 组输出接口 → 剔除开关 ==========
# CI- 组声明了某个名字就打开对应的剔除；换名字只改这两行。
# 两层含义：材质属性（EEVEE）与 Cycles 剔除组（见 CULL_GROUP_NAME）的输出选择。
CI_CULL_OUTPUT_RENDER = "Back Cull"          # 声明 → 摄影机（渲染）背面剔除
CI_CULL_OUTPUT_SHADOW = "Shadow Back Cull"   # 声明 → 摄影机 + 阴影背面剔除都开
# 兼容材质库里还没改名的旧接口（BackCull / ShadowBackCull 等）
CI_CULL_OUTPUT_RENDER_ALIASES = (CI_CULL_OUTPUT_RENDER, "BackCull", "BackfaceCull")
CI_CULL_OUTPUT_SHADOW_ALIASES = (CI_CULL_OUTPUT_SHADOW, "ShadowBackCull", "ShadowBackfaceCull")

_ci_cull_stats = {"render": 0, "shadow": 0, "scanned": 0}

def reset_CI_cull_stats():
    _ci_cull_stats["render"] = 0
    _ci_cull_stats["shadow"] = 0
    _ci_cull_stats["scanned"] = 0

def get_CI_cull_stats():
    '''最近一次同步的结果（用于日志）'''
    return dict(_ci_cull_stats)

def query_CI_outputs(group_CI):
    '''
    列出 CI- 组节点声明了哪些输出接口名。
    group_CI: 材质里的 CI 组节点，可为 None
    return: set[str]（节点组缺失时为空集）
    '''
    if group_CI is None or getattr(group_CI, "node_tree", None) is None:
        return set()
    try:
        return {output.name for output in group_CI.outputs}
    except Exception:
        return set()

def _material_CI_cull_flags(material):
    '''
    扫描材质里所有 CI- 组节点，返回 (是否声明渲染侧接口, 是否声明阴影侧接口)。
    叠加层帧里的 CI 节点也算，任一命中即视为声明。
    '''
    node_tree = getattr(material, "node_tree", None)
    if node_tree is None:
        return (False, False)
    render_on = shadow_on = False
    for node in node_tree.nodes:
        if node.type != "GROUP" or getattr(node, "node_tree", None) is None:
            continue
        if not node.node_tree.name.startswith("CI-"):
            continue
        names = query_CI_outputs(node)
        if any(name in names for name in CI_CULL_OUTPUT_RENDER_ALIASES):
            render_on = True
        if any(name in names for name in CI_CULL_OUTPUT_SHADOW_ALIASES):
            shadow_on = True
        if render_on and shadow_on:
            break
    return (render_on, shadow_on)

def sync_CI_culling(material, count=False):
    '''
    按 CI- 组的输出接口名决定一个材质的剔除开关（纯两向判断，无全局兜底）：
      - 声明 CI_CULL_OUTPUT_RENDER → 打开摄影机（渲染）背面剔除；
      - 声明 CI_CULL_OUTPUT_SHADOW → 摄影机和阴影的背面剔除都打开（阴影标识包含渲染侧）；
      - 合并过的双面几何只剩一张面 → 两者都关闭，保持双面可见。
    只在值不同时写入，避免无谓的属性写入。
    material: 目标材质
    count: 是否累加到 get_CI_cull_stats() 的统计里
    return: (渲染侧最终是否开启, 阴影侧最终是否开启)
    '''
    if material is None or getattr(material, "library", None) is not None:
        return (False, False)   # 链接库材质只读，跳过
    desired_render, desired_shadow = _material_CI_cull_flags(material)
    # 阴影标识已经包含渲染侧：声明 Shadow Back Cull 时摄影机和阴影都剔除
    if desired_shadow:
        desired_render = True
    # 合并后的双面几何只剩一张面：不剔除，否则背面看不见
    if _is_merged_double_sided(material):
        desired_render = False
        desired_shadow = False
    if getattr(material, "use_backface_culling", None) != desired_render:
        material.use_backface_culling = desired_render
    if (hasattr(material, "use_backface_culling_shadow") and
            material.use_backface_culling_shadow != desired_shadow):
        material.use_backface_culling_shadow = desired_shadow
    _sync_cull_group_output(material, desired_render, desired_shadow)
    if count:
        _ci_cull_stats["scanned"] += 1
        if desired_render:
            _ci_cull_stats["render"] += 1
        if desired_shadow:
            _ci_cull_stats["shadow"] += 1
    return (desired_render, desired_shadow)

# ========== 双面几何（doubleSided.json）==========
# 导出器把"零厚度贴片被分离出正反两面"的材质基础全名写成 doubleSided.json（对象格式，
# materials 数组已剥掉 @tint 后缀，另带 samePairs/mirrorPairs/otherPairs/mergedPairs 统计）。
# 这些材质在 Blender 里是双面几何，需要关闭背面剔除才不会漏光；
# 其余材质（实心几何）保持双面可见。
# CI- 组若声明了这个输入接口，就把 Cycles 侧的背面剔除打开（写 1）。
CI_BACKCULL_INPUT = "Cycles Back Cull"
double_sided_json_name = "doubleSided.json"
# doubleSided.json 的按 mtime 失效的读盘缓存：
# {"stamp": float|None, "names": set[str], "kinds": dict, "merged": set[str]}
_double_sided_cache = {"stamp": None, "names": set(), "kinds": {}, "merged": set()}

def load_double_sided_materials(dir_importer):
    '''
    读取 doubleSided.json：使用了双面几何的材质基础全名集合。
    查表口径与 Defs.material_base_name() 一致（导出器已剥 @ 后缀）。
    dir_importer: WorldImporter 输出目录
    return: set[str]（文件不存在或格式异常时为空集）
    '''
    path = os.path.join(dir_importer, double_sided_json_name)
    if not os.path.exists(path):
        return set()
    try:
        with open(path, "r", encoding="utf-8") as f:
            raw = json.load(f)
    except Exception as ex:
        warn_log(f"doubleSided.json 读取失败: {ex}")
        return set()
    if isinstance(raw, dict):
        raw = raw.get("materials", [])   # 对象格式：{"materials": [...], "samePairs": {...}, ...}
    if not isinstance(raw, list):
        warn_log("doubleSided.json 格式异常（materials 应为数组），已忽略")
        return set()
    return {str(name) for name in raw if name}

def load_double_sided_kinds(dir_importer):
    '''
    读取 doubleSided.json 里的分类统计：
      samePairs   : 同材质且 UV 一一对应的对（可安全合并）
      mirrorPairs : 同材质但 UV 镜像的对（两面从左到右都正常，保留两张面）
      otherPairs  : 材质/UV 区域不同的对
      mergedPairs : 导出器本次实际合并掉的 Same 对数
    这些信息只用于日志/诊断；背面剔除仍然只看 materials。
    dir_importer: WorldImporter 输出目录
    return: dict[str, dict[str, int]]；文件不存在、旧版纯数组或格式异常时返回空 dict
    '''
    path = os.path.join(dir_importer, double_sided_json_name)
    if not os.path.exists(path):
        return {}
    try:
        with open(path, "r", encoding="utf-8") as f:
            raw = json.load(f)
    except Exception as ex:
        warn_log(f"doubleSided.json 读取失败: {ex}")
        return {}
    if not isinstance(raw, dict):
        return {}
    kinds = {}
    for key in ("samePairs", "mirrorPairs", "otherPairs", "mergedPairs"):
        table = raw.get(key)
        if isinstance(table, dict):
            kinds[key] = {str(k): int(v) for k, v in table.items()
                          if isinstance(v, (int, float))}
    return kinds

def _is_merged_double_sided(material):
    '''材质是否在 doubleSided.json 的 mergedPairs 里：合并后只剩一张面，必须双面可见。'''
    merged = _double_sided_cache.get("merged")
    if not merged:
        return False
    try:
        return material_base_name(material.name) in merged
    except Exception:
        return False

def _write_socket_default(group_node, socket_name, value):
    '''
    给节点组里名为 socket_name 的输入接口写常量值。
    接口不存在、或已被连接时返回 False（default_value 对已连接的接口无效）。
    group_node:  材质里的 CI- 组节点
    socket_name: 接口名（约定见 CI_BACKCULL_INPUT）
    value:       要写入的常量值
    '''
    if group_node is None or getattr(group_node, "node_tree", None) is None:
        return False
    if socket_name not in group_node.inputs:
        return False
    socket = group_node.inputs[socket_name]
    if getattr(socket, "is_linked", False):
        return False
    socket.default_value = value
    return True

def apply_double_sided_backface_cull(material, dir_importer):
    '''
    双面几何材质的背面剔除：材质名命中 doubleSided.json 时
      - EEVEE：打开渲染侧 + 阴影侧背面剔除（对应 use_backface_culling / _shadow）；
      - Cycles：把 CI- 组里名为 CI_BACKCULL_INPUT 的输入接口写成 1。Cycles 没有
        EEVEE 那种材质开关，背面剔除只能靠材质节点实现，所以由 CI 组自己提供输入。
    其余材质不动，保持 sync_CI_culling 按 CI 输出接口名算出的结果。
    material:     目标材质
    dir_importer: WorldImporter 输出目录（doubleSided.json 所在处）
    return:       是否按双面几何处理了该材质
    '''
    if material is None or getattr(material, "library", None) is not None:
        return False   # 链接库材质只读，跳过
    # 每次导入只读一次清单（按文件 mtime 失效），避免逐材质重复读盘
    path = os.path.join(dir_importer, double_sided_json_name)
    try:
        stamp = os.path.getmtime(path)
    except OSError:
        return False
    global _double_sided_cache
    if _double_sided_cache.get("stamp") != stamp:
        _double_sided_cache["stamp"] = stamp
        _double_sided_cache["names"] = load_double_sided_materials(dir_importer)
        # 分类统计只用于诊断日志；mergedPairs 决定该材质合并后是否关闭剔除
        kinds = load_double_sided_kinds(dir_importer)
        _double_sided_cache["kinds"] = kinds
        _double_sided_cache["merged"] = set(kinds.get("mergedPairs", {}))
        if kinds:
            merged_pairs = sum(kinds.get("mergedPairs", {}).values())
            mirror_pairs = sum(kinds.get("mirrorPairs", {}).values())
            debug_log(f"doubleSided.json: 已合并 {merged_pairs} 对, "
                      f"保留 {mirror_pairs} 对镜像双面")
    base = material_base_name(material.name)
    if base not in _double_sided_cache["names"]:
        return False

    # 合并过的双面几何只剩一张面：关闭剔除，否则背面直接看不见
    if base in _double_sided_cache["merged"]:
        if getattr(material, "use_backface_culling", None) is not None:
            material.use_backface_culling = False
        if hasattr(material, "use_backface_culling_shadow"):
            material.use_backface_culling_shadow = False
        _sync_cull_group_output(material, False, False)
        return True

    # EEVEE：渲染侧 + 阴影侧（与 sync_CI_culling 的写法一致）
    if getattr(material, "use_backface_culling", None) is not None:
        material.use_backface_culling = True
    if hasattr(material, "use_backface_culling_shadow"):
        material.use_backface_culling_shadow = True

    # Cycles：剔除组选 Shadow Back Cull 输出并解除静音；
    # CI- 组提供输入接口时也写 1（叠加层帧里的 CI 节点同样处理）
    _sync_cull_group_output(material, True, True)
    node_tree = getattr(material, "node_tree", None)
    if node_tree is not None:
        for node in node_tree.nodes:
            if node.type != "GROUP" or getattr(node, "node_tree", None) is None:
                continue
            if node.node_tree.name.startswith("CI-"):
                _write_socket_default(node, CI_BACKCULL_INPUT, 1.0)
    return True

# ========== Cycles 剔除组（append.blend 的 Crafter-背面剔除） ==========
# CI 的 Cycles-Surface 不再直连最终输出：中间插一层剔除组节点，默认静音（直通），
# 声明了 Back Cull / Shadow Back Cull 标识时才解除静音并选对应输出。
CULL_GROUP_NAME = "Crafter-背面剔除"
CULL_GROUP_PROP = "crafter_cull_group"   # 标在材质里的剔除组节点上，便于重复加载时识别
CULL_GROUP_X_STEP = 200.0                # 插入剔除组后 CI 及相关节点整体左移的步长

def _cull_group_node_tree():
    return bpy.data.node_groups.get(CULL_GROUP_NAME)

def _find_cull_group_nodes(node_tree):
    if node_tree is None:
        return []
    return [node for node in node_tree.nodes if node.get(CULL_GROUP_PROP)]

def _cycles_output_node(node_tree):
    if node_tree is None:
        return None
    for node in node_tree.nodes:
        if node.type == "OUTPUT_MATERIAL" and getattr(node, "target", "") == "CYCLES":
            return node
    return None

def _apply_cull_layout_shift(material, nodes, group_CI, shift):
    '''把 CI 左侧的贴图/帧节点整体左移，给剔除组腾位置；按记录差值移动，重复加载不漂移。'''
    if material is None:
        return
    try:
        prev = float(material.get("Crafter_cull_shift", 0.0) or 0.0)
    except Exception:
        prev = 0.0
    delta = shift - prev
    if abs(delta) < 0.5:
        return
    threshold = group_CI.location.x + 0.5
    for node in nodes:
        if node.parent is not None:
            continue
        if node is group_CI or node.type == "OUTPUT_MATERIAL":
            continue
        if node.type == "GROUP" and node.node_tree is not None and (
                node.node_tree.name.startswith("CI-") or
                node.node_tree.name.startswith("C-PBR_Parser") or
                node.node_tree.name.startswith("Crafter-biomeTex")):
            continue
        if node.bl_idname == "ShaderNodeMixShader":
            continue
        if node.location.x <= threshold:
            node.location.x -= delta
    material["Crafter_cull_shift"] = shift

def _insert_cull_group(group_CI, node_output_Cycles, links, material=None):
    '''
    在 CI 的 Cycles 输出和最终 Cycles 输出之间插入剔除组节点（默认静音）。
    组或接口不存在时退回直连。
    '''
    node_tree = group_CI.id_data
    for old in _find_cull_group_nodes(node_tree):
        node_tree.nodes.remove(old)
    cull_tree = _cull_group_node_tree()
    if cull_tree is None or "Cycles-Surface" not in group_CI.outputs:
        if "Cycles-Surface" in group_CI.outputs:
            links.new(group_CI.outputs["Cycles-Surface"], node_output_Cycles.inputs["Surface"])
        return None
    # CI 及其左侧的解析器/贴图链整体往左，给剔除组腾出位置
    _apply_cull_layout_shift(material, node_tree.nodes, group_CI, CULL_GROUP_X_STEP)
    group_CI.location.x -= CULL_GROUP_X_STEP
    node = node_tree.nodes.new("ShaderNodeGroup")
    node.node_tree = cull_tree
    node.label = CULL_GROUP_NAME
    node[CULL_GROUP_PROP] = True
    node.mute = True
    node.location = ((group_CI.location.x + node_output_Cycles.location.x) * 0.5,
                     node_output_Cycles.location.y + 160.0)
    cull_input = next((s for s in node.inputs if s.type == 'SHADER' and s.enabled), None)
    # 默认连阴影剔除输出（静音时直通）；有标识时由 _sync_cull_group_output 切换
    cull_output = node.outputs.get("Shadow Back Cull")
    if cull_output is None:
        cull_output = next((s for s in node.outputs if s.type == 'SHADER' and s.enabled), None)
    if cull_input is None or cull_output is None:
        # 接口不符合预期：删掉节点，退回直连
        node_tree.nodes.remove(node)
        links.new(group_CI.outputs["Cycles-Surface"], node_output_Cycles.inputs["Surface"])
        return None
    links.new(group_CI.outputs["Cycles-Surface"], cull_input)
    links.new(cull_output, node_output_Cycles.inputs["Surface"])
    return node

def _sync_cull_group_output(material, render_on, shadow_on):
    '''
    按剔除标识切换剔除组节点的输出并解除/恢复静音：
      - Shadow Back Cull → 用组的 Shadow Back Cull 输出；
      - Back Cull        → 用组的 Back Cull 输出；
      - 都没有           → 保持静音（直通）。
    '''
    node_tree = getattr(material, "node_tree", None)
    out_node = _cycles_output_node(node_tree)
    if out_node is None:
        return
    for node in _find_cull_group_nodes(node_tree):
        out_socket = None
        if shadow_on and "Shadow Back Cull" in node.outputs:
            out_socket = node.outputs["Shadow Back Cull"]
        elif render_on and "Back Cull" in node.outputs:
            out_socket = node.outputs["Back Cull"]
        if out_socket is None:
            # 未开启任何剔除标识时也保持默认的 Shadow Back Cull 连接（静音时直通）
            out_socket = node.outputs.get("Shadow Back Cull")
        if out_socket is None:
            out_socket = next((s for s in node.outputs if s.type == 'SHADER' and s.enabled), None)
        if out_socket is not None:
            for link in list(node_tree.links):
                if link.from_node == node and link.to_node == out_node:
                    node_tree.links.remove(link)
            node_tree.links.new(out_socket, out_node.inputs["Surface"])
        node.mute = not (render_on or shadow_on)

def overlay_cull_input(material):
    '''
    overlay Mix 链的终点：材质里有 Cycles 剔除组时接到剔除组的 shader 输入，
    并断开基础 CI 的直连（由 Mix 链接管）。没有剔除组时返回 None。
    '''
    node_tree = getattr(material, "node_tree", None)
    for node in _find_cull_group_nodes(node_tree):
        inp = next((s for s in node.inputs if s.type == 'SHADER' and s.enabled), None)
        if inp is not None:
            for link in list(inp.links):
                node_tree.links.remove(link)
            return inp
    return None

def link_CI_output(group_CI, node_output_EEVEE, node_output_Cycles, links, material=None):
    '''
    group_CI: 材质组节点
    node_output_EEVEE: EEVEE输出节点
    node_output_Cycles: Cycles输出节点
    nodes: 目标材质节点组
    links:目标材质连接组
    '''
    if "EEVEE-Surface" in group_CI.outputs:
        links.new(group_CI.outputs["EEVEE-Surface"], node_output_EEVEE.inputs["Surface"])
    if "EEVEE-Volume" in group_CI.outputs: 
        links.new(group_CI.outputs["EEVEE-Volume"], node_output_EEVEE.inputs["Volume"])
    if "EEVEE-Displacement" in group_CI.outputs: 
        links.new(group_CI.outputs["EEVEE-Displacement"], node_output_EEVEE.inputs["Displacement"])
    if "EEVEE-Thickness" in group_CI.outputs: 
        if "Thickness" in node_output_EEVEE.inputs:
            links.new(group_CI.outputs["EEVEE-Thickness"], node_output_EEVEE.inputs["Thickness"])
        
    if "Cycles-Surface" in group_CI.outputs: 
        _insert_cull_group(group_CI, node_output_Cycles, links, material)
    if "Cycles-Volume" in group_CI.outputs: 
        links.new(group_CI.outputs["Cycles-Volume"], node_output_Cycles.inputs["Volume"])
    if "Cycles-Displacement" in group_CI.outputs: 
        links.new(group_CI.outputs["Cycles-Displacement"], node_output_Cycles.inputs["Displacement"])
    if "Cycles-Thickness" in group_CI.outputs: 
        if "Thickness" in node_output_Cycles.inputs:
            links.new(group_CI.outputs["Cycles-Thickness"], node_output_Cycles.inputs["Thickness"])

def compute_default_pbr_value(addon_prefs):
    '''Encode the default PBR texture value for the selected parser.'''
    roughness = addon_prefs.Default_Roughness
    emission_strength = addon_prefs.Default_Emission_Strength
    if addon_prefs.PBR_Parser == "old_PBR":
        return [1 - roughness, addon_prefs.Default_Metallic, emission_strength, 1]
    IOR = addon_prefs.Default_IOR
    f0 = ((IOR - 1) / (IOR + 1)) ** 2
    return [1 - roughness, min(229 / 255, f0), 0, emission_strength * 254 / 255]


def apply_default_pbr_to_parser_node(node_parser, addon_prefs):
    '''Set the fallback value; a connected PBR texture still takes precedence.'''
    node_parser.inputs["PBR"].default_value = compute_default_pbr_value(addon_prefs)


def ensure_pbr_parser_group(addon_prefs):
    '''Rewire the shared parser group without visiting any material nodes.'''
    group = bpy.data.node_groups["C-PBR_Parser"]
    for node in group.nodes:
        if node.type == "GROUP_OUTPUT":
            node_output = node
        elif node.type == "GROUP_INPUT":
            node_input = node
        elif node.type == "GROUP":
            node_parser = node
    node_parser.node_tree = bpy.data.node_groups["C-" + addon_prefs.PBR_Parser]
    for output in node_parser.outputs:
        group.links.new(output, node_output.inputs[output.name])
    for input in node_parser.inputs:
        group.links.new(input, node_input.outputs[input.name])


def apply_default_pbr_to_ci_inputs(group_CI, addon_prefs):
    '''
    没有法向/PBR 贴图、又不建 C-PBR_Parser 时，把解析器在"全默认输入"下本会输出的
    常量直接写进 CI 组输入，避免糙度/金属度/IOR 等退回模板默认值。
    只写没接线的插槽，写不进去（类型不符）就跳过，不影响导入。
    '''
    # 无贴图时解析器的输出就是常量：old PBR 的 G 是金属度、IOR 固定 1.5；
    # lab PBR 的 G 是 F0（换算回来就是偏好里的 IOR），金属度恒为 0。
    if str(getattr(addon_prefs, "PBR_Parser", "")) == "old_PBR":
        metallic = float(addon_prefs.Default_Metallic)
        ior = 1.5
    else:
        metallic = 0.0
        ior = float(addon_prefs.Default_IOR)
    defaults = (
        ("Roughness", float(addon_prefs.Default_Roughness)),
        ("Metallic", metallic),
        ("IOR", ior),
        ("AO", 1.0),
        ("Height", 1.0),
        ("Parsed Normal", (0.0, 0.0, 1.0)),
        ("SSS", 0.0),
        ("Porosity", 0.0),
        ("Emission Strength", float(addon_prefs.Default_Emission_Strength)),
    )
    for name, value in defaults:
        socket = group_CI.inputs.get(name)
        if socket is None or socket.is_linked:
            continue
        try:
            socket.default_value = value
        except (TypeError, ValueError):
            pass


def add_node_parser(group_CI, nodes, links, parent=None, addon_prefs=None,
                    has_pbr_texture=True):
    '''
    gout_CI: 材质组节点
    nodes: 目标材质节点组
    links:目标材质连接组
    parent: 新建节点要挂到的父帧（overlay 帧），None 表示不挂
    has_pbr_texture: 本材质（overlay 帧）是否有 PBR 或法线贴图；
                     没有且偏好里没开"始终添加"时，不建解析器，直接把默认常量写进 CI
    return: node_C_PBR_Parser（不建时为 None）
    '''
    if addon_prefs is None:
        addon_prefs = bpy.context.preferences.addons[__addon_name__].preferences
    if not getattr(addon_prefs, "Always_Add_PBR_Parser", False) and not has_pbr_texture:
        # 重复加载时先清掉上一轮留下的解析器（同一父帧/同一层），否则关掉开关也不生效
        for node in [n for n in nodes
                     if n.type == "GROUP" and n.node_tree is not None
                     and n.node_tree.name == "C-PBR_Parser" and n.parent == parent]:
            nodes.remove(node)
        apply_default_pbr_to_ci_inputs(group_CI, addon_prefs)
        return None
    node_C_PBR_Parser = nodes.new(type="ShaderNodeGroup")
    # 先挂父帧再设 location (node.location 是父帧相对坐标)
    if parent is not None:
        node_C_PBR_Parser.parent = parent
    node_C_PBR_Parser.location = (group_CI.location.x - parser_offset_x, group_CI.location.y - 160)
    node_C_PBR_Parser.node_tree = bpy.data.node_groups["C-PBR_Parser"]
    # Load Material passes its preferences once for base and overlay parsers, so
    # imports need no second material walk to initialize unconnected PBR inputs.
    apply_default_pbr_to_parser_node(node_C_PBR_Parser, addon_prefs)
    for output in node_C_PBR_Parser.outputs:
        if output.name in group_CI.inputs:
            links.new(output, group_CI.inputs[output.name])
    return node_C_PBR_Parser

def add_node_pbr(location_base_tex, y_subtract, dir, nodes, links, list_info_moving, parent=None):
    if os.path.exists(bpy.path.abspath(dir)):
        node_pbr = nodes.new(type="ShaderNodeTexImage")
        # 先挂父帧再设 location (node.location 是父帧相对坐标)
        if parent is not None:
            node_pbr.parent = parent
        node_pbr.location = (location_base_tex.x, location_base_tex.y - y_subtract)
        node_pbr.image = bpy.data.images.load(dir)
        node_pbr.interpolation = "Closest"
        bpy.data.images[node_pbr.image.name].colorspace_settings.name = "Non-Color"
        info_moving = add_node_moving_texture(node_pbr, nodes, links, list_info_moving, parent=parent)
        list_info_moving.append(info_moving)
        return node_pbr
    else:
        return None

def reuse_moving_info_entry(node_tex):
    '''
    取贴图节点"已有的动态纹理"信息条目，格式与 add_node_moving_texture 的返回值一致：
        [动态纹理_尾节点, [row, frametime, frames, list_frames, interpolate]]
    row 从尾节点的 "row" 输入读回（它就等于原始 info[0]，见 add_node_moving_texture 结尾）；
    动画参数填占位值：这个条目只会被取 [1][0] 当 row_base，不参与新贴图的动画参数比对。
    node_tex: 贴图节点；不是由 Crafter-动态纹理_尾 驱动的返回 None
    '''
    info = node_moving_tex_info(node_tex)
    node_from = info[1] if info[0] else None
    if node_from is None or getattr(node_from, "node_tree", None) is None:
        return None
    if not node_from.node_tree.name.startswith("Crafter-动态纹理_尾"):
        return None
    try:
        row = float(node_from.inputs["row"].default_value)
    except Exception:
        return None
    return [node_from, [row, 1, 1, [[0, 1]], False]]


def load_normal_and_PBR(node_tex_base, nodes, links, parent=None, want_normal=True, want_pbr=True, keep_moving_texture=False):
    '''
    以基础色节点添加法向贴图节点和PBR贴图节点、连接并添加动态纹理节点
    node_tex_base: 基础色节点
    nodes: 目标材质节点组
    links:目标材质连接组
    parent: 新建节点(含动态纹理帧)要挂到的父帧（overlay 帧），None 表示不挂
    want_normal / want_pbr: 去同目录找哪几类贴图（False = 这类不建节点，返回 None）
    keep_moving_texture: 基础色已经由动态纹理驱动时复用既有那份、不再新建一份。
                        补缺加载会带着已有的基础色再走一遍，不能给它叠第二份动态纹理帧。
    return: (法向节点, PBR节点)，没建的、或同目录没找到的为 None
    '''
    node_tex_normal = None
    node_tex_PBR = None
    if node_tex_base != None:
        name_image = fuq_bl_dot_number(node_tex_base.image.name)
        # 同族 PBR/法向文件与基础色共用块名：name_block = 去掉 _n/_s/_a 后缀与 .png 扩展名
        # （acacia_log.png -> acacia_log，孤立当基础色的 polaroids_a.png 同样得到 polaroids）
        name_block = texture_block_name(name_image)
        dir_image = os.path.dirname(node_tex_base.image.filepath)
        dir_n = os.path.join(dir_image,name_block + "_n.png")
        dir_s = os.path.join(dir_image,name_block + "_s.png")
        dir_a = os.path.join(dir_image,name_block + "_a.png")
        # 基础色本身带 _s/_a 后缀时（它被判为基础色），拼出来的同族文件就是它自己：
        # 不能把基础色再当 PBR 读一遍，否则同一张图会被当 PBR 用
        if name_image in (name_block + "_s.png", name_block + "_a.png"):
            dir_s = None
            dir_a = None
        
        list_info_moving = []
        if keep_moving_texture:
            # 基础色已经由动态纹理驱动 → 直接把既有那份的信息当首项，不再给它建第二份。
            # 新贴图是静态图时会照常拿到"UV缩放"节点（row_base 取自既有帧），动画不会跑偏。
            entry_existing = reuse_moving_info_entry(node_tex_base)
            if entry_existing is not None:
                list_info_moving.append(entry_existing)
        if not list_info_moving:
            info_moving_tex = add_node_moving_texture(node_tex_base, nodes, links, list_info_moving, parent=parent)
            list_info_moving.append(info_moving_tex)

        location_base_tex = node_tex_base.location

        if want_normal:
            node_tex_normal = add_node_pbr(location_base_tex, 300, dir_n, nodes, links, list_info_moving, parent=parent)
        if want_pbr:
            # 有 _a 用 _a，否则用 _s：缺哪个都不能把已经建好的那张覆盖成 None
            if dir_s is not None:
                node_pbr_s = add_node_pbr(location_base_tex, 600, dir_s, nodes, links, list_info_moving, parent=parent)
                if node_pbr_s is not None:
                    node_tex_PBR = node_pbr_s
            if dir_a is not None:
                node_pbr_a = add_node_pbr(location_base_tex, 600, dir_a, nodes, links, list_info_moving, parent=parent)
                if node_pbr_a is not None:
                    node_tex_PBR = node_pbr_a

    return node_tex_normal, node_tex_PBR

def link_base_normal_PBR(node_tex_base, group_CI, links, node_C_PBR_Parser, node_tex_normal, node_tex_PBR):
    if node_tex_base != None:
        if "Base Color" in group_CI.inputs:
            links.new(node_tex_base.outputs["Color"], group_CI.inputs["Base Color"])
        if "Alpha" in group_CI.inputs:
            links.new(node_tex_base.outputs["Alpha"], group_CI.inputs["Alpha"])
    if node_tex_normal != None:
        if node_C_PBR_Parser is not None:
            links.new(node_tex_normal.outputs["Color"], node_C_PBR_Parser.inputs["Normal"])
            links.new(node_tex_normal.outputs["Alpha"], node_C_PBR_Parser.inputs["Normal Alpha"])
        if "Normal" in group_CI.inputs:
            links.new(node_tex_normal.outputs["Color"], group_CI.inputs["Normal"])
        if "Normal Alpha" in group_CI.inputs:
            links.new(node_tex_normal.outputs["Alpha"], group_CI.inputs["Normal Alpha"])
    if node_tex_PBR != None:
        if node_C_PBR_Parser is not None:
            links.new(node_tex_PBR.outputs["Color"], node_C_PBR_Parser.inputs["PBR"])
            links.new(node_tex_PBR.outputs["Alpha"], node_C_PBR_Parser.inputs["PBR Alpha"])
        if "PBR" in group_CI.inputs:
            links.new(node_tex_PBR.outputs["Color"], group_CI.inputs["PBR"])
        if "PBR Alpha" in group_CI.inputs:
            links.new(node_tex_PBR.outputs["Alpha"], group_CI.inputs["PBR Alpha"])

# ==================== 时间驱动（Time Driver，旧名 Crafter-time）====================
# 时间不走几何节点：几何节点每帧都要在整个网格上重算，几何一多模型就卡。
# 改成给物体的颜色属性 alpha 通道挂一个驱动器 #frame * fps_base / fps，也就是场景
# 当前秒数（与几何节点 Scene Time 的 Seconds 一致：frame / (fps / fps_base)）。
# 材质侧用 Object Info 节点的 Alpha 输出取值，代价只有一次驱动器求值，与几何量无关。
CRAFTER_TIME_DRIVER_EXPRESSION = "frame*fps_base/fps"
CRAFTER_TIME_DRIVER_PATH = "color"
CRAFTER_TIME_DRIVER_INDEX = 3               # 颜色属性的 alpha 通道
CRAFTER_TIME_MODIFIER_NAME = "Crafter-time" # 旧版本几何节点修改器的名字


def crafter_time_scene(obj=None):
    '''时间跟哪个场景的帧率走：优先物体所在场景，拿不到就用当前场景'''
    if obj is not None and len(obj.users_scene) > 0:
        return obj.users_scene[0]
    return getattr(bpy.context, "scene", None)


def crafter_time_seconds(scene=None):
    '''当前秒数 = frame * fps_base / fps，和几何节点 Scene Time 的 Seconds 一致'''
    if scene is None:
        scene = getattr(bpy.context, "scene", None)
    if scene is None:
        return 0.0
    fps = float(scene.render.fps) / float(scene.render.fps_base or 1.0)
    return (float(scene.frame_current) / fps) if fps > 0.0 else 0.0


def is_crafter_time_driver(fcurve):
    '''这条驱动器是不是 Crafter-time 加在颜色 alpha 上的那条'''
    if fcurve is None or fcurve.data_path != CRAFTER_TIME_DRIVER_PATH:
        return False
    if fcurve.array_index != CRAFTER_TIME_DRIVER_INDEX:
        return False
    driver = getattr(fcurve, "driver", None)
    return (driver is not None and driver.type == 'SCRIPTED'
            and driver.expression == CRAFTER_TIME_DRIVER_EXPRESSION)


def get_crafter_time_driver(obj):
    '''取物体颜色 alpha 上已有的 Crafter-time 驱动器，没有就返回 None'''
    anim_data = obj.animation_data
    if anim_data is None:
        return None
    for fcurve in anim_data.drivers:
        if is_crafter_time_driver(fcurve):
            return fcurve
    return None


def remove_crafter_time_modifier(obj):
    '''清掉旧版本留下的 Crafter-time 几何节点修改器'''
    for modifier in list(obj.modifiers):
        if modifier.type != 'NODES' or modifier.node_group is None:
            continue
        if modifier.node_group.name == CRAFTER_TIME_MODIFIER_NAME:
            obj.modifiers.remove(modifier)


def remove_Crafter_time(obj):
    '''撤掉时间：删掉颜色 alpha 上的驱动器（alpha 还原成 1），并清理旧的几何节点修改器'''
    if obj is None or obj.library is not None:  # 库链接的物体改不了
        return
    if get_crafter_time_driver(obj) is not None:
        obj.driver_remove(CRAFTER_TIME_DRIVER_PATH, CRAFTER_TIME_DRIVER_INDEX)
        obj.color[CRAFTER_TIME_DRIVER_INDEX] = 1.0
    remove_crafter_time_modifier(obj)
    unregister_crafter_time_object(obj)


def add_Crafter_time(obj):
    '''
    给物体的颜色 alpha 挂时间驱动器（幂等：重复调用只是刷新一下场景变量）。
    材质侧用 Object Info 节点的 Alpha 输出取当前秒数，不再需要几何节点。
    '''
    if obj is None or obj.library is not None:  # 库链接的物体改不了驱动器
        return
    scene = crafter_time_scene(obj)
    if scene is None:
        return
    # 旧版本的几何节点直接清掉，免得时间叠一层
    remove_crafter_time_modifier(obj)

    fcurve = get_crafter_time_driver(obj)
    if fcurve is None:
        fcurve = obj.driver_add(CRAFTER_TIME_DRIVER_PATH, CRAFTER_TIME_DRIVER_INDEX)
    driver = fcurve.driver
    driver.type = 'SCRIPTED'
    for name, data_path in (("fps", "render.fps"), ("fps_base", "render.fps_base")):
        variable = driver.variables.get(name)
        if variable is None:
            variable = driver.variables.new()
            variable.name = name
        variable.type = 'SINGLE_PROP'
        target = variable.targets[0]
        target.id_type = 'SCENE'
        target.id = scene
        target.data_path = data_path
    # 表达式的变量齐了再写表达式，免得中途降到 Python 求值
    driver.expression = CRAFTER_TIME_DRIVER_EXPRESSION
    # 不逐帧求值的时候（比如直接看 UI）alpha 也应该是当前秒数
    obj.color[CRAFTER_TIME_DRIVER_INDEX] = crafter_time_seconds(scene)
    register_crafter_time_object(obj)


# ---------- Cycles 刷新补丁 ----------
# Cycles 只在对象被标记为"变换/着色有更新"时才重建该对象的数据（含物体颜色 alpha）。
# 单纯驱动物体颜色时，4.2 的 Cycles 不会自己发现，Object Info 的 Alpha 会停在旧值，
# 必须切一次渲染引擎才刷新。这里在换帧时给带时间驱动器的物体补一次标记，让 Cycles 重新
# 同步；EEVEE 每帧直接读物体颜色，不需要，所以补丁只在 CYCLES 引擎下才动手。
_crafter_time_objects = {}   # obj.as_pointer() -> Object（存引用，改名字不影响）
# addon register 期间 bpy.data 会被 Blender 换成空的占位对象，那时不能扫物体建表，
# 所以表标成"待建"，等第一次换帧或打开文件时再真正重建。
_crafter_time_registry_stale = True


def register_crafter_time_object(obj):
    '''登记一个带时间驱动器的物体，供 Cycles 刷新补丁逐帧打标记'''
    if obj is None:
        return
    try:
        _crafter_time_objects[obj.as_pointer()] = obj
    except ReferenceError:
        pass
    # 材质刚加载/重载过，置换判定要重新问一次
    _crafter_time_displacement_cache.clear()


def unregister_crafter_time_object(obj):
    '''注销（物体删了、驱动器被移除时调用）'''
    if obj is None:
        return
    try:
        _crafter_time_objects.pop(obj.as_pointer(), None)
    except ReferenceError:
        pass


def _crafter_time_refresh_enabled():
    '''偏好里的开关：关掉就完全不做事，方便开着/关着对比'''
    try:
        addon_prefs = bpy.context.preferences.addons[__addon_name__].preferences
        return bool(addon_prefs is not None and addon_prefs.Cycles_Time_Refresh)
    except (KeyError, AttributeError):
        return False


def _crafter_time_has_driver(obj):
    '''物体上还有没有我们那条驱动器（用户手动删掉时要能认出来）'''
    anim_data = obj.animation_data
    if anim_data is None:
        return False
    for fcurve in anim_data.drivers:
        if fcurve.data_path == CRAFTER_TIME_DRIVER_PATH \
                and fcurve.array_index == CRAFTER_TIME_DRIVER_INDEX:
            return True
    return False


def _crafter_time_rebuild_registry():
    '''按驱动器重建注册表。

    注册阶段 bpy.data 是 Blender 的占位对象（addon_utils 的 RestrictBlend），
    这时直接跳过并保持 stale，绝不能在 register() 里抛异常——那会让 Blender
    移除 addon 条目，所有面板都会因为拿不到偏好而变空。
    '''
    global _crafter_time_registry_stale
    try:
        objects = bpy.data.objects
    except AttributeError:
        return
    _crafter_time_objects.clear()
    _crafter_time_displacement_cache.clear()
    for obj in objects:
        try:
            if _crafter_time_has_driver(obj):
                _crafter_time_objects[obj.as_pointer()] = obj
        except (ReferenceError, AttributeError):
            continue
    _crafter_time_registry_stale = False


_crafter_time_displacement_cache = {}   # 材质名 -> 是否用真置换（需要重算几何）


def _crafter_time_material_needs_geometry(material):
    '''材质是否用了"真置换"：Displacement 输出接了线，且 displacement_method 不是 BUMP。

    法线/Bump 是着色阶段算的，刷新物体数据就够；真置换要重新细分/位移几何，
    只刷物体数据不会变，必须连几何一起标记。
    '''
    cached = _crafter_time_displacement_cache.get(material.name)
    if cached is not None:
        return cached
    needs = False
    try:
        if material.displacement_method != 'BUMP':
            tree = getattr(material, "node_tree", None)
            if tree is not None:
                for node in tree.nodes:
                    if node.type == 'OUTPUT_MATERIAL' and node.inputs["Displacement"].is_linked:
                        needs = True
                        break
    except (AttributeError, KeyError):
        needs = False
    _crafter_time_displacement_cache[material.name] = needs
    return needs


def _crafter_time_object_uses_displacement(obj):
    '''物体上有没有用真置换的材质（结果按材质名缓存）'''
    for slot in obj.material_slots:
        material = slot.material
        if material is not None and _crafter_time_material_needs_geometry(material):
            return True
    return False


def _crafter_time_cycles_is_drawing():
    '''当前是不是真有 Cycles 在出图：最终渲染中，或某个 3D 视口处于 Rendered。

    scene.render.engine 是 CYCLES 并不代表视口在用 Cycles——Material Preview / Solid
    视口跑的其实是 EEVEE / Workbench，它们每帧自己读物体颜色，不需要我们补标记；
    给它们打几何标记反而会让这些视口每帧重建几何，明显掉帧。
    '''
    if bpy.app.is_job_running('RENDER'):
        return True
    window_manager = getattr(bpy.context, "window_manager", None)
    if window_manager is None:
        return False
    for window in window_manager.windows:
        screen = getattr(window, "screen", None)
        if screen is None:
            continue
        for area in screen.areas:
            if area.type != 'VIEW_3D':
                continue
            space = area.spaces.active
            if space is not None and space.shading.type == 'RENDERED':
                return True
    return False


def _crafter_time_refresh_for_cycles(scene):
    '''换帧时给带时间驱动器的物体打标记，让 Cycles 重新同步物体数据（真置换连几何一起）'''
    if scene is None or scene.render.engine != 'CYCLES':
        return  # EEVEE 每帧自己读物体颜色，不用管
    if not _crafter_time_cycles_is_drawing():
        return  # 视口是 Material Preview / Solid，或者根本没在用 Cycles 出图
    if _crafter_time_registry_stale:
        _crafter_time_rebuild_registry()   # 注册阶段建不了表，第一次真正用到时补建
    if not _crafter_time_objects or not _crafter_time_refresh_enabled():
        return
    stale = []
    for pointer, obj in _crafter_time_objects.items():
        try:
            if obj.name not in bpy.data.objects or not _crafter_time_has_driver(obj):
                stale.append(pointer)   # 已删除 / 驱动器被手动移除
                continue
            # OBJECT = ID_RECALC_TRANSFORM：Cycles 重新同步该物体（颜色/alpha 等对象数据）
            refresh = {'OBJECT'}
            if _crafter_time_object_uses_displacement(obj):
                # DATA = ID_RECALC_GEOMETRY：真置换要重算几何才会跟着时间变
                refresh.add('DATA')
            obj.update_tag(refresh=refresh)
        except ReferenceError:          # 换文件 / 撤销后引用失效
            stale.append(pointer)
    for pointer in stale:
        _crafter_time_objects.pop(pointer, None)


@bpy.app.handlers.persistent
def _crafter_time_on_frame_change(scene):
    _crafter_time_refresh_for_cycles(scene)


@bpy.app.handlers.persistent
def _crafter_time_on_load(*_args):
    '''handler 跨文件存活，但表里是上一个文件的对象：清空后按驱动器重建'''
    _crafter_time_rebuild_registry()


def register_crafter_time_handlers():
    '''挂 handler（幂等：先摘再挂，避免重载插件时叠好几份）

    注意：addon register 期间 Blender 会把 bpy.data 换成空的占位对象
    （addon_utils 的 RestrictBlend），所以这里只挂 handler、不能扫物体，
    注册表推迟到第一次换帧或打开文件时再建。
    '''
    global _crafter_time_registry_stale
    remove_crafter_time_handlers()
    bpy.app.handlers.frame_change_pre.append(_crafter_time_on_frame_change)
    bpy.app.handlers.load_post.append(_crafter_time_on_load)
    _crafter_time_registry_stale = True


def remove_crafter_time_handlers():
    global _crafter_time_registry_stale
    for handlers, callback in ((bpy.app.handlers.frame_change_pre, _crafter_time_on_frame_change),
                               (bpy.app.handlers.load_post, _crafter_time_on_load)):
        while callback in handlers:
            handlers.remove(callback)
        # 插件重新加载后，列表里可能残留旧模块里的同名函数对象：按名字再清一遍
        for existing in list(handlers):
            if getattr(existing, "__name__", "") == callback.__name__:
                handlers.remove(existing)
    _crafter_time_objects.clear()
    _crafter_time_displacement_cache.clear()
    _crafter_time_registry_stale = True


def link_biome_tex(node_biomeTex, group_CI, links):
    if not node_biomeTex == None:
        for output in node_biomeTex.outputs:
            if output.name in group_CI.inputs:
                links.new(output, group_CI.inputs[output.name])

def find_biome_tex_group():
    '''
    找当前导入克隆出来的 biomeTex 节点组（名字形如 Crafter-biomeTex_<时间>）。
    找不到克隆时退回源组 Crafter-biomeTex。
    '''
    fallback = None
    best = None
    best_index = -1
    for group in bpy.data.node_groups:
        if group.name == "Crafter-biomeTex":
            fallback = group
        elif group.name.startswith("Crafter-biomeTex_"):
            suffix = group.name[len("Crafter-biomeTex_"):]
            # 只认克隆出来的数字后缀(如 Crafter-biomeTex_1699999999)，
            # 忽略依赖组 Crafter-biomeTex_vector 等非数字后缀
            if not suffix.isdigit():
                continue
            index = int(suffix)
            if best is None or index > best_index:
                best = group
                best_index = index
    return best if best is not None else fallback

def ensure_biome_tex_node(nodes, group_CI, group=None):
    '''
    材质里没有 biomeTex 组节点时按需追加；已有则顺便把 x 对齐到 Parser 列。
    位置：与基础 Parser 同 x 列（基础 CI 左侧 parser_offset_x 处），纵向放在 EEVEE 输出节点下方。
    '''
    node_biome = None
    for node in nodes:
        if node.type == "GROUP" and node.node_tree is not None and node.node_tree.name.startswith("Crafter-biomeTex"):
            node_biome = node
            break

    out_ev = None
    for nd in nodes:
        if nd.type == "OUTPUT_MATERIAL" and getattr(nd, "target", "") in ("EEVEE", "ALL"):
            out_ev = nd
            break

    # Parser 列的 x: 基础 CI 在输出左侧 (层数+1)*step(无 overlay 为 200)，Parser 再左移 parser_offset_x
    target_x = None
    if out_ev is not None:
        overlay_count = len(find_overlay_frames(nodes))
        ci_offset = overlay_mix_step * (overlay_count + 1) if overlay_count > 0 else 200.0
        target_x = out_ev.location.x - ci_offset - parser_offset_x

    if node_biome is not None:
        if target_x is not None:
            node_biome.location = (target_x, node_biome.location.y)
        return node_biome

    if group is None:
        group = find_biome_tex_group()
    if group is None:
        return None
    node_biome = nodes.new(type="ShaderNodeGroup")
    node_biome.node_tree = group
    if target_x is not None:
        node_biome.location = (target_x, out_ev.location.y - 550)
    elif group_CI is not None:
        node_biome.location = (group_CI.location.x - 600, group_CI.location.y - 400)
    return node_biome

def biome_tex_output_names(group):
    '''
    取 biomeTex 节点组的输出名。Blender 4.x 的 ShaderNodeTree 没有 outputs 属性，
    需要走 interface.items_tree；旧版本回退到 outputs。
    '''
    names = []
    interface = getattr(group, "interface", None)
    if interface is not None:
        for item in interface.items_tree:
            if getattr(item, "item_type", "") == "SOCKET" and getattr(item, "in_out", "") == "OUTPUT":
                names.append(item.name)
        if names:
            return names
    outputs = getattr(group, "outputs", None)
    if outputs is not None:
        return [socket.name for socket in outputs]
    return names

def get_material_tint(material):
    '''
    读取导入时写入的 tint 元数据（见 ImportWorld.finish_import）。
    return: {"kind", "color"?}；{} 表示明确不上色；None 表示没有元数据（旧导出器）
    '''
    if material is None:
        return None
    try:
        keys = material.keys()
    except AttributeError:
        return None
    if "Crafter_tint_kind" not in keys:
        return None
    tint = {}
    kind = str(material["Crafter_tint_kind"])
    if kind:
        tint["kind"] = kind
        if "Crafter_tint_color" in keys:
            color = material["Crafter_tint_color"]
            tint["color"] = (float(color[0]), float(color[1]), float(color[2]))
    return tint

def get_overlay_tint(material, index):
    '''
    读取导入时写入的 overlay 层 tint 元数据（Crafter_overlay<i>_kind/_color）。
    约定同 get_material_tint：{"kind", "color"?}；{} 表示明确不上色；None 表示没有元数据。
    '''
    if material is None:
        return None
    try:
        keys = material.keys()
    except AttributeError:
        return None
    kind_key = "Crafter_overlay" + str(index) + "_kind"
    if kind_key not in keys:
        return None
    tint = {}
    kind = str(material[kind_key])
    if kind:
        tint["kind"] = kind
        color_key = "Crafter_overlay" + str(index) + "_color"
        if color_key in keys:
            color = material[color_key]
            tint["color"] = (float(color[0]), float(color[1]), float(color[2]))
    return tint

def apply_tint(group_CI, nodes, links, tint):
    '''
    CI 驱动的 tint 供给：
    - CI 组暴露的命名群系输入（grass/foliage/dryfoliage/water/waterFog/fog/sky）：
      按需追加 biomeTex 节点并连接（CI 想要就给，与元数据无关）。
    - Tint Color（tint_interface_color）：元数据有 kind 时，群系类连对应输出、fixed 类写常量；
      无 kind（明确不上色）写白色；没有元数据（旧导出器）则不动。
    - Tint Enable（tint_interface_enable）：有元数据时写 0/1。
    - 固定色也只走 Tint Color：组里没有这个接口就不上色，不再往材质里插"正片叠底"兜底。
    tint: {"kind", "color"?} / {} / None
    '''
    group_biomeTex = find_biome_tex_group()
    node_biomeTex = None

    kind = tint.get("kind") if tint is not None else None
    named_outputs = []
    if group_biomeTex is not None:
        available_outputs = biome_tex_output_names(group_biomeTex)
        named_outputs = [name for name in available_outputs if name in group_CI.inputs]
    need_biome_output = (tint_interface_color in group_CI.inputs) and (kind in TINT_BIOME_KINDS)
    if named_outputs or need_biome_output:
        node_biomeTex = ensure_biome_tex_node(nodes=nodes, group_CI=group_CI, group=group_biomeTex)
    if node_biomeTex is not None:
        for name in named_outputs:
            links.new(node_biomeTex.outputs[name], group_CI.inputs[name])

    if tint is None:
        return

    if tint_interface_color in group_CI.inputs:
        socket = group_CI.inputs[tint_interface_color]
        for link in list(socket.links):
            links.remove(link)
        if kind == "fixed":
            color = tint.get("color", (1.0, 1.0, 1.0))
            socket.default_value = (color[0], color[1], color[2], 1.0)
        elif kind in TINT_BIOME_KINDS:
            output = node_biomeTex.outputs.get(kind) if node_biomeTex is not None else None
            if output is not None:
                links.new(output, socket)
            else:
                socket.default_value = (1.0, 1.0, 1.0, 1.0)
        else:
            socket.default_value = (1.0, 1.0, 1.0, 1.0)
    if tint_interface_enable in group_CI.inputs:
        group_CI.inputs[tint_interface_enable].default_value = 1.0 if kind else 0.0

def reload_Undivided_Vsersions(context: bpy.types.Context,dir_versions):#刷新无版本隔离列表

        addon_prefs = context.preferences.addons[__addon_name__].preferences

        list_versions = os.listdir(dir_versions)
        addon_prefs.Undivided_Vsersions_List.clear()
        for version in list_versions:
            versionPath = os.path.join(dir_versions, version)
            undivided_version = addon_prefs.Undivided_Vsersions_List.add()
            undivided_version.name = versionPath
        if (len(addon_prefs.Undivided_Vsersions_List) - 1 < addon_prefs.Undivided_Vsersions_List_index) or (addon_prefs.Undivided_Vsersions_List_index < 0):
            addon_prefs.Undivided_Vsersions_List_index = 0
    
def node_moving_tex_info(node):
    info = [False,None]
    if node == None:
        return info
    if len(node.inputs["Vector"].links) >0:
        info[0] = True
        info[1] = node.inputs["Vector"].links[0].from_node

    return info

def creat_parallax_node(node_tex_height, iterations, smooth, info_moving_normal, nodes, links, height_output="Alpha", gusess_scale=1):
    # 创建框，方便清除
    node_frame = nodes.new(type="NodeFrame")
    location = [node_tex_height.location.x - 1500, node_tex_height.location.y]
    node_frame.location = location
    node_frame.label = "Crafter-视差"

    move = 190
    iterations = max(iterations, 1)

    if info_moving_normal[0]:
        row_dao = 1 / info_moving_normal[1].inputs["row"].default_value
    else:
        row_dao = 1

    i = 1
    while iterations >= i:

        node_parallax = nodes.new("ShaderNodeGroup")
        node_parallax.node_tree = bpy.data.node_groups["CP-视差"]
        node_parallax.location = location
        node_parallax.inputs["1 / row"].default_value = row_dao
        location[0] -= 1.5 * move# location
        node_parallax.parent = node_frame
        if i == 1:
            node_final_parallax = node_parallax
        else:
            links.new(node_parallax.outputs["Next Start"], node_last.inputs["Start"])
            links.new(node_parallax.outputs["UV"], node_last.inputs["UV"])
            links.new(node_parallax.outputs["Current Depth"], node_last.inputs["Current Depth"])
            links.new(node_parallax.outputs["UV"], node_height.inputs["Vector"])
        node_last = node_parallax

        node_height = nodes.new("ShaderNodeTexImage")
        node_height.image = node_tex_height.image
        node_height.location = location
        location[0] -= move# location
        node_height.parent = node_frame
        if smooth:
            node_height.interpolation = "Linear"
        else:
            node_height.interpolation = "Closest"
        if gusess_scale == 1:
            links.new(node_height.outputs[height_output], node_last.inputs["Depth"])
        else:
            node_math = nodes.new("ShaderNodeMath")
            node_math.operation = "MULTIPLY_ADD"
            node_math.location = location
            location[0] -= move# location
            node_math.inputs["Value_001"].default_value = gusess_scale
            node_math.inputs["Value_002"].default_value = 1 - gusess_scale
            links.new(node_height.outputs[height_output], node_math.inputs["Value"])
            links.new(node_math.outputs["Value"], node_last.inputs["Depth"])
            node_math.parent = node_frame

        if i == iterations:
            if info_moving_normal[0]:
                node_moving = copy_node_tree_recursive(source_node=info_moving_normal[1], nodes=nodes, links=links, to_location=location, parent=node_frame)

                links.new(node_moving.outputs["Vector"], node_height.inputs["Vector"])
                links.new(node_moving.outputs["Vector"], node_last.inputs["UV"])
            else:
                node_TexCoord = nodes.new("ShaderNodeTexCoord")
                node_TexCoord.location = location
                node_TexCoord.parent = node_frame

                links.new(node_TexCoord.outputs["UV"], node_height.inputs["Vector"])
                links.new(node_TexCoord.outputs["UV"], node_last.inputs["UV"])

        i += 1

        links.new(node_final_parallax.outputs["UV"], node_tex_height.inputs["Vector"])

    return node_final_parallax, node_frame

def create_parallax_final(node, node_final_depth, info_height, info_moving, nodes, links, node_frame=None):
    node_final = nodes.new("ShaderNodeGroup")
    node_final.node_tree = bpy.data.node_groups["CP-视差转换"]
    Scale = 1
    if info_height[0]:
        Scale = info_height[1].inputs["row"].default_value
        links.new(info_height[1].inputs["frame / row"].links[0].from_node.outputs["Alpha"], node_final.inputs["old move"])

    if info_moving[0]:
        Scale /= info_moving[1].inputs["row"].default_value
        links.new(info_moving[1].inputs["frame / row"].links[0].from_node.outputs["Alpha"], node_final.inputs["new move"])

    node_final.location = node.location.x - 1400, node.location.y
    node_final.inputs["Scale"].default_value = Scale

    if node_frame != None:
        node_final.parent = node_frame
    links.new(node_final_depth.outputs["UV"], node_final.inputs["UV"])
    links.new(node_final.outputs["UV"], node.inputs["Vector"])

    return node_final

def is_alpha_channel_all_one(image_node):
    """
    判断图像纹理节点的图像纹理的alpha通道是否全部为1。
    
    参数:
        image_node (ShaderNodeTexImage): 图像纹理节点对象。
        
    返回:
        bool: 如果所有alpha通道值都为1，则返回True；否则返回False。
    """
    if not image_node or image_node.type != 'TEX_IMAGE' or not image_node.image:
        return False  # 确保提供了有效的图像纹理节点
    
    image = image_node.image
    pixels = image.pixels[:]  # 获取像素数据（RGBA格式）

    for i in range(0, len(pixels), 4):
        alpha = pixels[i + 3]  # 提取alpha通道的值
        if alpha < 1.0 - 1e-6:  # 使用一个小的容差来处理浮点精度问题
            return False  # 发现非1的alpha值，直接返回False

    return True  # 所有alpha值都是1

def copy_node_tree_recursive(source_node, nodes, links, node_mapping=None, to_location=[0,0], parent=None):
    """
    递归复制节点及其所有上游节点，并保持它们之间的连接关系
    
    参数:
        source_node: 要复制的源节点
        target_nodes: 目标节点集合 (nodes)
        target_links: 目标连接集合 (links)
        node_mapping: 用于跟踪已复制节点的字典，避免重复复制
    
    返回:
        复制后的新节点
    """
    

    # 初始化映射字典
    if node_mapping is None:
        node_mapping = {}
    
    # 如果节点已经复制过，直接返回已复制的节点
    if source_node in node_mapping:
        return node_mapping[source_node]
    
    # 复制当前节点
    new_node = nodes.new(type=source_node.bl_idname)

    new_node.location = to_location[0], to_location[1]
    if parent != None:
        new_node.parent = parent
    
    # 复制特殊属性（根据节点类型）
    if hasattr(source_node, 'node_tree') and source_node.node_tree:
        new_node.node_tree = source_node.node_tree
    
    if source_node.type == 'TEX_IMAGE' and source_node.image:
        new_node.image = source_node.image
        new_node.projection = source_node.projection
        new_node.interpolation = source_node.interpolation
        
    # 复制颜色渐变的插值模式
    if source_node.type == 'VALTORGB' and hasattr(source_node, 'color_ramp'):
        new_node.color_ramp.interpolation = source_node.color_ramp.interpolation
        new_node.color_ramp.color_mode = source_node.color_ramp.color_mode
        new_node.color_ramp.hue_interpolation = source_node.color_ramp.hue_interpolation
        
        # 复制所有颜色元素
        for i, element in enumerate(source_node.color_ramp.elements):
            if i == 0 or i == 1:
                # 第一个元素已经存在，只需设置其属性
                new_element = new_node.color_ramp.elements[i]
                new_element.position = element.position
                new_element.color = element.color
            else:
                # 添加新元素
                new_element = new_node.color_ramp.elements.new(element.position)
                new_element.color = element.color
    
    # 复制节点属性
    for input in source_node.inputs:
        new_node.inputs[input.name].default_value = input.default_value
        
    # 将新节点添加到映射字典中
    node_mapping[source_node] = new_node
    
    # 递归处理所有上游节点（输入连接）
    for input_socket in source_node.inputs:
        for link in input_socket.links:
            from_node = link.from_node
            from_socket = link.from_socket
            to_socket = link.to_socket
            
            # 递归复制上游节点
            new_from_node = copy_node_tree_recursive(from_node, nodes, links, node_mapping, to_location=[from_node.location.x - source_node.location.x + to_location[0], from_node.location.y - source_node.location.y + to_location[1]], parent=parent)
            
            # 找到对应的输出socket
            new_from_socket = None
            if from_socket.name in new_from_node.outputs:
                new_from_socket = new_from_node.outputs[from_socket.name]
            else:
                # 如果名称不匹配，尝试按索引查找
                try:
                    socket_index = list(from_node.outputs).index(from_socket)
                    if socket_index < len(new_from_node.outputs):
                        new_from_socket = new_from_node.outputs[socket_index]
                except ValueError:
                    pass
            
            # 找到新节点上对应的输入socket
            new_to_socket = None
            if to_socket.name in new_node.inputs:
                new_to_socket = new_node.inputs[to_socket.name]
            else:
                # 如果名称不匹配，尝试按索引查找
                try:
                    socket_index = list(source_node.inputs).index(to_socket)
                    if socket_index < len(new_node.inputs):
                        new_to_socket = new_node.inputs[socket_index]
                except ValueError:
                    pass
            
            # 创建连接
            if new_from_socket and new_to_socket:
                links.new(new_from_socket, new_to_socket)
    
    return new_node

def nodes_distance(node1, node2):
    """
    计算两个节点之间的欧几里得距离
    
    参数:
        node1: 第一个节点对象
        node2: 第二个节点对象
        
    返回:
        float: 两个节点之间的距离
    """
    loc1 = node1.location
    loc2 = node2.location
    
    # 使用欧几里得距离公式计算距离
    distance = ((loc1.x - loc2.x) ** 2 + (loc1.y - loc2.y) ** 2) ** 0.5
    return distance

def same_nodes(node1, node2, visited_pairs=None):
    
    """
    递归向上游对比两个节点及其所有上游节点，检查每个接口的数值是否相同
    
    参数:
        node1: 第一个节点对象
        node2: 第二个节点对象
        visited_pairs: 已经对比过的节点对，防止无限循环
    
    返回:
        bool: 如果所有节点及其接口数值都相同返回True，否则返回False
    """
    # 初始化已访问节点对集合
    if visited_pairs is None:
        visited_pairs = set()
    
    # 如果节点对已经对比过，跳过以防止循环引用导致的无限递归
    node_pair = (node1, node2)
    if node_pair in visited_pairs:
        return True  # 假设已经对比过的节点对是相同的
    
    # 将当前节点对添加到已访问集合中
    visited_pairs.add(node_pair)
    
    # 首先检查节点本身是否相同类型
    if node1.bl_idname != node2.bl_idname:
        return False
    
    # 检查节点节点树
    if hasattr(node1, 'node_tree') and hasattr(node2, 'node_tree'):
        if node1.node_tree != node2.node_tree:
            return False
    
    # 检查输入接口数量是否相同
    if len(node1.inputs) != len(node2.inputs):
        return False
    
    # 检查输出接口数量是否相同
    if len(node1.outputs) != len(node2.outputs):
        return False
    
    # 检查输入接口的默认值是否相同
    for i in range(len(node1.inputs)):
        input1 = node1.inputs[i]
        input2 = node2.inputs[i]
        # 检查默认值是否相同
        if input1.default_value != input2.default_value:
            # 对于颜色等向量值，需要特殊处理
            if hasattr(input1.default_value, '__iter__') and hasattr(input2.default_value, '__iter__'):
                try:
                    if tuple(input1.default_value) != tuple(input2.default_value):
                        return False
                except:
                    # 如果无法转换为元组，则直接比较
                    if input1.default_value != input2.default_value:
                        return False
            else:
                return False
    
    # 检查特殊属性（如图像纹理节点的图像）
    if node1.type == 'TEX_IMAGE' and node2.type == 'TEX_IMAGE':
        if node1.image != node2.image:
            return False
        if node1.projection != node2.projection:
            return False
        if node1.interpolation != node2.interpolation:
            return False
    
    # 检查颜色渐变节点
    if node1.type == 'VALTORGB' and node2.type == 'VALTORGB':
        if node1.color_ramp.interpolation != node2.color_ramp.interpolation:
            return False
        if len(node1.color_ramp.elements) != len(node2.color_ramp.elements):
            return False
        for i in range(len(node1.color_ramp.elements)):
            if node1.color_ramp.elements[i].position != node2.color_ramp.elements[i].position:
                return False
            if tuple(node1.color_ramp.elements[i].color) != tuple(node2.color_ramp.elements[i].color):
                return False
    
    # 递归检查所有上游连接的节点
    upstream_nodes1 = {}
    upstream_nodes2 = {}
    
    # 收集第一个节点的所有上游节点
    for input_socket in node1.inputs:
        for link in input_socket.links:
            from_node = link.from_node
            from_socket_name = link.from_socket.name
            to_socket_name = link.to_socket.name
            upstream_nodes1[(from_socket_name, to_socket_name)] = from_node
    
    # 收集第二个节点的所有上游节点
    for input_socket in node2.inputs:
        for link in input_socket.links:
            from_node = link.from_node
            from_socket_name = link.from_socket.name
            to_socket_name = link.to_socket.name
            upstream_nodes2[(from_socket_name, to_socket_name)] = from_node
    
    # 检查上游连接是否相同
    if set(upstream_nodes1.keys()) != set(upstream_nodes2.keys()):
        return False
    
    # 递归对比所有上游节点
    for key in upstream_nodes1.keys():
        if key not in upstream_nodes2:
            return False
        
        upstream_node1 = upstream_nodes1[key]
        upstream_node2 = upstream_nodes2[key]
        
        # 递归对比上游节点
        if not same_nodes(upstream_node1, upstream_node2, visited_pairs):
            return False
    
    return True

def is_moving_same(info_tex, info_height):
    moving_same = False
    if info_height[0] == info_tex[0]:
        if info_height[0]:
            moving_same = same_nodes(info_tex[1], info_height[1])
        else:
            moving_same = True
    return moving_same 

def make_true_object_mode(context):
    if context.active_object:
        bpy.ops.object.mode_set(mode='OBJECT')


# ==================== 异步进程启动（不阻塞 Blender UI） ====================

def start_importer_async(exe_path, work_dir=None):
    """跨平台非阻塞启动 WorldImporter，返回 poll() 函数。

    poll() 返回:
      - None  → 进程仍在运行
      - True  → 进程成功结束
      - False → 进程失败
    """
    current_platform = platform.system()
    if current_platform == "Windows":
        return _start_windows_async(exe_path, work_dir)
    else:
        return _start_unix_async(exe_path, work_dir)


def _start_pipe_reader(proc):
    """启动后台线程持续读取 stdout，防止管道积压阻塞 exe。
    返回 (buffer, poll_fn)，buffer 是线程安全的列表，poll_fn 供定时器调用。
    """
    import threading
    buffer = []
    lock = threading.Lock()
    stop = threading.Event()

    def _reader():
        try:
            for line in iter(proc.stdout.readline, ''):
                if stop.is_set():
                    break
                stripped = line.rstrip('\n\r')
                if stripped:
                    with lock:
                        buffer.append(stripped)
        except:
            pass
        finally:
            try:
                proc.stdout.close()
            except:
                pass

    thread = threading.Thread(target=_reader, daemon=True)
    thread.start()

    def poll():
        nonlocal buffer
        with lock:
            if buffer:
                lines = list(buffer)
                buffer.clear()
                return '\n'.join(lines)
        ret = proc.poll()
        if ret is None:
            return None
        # 等 reader 线程读完剩余内容
        thread.join(timeout=2)
        with lock:
            if buffer:
                lines = list(buffer)
                buffer.clear()
                return '\n'.join(lines)
        return ret == 0

    return poll


def _start_windows_async(exe_path, work_dir=None):
    """Windows 非阻塞启动，优先用 Popen（可获取日志），回退 ShellExecuteExW"""
    if platform.system() != "Windows":
        return None
    # 尝试 subprocess.Popen（可捕获 stdout，但可能无管理员权限）
    try:
        proc = subprocess.Popen(
            [exe_path],
            cwd=work_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            universal_newlines=True,
            bufsize=1,
        )
        return _start_pipe_reader(proc)
    except Exception as e:
        print(f"Popen failed, fallback to ShellExecuteExW: {e}")

    # 回退：ShellExecuteExW（无日志捕获）
    try:
        class SHELLEXECUTEINFOW(ctypes.Structure):
            _fields_ = [
                ("cbSize", wintypes.DWORD),
                ("fMask", ctypes.c_ulong),
                ("hwnd", wintypes.HWND),
                ("lpVerb", wintypes.LPCWSTR),
                ("lpFile", wintypes.LPCWSTR),
                ("lpParameters", wintypes.LPCWSTR),
                ("lpDirectory", wintypes.LPCWSTR),
                ("nShow", ctypes.c_int),
                ("hInstApp", wintypes.HINSTANCE),
                ("lpIDList", ctypes.c_void_p),
                ("lpClass", wintypes.LPCWSTR),
                ("hKeyClass", wintypes.HKEY),
                ("dwHotKey", wintypes.DWORD),
                ("hIcon", wintypes.HANDLE),
                ("hProcess", wintypes.HANDLE)
            ]
        sei = SHELLEXECUTEINFOW()
        sei.cbSize = ctypes.sizeof(SHELLEXECUTEINFOW)
        sei.fMask = 0x00000040
        sei.lpVerb = 'runas'
        sei.lpFile = exe_path
        sei.lpDirectory = work_dir
        sei.nShow = 1
        if not ctypes.windll.shell32.ShellExecuteExW(ctypes.byref(sei)):
            return None
        hProcess = sei.hProcess
        def poll():
            if hProcess is None:
                return False
            wr = ctypes.windll.kernel32.WaitForSingleObject(hProcess, 0)
            if wr == 0x0:
                ec = wintypes.DWORD()
                ctypes.windll.kernel32.GetExitCodeProcess(hProcess, ctypes.byref(ec))
                ctypes.windll.kernel32.CloseHandle(hProcess)
                return ec.value == 0
            elif wr == 0x00000102:
                return None
            else:
                ctypes.windll.kernel32.CloseHandle(hProcess)
                return False
        return poll
    except Exception as e:
        print(f"Windows async start error: {e}")
        return None


def _start_unix_async(exe_path, work_dir=None):
    """macOS/Linux 非阻塞启动（subprocess.Popen + 轮询）"""
    try:
        # macOS 需要替换 .exe 后缀
        actual_exe = exe_path
        if platform.system() == "Darwin" and exe_path.endswith("WorldImporter.exe"):
            macos_exe = exe_path.replace("WorldImporter.exe", "WorldImporter")
            if os.path.exists(macos_exe):
                actual_exe = macos_exe

        process = subprocess.Popen(
            [actual_exe],
            cwd=work_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            universal_newlines=True,
            bufsize=1,
        )

        return _start_pipe_reader(process)

    except Exception as e:
        print(f"Unix async start error: {e}")
        return None

