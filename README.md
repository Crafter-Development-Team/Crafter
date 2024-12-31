# 维护插件注意事项

## 设置与运行

本插件基于由[ZHU Xinyu](https://github.com/xzhuah)制作的[Blender插件打包工具](https://github.com/xzhuah/BlenderAddonPackageTool)测试及打包。拉取源码后应当执行如下操作。

### 1. 设置**main.py**中的路径为您的Blender路径

```python
# The path of the blender executable. Blender2.93 is the minimum version required
# Blender可执行文件的路径，Blender2.93是所需的最低版本
BLENDER_EXE_PATH = "C:/Program Files/Blender Foundation/Blender 4.2/blender.exe"

# Linux example Linux示例
# BLENDER_EXE_PATH = "/usr/local/blender/blender-3.6.0-linux-x64/blender"

# MacOS examplenotice "/Contents/MacOS/Blender" will be appended automatically if you didn't write it explicitly
# MacOS示例 框架会自动附加"/Contents/MacOS/Blender" 所以您不必写出
# BLENDER_EXE_PATH = "/Applications/Blender/blender-3.6.0-macOS/Blender.app"
```

### 2. 运行**text.py**

### 3. 修改代码并测试后提交至Crafter库

*【有关框架的更多功能请访问[插件打包工具文档](https://github.com/xzhuah/BlenderAddonPackageTool?tab=readme-ov-file#blender-插件开发框架及打包工具)】*

## 属性位置

为了使用户保留上次的使用记录，UI中应当保留的属性都定义在**AddonPreferences.py**中。  
*【仅在用户开启偏好设置自动保存时有效】*
