import ctypes
import math
import os
import platform
import subprocess

_MIB = 1024 * 1024
_GIB = 1024 * _MIB

# 一个区块（16×16×384 全高度，含网格与临时数据）的全链路峰值内存估算。
# 注：GreedyMesh/去重阶段会额外产生接近 1 倍的结构开销，
# 因此按更高峰值估算，避免自动分块在低内存机器上过大导致卡顿。
_PER_CHUNK_BYTES = 24 * _MIB

# 1.18+ 主世界全高度的 section 数（y=-64~319，384 格 ÷ 16）。
# 批次以区块计，写入 C++ 时固定按最高高度换算成 section 任务数。
_MAX_SECTIONS_Y = 24


def get_memory_info():
    """返回 (总物理内存, 当前可用物理内存)，失败时使用保守回退值。"""
    try:
        if platform.system() == "Windows":
            class MEMORYSTATUSEX(ctypes.Structure):
                _fields_ = [
                    ("dwLength", ctypes.c_ulong),
                    ("dwMemoryLoad", ctypes.c_ulong),
                    ("ullTotalPhys", ctypes.c_ulonglong),
                    ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong),
                    ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong),
                    ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
                ]

            status = MEMORYSTATUSEX()
            status.dwLength = ctypes.sizeof(status)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
                return int(status.ullTotalPhys), int(status.ullAvailPhys)

        if platform.system() == "Darwin":
            total = int(subprocess.check_output(["sysctl", "-n", "hw.memsize"], text=True).strip())
            page_size = int(subprocess.check_output(["sysctl", "-n", "hw.pagesize"], text=True).strip())
            vm_stat = subprocess.check_output(["vm_stat"], text=True)
            pages = 0
            for line in vm_stat.splitlines():
                if line.startswith(("Pages free", "Pages inactive", "Pages speculative")):
                    pages += int(line.split(":", 1)[1].strip().rstrip("."))
            return total, pages * page_size

        page_size = os.sysconf("SC_PAGE_SIZE")
        total = page_size * os.sysconf("SC_PHYS_PAGES")
        available = page_size * os.sysconf("SC_AVPHYS_PAGES")
        return int(total), int(available)
    except Exception:
        # 不让内存检测失败阻止导入；按 8GB 机器、4GB 可用保守计算。
        return 8 * _GIB, 4 * _GIB


def calculate_auto_chunk_settings(total_memory=None, available_memory=None):
    """根据可用内存计算 partitionSize/maxTasksPerBatch。

    partitionSize 的单位是 chunk 边长；maxTasksPerBatch 的单位是 chunk section。
    工作预算为可用内存的 70%，保留 Blender、缓存及最终网格的余量。
    输出分组与加载批次解耦：分区最多 4×4，批次最多 64 个区块，
    避免整个批次只有一个超大组而无法利用多个模型工作线程。
    换算为 section 任务数（固定按最高高度 _MAX_SECTIONS_Y）后写入 maxTasksPerBatch。
    返回值只使用旧版 C++ 已支持的配置字段，因此保持向后兼容。
    """
    if total_memory is None or available_memory is None:
        total_memory, available_memory = get_memory_info()

    total_memory = max(int(total_memory), 1 * _GIB)

    available_memory = max(0, int(available_memory))
    working_budget = int(available_memory * 0.70)
    chunks_per_batch = min(64, max(1, working_budget // _PER_CHUNK_BYTES))
    cpu_threads = min(32, max(1, os.cpu_count() or 1))
    # Fit several independently schedulable groups inside the loading budget.
    target_groups = min(cpu_threads, chunks_per_batch)
    partition_size = min(4, max(1, int(math.sqrt(chunks_per_batch / target_groups))))
    max_tasks_per_batch = chunks_per_batch * _MAX_SECTIONS_Y
    # Keep a per-worker memory allowance instead of a fixed CPU limit.
    memory_threads = max(1, working_budget // (128 * _MIB))
    model_threads = min(cpu_threads, memory_threads)

    return {
        "partitionSize": int(partition_size),
        "maxTasksPerBatch": int(max_tasks_per_batch),
        "totalMemoryBytes": int(total_memory),
        "availableMemoryBytes": int(available_memory),
        "workingBudgetBytes": int(working_budget),
        "chunksPerBatch": int(chunks_per_batch),
        "modelThreads": int(model_threads),
    }
