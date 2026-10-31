# DEBUG 日志

Crafter 和 WorldImporter 的诊断日志默认开启。修改 C++ 源码后需要重新编译 WorldImporter；已有的 `WorldImporter.exe` 不会自动更新。

## 内容

- Crafter 注册/卸载：版本、Blender/Python、系统、架构、可执行文件与插件路径。
- 材质：来源、强制重载、材质库复用/重载原因、分类文件、处理数量；DEBUG 中逐材质名称、耗时及异常堆栈。
- WorldImporter / Java 选择器：命令、工作目录、PID、stdout/stderr、退出码、耗时、启动失败和回传失败。
- Java 版本、macOS 内存探测命令：退出码、耗时及 DEBUG 输出；Java 探测仍走原缓存。
- 原生导出：启动环境、配置路径/摘要、初始化、方块/实体/画作阶段；异常展开的阶段记为失败，不再误报完成。

Python JSONL 字段包含 `timestamp`、`level`、`stage`、`operation`、`parent_operation`、`session`、`source`、`function`、`pid`、`thread_id`、`duration_ms` 等；耗时字段仅存在于计时事件。子进程使用 `child_pid`。C++ 为带同类元数据的文本日志。

旧 C++ `cout/cerr` 输出也会转入文件；它们的源码位置是日志适配器，不是原调用行。新日志接口及阶段计时器使用真实调用位置。stdout/stderr 合并的进程会标为 `stdout+stderr`。

## 文件与导出

默认目录：

- macOS：`~/Library/Logs/Crafter/`
- Windows：`%LOCALAPPDATA%/Crafter/logs/`
- Linux：`${XDG_STATE_HOME:-~/.local/state}/crafter/logs/`

`crafter-<pid>.jsonl` / `worldimporter-<pid>.log` 按进程分开写入，单个文件 5 MiB，保留 3 份轮转文件。每条记录写入即刷新。不同历史进程文件不会自动删除，可按需清理。

Crafter 日志目录不可写时尝试系统临时目录，C++ 无法写入时保留控制台输出并报错。两者都不应因日志写入故障替代原业务错误。

UI 只保留最近 2000 条，清空 UI 不删除磁盘日志。导出按钮导出当前 Blender 进程保留的完整轮转窗口，包含先前 UI 清空前的记录；WorldImporter 文件需要另行取出。复制按钮仍复制近期可读日志。

可在启动 Blender/WorldImporter 前设置：

```sh
export CRAFTER_LOG_DIR=/path/to/logs
export CRAFTER_DEBUG=0  # 关闭详细 DEBUG，保留 INFO/WARN/ERROR
```

日志不枚举完整环境变量，但会包含本地路径、材质名、进程命令和子进程输出；对外分享前请检查敏感内容。

## 验证

```sh
python3 -m unittest discover -s tests -v
python3 -m unittest discover -s addons/Crafter/operators -p 'test_*.py' -v
python3 addons/Crafter/replay/test_game_dirs.py
blender --background --factory-startup --python tests/blender_log_smoke.py
```

WorldImporter 日志独立测试无需导入地图或链接导出器核心：

```sh
c++ -std=c++20 -pthread -IWorldImporter WorldImporter/tests/logutil_tests.cpp -o /tmp/wi-log-test
CRAFTER_LOG_DIR=/tmp/wi-log-test-logs /tmp/wi-log-test
# 或 CMake 配置后：
cmake --build /path/to/build --target logutil_tests
ctest --test-dir /path/to/build -R '^logutil$' --output-on-failure
```

## 扩展诊断

- 资源包替换：方案目录、每包扫描量、匹配/未匹配贴图、最终替换统计及解压错误。
- 物品/资产：贴图路径和尺寸、细分网格计划、材质复用原因、结果几何数量、资产复制路径。
- 回放：协议/版本、包数、区块数、备用世界、快照/动态分组写入、相机/实体/粒子构建、异步分组异常。
- 原生：批次预期/已完成任务、工作线程与任务坐标、输出文件路径/字节/耗时、配置解析和降级错误。
- 慢操作：`CRAFTER_SLOW_MS` 可设置 Python 操作阈值（默认 5000 毫秒）；超过阈值的结束事件包含 `slow=true` 并升级为 WARN。
- 被捕获后继续执行的错误会让操作结束状态变为 `completed_with_errors`，警告为 `completed_with_warnings`。C++ 保持原退出码策略，额外记录错误计数；退出码 0 不代表导出无遗漏。
- 磁盘日志故障只告警一次并停止失败的文件写入，UI 内存日志继续工作。
