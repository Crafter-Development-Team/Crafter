"""Blender-free: python3 addons/Crafter/operators/test_map_selector_runtime.py"""
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

_spec = importlib.util.spec_from_file_location("map_selector_runtime", Path(__file__).with_name("map_selector_runtime.py"))
runtime = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(runtime)


class CoordinatesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "coordinates.json"
        self.bounds = dict(minX=32, minY=100, minZ=-1, maxX=-16, maxY=-64, maxZ=-33)
        self.lod = dict(maxLevel=2, autoCenter=True, centerX=-16, centerZ=-16,
                        distance0=8, distance1=0, distance2=16, distance3=24)

    def read(self, data):
        self.path.write_text(json.dumps(data), encoding="utf-8-sig")
        return runtime.read_coordinates(self.path)

    def test_legacy_normalizes_aabb_and_preserves_lod_preferences(self):
        first, second, lod = self.read(self.bounds)
        self.assertEqual((first, second), ((-16, -64, -33), (32, 100, -1)))
        prefs = SimpleNamespace(Max_LOD_Level="4")
        runtime.apply_import_lod(prefs, lod)
        self.assertEqual(prefs.Max_LOD_Level, "4")

    def test_round_trip_preserves_units_and_ignores_unknown_keys(self):
        data = dict(self.bounds, importLod=dict(self.lod, futureFlag="ignored"), boxes=[])
        first, second, lod = self.read(data)
        self.assertEqual(lod, self.lod)
        prefs = SimpleNamespace()
        runtime.apply_import_lod(prefs, lod)
        self.assertEqual(prefs.Max_LOD_Level, "2")
        self.assertEqual(prefs.LODCenterX, -16)
        self.assertEqual(prefs.LOD1renderDistance, 0)
        self.assertIs(prefs.isLODAutoCenter, True)
        args = runtime.import_lod_seed_args(prefs)
        flags = dict(zip(args[::2], args[1::2]))
        self.assertEqual(len(flags), 8)
        self.assertEqual(flags["--import-lod-max"], "2")
        self.assertEqual(flags["--import-lod-auto-center"], "true")
        self.assertEqual(flags["--import-lod-cx"], "-16")
        self.assertEqual(flags["--import-lod-d1"], "0")

    def test_off_explicitly_disables_prior_preferences(self):
        prefs = SimpleNamespace(Max_LOD_Level="4")
        lod = dict(self.lod, maxLevel=0, autoCenter=False, centerX=-1, centerZ=17)
        runtime.apply_import_lod(prefs, self.read(dict(self.bounds, importLod=lod))[2])
        self.assertEqual(prefs.Max_LOD_Level, "0")
        self.assertIs(prefs.isLODAutoCenter, False)
        self.assertEqual(prefs.LODCenterX, -1)  # blocks, no conversion here
        self.assertIn("false", runtime.import_lod_seed_args(prefs))

    def test_invalid_coordinates(self):
        for data in ([], None, {}, dict(self.bounds, minX=True), dict(self.bounds, maxY=1.5),
                     dict(self.bounds, minZ="2"), dict(self.bounds, maxX=2 ** 31)):
            with self.subTest(data=data), self.assertRaises(ValueError):
                self.read(data)

    def test_invalid_lod_is_rejected_before_any_apply(self):
        invalid = [None, [], {}, dict(self.lod, maxLevel=True), dict(self.lod, maxLevel=5),
                   dict(self.lod, maxLevel=-1), dict(self.lod, maxLevel="2"),
                   dict(self.lod, autoCenter=1), dict(self.lod, autoCenter="false"),
                   dict(self.lod, centerX=2 ** 31), dict(self.lod, centerZ=-(2 ** 31)-1),
                   dict(self.lod, centerX=False)]
        for i in range(4):
            for value in (-1, 2 ** 31, 1.5, True, "8"):
                invalid.append(dict(self.lod, **{"distance" + str(i): value}))
        for lod in invalid:
            with self.subTest(lod=lod), self.assertRaises(ValueError):
                self.read(dict(self.bounds, importLod=lod))
        prefs = SimpleNamespace(Max_LOD_Level="3")
        with self.assertRaises(ValueError):
            runtime.apply_import_lod(prefs, dict(self.lod, distance3=-1))
        self.assertEqual(vars(prefs), {"Max_LOD_Level": "3"})

    def test_int32_limits_and_zero_distances(self):
        lod = dict(self.lod, centerX=-(2 ** 31), centerZ=2 ** 31 - 1,
                   distance0=0, distance3=2 ** 31 - 1)
        self.assertEqual(self.read(dict(self.bounds, importLod=lod))[2], lod)


if __name__ == "__main__":
    unittest.main()
