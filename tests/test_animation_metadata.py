import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('animation_metadata', Path(__file__).resolve().parents[1] / 'addons/Crafter/animation_metadata.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
parse = module.parse_animation_metadata


class AnimationMetadataTest(unittest.TestCase):
    def test_empty_frames_use_all_frames(self):
        self.assertEqual(parse({'animation': {'frames': []}}, 16, 64),
                         [4, 1, 4, [[0, 1], [1, 1], [2, 1], [3, 1]], False])

    def test_explicit_dimensions(self):
        self.assertEqual(parse({'animation': {'width': 16, 'height': 16}}, 16, 64)[0], 4)
        self.assertEqual(parse({'animation': {'height': 32}}, 16, 64)[0], 2)
        self.assertEqual(parse({'animation': {'width': 16}}, 16, 64)[0], 1)

    def test_optional_time_and_custom_order(self):
        result = parse({'animation': {'frametime': 2, 'frames': [{'index': 2}, 0, {'index': 1, 'time': 6}], 'interpolate': True}}, 16, 64)
        self.assertEqual(result, [4, 2, 5, [[2, 1], [0, 1], [1, 3]], True])

    def test_static_metadata_is_not_animation(self):
        self.assertIsNone(parse({'texture': {'blur': False}}, 16, 64))
        self.assertIsNone(parse({}, 16, 64))

    def test_invalid_metadata_rejected(self):
        cases = [None, [], {'animation': None}, {'animation': {'frametime': 0}},
                 {'animation': {'frametime': -1}}, {'animation': {'frametime': True}},
                 {'animation': {'frametime': 1.5}}, {'animation': {'frames': None}},
                 {'animation': {'frames': [-1]}}, {'animation': {'frames': [4]}},
                 {'animation': {'frames': [True]}}, {'animation': {'frames': [{}]}},
                 {'animation': {'frames': [{'index': 0, 'time': 0}]}},
                 {'animation': {'width': 0}}, {'animation': {'height': 3}},
                 {'animation': {'interpolate': 'false'}}]
        for case in cases:
            with self.subTest(case=case), self.assertRaises(ValueError):
                parse(case, 16, 64)

    def test_multi_column_is_explicitly_unsupported(self):
        with self.assertRaisesRegex(ValueError, 'multi-column'):
            parse({'animation': {'width': 16, 'height': 16}}, 32, 32)


if __name__ == '__main__':
    unittest.main()
