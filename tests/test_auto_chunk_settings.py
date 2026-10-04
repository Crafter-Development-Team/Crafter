import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

PATH = Path(__file__).resolve().parents[1] / 'addons/Crafter/operators/AutoChunkSettings.py'
spec = importlib.util.spec_from_file_location('auto_chunk_settings', PATH)
auto = importlib.util.module_from_spec(spec)
spec.loader.exec_module(auto)


class AutoChunkSettingsTests(unittest.TestCase):
    def test_memory_boundaries(self):
        for memory in (0, 128*auto._MIB, 512*auto._MIB, 2*auto._GIB, 64*auto._GIB):
            with self.subTest(memory=memory):
                result = auto.calculate_auto_chunk_settings(64*auto._GIB, memory)
                self.assertGreaterEqual(result['partitionSize'], 1)
                self.assertLessEqual(result['partitionSize'], 4)
                self.assertLessEqual(result['partitionSize']**2, result['chunksPerBatch'])
                self.assertLessEqual(result['chunksPerBatch'], 64)
                self.assertEqual(result['maxTasksPerBatch'], result['chunksPerBatch']*24)
                self.assertGreaterEqual(result['modelThreads'], 1)
                self.assertLessEqual(result['modelThreads'], 32)

    def test_multicore_batch_contains_several_groups(self):
        with patch.object(auto.os, 'cpu_count', return_value=16):
            result = auto.calculate_auto_chunk_settings(16*auto._GIB, 8*auto._GIB)
        self.assertEqual(result['modelThreads'], 16)
        self.assertEqual(result['partitionSize'], 2)
        self.assertEqual(result['chunksPerBatch'], 64)
        self.assertLessEqual(result['workingBudgetBytes'], 8*auto._GIB*0.70)

    def test_missing_cpu_detection(self):
        with patch.object(auto.os, 'cpu_count', return_value=None):
            result = auto.calculate_auto_chunk_settings(8*auto._GIB, 4*auto._GIB)
        self.assertEqual(result['modelThreads'], 1)

    def test_low_memory_limits_workers(self):
        with patch.object(auto.os, 'cpu_count', return_value=32):
            result = auto.calculate_auto_chunk_settings(8*auto._GIB, 512*auto._MIB)
        self.assertEqual(result['modelThreads'], 2)


if __name__ == '__main__':
    unittest.main()
