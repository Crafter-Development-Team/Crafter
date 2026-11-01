"""blender --background --factory-startup --python-exit-code 1 --python tests/blender_animation_regression.py"""
import ast
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import bpy

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('animation_metadata', ROOT / 'addons/Crafter/animation_metadata.py')
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)
tree = ast.parse((ROOT / 'addons/Crafter/operators/Defs.py').read_text())
names = {'add_node_moving_texture', 'fuq_bl_dot_number', '_crafter_time_material_needs_geometry', '_crafter_time_object_uses_displacement'}
warnings = []
ns = dict(bpy=bpy, os=os, json=json, parse_animation_metadata=metadata.parse_animation_metadata,
          warn_log=lambda *args, **kwargs: warnings.append((args, kwargs)), debug_log=lambda *args, **kwargs: None)
exec(compile(ast.Module(body=[node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in names], type_ignores=[]), 'Defs.py', 'exec'), ns)
with bpy.data.libraries.load(str(ROOT / 'addons/Crafter/defaults/append.blend'), link=False) as (source, target):
    target.node_groups = [name for name in source.node_groups if name.startswith('Crafter-动态纹理') or name == 'Crafter-UV缩放']

with tempfile.TemporaryDirectory() as directory:
    def case(label, meta, rename=False):
        image = bpy.data.images.new(label + '.png', width=16, height=64)
        image.filepath = os.path.join(directory, label + '.png')
        Path(image.filepath + '.mcmeta').write_text(json.dumps(meta))
        if rename:
            image.name = 'renamed-datablock'
        material = bpy.data.materials.new(label)
        material.use_nodes = True
        node = material.node_tree.nodes.new('ShaderNodeTexImage')
        node.image = image
        before = len(material.node_tree.nodes)
        result = ns['add_node_moving_texture'](node, material.node_tree.nodes, material.node_tree.links, [])
        return result, len(material.node_tree.nodes) - before, material, node

    for label, meta in [('empty', {'animation': {'frames': []}}),
                        ('dimensions', {'animation': {'width': 16, 'height': 16}}),
                        ('renamed', {'animation': {}})]:
        result, added, material, node = case(label, meta, rename=label == 'renamed')
        assert result[1][0] == 4 and result[1][2] == 4, (label, result[1])
        assert added == 4 and node.inputs['Vector'].is_linked
        assert result[0].inputs['row'].default_value == 4
    result, _, _, _ = case('optional-time', {'animation': {'frametime': 2, 'frames': [{'index': 0}, {'index': 1, 'time': 4}]}})
    assert result[1][3] == [[0, 1], [1, 2]]
    for label, meta in [('zero', {'animation': {'frametime': 0}}),
                        ('static', {'texture': {'blur': False}}),
                        ('out-of-range', {'animation': {'frames': [8]}})]:
        result, added, _, node = case(label, meta)
        assert result is None and added == 0 and not node.inputs['Vector'].is_linked, label
    assert len(warnings) == 2, warnings
    # A static normal texture accompanying an animated base retains UV scaling.
    result, _, mat, _ = case('base', {'animation': {}})
    image = bpy.data.images.new('static-normal.png', width=16, height=16)
    node = mat.node_tree.nodes.new('ShaderNodeTexImage'); node.image = image
    scaled = ns['add_node_moving_texture'](node, mat.node_tree.nodes, mat.node_tree.links, [result])
    assert scaled[1] is None and scaled[0].inputs['row_tex / row'].default_value == 4

material = bpy.data.materials.new('displacement')
material.use_nodes = True
material.displacement_method = 'BOTH'
check = ns['_crafter_time_material_needs_geometry']
assert check(material) is False
output = next(n for n in material.node_tree.nodes if n.type == 'OUTPUT_MATERIAL')
displacement = material.node_tree.nodes.new('ShaderNodeDisplacement')
link = material.node_tree.links.new(displacement.outputs['Displacement'], output.inputs['Displacement'])
assert check(material) is True
material.displacement_method = 'BUMP'
assert check(material) is False
material.displacement_method = 'BOTH'
assert check(material) is True
material.node_tree.links.remove(link)
assert check(material) is False
print('ANIMATION_REGRESSION_OK: metadata, real nodes, renamed images, static PBR, displacement edits')
