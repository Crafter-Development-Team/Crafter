# Minecraft Map Selector

读取 Minecraft `.mca` 区域文件，渲染 2D 俯视图并框选导入区域。

## 构建与启动

构建环境为 JDK 9+、Maven 3+；使用 Java 8 API 编译，产物可在 Java 8+ 运行，不需要 OpenGL 或平台 natives。

```bash
mvn -q clean package
java -jar target/minecraft-map-selector-1.0.0.jar
# Crafter / Blender 集成（坐标文件协议不变）
java -jar target/minecraft-map-selector-1.0.0.jar --world-path /path/to/world --output-file coordinates.json
```

## 地图高度

- 鼠标悬停：状态栏与 tooltip 显示该列的 X、Y、Z。未加载/空列的 Y 显示 `—`。
- 地图和 `int[]` 高度缓存一起生成、发布和淘汰；悬停只读取内存。高度为所画非空气顶块的世界 Y，水/树叶/模组方块同样参与取顶。
- 按存档实际 section Y 扫描，支持负 Y 和扩展高度，不截断到 vanilla 范围。图片像素循环同步写入高度，取高不再读 MCA。
- `View → Hide Nether roof` 默认开启：在 DIM-1 / the_nether 中跳过基岩顶床及其连续天花板，显示洞内表面。取消可查看顶床及顶床上建筑。检测 y>=32 的 bedrock 作为顶床；非基岩模组天花板或基岩装饰可关闭此选项。
- 默认保留云端的邻列高度差明暗；View 菜单可显式启用透明水、洞底、资源驱动的 biome 颜色及等高线等数据叠层。
- 使用云端右侧 Import Y 三滑块（下限、上限、紫色兴趣带上沿）与 ColumnCache 实时换带；选择拟合和预设可原子扩展 Y 范围。

## 无头 CLI

```bash
# 单个 MCA：输入、JSON、PNG、线程数、region 大小、采样间隔、表面模式
java -Djava.awt.headless=true -jar target/minecraft-map-selector-1.0.0.jar /path/to/r.0.0.mca blocks.json map.png 4 32 1 AUTO
# 强制顶床或强制洞内（包括自定义维度目录）
java -Djava.awt.headless=true -jar target/minecraft-map-selector-1.0.0.jar /path/to/r.0.0.mca roof.json roof.png 4 32 1 TOP
# 世界目录：输入、线程数、采样间隔、JSON、PNG、表面模式
java -Djava.awt.headless=true -jar target/minecraft-map-selector-1.0.0.jar /path/to/world 4 1 blocks.json map.png AUTO
```

表面模式为 `AUTO`（根据路径识别下界）、`TOP`、`NETHER_INTERIOR`。GUI 全采样；CLI 采样间隔 >1 时像素和高度均使用同一近邻样本，是近似高度。

JSON 的 `blocks[z][x]` 和 `heights[z][x]` 与图片对应，`noHeight=-2147483648` 标识空列；世界目录输出包含 originX/originZ。该高度 JSON 与 Blender 的选区 coordinates.json 是不同输出。PNG 不持久化高度元数据，内部缓存随渲染会话存在。

## 源码

- `core/MapRenderer`：取顶、着色；`core/RenderedMap`：图像及高度缓存
- `core/DynamicRegionManager`：按视口加载、淘汰 region 瓦片
- `gui/`：静态/动态画布与 Swing UI
- `region/`、`nbt/`：MCA / NBT 读取
- `src/test/java/`：合成 MCA、高度/颜色一致性、下界、扩展 section、缓存生命周期与无头悬停测试

当前读取路径针对现代 palette/zlib MCA；旧 numeric Blocks、1.13–1.15 跨 long 打包、LZ4/外置 chunk 不在本次扩展范围。

### 真实材质取色 / 俯视表面（2026-10）

CLI、普通 GUI 和 Blender 的 `--world-path / --output-file` 共用以下选项，均可重复：

```
--jar-path <vanilla.jar>       原版客户端资源；多个按给定顺序覆盖
--mods-dir <mods目录>         加载目录内按文件名排序的所有 mod JAR
--mod-jar <mod.jar>           单独指定模组资源
--resource-pack <pack.zip>    资源包；后给的包覆盖前给的包
--no-auto-resources           只加载显式资源
--render-y <整数Y>            指定渲染顶层；省略时保持 AUTO
```

覆盖顺序：原版 JAR → mod JAR → resource pack ZIP。选项可以放在原来的位置参数前后；旧位置参数依然是 `MCA JSON PNG threads regionSize sampleInterval [AUTO|TOP|NETHER_INTERIOR]`。CLI 现在会初始化真实资源，每张 PNG 旁附 `.png.colors.json`（实际查询 id、来源和次数）。模型跨包继承，纹理支持任意 `assets/<namespace>/textures/**.png` 路径和 alpha 权重，不再生成随机色。完全透明层跳过；未知材料以中性灰显示并记录。

```
java -Djava.awt.headless=true -jar minecraft-map-selector-1.0.0.jar \
  '/path/to/world/region/r.-1.-1.mca' blocks.json map.png 4 32 1 AUTO \
  --jar-path '/path/to/1.20.1.jar' --mods-dir '/path/to/mods' \
  --resource-pack '/path/to/yuushya_modpack_inside_wi.zip'
```

自动发现会读取 level.dat / MCA 的 DataVersion 和 palette namespace，向路径上级及实例兄弟目录寻找 `versions/`、`mods/`；客户端版本按 JAR 的 version.json/world_version 匹配，模组和资源包按实际 assets namespace 匹配，不硬编码地图名、模组名或客户端版本。资源包优先 options.txt 的启用顺序；缺失时按文件名合并同实例的匹配资源包。跨工作区或需要精确资源版本时请显式指定资源。GUI 的 File 菜单也可选 MC Client、Mods Folder、Resource Packs，选后重建颜色和地图缓存。资源包文件多选的返回顺序就是覆盖顺序；需要精确排序可用启动参数。每次重新初始化资源，历史无资源指纹的 colors JSON/properties 不会静默加载。

俯视用模型的水平投影覆盖率与下层合成：薄但完整的 slab/carpet/关闭活板门不会因体积小而穿透；侧半砖、楼梯、打开活板门、压力板和透明纹理按实际或近似覆盖显示。雪层 1..8 层对应 70%..100% 白色覆盖。Yuushya showblock 会读取 NBT 的可见关联 BlockState；LittleTiles/FramedBlocks 可识别材料引用，复杂动态/压缩几何仍近似或降级。每格只输出一个地图像素，任意模型越界、材质 UV、游戏光照/biome tint 尚非游戏级渲染。

GUI 右侧保留云端 **Import Y** 竖向三滑块：绿色 = 最低 Y、橙色 = 最高 Y、紫色 = 兴趣带上沿。拖动时优先用 ColumnCache 实时重合成，缓存不可用时才回落到临时裁剪或防抖重渲。同一对值既决定回传 Blender 的 minY/maxY，也决定预览的 Y 带：带内从顶向下取可见表面，带里没有方块的列透明，所以把上 thumb 压到地面以下就能直接看地下结构。滑条坐标轴按传入的高度范围自动留边（不需要数字输入框，值画在 thumb 旁），不再有旧的固定 -64..365 截断。上界裁剪不会关闭下界的自动去顶床，AUTO 与 View→Hide Nether roof 仍决定下界行为；鼠标高度仍来自当前图片缓存。底部滚动日志显示世界解析、各资源包提取、region 扫描和渲染阶段；启动加载时进度条先用不定百分比，能计算区块百分比后切换实际进度。

拖 Y 带时会走**逐列层缓存**：第一次拖动会先在后台把当前视图的每个 region 整列扫一遍（含每列的方块层栈，相邻同方块同色压成一段，约 5 段/列、31MB/region，缓存个数按堆内存自适应），之后两个方向换带都只做内存重合成 —— 实测 64 区块重合成+出图约 13ms（整 region 约 200ms），建立约 137ms（整 region 约 2.2s）。缓存没就绪或对不上的情况自动回落到原来的防抖全量渲染；下界的自动去顶床（AUTO 下的下界维度）依赖扫描窗口本身，那类 region 不缓存，仍走全量渲染。重合成用的是同一套出图代码，像素与全量渲染逐点一致（见 ColumnCacheTest）。


### 选择功能 P0–P2（合入云端 UI）

- 右键拖动替换一个包含端点的 AABB；Alt/Ctrl 右拖也只替换。Shift 保持正方形，边/角可调整；Selection 菜单支持坐标编辑、16/512 对齐、撤销和选择视口。
- Chunk paint / Circle / Add box 不在 GUI 显示或派发。旧预设形状加载为其联合 AABB；形状模型、CLI 验证和可选多框元数据仍保留。Blender 当前导入根对象的单个 AABB。
- Enter 确认并显示导入范围与容量；Esc 清除、Ctrl+Z 撤销、Ctrl+A 选择视口、Ctrl+Shift+C 复制坐标。Alt+方向键扩展，Ctrl+Alt+方向键收缩，Shift 步长为 16。
- Space 从已加载列拟合 Import Y（跳过水/玻璃）；Shift+Space 使用不透明策略；中键设下限。拟合、悬停和覆盖统计只读内存，换高度带或拼图后仍保留方块状态、生物群系、空列与缺块数据。
- Navigate 菜单提供出生点/玩家/选区跳转、Go to、缩放和航点；File 菜单支持预设、航点、视图 PNG、最近世界及文件拖放。View 菜单提供资源覆盖、可选叠层、小地图、HUD、矿物扫描和缓存整理。
- 云端棋盘背景、紫色兴趣区域、竖向高度轴和工具栏外观保留；等高线、夜景、缺块着色、比例尺、小地图与 HUD 默认关闭。

```bash
# 无界面测试（含 P0–P2、云端 ColumnCache 和合并集成回归）
mvn -Djava.awt.headless=true clean package
# 校验导入坐标协议；失败退出码为 1
java -Djava.awt.headless=true -jar target/minecraft-map-selector-1.0.0.jar \
  --validate-selection coordinates.json --max-columns 1048576
# 启动时灌入选区；可选元数据不改变根 AABB 协议
java -jar target/minecraft-map-selector-1.0.0.jar --world-path /path/to/world \
  --min-x -16 --max-x 15 --min-z -16 --max-z 15 --multi-box --include-waypoints
```
