"""Blender-free regression for vanilla tint kind inference."""
import importlib.util
import pathlib
import unittest


def _load_defs():
    path = pathlib.Path(__file__).with_name("Defs.py")
    # Defs.py imports bpy at module level; stub minimal surface for helpers we need.
    import sys
    import types
    if "bpy" not in sys.modules:
        bpy = types.ModuleType("bpy")
        bpy.props = types.SimpleNamespace(
            StringProperty=lambda **k: None,
            BoolProperty=lambda **k: None,
            IntProperty=lambda **k: None,
            FloatProperty=lambda **k: None,
            EnumProperty=lambda **k: None,
            CollectionProperty=lambda **k: None,
            PointerProperty=lambda **k: None,
            FloatVectorProperty=lambda **k: None,
        )
        bpy.types = types.SimpleNamespace(
            Operator=object, PropertyGroup=object, UIList=object, Panel=object,
            AddonPreferences=object, Menu=object, Context=object,
        )
        bpy.data = types.SimpleNamespace(materials={}, node_groups={}, images={}, objects={})
        bpy.context = types.SimpleNamespace()
        bpy.ops = types.SimpleNamespace()
        sys.modules["bpy"] = bpy
    # Stub package-relative imports used at Defs import time
    for name in (
        "addons",
        "addons.Crafter",
        "addons.Crafter.config",
        "addons.Crafter.__init__",
        "addons.common",
        "addons.common.i18n",
        "addons.common.i18n.i18n",
        "addons.Crafter.properties",
    ):
        if name not in sys.modules:
            mod = types.ModuleType(name)
            sys.modules[name] = mod
    sys.modules["addons.Crafter.config"].__addon_name__ = "Crafter"
    sys.modules["addons.common.i18n.i18n"].i18n = lambda s: s
    sys.modules["addons.Crafter.__init__"].dir_blend_append = ""
    sys.modules["addons.Crafter.__init__"].dir_init_main = ""
    sys.modules["addons.Crafter.properties"].dirs_temp = []

    # Load Defs by rewriting relative imports is hard; exec only the pure helpers.
    src = path.read_text(encoding="utf-8")
    # Extract and exec just the functions we need by building a tiny namespace
    ns = {"__name__": "infer_tint_test"}
    # Pull fuq_bl_dot_number, material_base_name, parse_material_name, tables, infer
    import re
    # simpler: import via runpy after path hack
    spec = importlib.util.spec_from_file_location("crafter_defs_tint", path)
    # Instead parse manually - copy the small pure subset
    exec(compile("""
import re, fnmatch
def fuq_bl_dot_number(name):
    last_dot_index = name.rfind('.')
    if last_dot_index != -1 and all('0' <= i <= '9' for i in name[last_dot_index + 1:]):
        name = name[:last_dot_index]
    return name
def material_base_name(name):
    name = fuq_bl_dot_number(name)
    at = name.find('@')
    if at != -1:
        name = name[:at]
    return name
def parse_material_name(name):
    if not name or name.startswith('color#'):
        return None
    left = name
    texture = None
    tilde = left.find('~')
    if tilde != -1:
        texture = left[tilde + 1:]
        left = left[:tilde]
    key = None
    sharp = left.find('#')
    if sharp != -1:
        key = left[sharp + 1:]
        left = left[:sharp]
    if not left:
        return None
    if ':' in left:
        namespace, block = left.split(':', 1)
    else:
        namespace, block = 'minecraft', left
    if not namespace:
        namespace = 'minecraft'
    if not texture:
        texture = block
    return {'block': namespace + ':' + block, 'key': key or None, 'texture': texture or None}
""", "helpers", "exec"), ns)
    # Read infer_vanilla_tint and tables from Defs source
    start = src.index("_VANILLA_TINT_KIND = {")
    end = src.index("\ndef _compile_classification_pattern")
    exec(compile(src[start:end], "infer", "exec"), ns)
    return ns


class InferVanillaTintTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ns = _load_defs()
        cls.infer = staticmethod(cls.ns["infer_vanilla_tint"])

    def test_oak_leaves(self):
        t = self.infer("minecraft:oak_leaves@foliage")
        self.assertEqual(t["kind"], "foliage")

    def test_birch_leaves_empty_suffix(self):
        t = self.infer("minecraft:birch_leaves")
        self.assertEqual(t["kind"], "fixed")
        self.assertIn("color", t)

    def test_sugar_cane(self):
        self.assertEqual(self.infer("minecraft:sugar_cane")["kind"], "grass")

    def test_grass_block_top_only(self):
        self.assertEqual(self.infer("minecraft:grass_block#top~grass_block_top")["kind"], "grass")
        self.assertIsNone(self.infer("minecraft:grass_block#side~grass_block_side"))
        self.assertIsNone(self.infer("minecraft:grass_block#bottom~dirt"))

    def test_mod_namespace_ignored(self):
        self.assertIsNone(self.infer("yuushya:big_oak_leaves#0~oak_leaves_branch"))

    def test_cherry_not_biome_tinted(self):
        self.assertIsNone(self.infer("minecraft:cherry_leaves"))


if __name__ == "__main__":
    unittest.main()
