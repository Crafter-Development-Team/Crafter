# Minecraft Map Selector

读取 Minecraft `.mca` 区域文件，渲染为 2D/3D 可视化地图。

## 快速开始

```bash
mvn clean package -DskipTests
java -jar target/minecraft-map-selector-1.0.0.jar
```

## 核心功能

- **2D 俯视图渲染** — 读取 MCA 文件，方块颜色映射，多线程渲染
- **3D 透视图** — OpenGL 渲染，可旋转/缩放
- **GUI 模式** — Java Swing 界面，拖拽选择坐标范围
- **Blender 集成** — `--world-path` / `--output-file` 参数供 Crafter 插件调用
- **命令行模式** — 批量和自动化操作

## 使用示例

```bash
# GUI 模式
java -jar target/minecraft-map-selector-1.0.0.jar

# CLI：处理单个区域文件
java -jar target/minecraft-map-selector-1.0.0.jar /path/to/r.0.0.mca
```

## 构建要求

- Java 8+
- Maven 3+

## 目录结构

```
src/main/java/com/minecraft/selector/
├── MinecraftMapSelector.java      # 入口
├── core/                           # 渲染引擎
├── gui/                            # Swing 界面
├── render3d/                       # 3D 渲染管线
├── region/                         # MCA 文件解析
├── nbt/                            # NBT 读取
└── utils/                          # 工具类
```
