# Crafter

## 适配范围

- Windows系统
- Minecraft版本大于1.12

## 使用方法

### 安装

与其他插件安装方式相同

- 高于4.2版本安装Crafter_exr.zip

- 低于4.2版本安装Crafter.zip

---

-*【由于插件中包含C++编译程序，所以报毒是正常现象。不放心可以检查[WorldImporter](https://github.com/BaiGave/WorldImporter)中的代码，编译并替换确认安全性】*

### 导入

## tint 接口约定（预设作者）

导入时 WorldImporter 会在 `importer/tint.json` 输出每个材质的 tint 元数据，插件按下面的约定按需供给：

- 命名群系输入：CI 组只要暴露 `grass`/`foliage`/`dryfoliage`/`water`/`waterFog`/`fog`/`sky` 中任意一个，插件就会按需追加 `Crafter-biomeTex` 节点并连上对应输出（与元数据无关，CI 想要就给）。
- 统一接口（可选）：
  - `tint_color`（Color）：元数据有 `kind` 时，群系类连对应群系图输出，`fixed` 类写 JSON 里的线性常量色；元数据没有 `kind`（明确不上色）写白色；没有元数据（旧导出器）则不动这个接口。
  - `tint_enable`（Float 0/1）：有元数据时写 1/0，用于让预设把开关权交给元数据；不暴露就不受元数据约束。
- `kind` 的有无就是“是否上色”：条目在但没有 `kind` 表示明确不上色，条目/自定义属性不存在表示没有元数据（旧导出器，只做命名连接）。

`tint.json` 形如：

```json
{
  "minecraft:block/grass_block_top": {"kind": "grass"},
  "minecraft:block/spruce_leaves":  {"kind": "fixed", "color": [0.11, 0.31, 0.11]},
  "minecraft:block/stone":          {}
}
```

同一张纹理出现多种 tint 结果时，导出器会复制材质并加后缀（如 `@grass`、`@c619961`），插件无需特殊处理。

## 分类依据与材质名

- 默认分类依据（`classification basis/minecraft.json`）只用 `full` 精确匹配，条目是完整材质名（如 `minecraft:block/oak_leaves`），不再用 `keyw` 子串匹配，避免误伤模组纹理。
- 插件分类时优先取材质名的完整名（去掉第一个 `@` 之后的后缀）；没有命名空间的材质回退图像名（兼容自己导入的 OBJ）。
- CTM 材质名格式为 `<原材质名>@<短标识>`：OptiFine tile 是 `@ctm/00_<hash4>`、atlas 是 `@ctm/atlas_...`、mcmeta CTM 是 `@ctm_mcmeta/<签名>`；Create 连接纹理是 `@create_ct`。`@` 之后只用于区分材质，插件分类只看 `@` 之前。
- 自定义分类依据可以继续用 `keyw` 子串（针对完整名匹配）或 `full`（精确匹配完整名）。

## 维护插件注意事项

### 设置与运行

本插件基于由[异次元学者](https://space.bilibili.com/181717176)制作的[Blender插件打包工具](https://github.com/xzhuah/BlenderAddonPackageTool)测试及打包。拉取源码后应当执行如下操作。

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

#### 白给/BaiGave

[![白给/BaiGave](https://i0.hdslb.com/bfs/face/d7d82f14e8469c3c973cd1d7b981ed275069aa55.jpg@100w_100h_1c_1s.png)](https://space.bilibili.com/3461563635731405)

- 插件的C++部分

---

#### 被盗号的高锰酸钾/MCL0

[![被盗号的高锰酸钾/MCL0](https://i2.hdslb.com/bfs/face/4cc9254634fc212567c7e2286554bf664bfedd92.jpg@100w_100h_1c_1s.png)](https://space.bilibili.com/511403657)

- 世界维度列表读取
- 坐标选择器
- 修复大量bug

---

#### 少年忠城/zhong_cheng

[![少年忠城/zhong_cheng](https://i0.hdslb.com/bfs/face/cca0a2997727b7c9625b6c84b79d75b1d20b0505.jpg@100w_100h_1c_1s.png)](https://space.bilibili.com/455309610)

- python部分的大部分程序
- 默认材质预设制作

---

### 鸣谢

#### 若有来生LS

[![若有来生LS](https://i2.hdslb.com/bfs/face/69caf616d0ec1d734456ba5c3dcfb7228df98dc3.jpg@100w_100h_1c_1s.png)](https://space.bilibili.com/328067113)

- 默认LOD1无LOD方块表整理
- 制作了很好用的材质编辑插件，正在适配本插件

---

#### 异次元学者

[![异次元学者](https://i2.hdslb.com/bfs/face/68a1603186d8655a14d7e7fc920b4c95f23929d4.jpg@100w_100h_1c_1s.png)](https://space.bilibili.com/181717176)

- 插件框架作者

---

#### 像素艺作_VoxelCraft

[![像素艺作_VoxelCraft](https://i2.hdslb.com/bfs/face/1032246c6c38fc376fc6ae29d525edb85bc7da97.jpg@100w_100h_1c_1s.png)](https://space.bilibili.com/26149666)

- 群系着色图像映射
- EEVEE着色器材质解惑
- 动态纹理按序播放思路提供
- 基于传递UV的视差的制作
- 改进时间获取方式

---

#### -blueish-

[![-blueish-](https://i2.hdslb.com/bfs/face/e7ebd5fd6c2267d6bab44a2e89b9d1671a818f13.jpg@100w_100h_1c_1s.png)](https://space.bilibili.com/3546391456516604)

- 提供了视差节点

---

-*【排名不分先后，按首字母排序】*
