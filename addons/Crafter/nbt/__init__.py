"""
NBT模块的初始化文件，用于支持Minecraft NBT数据格式的读取和写入。
"""

try:
    # 尝试从系统安装的nbt模块导入
    from .nbt import *
except ImportError:
    # 如果系统未安装，可能在此处添加一个空壳或发出警告
    print("警告: 无法导入nbt模块的完整功能") 