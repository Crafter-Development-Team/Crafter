import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch, Mock

SOURCE = Path(__file__).resolve().parents[1] / "addons/Crafter/operators/map_selector_runtime.py"
spec = importlib.util.spec_from_file_location("map_selector_runtime", SOURCE)
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class MapSelectorRuntimeTests(unittest.TestCase):
    def setUp(self):
        runtime._java_cache.clear()

    def test_java_probe_is_cached(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "java.exe"
            path.write_bytes(b"fake")
            result = Mock(returncode=0, stdout=b"", stderr=b"OpenJDK 64-Bit Server VM")
            with patch.object(runtime.subprocess, "run", return_value=result) as run:
                self.assertEqual(runtime.probe_java(str(path)), str(path))
                self.assertEqual(runtime.probe_java(str(path)), str(path))
                run.assert_called_once()

    def test_bad_probe_is_also_cached(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "java.exe"
            path.write_bytes(b"fake")
            result = Mock(returncode=1, stdout=b"", stderr=b"error:")
            with patch.object(runtime.subprocess, "run", return_value=result) as run:
                self.assertIsNone(runtime.probe_java(str(path)))
                self.assertIsNone(runtime.probe_java(str(path)))
                run.assert_called_once()

    def read(self, data):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "coordinates.json"
            path.write_text(json.dumps(data), encoding="utf-8-sig")
            return runtime.read_coordinates(path)

    def test_coordinates_normalized_and_bom_supported(self):
        data = dict(minX=5, minY=64, minZ=-512, maxX=-5, maxY=-64, maxZ=-1)
        self.assertEqual(self.read(data), ((-5, -64, -512), (5, 64, -1)))

    def test_invalid_json_fields_rejected(self):
        data = dict(minX=0, minY=0, minZ=0, maxX=1, maxY=1, maxZ=1)
        for value in (True, 1.5, "0", 2**31):
            with self.assertRaises(ValueError):
                self.read(dict(data, minX=value))
        del data["maxX"]
        with self.assertRaises(ValueError):
            self.read(data)


if __name__ == "__main__":
    unittest.main()
