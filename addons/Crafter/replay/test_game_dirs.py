"""Blender-free tests for jar → game-dir discovery."""
from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path

from game_dirs import discover_game_dirs_from_jar, resourcepacks_json_key


class DiscoverGameDirsTest(unittest.TestCase):
    def test_official_dot_minecraft_layout(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / ".minecraft"
            ver = root / "versions" / "1.21.1"
            ver.mkdir(parents=True)
            jar = ver / "1.21.1.jar"
            jar.write_bytes(b"PK\x05\x06" + b"\x00" * 18)
            (root / "resourcepacks").mkdir()
            (root / "mods").mkdir()
            dm, rp, mods = discover_game_dirs_from_jar(str(jar))
            self.assertEqual(dm, str(root))
            self.assertEqual(rp, str(root / "resourcepacks"))
            self.assertEqual(mods, str(root / "mods"))

    def test_prism_like_instance_beside_versions(self):
        with tempfile.TemporaryDirectory() as tmp:
            inst = Path(tmp) / "instances" / "MyPack"
            ver = inst / "versions" / "1.20.1"
            ver.mkdir(parents=True)
            jar = ver / "1.20.1.jar"
            jar.write_bytes(b"PK\x05\x06" + b"\x00" * 18)
            (inst / "resourcepacks").mkdir()
            (inst / "mods").mkdir()
            dm, rp, mods = discover_game_dirs_from_jar(str(jar))
            self.assertEqual(dm, str(inst))
            self.assertEqual(rp, str(inst / "resourcepacks"))
            self.assertEqual(mods, str(inst / "mods"))

    def test_no_neighbors_returns_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            jar = Path(tmp) / "lonely" / "client.jar"
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b"PK\x05\x06" + b"\x00" * 18)
            dm, rp, mods = discover_game_dirs_from_jar(str(jar))
            self.assertEqual((dm, rp, mods), ("", "", ""))

    def test_resourcepacks_json_key_mcpr_vs_world(self):
        mcpr = "/home/u/replays/demo.mcpr"
        self.assertEqual(resourcepacks_json_key(mcpr), os.path.abspath(mcpr))
        world = "/home/u/.minecraft/saves/MyWorld"
        self.assertEqual(resourcepacks_json_key(world), os.path.abspath("/home/u/.minecraft/saves"))


if __name__ == "__main__":
    unittest.main()
