"""Run: python3 -m unittest discover -s tests -v (no Blender needed)."""
import ast
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


class DiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.env = patch.dict(os.environ, {"CRAFTER_LOG_DIR": self.directory.name, "CRAFTER_DEBUG": "1"})
        self.env.start()
        self.addCleanup(self.env.stop)
        spec = importlib.util.spec_from_file_location("crafter_diagnostics_test", ROOT / "addons/Crafter/diagnostics.py")
        self.log = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.log)
        self.addCleanup(lambda: self.log._handler and self.log._handler.close())

    def records(self):
        return [json.loads(line) for line in Path(self.log.log_file_path()).read_text().splitlines()]

    def test_metadata_and_exception(self):
        @self.log.trace_operation("material", detail=lambda: dict(material="stone"))
        def failing():
            raise ValueError("missing texture")
        with self.assertRaises(ValueError):
            failing()
        start, end = self.records()
        self.assertEqual(end["status"], "error")
        self.assertEqual(start["operation"], end["operation"])
        self.assertEqual(end["material"], "stone")
        self.assertIn("ValueError: missing texture", end["traceback"])
        self.assertGreaterEqual(end["duration_ms"], 0)
        for key in ("timestamp", "pid", "thread_id", "source", "function", "session"):
            self.assertTrue(end[key])

    def test_nested_cancel_and_context_restoration(self):
        @self.log.trace_operation("child")
        def child():
            return {"CANCELLED"}
        @self.log.trace_operation("parent")
        def parent():
            child()
            self.log.log_event("restored")
        parent()
        records = self.records()
        self.assertEqual(records[2]["status"], "cancelled")
        self.assertEqual(records[1]["parent_operation"], records[0]["operation"])
        self.assertEqual(records[3]["operation"], records[0]["operation"])
        self.assertEqual(self.log._operation.get(), "")

    def test_clear_keeps_disk_and_ui_is_bounded(self):
        for index in range(2010):
            self.log.log_event(str(index))
        self.assertEqual(len(self.log.recent_log), 2000)
        self.assertEqual(len(self.records()), 2010)
        self.log.clear_recent_log()
        self.assertEqual(len(self.log.recent_log), 1)
        self.assertEqual(len(self.records()), 2011)
        self.log.export_session(Path(self.directory.name) / "export.log", "header")
        self.assertIn('"text": "0"', (Path(self.directory.name) / "export.log").read_text())

    def test_rotation_debug_and_threads(self):
        self.log.log_file_path()
        self.log._handler.maxBytes = 2048
        def emit():
            for index in range(40):
                self.log.log_event(f"thread test {index}")
        threads = [threading.Thread(target=emit) for _ in range(3)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        self.assertEqual(len(self.log.recent_log), 120)
        self.assertTrue(Path(self.log.log_file_path() + ".1").exists())
        for path in Path(self.directory.name).glob("*.jsonl*"):
            for line in path.read_text().splitlines():
                json.loads(line)
        with patch.dict(os.environ, {"CRAFTER_DEBUG": "0"}):
            self.log.log_event("hidden", "DEBUG")
        self.assertEqual(len(self.log.recent_log), 120)

    def test_log_directory_fallback_and_no_self_export(self):
        bad_directory = Path(self.directory.name) / "not-a-directory"
        bad_directory.write_text("occupied")
        with patch.dict(os.environ, {"CRAFTER_LOG_DIR": str(bad_directory)}):
            self.log.log_event("fallback")
        self.assertTrue(self.log.log_file_path())
        with self.assertRaises(ValueError):
            self.log.export_session(self.log.log_file_path())
        self.assertTrue(any(record["text"] == "fallback" for record in self.records()))

    def test_export_operator_success_and_failure(self):
        source = (ROOT / "addons/Crafter/operators/ImportWorld.py").read_text()
        tree = ast.parse(source)
        operator = next(node for node in tree.body if isinstance(node, ast.ClassDef)
                        and node.name == "VIEW3D_OT_CrafterExportImportLog")
        execute = next(node for node in operator.body if isinstance(node, ast.FunctionDef) and node.name == "execute")
        # Bind package-free diagnostics, as in the other pure helper tests.
        class StripImports(ast.NodeTransformer):
            def visit_ImportFrom(self, node):
                return None
        execute = StripImports().visit(execute)
        namespace = dict(format_log_text=self.log.format_recent_log,
                         export_session=self.log.export_session, log_exception=self.log.log_exception)
        exec(compile(ast.Module(body=[execute], type_ignores=[]), "ImportWorld.py", "exec"), namespace)
        from types import SimpleNamespace
        self.log.log_event("export test")
        instance = SimpleNamespace(filepath=str(Path(self.directory.name) / "export.log"), report=lambda *args: None)
        self.assertEqual(namespace["execute"](instance, None), {"FINISHED"})
        instance.filepath = str(Path(self.directory.name) / "missing" / "export.log")
        self.assertEqual(namespace["execute"](instance, None), {"CANCELLED"})
        self.assertEqual(self.log.recent_log[-1]["level"], "ERROR")

    def test_explicit_child_severity(self):
        self.assertEqual(self.log.output_level("[DEBUG] no error expected"), "DEBUG")
        self.assertEqual(self.log.output_level("■ failed"), "ERROR")
        self.assertEqual(self.log.output_level("警告: file missing"), "WARN")

    def test_slow_and_handled_errors_are_not_clean_success(self):
        with patch.dict(os.environ, {"CRAFTER_SLOW_MS": "0"}):
            with self.log.operation_scope("slow"):
                pass
        self.assertTrue(self.records()[-1]["slow"])
        self.assertEqual(self.records()[-1]["level"], "WARN")
        with self.log.operation_scope("outer"):
            with self.log.operation_scope("inner"):
                self.log.log_event("handled failure", "ERROR")
        self.assertEqual(self.records()[-1]["status"], "completed_with_errors")
        self.assertEqual(self.records()[-1]["error_count"], 1)
        self.assertEqual(self.log._scopes.get(), ())

    def test_broken_sink_warns_once_and_ui_continues(self):
        self.log.log_file_path()
        import io
        class BrokenStream:
            def write(self, data):
                raise OSError("disk full")
            def flush(self):
                pass
        self.log._handler.stream.close()
        self.log._handler.stream = BrokenStream()
        with patch("sys.stderr", new_callable=io.StringIO) as stderr:
            self.log.log_event("first")
            self.log.log_event("second")
            self.assertEqual(stderr.getvalue().count("Persistent log failed"), 1)
        self.assertEqual(self.log.recent_log[-1]["text"], "second")
        self.log._handler.stream = None

    def test_probe_command_success_and_failure(self):
        result = self.log.run_logged_command([sys.executable, "-c", "print('probe-version')"],
                                              capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0)
        self.assertTrue(any("probe-version" in row["text"] for row in self.records()))
        with self.assertRaises(subprocess.CalledProcessError):
            self.log.run_logged_command([sys.executable, "-c", "raise SystemExit(3)"],
                                         capture_output=True, check=True, timeout=5)
        self.assertEqual(self.records()[-1]["returncode"], 3)
        self.assertEqual(self.records()[-1]["status"], "error")

    def test_child_process_pipe_and_exit(self):
        tree = ast.parse((ROOT / "addons/Crafter/operators/Defs.py").read_text())
        function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "_start_pipe_reader")
        namespace = dict(self.log.__dict__, _time=time, warn_log=self.log.log_event)
        exec(compile(ast.Module(body=[function], type_ignores=[]), "Defs.py", "exec"), namespace)
        proc = subprocess.Popen([sys.executable, "-c", "import sys; print('hello'); print('failure', file=sys.stderr); sys.exit(7)"],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8")
        self.addCleanup(lambda: proc.poll() is None and proc.kill())
        poll = namespace["_start_pipe_reader"](proc)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            result = poll()
            if isinstance(result, bool):
                break
            time.sleep(.005)
        else:
            self.fail("process poll timed out")
        self.assertFalse(result)
        self.assertEqual(proc.wait(), 7)
        records = self.records()
        self.assertEqual(records[-1]["returncode"], 7)
        self.assertEqual(records[-1]["child_pid"], proc.pid)
        self.assertTrue(any(e["text"] == "hello" for e in records))
        self.assertEqual(poll(), False)
        self.assertEqual(len(self.records()), len(records))


if __name__ == "__main__":
    unittest.main()
