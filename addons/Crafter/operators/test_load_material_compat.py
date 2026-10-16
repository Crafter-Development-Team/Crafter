"""Blender-free: python3 addons/Crafter/operators/test_load_material_compat.py"""
import ast
from contextlib import nullcontext
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, call


def load_helpers(filename, operator, warning, with_window=False):
    # Compile the real helpers alone to avoid importing Blender and the add-on UI.
    tree = ast.parse(Path(__file__).with_name(filename).read_text(encoding="utf-8"))
    helpers = [node for node in ast.walk(tree)
               if isinstance(node, ast.FunctionDef)
               and node.name in {"_run_ops", "_run_load_material"}]
    region = SimpleNamespace(type="WINDOW")
    area = SimpleNamespace(type="VIEW_3D", regions=[region])
    window = SimpleNamespace(screen=SimpleNamespace(areas=[area]))
    context = SimpleNamespace(
        window=window if with_window else None,
        window_manager=SimpleNamespace(windows=[window] if with_window else []),
        temp_override=Mock(side_effect=lambda **kw: nullcontext()),
    )
    namespace = {
        "bpy": SimpleNamespace(context=context, ops=SimpleNamespace(
            crafter=SimpleNamespace(load_material=operator))),
        "warn_log": warning,
    }
    exec(compile(ast.Module(body=helpers, type_ignores=[]), filename, "exec"), namespace)
    return namespace["_run_load_material"], context


class LoadMaterialCompatibilityTest(unittest.TestCase):
    targets = (("ImportWorld.py", False), ("ImportWorld.py", True), ("Item.py", False))

    def test_stale_operator_retries_without_keyword_and_warns_once(self):
        messages = (
            'Converting py args to operator properties:: keyword "from_import" unrecognized',
            "load_material() got an unexpected keyword argument 'from_import'",
        )
        for filename, with_window in self.targets:
            for message in messages:
                with self.subTest(filename=filename, with_window=with_window, message=message):
                    def stale_operator(**kw):
                        if "from_import" in kw:
                            raise TypeError(message)
                        return {"FINISHED"}

                    operator = Mock(side_effect=stale_operator)
                    warning = Mock()
                    run, context = load_helpers(filename, operator, warning, with_window)
                    self.assertEqual(run(from_import=True), {"FINISHED"})
                    attempts = 3 if with_window else 1
                    self.assertEqual(operator.call_args_list,
                                     [call(from_import=True)] * attempts + [call()])
                    warning.assert_called_once()
                    self.assertIn("from_import", warning.call_args.args[0])
                    if with_window:
                        # The no-keyword retry also uses the context override path.
                        self.assertEqual(context.temp_override.call_count, 3)

    def test_current_operator_keeps_import_behavior(self):
        for filename, with_window in self.targets:
            with self.subTest(filename=filename, with_window=with_window):
                operator, warning = Mock(return_value={"FINISHED"}), Mock()
                run, _ = load_helpers(filename, operator, warning, with_window)
                self.assertEqual(run(from_import=True), {"FINISHED"})
                operator.assert_called_once_with(from_import=True)
                warning.assert_not_called()

    def test_other_errors_are_not_treated_as_stale_properties(self):
        errors = (TypeError("from_import must be a bool"),
                  TypeError('keyword "other" unrecognized'), RuntimeError("poll failed"))
        for filename, _ in self.targets:
            for error in errors:
                with self.subTest(filename=filename, error=error):
                    operator, warning = Mock(side_effect=error), Mock()
                    run, _ = load_helpers(filename, operator, warning)
                    with self.assertRaises(type(error)) as raised:
                        run(from_import=True)
                    self.assertIs(raised.exception, error)
                    operator.assert_called_once_with(from_import=True)
                    warning.assert_not_called()

    def test_retry_failure_is_not_retried_again(self):
        for filename in ("ImportWorld.py", "Item.py"):
            with self.subTest(filename=filename):
                error = TypeError("material setup failed")
                operator = Mock(side_effect=[
                    TypeError('keyword "from_import" unrecognized'), error])
                warning = Mock()
                run, _ = load_helpers(filename, operator, warning)
                with self.assertRaises(TypeError) as raised:
                    run(from_import=True)
                self.assertIs(raised.exception, error)
                self.assertEqual(operator.call_args_list, [call(from_import=True), call()])
                warning.assert_called_once()


if __name__ == "__main__":
    unittest.main()
