"""Blender-free regression: only entities.obj is gated by the Entities switch."""
import ast
import os
from pathlib import Path
import unittest


def load_helpers():
    tree = ast.parse(Path(__file__).with_name("ImportWorld.py").read_text(encoding="utf-8"))
    names = {"is_entity_obj", "select_obj_files"}
    body = [n for n in tree.body
            if (isinstance(n, ast.FunctionDef) and n.name in names)
            or (isinstance(n, ast.Assign)
                and any(getattr(t, "id", None) == "ENTITY_OBJ_FILENAME" for t in n.targets))]
    ns = {"os": os}
    exec(compile(ast.Module(body=body, type_ignores=[]), "ImportWorld.py", "exec"), ns)
    return ns


class EntityObjFilterTest(unittest.TestCase):
    def setUp(self):
        ns = load_helpers()
        self.is_entity = ns["is_entity_obj"]
        self.select = ns["select_obj_files"]

    def test_world_objs_always_selected(self):
        files = ["chunk_1.obj", "chunk_2.obj", "entities.mtl", "tint.json"]
        self.assertEqual(self.select(files, True), ["chunk_1.obj", "chunk_2.obj"])
        self.assertEqual(self.select(files, False), ["chunk_1.obj", "chunk_2.obj"])

    def test_entities_obj_gated_by_switch(self):
        files = ["chunk_1.obj", "entities.obj"]
        self.assertEqual(self.select(files, True), ["chunk_1.obj", "entities.obj"])
        self.assertEqual(self.select(files, False), ["chunk_1.obj"])

    def test_entity_name_is_case_insensitive(self):
        for name in ("entities.obj", "Entities.OBJ", "ENTITIES.Obj"):
            with self.subTest(name=name):
                self.assertTrue(self.is_entity(name))
                self.assertEqual(self.select([name], False), [])

    def test_other_files_named_like_entities_are_not_gated(self):
        # 只有完整文件名 entities.obj 算生物模型，避免误伤其它 obj
        for name in ("my_entities.obj", "entities_1.obj"):
            with self.subTest(name=name):
                self.assertFalse(self.is_entity(name))
                self.assertEqual(self.select([name], False), [name])
        # 非 .obj 后缀本来就会被一律排除，不应算作生物模型
        self.assertFalse(self.is_entity("entities.obj.bak"))
        self.assertEqual(self.select(["entities.obj.bak"], False), [])

    def test_full_path_still_matches(self):
        self.assertTrue(self.is_entity(os.path.join("importer", "entities.obj")))
        self.assertEqual(self.select([os.path.join("importer", "entities.obj")], False), [])


if __name__ == "__main__":
    unittest.main()
