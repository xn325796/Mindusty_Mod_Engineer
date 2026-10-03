# Mindustry ModMaker

节点画布式的 Mindustry 模组构建器（Java + JavaFX 桌面应用）。

像白板一样把内容卡片摆出来、用连线表达引用与科技树；**能读入已有模组包继续编辑**，
**能输出可被游戏加载的完整模组**（未改动过的文件字节级原样保留）。

架构说明见 [architecture.md](architecture.md)
**接手开发/交付请先读 [receiving_EN.md](receiving.md)**——自包含的蒸馏文档：环境、架构、格式契约、验证协议、陷阱备忘录。

## 运行

**最简单的方式：双击 `run.bat`**（自动定位 JDK，首次运行自动生成 schema，约 10 秒出窗口）。

也可以带参数启动，直接看导入真实模组的效果：

```bat
启动ModMaker.bat --import=..\格雷工业 --projectDir=build\my-import
```

命令行方式（等价）：

```bash
./gradlew run      # 启动应用（首次会联网下载 JavaFX）
./gradlew test     # 57 个测试：格式规则、容错解析、真机模组往返、稳定性校验
```

首次使用前生成 schema（扫 Mindustry 源码；启动脚本会自动做）：

```bash
./gradlew schemaBootstrap
```

- **新建项目** → 选一个目录，得到 `<目录>/<模组名>/`：里面就是完整的模组文件夹骨架。
- **导入模组包** → 选 `.zip` 或已有的模组文件夹，应用会把它整个解包成可编辑项目（内容、精灵、脚本、
  地图、bundle 全部保留），并在画布上按类型分列摆好、按引用关系连线。
- **编辑** → 点卡片，右侧是 schema 驱动的字段编辑器（数字/颜色/枚举/内容引用/堆叠/原始 JSON），
  底部字符串表按「键 × 语言」编辑名称与描述。
- **构建** → 输出到文件夹或打成 `.zip`（单层包装目录，解压即 `mods/<名>/`）。
- **实机测试** → 把模组装进一个临时数据目录并启动 Mindustry（`MINDUSTRY_DATA_DIR`，
  不碰真实存档），退出后读游戏日志报告内容错误。

## 工程结构

```
src/main/java/dev/modmaker/
  core/json/      容错 HJSON 读取、规范 JSON 写出、JSON 路径
  core/fmt/       ContentType（9 类可文件化）、命名规则、精灵区域、bundle 编解码
  core/model/     ModProject / ContentRecord / Board / CanvasNode / Edge / StringTable / AssetRef
  core/schema/    SchemaRegistry（精编 + 源码扫描 + 原版内容名录）
  core/io/        读取模组包 → 建项目 → 存盘 → 构建/打包，自动布局，JSON Canvas
  core/validate/  校验器（重名/非法名/缺 type/悬空引用/缺精灵/孤儿节点）
  tools/          SchemaBootstrap（扫 Mindustry 源码生成 schema 与主体名录）
  ui/             AppShell、NodeCanvasPane、ContentCard、RecordInspector、StringsPane、IssuesPane、GameLauncher
src/test/java/    格式规则、容错解析、导入、往返、创作、稳定性、游戏启动、UI 冒烟
schemas/          schema.json（精编）+ fields.json / vanilla-content.json（生成物）
```
