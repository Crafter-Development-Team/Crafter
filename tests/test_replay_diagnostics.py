"""Replay diagnostics tests without importing Blender or the add-on __init__."""
import importlib
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1] / "addons" / "Crafter"


class ReplayDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        package = types.ModuleType("replay_log_test")
        package.__path__ = [str(ROOT)]
        self.modules = patch.dict(sys.modules, {"replay_log_test": package})
        self.modules.start()
        self.addCleanup(self.modules.stop)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.env = patch.dict(os.environ, {"CRAFTER_LOG_DIR": self.directory.name, "CRAFTER_DEBUG": "1"})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.replay = importlib.import_module("replay_log_test.replay.import_replay")
        self.log = importlib.import_module("replay_log_test.diagnostics")
        self.addCleanup(lambda: self.log._handler and self.log._handler.close())

    def recording(self, invalid=False):
        path = Path(self.directory.name) / "sample.mcpr"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("metaData.json", json.dumps(dict(mcversion="1.21.1", protocol=767, duration=1000)))
            archive.writestr("recording.tmcpr", struct.pack(">ii", 0, 999) if invalid else b"")
        return path

    def test_empty_replay_reports_counts_and_fallback(self):
        destination, info = self.replay.materialize_world_from_mcpr(str(self.recording()))
        self.addCleanup(__import__("shutil").rmtree, destination)
        self.assertEqual(info["packets"], 0)
        self.assertTrue((Path(destination) / "level.dat").exists())
        self.assertTrue(any(row["text"] == "回放版本元数据" and row["protocol"] == 767 for row in self.log.recent_log))
        self.assertEqual(self.log.recent_log[-1]["status"], "completed_with_warnings")

    def test_bad_packet_records_path_and_traceback(self):
        path = self.recording(invalid=True)
        with self.assertRaises(ValueError):
            self.replay.materialize_world_from_mcpr(str(path))
        last = self.log.recent_log[-1]
        self.assertEqual(last["path"], str(path))
        self.assertEqual(last["status"], "error")
        self.assertIn("bad tmcpr length", last["traceback"])

    def test_failed_snapshot_restores_blocks(self):
        class Store:
            value = 42
            def get_block(self, *position):
                return self.value
            def set_block(self, *position):
                self.value = position[-1]
            def write_anvil_world(self, path):
                raise OSError("snapshot disk full")
        store = Store()
        info = dict(chunk_store=store, block_segments={(1, 2, 3): [(42, 0, 100), (43, 100, None)]})
        with self.assertRaises(OSError):
            self.replay.write_time_varying_worlds(info, self.directory.name)
        self.assertEqual(store.value, 42)
        self.assertEqual(self.log.recent_log[-1]["status"], "error")
        self.assertTrue(any("snapshot disk full" in row.get("traceback", "") for row in self.log.recent_log))


if __name__ == "__main__":
    unittest.main()
