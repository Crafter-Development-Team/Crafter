"""Blender-free: python3 addons/Crafter/operators/test_render_layer_alpha.py"""
import ast
import json
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

NAMES = {"fuq_bl_dot_number", "material_base_name", "load_render_layer_cutoffs",
         "material_block_id", "apply_render_layer_alpha"}
CONSTS = {"render_layer_json_name", "RENDER_LAYER_CUTOFF", "ALPHA_CUTOFF_NODE_NAME", "_render_layer_cache"}


def load_helpers():
    # 只编译 Defs.py 里用到的几个函数/常量，不导入 Blender
    tree = ast.parse(Path(__file__).with_name("Defs.py").read_text(encoding="utf-8"))
    body = []
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name in NAMES:
            body.append(node)
        elif isinstance(node, ast.Assign) and any(getattr(t, "id", None) in CONSTS for t in node.targets):
            body.append(node)
    ns = {"os": os, "json": json, "warn_log": lambda *a: None}
    exec(compile(ast.Module(body=body, type_ignores=[]), "Defs.py", "exec"), ns)
    return ns


class Socket:
    def __init__(self, node, name, value=0.0):
        self.node, self.name, self.default_value, self.links = node, name, value, []

    @property
    def is_linked(self):
        return bool(self.links)


class Node:
    def __init__(self, type_, name="", tree_name=None, inputs=(), outputs=()):
        self.type, self.name, self.label, self.parent = type_, name, "", None
        self.location = SimpleNamespace(x=0.0, y=0.0)
        self.node_tree = SimpleNamespace(name=tree_name) if tree_name else None
        self.operation = None
        self.inputs = {n: Socket(self, n) for n in inputs}
        self.outputs = {n: Socket(self, n) for n in outputs}

    def __getattr__(self, item):
        raise AttributeError(item)


class Inputs(dict):
    def __getitem__(self, k):
        return list(self.values())[k] if isinstance(k, int) else dict.__getitem__(self, k)


class Tree:
    def __init__(self):
        self.nodes = Nodes(self)
        self.links = Links()


class Nodes(list):
    def __init__(self, tree):
        super().__init__()
        self.tree = tree

    def new(self, kind):
        n = Node("MATH", inputs=(), outputs=())
        n.inputs = Inputs({"A": Socket(n, "A"), "B": Socket(n, "B")})
        n.outputs = Inputs({"Value": Socket(n, "Value")})
        self.append(n)
        return n


class Link:
    def __init__(self, a, b):
        self.from_socket, self.to_socket, self.from_node = a, b, a.node


class Links:
    def new(self, a, b):
        l = Link(a, b)
        b.links = [l]
        return l

    def remove(self, l):
        l.to_socket.links = []


def make_material(name):
    tree = Tree()
    tex = Node("TEX_IMAGE", outputs=("Color", "Alpha"))
    ci = Node("GROUP", tree_name="CI-default", inputs=("Base Color", "Alpha"))
    tree.nodes.extend([tex, ci])
    tree.links.new(tex.outputs["Alpha"], ci.inputs["Alpha"])
    return SimpleNamespace(name=name, library=None, node_tree=tree), tex, ci


class RenderLayerAlphaTest(unittest.TestCase):
    def setUp(self):
        self.ns = load_helpers()
        self.dir = tempfile.mkdtemp()
        with open(os.path.join(self.dir, "renderLayer.json"), "w", encoding="utf-8") as f:
            json.dump({"cutout": ["yuushya:title_white"], "cutout_mipped": ["minecraft:oak_leaves"],
                       "translucent": ["minecraft:white_stained_glass"]}, f)

    def test_block_id(self):
        f = self.ns["material_block_id"]
        self.assertEqual(f("yuushya:title_white#0~character_white@ctm/atlas_8x7_1"), "yuushya:title_white")
        self.assertEqual(f("minecraft:jungle_trapdoor.001"), "minecraft:jungle_trapdoor")
        self.assertEqual(f("minecraft:grass_block#side~grass_block_side"), "minecraft:grass_block")

    def test_cutoffs(self):
        c = self.ns["load_render_layer_cutoffs"](self.dir)
        self.assertEqual(c, {"yuushya:title_white": 0.1, "minecraft:oak_leaves": 0.5})

    def test_insert_and_idempotent(self):
        apply = self.ns["apply_render_layer_alpha"]
        mat, tex, ci = make_material("yuushya:title_white#0~character_white@ctm/x")
        self.assertTrue(apply(mat, self.dir))
        cut = ci.inputs["Alpha"].links[0].from_node
        self.assertEqual(cut.operation, "GREATER_THAN")
        self.assertAlmostEqual(cut.inputs[1].default_value, 0.1 - 1e-4)
        self.assertIs(cut.inputs[0].links[0].from_socket, tex.outputs["Alpha"])
        n = len(mat.node_tree.nodes)
        self.assertTrue(apply(mat, self.dir))
        self.assertEqual(len(mat.node_tree.nodes), n)   # 不重复插入

    def test_untouched(self):
        apply = self.ns["apply_render_layer_alpha"]
        for name in ("minecraft:white_stained_glass", "create:cogwheel#0~x"):
            mat, tex, ci = make_material(name)
            self.assertFalse(apply(mat, self.dir))
            self.assertIs(ci.inputs["Alpha"].links[0].from_socket, tex.outputs["Alpha"])
        mat, tex, ci = make_material("yuushya:title_white")
        self.assertFalse(apply(mat, tempfile.mkdtemp()))   # 没有 renderLayer.json


if __name__ == "__main__":
    unittest.main()
