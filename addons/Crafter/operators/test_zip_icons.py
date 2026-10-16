"""Blender-free regression for resource-pack icon ZIP filename mismatches."""
import ast
import copy
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import Mock
import zipfile


class ZipIconTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "pack.zip"
        self.dirs = []
        self.addCleanup(lambda: [shutil.rmtree(p) for p in self.dirs])
        self.icons = Mock()
        tree = ast.parse(Path(__file__).with_name("Defs.py").read_text(encoding="utf-8"))
        helpers = [n for n in tree.body if isinstance(n, ast.FunctionDef)
                   and n.name in {"open_zip_icon", "load_icon_from_zip"}]
        ns = dict(copy=copy, os=os, tempfile=tempfile, zipfile=zipfile, dirs_temp=self.dirs)
        exec(compile(ast.Module(body=helpers, type_ignores=[]), "Defs.py", "exec"), ns)
        self.load = ns["load_icon_from_zip"]

    def make_zip(self, central="pack.png", local=None, compression=zipfile.ZIP_STORED):
        with zipfile.ZipFile(self.path, "w", compression=compression) as archive:
            archive.writestr(central, b"icon payload")
        if local is not None:
            self.assertEqual(len(central), len(local))
            data = bytearray(self.path.read_bytes())
            # Change ONLY the local filename; leave the central directory intact.
            data[30:30 + len(local)] = local.encode("ascii")
            self.path.write_bytes(data)

    def check_loaded(self, expected_name):
        self.assertTrue(self.load(str(self.path), self.icons, "pack", 2))
        name, path, kind = self.icons.load.call_args.args
        self.assertEqual((name, kind), ("pack_icon_2", "IMAGE"))
        self.assertEqual(Path(path).name, expected_name)
        self.assertEqual(Path(path).read_bytes(), b"icon payload")

    def test_local_header_case_mismatch(self):
        for central, local in (("pack.png", "PACK.PNG"), ("PACK.JPG", "pack.jpg")):
            for compression in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                with self.subTest(central=central, compression=compression):
                    self.make_zip(central, local, compression)
                    with zipfile.ZipFile(self.path) as archive:
                        with self.assertRaises(zipfile.BadZipFile):
                            archive.read(central)
                    original = self.path.read_bytes()
                    self.check_loaded(central.lower())
                    self.assertEqual(self.path.read_bytes(), original)

    def test_normal_and_case_variant_members(self):
        for name in ("pack.png", "Pack.PnG", "pack.jpg", "PACK.JPG"):
            with self.subTest(name=name):
                self.make_zip(name)
                self.check_loaded(name.lower())

    def test_png_preferred_over_jpg(self):
        self.make_zip("Pack.PNG")
        with zipfile.ZipFile(self.path, "a") as archive:
            archive.writestr("pack.jpg", b"jpg")
        self.check_loaded("pack.png")

    def test_missing_icon_does_not_load_nonexistent_file(self):
        self.make_zip("assets/icon.png")
        self.assertFalse(self.load(str(self.path), self.icons, "pack", 2))
        self.icons.load.assert_not_called()
        self.assertEqual(self.dirs, [])

    def test_non_case_mismatch_still_rejected(self):
        self.make_zip("pack.png", "evil.png")
        with self.assertRaises(zipfile.BadZipFile):
            self.load(str(self.path), self.icons, "pack", 2)
        self.icons.load.assert_not_called()

    def test_crc_still_checked_after_normalization(self):
        self.make_zip("pack.png", "PACK.PNG")
        data = bytearray(self.path.read_bytes())
        data[38] ^= 1  # First uncompressed data byte after the 30+8 byte header.
        self.path.write_bytes(data)
        with self.assertRaisesRegex(zipfile.BadZipFile, "CRC"):
            self.load(str(self.path), self.icons, "pack", 2)
        self.icons.load.assert_not_called()


if __name__ == "__main__":
    unittest.main()
