"""Run with Blender --background --factory-startup --python tests/blender_log_smoke.py."""
import sys, types, importlib, pathlib, tempfile, os, json
root = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root))
os.environ['CRAFTER_LOG_DIR'] = tempfile.mkdtemp(prefix='crafter-blender-log-')
# Preserve the development framework's relative package structure. Import only;
# full register() copies user data, so exercise lifecycle against a sandbox copy.
package = types.ModuleType('crafter_dev')
package.__path__ = [str(root)]
sys.modules['crafter_dev'] = package
try:
    addon = importlib.import_module('crafter_dev.addons.Crafter')
    defs = importlib.import_module('crafter_dev.addons.Crafter.operators.Defs')
    material = importlib.import_module('crafter_dev.addons.Crafter.operators.LoadMaterial')
    import_world = importlib.import_module('crafter_dev.addons.Crafter.operators.ImportWorld')
    for name in ('operators.Item', 'operators.Asset', 'operators.ReplaceResources',
                 'replay.import_replay', 'replay.scene_builder'):
        importlib.import_module('crafter_dev.addons.Crafter.' + name)
    log = importlib.import_module('crafter_dev.addons.Crafter.diagnostics')
    import bpy
    log.log_environment(bpy, addon.bl_info['version'], addon.__file__)
    defs.log_stage_begin('outer')
    defs.log_stage_begin('inner')
    defs.log_stage_end('inner')
    assert defs.import_stage == 'outer'
    defs.log_stage_end('outer')
    assert defs.import_stage == ''
    output = os.path.join(os.environ['CRAFTER_LOG_DIR'], 'export.log')
    log.export_session(output)
    assert 'blender_version_string' in pathlib.Path(output).read_text()
    # Successful no-node material path and real error path without a CI library.
    mat = bpy.data.materials.new('diagnostic-smoke-material')
    material.process_single_material(types.SimpleNamespace(name='no-nodes', node_tree=None),
                                     bpy.context, [], addon_prefs=types.SimpleNamespace())
    try:
        material.process_single_material(mat, bpy.context, [], addon_prefs=types.SimpleNamespace())
    except KeyError:
        failure = log.recent_log[-1]
        assert failure['status'] == 'error'
        assert failure['material'] == mat.name
        assert 'CI-' in failure['traceback']
    else:
        raise AssertionError('expected missing CI library error')
    # Call export operator methods directly; no user preferences/UI are modified.
    class Export:
        filepath = output
        def report(self, level, message):
            print(level, message)
    assert import_world.VIEW3D_OT_CrafterExportImportLog.execute(Export(), bpy.context) == {'FINISHED'}
    assert pathlib.Path(output).stat().st_size > 0
    bpy.data.materials.remove(mat)
    print('CRAFTER_LOG_SMOKE_OK', log.log_file_path())
except Exception:
    import traceback
    traceback.print_exc()
    sys.exit(1)
