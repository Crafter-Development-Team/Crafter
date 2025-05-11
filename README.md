# Crafter

## 适配范围

- Windows系统
- Minecraft版本大于1.12

## 使用方法

### 安装

与其他插件安装方式相同

- 高于4.2版本安装Crafter_exr.zip

- 低于4.2版本安装Crafter.zip

-*【由于插件中包含C++编译程序，所以报毒是正常现象。不放心可以检查[WorldImporter](https://github.com/BaiGave/WorldImporter)中的代码，编译并替换确认安全性】*

### 导入

## 维护插件注意事项

### 设置与运行

本插件基于由[ZHU Xinyu](https://github.com/xzhuah)制作的[Blender插件打包工具](https://github.com/xzhuah/BlenderAddonPackageTool)测试及打包。拉取源码后应当执行如下操作。

#### 1. 设置**main.py**中的路径为您的Blender路径

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

#### 2. 运行**text.py**

#### 3. 修改代码并测试后提交至Crafter库

-*【有关框架的更多功能请访问[插件打包工具文档](https://github.com/xzhuah/BlenderAddonPackageTool?tab=readme-ov-file#blender-插件开发框架及打包工具)】*

## 开发组和鸣谢

### 开发组
<a href="https://space.bilibili.com/3461563635731405" target="_blank" style="color: inherit; text-decoration: none;">
    <div style="display: flow-root;">
    <img 
        src="https://i0.hdslb.com/bfs/face/d7d82f14e8469c3c973cd1d7b981ed275069aa55.jpg@128w_128h_1c_1s.webp" 
        style="float: left; width: 60px; border-radius: 50%; margin-right: 15px;"
    >
    <div style="padding-top: 15px;">
        <strong style="font-size: 1.2em;">BaiGave</strong>
        <br>
    </div>
    </div>
</a>

<a href="https://space.bilibili.com/455309610" target="_blank" style="color: inherit; text-decoration: none;">
    <div style="display: flow-root;">
    <img 
        src="https://i0.hdslb.com/bfs/face/cca0a2997727b7c9625b6c84b79d75b1d20b0505.jpg@128w_128h_1c_1s.webp" 
        style="float: left; width: 60px; border-radius: 50%; margin-right: 15px;"
    >
    <div style="padding-top: 15px;">
        <strong style="font-size: 1.2em;">少年忠城</strong>
        <br>
    </div>
    </div>
</a>