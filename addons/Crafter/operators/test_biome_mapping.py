"""Blender-free regression for the WI -> biome texture coordinate contract."""
import ast
from pathlib import Path
import unittest


class BiomeTextureBoundsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = Path(__file__).with_name("ImportWorld.py")
        tree = ast.parse(path.read_text(encoding="utf-8"))
        helper = next(n for n in tree.body if isinstance(n, ast.FunctionDef)
                      and n.name == "biome_texture_bounds")
        namespace = {}
        exec(compile(ast.Module(body=[helper], type_ignores=[]), str(path), "exec"), namespace)
        cls.bounds = staticmethod(namespace["biome_texture_bounds"])

    def test_block_precision_centers_reach_first_and_last_pixels(self):
        config = dict(minX=-3, maxX=5, minZ=-7, maxZ=2)
        left, bottom, right, top = self.bounds(config)
        self.assertEqual((left, bottom, right, top), (-3, -3, 6, 7))
        width, height = 9, 10
        # PNG row 0 is minZ; Blender image V increases from the bottom.
        self.assertAlmostEqual(((-3 + .5) - left) / (right - left), .5 / width)
        self.assertAlmostEqual(((-(-7 + .5)) - bottom) / (top - bottom), 1 - .5 / height)
        self.assertAlmostEqual(((5 + .5) - left) / (right - left), 1 - .5 / width)
        self.assertAlmostEqual(((-(2 + .5)) - bottom) / (top - bottom), .5 / height)

    def test_chunk_precision_uses_importer_bounds(self):
        self.assertEqual(self.bounds(dict(minX=-3, maxX=5, minZ=-7, maxZ=2,
                                          useChunkPrecision=True)), (-16, -17, 17, 16))

    def test_negative_endpoints_and_aligned_endpoints(self):
        self.assertEqual(self.bounds(dict(minX=-17, maxX=-3, minZ=0, maxZ=16,
                                          useChunkPrecision=True)), (-32, -17, -15, 0))


if __name__ == "__main__":
    unittest.main()
