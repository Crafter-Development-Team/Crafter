import ctypes
import math
import os
import platform
import subprocess

_MIB = 1024 * 1024
_GIB = 1024 * _MIB


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


def calculate_auto_chunk_settings(min_x, max_x, min_y, max_y, min_z, max_z,
                                  total_memory=None, available_memory=None):
    """根据区域和内存计算 partitionSize/maxTasksPerBatch。

    partitionSize 的单位是 chunk 边长；maxTasksPerBatch 的单位是 chunk section。
    返回值只使用旧版 C++ 已支持的配置字段，因此保持向后兼容。
    """
    if total_memory is None or available_memory is None:
        total_memory, available_memory = get_memory_info()

    total_memory = max(int(total_memory), 1 * _GIB)
    available_memory = max(256 * _MIB, min(int(available_memory), total_memory))
    min_x, max_x = sorted((int(min_x), int(max_x)))
    min_y, max_y = sorted((int(min_y), int(max_y)))
    min_z, max_z = sorted((int(min_z), int(max_z)))

    chunk_x_start, chunk_x_end = min_x // 16, max_x // 16
    chunk_z_start, chunk_z_end = int(min_z) // 16, int(max_z) // 16
    section_y_start, section_y_end = int(min_y) // 16, int(max_y) // 16
    chunks_x = max(1, chunk_x_end - chunk_x_start + 1)
    chunks_z = max(1, chunk_z_end - chunk_z_start + 1)
    sections_y = max(1, section_y_end - section_y_start + 1)
    total_tasks = chunks_x * chunks_z * sections_y

    # 给 Blender、系统和纹理缓存留余量，导入器最多使用总内存约 30%、
    # 当前可用内存约 45% 中更小的一项。
    reserve = min(1 * _GIB, int(total_memory * 0.08))
    safe_available = max(256 * _MIB, available_memory - reserve)
    working_budget = min(int(total_memory * 0.30), int(safe_available * 0.45))
    working_budget = max(256 * _MIB, working_budget)

    # 一个复杂 section 生成模型时按约 3MiB 估算。组预算控制单个 OBJ
    # 去重/GreedyMesh 的峰值，限制在 128MiB~1GiB。
    group_budget = max(128 * _MIB, min(1 * _GIB, int(working_budget * 0.18)))
    estimated_model_bytes_per_task = 3 * _MIB
    target_group_tasks = max(1, group_budget // estimated_model_bytes_per_task)
    partition_size = int(math.sqrt(max(1, target_group_tasks // sections_y)))
    partition_size = max(1, min(8, chunks_x, chunks_z, partition_size))
    group_tasks = partition_size * partition_size * sections_y

    # 批次主要保存 NBT、方块调色板和邻居缓存，按 256KiB/section 估算。
    batch_budget = max(128 * _MIB, int(working_budget * 0.45))
    estimated_loaded_bytes_per_task = 256 * 1024
    target_batch_tasks = max(64, batch_budget // estimated_loaded_bytes_per_task)
    target_batch_tasks = min(8192, total_tasks, target_batch_tasks)

    # 批次不能小于一个完整分组，并尽量取分组任务数的整数倍。
    if target_batch_tasks >= group_tasks:
        max_tasks_per_batch = max(group_tasks, (target_batch_tasks // group_tasks) * group_tasks)
    else:
        max_tasks_per_batch = group_tasks
    max_tasks_per_batch = max(1, min(max_tasks_per_batch, max(total_tasks, group_tasks)))

    # 当前模型后处理内部已自行并行；同时并行多个 ModelData 仍有第三方/历史
    # 缓存竞争风险。暂固定外层为 1 线程，保留配置字段供后续安全流水线使用。
    model_threads = 1

    return {
        "partitionSize": int(partition_size),
        "maxTasksPerBatch": int(max_tasks_per_batch),
        "totalMemoryBytes": int(total_memory),
        "availableMemoryBytes": int(available_memory),
        "workingBudgetBytes": int(working_budget),
        "chunksX": int(chunks_x),
        "chunksZ": int(chunks_z),
        "sectionsY": int(sections_y),
        "totalTasks": int(total_tasks),
        "groupTasks": int(group_tasks),
        "modelThreads": int(model_threads),
    }
