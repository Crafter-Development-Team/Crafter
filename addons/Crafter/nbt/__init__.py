#init of nbt

try:
    #尝试从系统安装的nbt模块导入
    from .nbt import *
except ImportError:
    #如果系统未安装,提示一下
    print("警告: 无法导入nbt模块的完整功能,使用内置缩减版本") 
