"""Blender-free tests for jar → game-dir discovery.

Runs both as a package test (``python -m unittest addons.Crafter.replay.test_game_dirs``
or plain ``unittest discover`` from the addon root) and as a script from this
directory, so CI can pick it up without a cd.
"""
from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

try:  # package import (discover / -m unittest)
    from .game_dirs import discover_game_dirs_from_jar, find_jar_for_version, resourcepacks_json_key
except ImportError:  # run directly as a script from this directory
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from game_dirs import discover_game_dirs_from_jar, find_jar_for_version, resourcepacks_json_key


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

    def test_find_jar_for_version_in_game_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / ".minecraft"
            ver = root / "versions" / "1.21.1"
            ver.mkdir(parents=True)
            jar = ver / "1.21.1.jar"
            jar.write_bytes(b"PK\x05\x06" + b"\x00" * 18)
            self.assertEqual(find_jar_for_version("1.21.1", extra_roots=[str(root)]), str(jar))

    def test_find_jar_for_version_client_jar_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "instance"
            ver = root / "versions" / "1.20.1"
            ver.mkdir(parents=True)
            jar = ver / "client.jar"
            jar.write_bytes(b"PK\x05\x06" + b"\x00" * 18)
            self.assertEqual(find_jar_for_version("1.20.1", extra_roots=[str(root)]), str(jar))

    def test_find_jar_for_version_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / ".minecraft"
            (root / "versions").mkdir(parents=True)
            self.assertEqual(find_jar_for_version("1.21.1", extra_roots=[str(root)]), "")
            self.assertEqual(find_jar_for_version("", extra_roots=[str(root)]), "")

    def test_find_jar_for_version_env_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "pipeline-mc"
            ver = root / "versions" / "1.21.1"
            ver.mkdir(parents=True)
            jar = ver / "1.21.1.jar"
            jar.write_bytes(b"PK\x05\x06" + b"\x00" * 18)
            old = os.environ.get("CRAFTER_PIPELINE_MC")
            os.environ["CRAFTER_PIPELINE_MC"] = str(root)
            try:
                self.assertEqual(find_jar_for_version("1.21.1"), str(jar))
            finally:
                if old is None:
                    os.environ.pop("CRAFTER_PIPELINE_MC", None)
                else:
                    os.environ["CRAFTER_PIPELINE_MC"] = old


if __name__ == "__main__":
    unittest.main()
