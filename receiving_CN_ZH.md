# Mindustry ModMaker — 交付文档（HANDOFF）

> 本文是项目的**自包含蒸馏**：任何没有历史上下文的开发者或大模型，只读这一份即可接手开发、验证与交付。
> 更详细的推导过程见 `架构设计.md`（分层决策、格式契约逐条源码依据、各轮迭代记录），本文不重复推导，只保留可执行的事实。
> 最后更新：2026-10-02（第四轮迭代后，70 测试全绿）。

---

## 1. 项目是什么

节点画布式的 Mindustry 模组构建器（Java + JavaFX 桌面应用，**非 Electron**）：

- **读入**已有模组包（.zip / 文件夹）→ 解包为可编辑项目，画布上按类型分列卡片、按引用关系连线；
- **编辑**：schema 驱动的字段检查器（全量中文）、字符串表、校验面板、撤销/重做、类自适应下拉；
- **输出**：完整可加载的模组（文件夹或 .zip），**未改动过的文件字节级原样保留**。

项目位置：`C:\Users\XN325\Desktop\mindustry\Mindustry-modmaker`（同时是一个 Obsidian vault 的子目录，vault 根为 `C:\Users\XN325\Desktop\mindustry`）。

## 2. 环境与命令（本机硬编码事实）

| 事实 | 值 |
|---|---|
| JDK | `C:\Program Files\Java\jdk-21.0.12.1`（已写死在 `gradle.properties` 的 `org.gradle.java.home`） |
| 构建 | Gradle 9.3.1 wrapper（已缓存可离线）；JavaFX 21.0.4 win + gson 2.11 来自 Maven Central（首次需联网） |
| 运行 | `JAVA_HOME="C:/Program Files/Java/jdk-21.0.12.1" ./gradlew run` |
| 测试 | `./gradlew test`（**70 个，交付前必须全绿**） |
| schema 再生成 | `./gradlew schemaBootstrap`（扫 `../Mindustry-160.5/core/src/mindustry` → `schemas/fields.json` + `vanilla-content.json`） |
| 启动脚本 | `启动ModMaker.bat`（**内容必须纯 ASCII + CRLF**——cmd 在中文 Windows 用 GBK 解析 .bat，UTF-8 中文注释会吞换行符） |
| 测试夹具 | **已失效（2026-10-02 晚被移走）**：原为 `../格雷工业`（真实模组 955 文件）。依赖它的约 22 个金标测试会**干净跳过**（看测试报告的 skipped 数，别当全绿）；把模组文件夹放回 workspace 任意常见位置（如 `../格雷工业`）即自动恢复，测试用 `TestFixtures.realModRoot()` 多候选探测 |
| 实机游戏 | `../Mindustry/Mindustry.jar`（160.5）；支持 `MINDUSTRY_DATA_DIR` 隔离数据目录（`ClientLauncher.java:42`） |

命令行钩子（自动化验证用）：`--open=<项目目录>`、`--import=<模组包>`、`--projectDir=<落盘位置>`、`--snapshot=<png>`（截图后自动退出）。

## 3. 架构（两层，格式内核零 JavaFX 依赖）

```
core/（纯 Java，可离线单测）
  json/    HjsonReader(容错读:注释/缺逗号/裸键/单引号/BOM,深度上限256) Json(规范写) JsonPath
  fmt/     ContentType(9类可文件化+3内联,历史别名status/weathers) NameRules(名字/前缀/精灵区域)
           PropertiesFile(bundle编解码,UTF-8原文) BundleCodec(键约定+locale) TechTreeRules ReferenceOps
  model/   ModProject(唯一真相) ContentRecord(raw+dirty+inline*) Board/CanvasNode/CanvasEdge(DETERMINISTIC_ORDER)
           StringTable(bundleRaw原文+dirty) AssetRef
  schema/  SchemaRegistry(三层合并:精编schema.json > 扫描fields.json; 双父类!见§5) FieldDef/FieldType
  io/      ModPackageReader(读模组包:zip/文件夹/单层包装/噪声过滤/safeResolve防穿越)
           ModImporter(补丁/内联子弹识别,自动布局+连线,确定性排序) ProjectIo(存盘/重开,原子写,资产保护剪枝)
           ModExporter(构建=复制,zip单层包装+固定时间戳,含空目录) JsonCanvas(Obsidian兼容) AutoLayout
  validate/Validator(错误=游戏会挂,警告=可疑; 消息目前英文→待办)
ui/（JavaFX）
  ProjectController  唯一致变入口（开/导入/增删改/连线/撤销栈/构建），视图只订阅通知
  主区选项卡         TabPane(节点画布/物品与液体/星球与区块) Workspace.of(type) 路由"添加"动作
  NodeCanvasPane     画布:world Group(pan+zoom transform) + clip + pressAt/dragTo/releaseAt 可测交互核心
  ItemsPane          物品/液体管理表格(类型/名称/文件/类/颜色色块/描述)
  CosmosPane         行星→区块编排树(区块按 planet 字段分组,原版行星只读分组)
  PlanetPreview      星球预览工作台(三选项卡):①区块网格(测地网格+占用高亮+索引,容量=10*3^size+2)
                     ②星球外观(MeshBuilder.buildHex 逐参数移植:SimplexNoise 与 arc 反射比对逐位一致、
                       噪声着色/高度抬升、cloudMesh 云层、大气边缘,拖拽旋转) ③星系位置(轨道半径按
                       Planet 构造公式推算的示意图:恒星+原版行星上下文+本行星+卫星)
  PlanetOrbits       orbitRadius = parent.totalRadius + spacing + totalRadius 公式实现(原版:太阳 r=4/spacing=2)
  SimplexNoise       arc.util.noise.Simplex.noise3d 逐行移植(seed hash/skew 完全一致,反射比对测试锁定)
  PlanetMeshes       buildHex 族移植:NoiseMesh/SunMesh/HexSkyMesh 的 getHeight/getColor/skip 语义
  PlanetTemplates    新建行星自动套 serpulo 模板(/templates/planet-serpulo.json):
                     网格=NoiseMesh(游戏 JSON 解析器不支持 serpulo 自带的 HexMesh!)、大气、云层、
                     战役开关、startSector=170 等;每次应用都是深拷贝;sectorSize=3
  MeshTypes/MeshEditor mesh/cloudMesh 结构化子表:仅提供 parseMesh 可解析的 5 种类型,
                     每种类型固定字段集(逐参数对照源码),只写用户编辑过的键,未触碰的键走解析器默认值
  ContentCard        卡片:左右端口(锚点是常量,不依赖layout)  RecordInspector  StringsPane  IssuesPane
  Labels/Messages    标识符中文标签(数据文件驱动,未翻译回退原名) / UI文案
  GameLauncher       实机验证:构建进临时数据目录+MINDUSTRY_DATA_DIR启动游戏+解析last_log.txt
```

**核心不变式：项目文件夹 = 真实模组文件夹 + `modmaker/` 创作层**（`modmaker/project.json` 画板与 id 映射 + `boards/*.canvas` JSON Canvas 1.0）。由此推出三件事：
1. 未编辑的记录/meta/bundle 按**字节原文**回写（`raw`/`metaText`/`bundleRaw` 含 BOM）；
2. 构建 = 复制（排除 `modmaker/`），没有第二套生成逻辑；
3. 项目文件夹本身可直接放进游戏 `mods/` 实机跑。

## 4. Mindustry 格式契约（写 core 时必须遵守，均有源码依据，详见 `架构设计.md` §3）

- **只有 9 类内容可文件化**：block/unit/weather/item/liquid/status/sector/planet/team（`ContentParser.java:577` 解析器表）。**bullet/unitCommand/unitStance 无解析器**，只能内联在宿主文件（如炮塔 `ammoTypes`）→ 编辑内联对象写回宿主，不产生独立文件。
- 内容类型由**目录**决定（`content/<folder>/`，历史别名 status↔statuses、weathers↔weather），**递归**扫描 `.json|.hjson`（都是 HJSON 方言）。
- 内容名 = 文件名（允许中文、禁止空格/非法字符）；注册名 = `<内部名>-<文件名>`；内部名 = meta.name 小写+空格转连字符。
- 补丁：文件名与既有内容重名即"修改它"；**方块补丁必须省略 `type`**。矿石名是派生的（`new OreBlock(Items.x)` → `ore-x`）。
- 精灵：`sprites/` 仅小写 `.png`，区域名 = 文件名+模组前缀（分类前缀如 `block-x-full` 除外）；`sprites-override/` 不加前缀。
- bundle：`bundles/bundle*.properties`（`---bundle.properties---` 这类游戏不加载但须原样保留为资产）；键 `<type>.<模组名-内容名>.<name|description|details|credit>`；`bundle.properties` 是英语基包（**没有 bundle_en.properties**）；bundle 优先于 JSON 内联字段。
- weather 必须显式 `type`；未知字段仅警告；未知 `consumes` 子键/未知 `type` 是硬错误。
- 科技树无独立文件，靠内容 JSON 的 `research` 字段。
- 术语汉化以游戏官方 `Mindustry-160.5/core/assets/bundles/bundle_zh_CN.properties` 为准（曾因自译踩坑：Duct=物品管道不是导管、Router=路由器不是分导器）。

## 5. 五个容易做错的关键实现决策

1. **双父类**（`ClassSchema.parent` vs `hierarchyParent`）：精编 schema.json 的 `extends` 是为字段合并简化的（Battery 直接写 Block），会污染层级判定。字段合并走 `parent`（精编可覆盖），同族判定/祖先链走 `hierarchyParent`（源码扫描真实链，**祖先链必须在类型根处截断**，否则 UnlockableContent 让全类型"同族"）。见 `SchemaRegistry.relatedClasses`。
2. **坐标换算**：JavaFX 鼠标事件冒泡时 `event.getX()` 是相对事件源子节点的坐标。画布交互必须 `sceneToLocal → toWorld`；交互核心拆成 `pressAt/dragTo/releaseAt(localX,localY)` 纯入口供测试直接驱动（不模拟 OS 输入）。
3. **重开项目恢复 id**：先恢复文件型记录并建旧→新 id 映射，再重连内联记录的 `inlineOwnerId`、恢复内联 id——顺序错了会导致重开后编辑内联子弹**静默丢失**。
4. **保存剪枝必须保护资产**：`pruneManaged` 会删除 content/ 下不再被记录引用的 .json——但未知 content 子目录的文件是**资产**（`project.assetPaths()`），永不剪。写文件用临时文件+move（崩溃不留半个文件）。
5. **确定性**：内容记录按 `DETERMINISTIC_ORDER` 排序、zip 固定时间戳、画板名存 project.json 不存 canvas——保证重复保存/重开/构建**逐字节稳定**（有测试锁住）。

## 6. 验证协议（交付前全跑）

1. `./gradlew test` 全绿（70 个）。分层：格式规则/容错解析（含真实模组 265 文件解析）→ 导入报告 → **金标往返**（955 文件 导入→落盘→构建 逐字节比对 缺失/不同/多余=0）→ 编辑/内联/删除/改名幂等 → 恶意输入（假zip/路径穿越/坏meta）→ **UI 场景图+像素断言**（卡片数、clip 越界、电池下拉无管道类、拖拽+连线+撤销全链路）。
2. 界面改动：`UiSmokeTest` 里 `controller.select(...)` 后 `Snapshotter.write(scene, snapshots/ui-smoke.png)`，**用 Read 工具亲眼看图**（渲染对≠能交互，截图是唯一可靠的肉眼通道）。
3. 实机：`GameLauncher.buildAndLaunch`（隔离数据目录）或手动放 mods/；解析游戏日志找 `Error loading content`/`Sprite not found`。
4. 修 bug 必须同时加回归测试（本轮修的 7+2 个缺陷全有对应测试）。

## 7. 状态清单

**已完成**：导入（zip/文件夹，真实模组 265 内容+45 内联+6 补丁+531 边+688 资产，0 失败 0 警告）；构建/打包（955 文件字节一致往返）；**主区工作流选项卡**（`Workspace.of(type)` 路由：节点画布=方块/单位流程编辑、物品与液体=管理表格、星球与区块=编排树（行星为根、区块按 planet 字段分组、原版行星作只读分组根）；物品/液体仍保留画布节点——它们是 requirements/research 连线的目标）；**星球预览工作台三选项卡**（区块网格/星球外观/星系位置——外观渲染移植 MeshBuilder.buildHex + arc Simplex 噪声（反射比对逐位一致），星系按 Planet 构造公式推算轨道；三页均拖拽旋转、像素守卫测试防空白回归）；**行星 serpulo 模板**（新建行星自动预填 serpulo 默认数据：sectorSize=3/大气/云层/战役开关等，见 `templates/planet-serpulo.json`）；**mesh/cloudMesh 结构化子表**（`MeshTypes` 固定字段清单 + `MeshEditor`：类型下拉限 5 种可解析类型、固定字段集、只写编辑过的键、MultiMesh 子网格列表、移除按钮）；画布（缩放/平移/拖拽/框选/端口拖拽连线/边选择删除/undo+redo/面板拖放/越界裁剪）；schema 检查器（全量中文：1258 字段+201 类名）；字符串表；精灵选项卡（资产列表/预览/缺失清单）；实验性类自适应（同分支收窄）；中英 UI；实机测试闭环；稳定性加固。

**未做（按价值排序）**：① 校验面板消息汉化（需把 `Issue` 改成 key+args 结构，core 不依赖 Messages）；② 精灵面板续建：导入 PNG 进项目、卡片上预览精灵、拖拽关联内容；③ 工作区深化：物品/液体表格的行内编辑、星球与区块树的拖拽归属/区块研究链可视化、预览点击区块直接编辑该区块记录；④ 迷你地图、便签/分组新建入口；⑤ jpackage 单文件分发；⑥ 大项目异步导入（955 文件约 2.7s，同步在 UI 线程）；⑦ 连线时指定堆叠数量（现固定 amount=1）。

**已知限制**：内联记录的宿主文件一旦被编辑就整体重新序列化（宿主内其它注释丢失）；类自适应的"同族"按继承链，跨族需求需手动输入类名；单人维护，无 git 仓库（建议 `git init`）。

## 8. 陷阱备忘录（每条都真实踩过）

- **.bat 文件**：内容必须纯 ASCII；UTF-8 中文注释被 GBK 解析会吞换行符，注释变命令执行。
- **JavaFX**：Region 默认不裁剪子节点（画布必须 `setClip`）；可编辑 ComboBox 按钮显示走 **StringConverter 而非 buttonCell**；FX 应用线程非守护，测试 JVM 会挂死 → 用 JUnit `LauncherSessionListener`（需 testImplementation 的 junit-platform-launcher）在会话结束 `Platform.exit()`，且**工具链每 JVM 只启动一次，测试间严禁 Platform.exit**。
- **中文 properties 文件**：UTF-8 原文读写（游戏官方 bundle 也是 UTF-8 中文，非 \u 转义）；bash 里 heredoc/python 内嵌中文曾静默失败——优先用文件编辑工具。
- **资源文件改动后**必须 `gradlew run`（processResources 会重打包），别用旧进程判断效果。
- **用户会挪动文件**（fixture 曾被移动、README 曾被删）：路径探测要宽容，重要交付物改动前先确认存在。
- **gold 测试靠夹具**：`TestFixtures.realModRoot()` 探测 `../格雷工业` 等候选位置；夹具缺失时相关测试自动 skip（别把 skip 当全绿，看 "tests completed" 总数）。

## 9. 接手约定

- 改 core 必须带 JUnit；改 UI 必须带场景图断言或像素断言（参考 `UiSmokeTest` 现有三例）。
- 消息键：UI 文案进 `messages(_zh_CN).properties`；标识符标签进 `field-labels/class-labels(_zh_CN).properties`（未翻译自动回退原键名，schema 重扫后新字段无需立即翻译）。
- 每轮迭代结束更新本文的"状态清单"与 `架构设计.md`（追加小节，别改写历史章节——它是决策记录）。
- 用户偏好：中文交流与中文界面；实验性功能做成**可见开关**（默认开+琥珀色标注）；交付前主动验证并给截图/数据，不做"应该可以"的口头承诺。
