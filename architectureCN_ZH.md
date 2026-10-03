# Mindustry ModMaker 架构设计

节点画布式的 Mindustry 模组构建器：在画布上把内容（物品/方块/液体/单位/炮塔…）摆出来、用连线表达引用与科技树，
并且**能直接读入已有的模组包继续编辑**，**能输出可被游戏加载的完整模组**。

实现语言是 Java + JavaFX（与游戏同栈），不是 Electron。架构图见同目录的 `架构图.canvas`（Obsidian 打开即可）。

---

## 一、分层总览

```
┌── ui (JavaFX) ───────────────────────────────────────────────┐
│  AppShell 菜单/工具栏/状态栏 · NodeCanvasPane 节点画布        │
│  ContentCard · RecordInspector(schema 驱动) · StringsPane     │
│  IssuesPane · GameLauncher(隔离目录实机测试)                  │
│  ProjectController = 唯一致变入口，视图只订阅通知             │
├── core.io (纯 Java) ─────────────────────────────────────────┤
│  ModPackageReader 读模组包 → ModImporter 建项目 → ProjectIo   │
│  存盘 → ModExporter 构建/打包 · AutoLayout · JsonCanvas       │
├── core.fmt / core.json / core.schema / core.validate ────┤
│  容错 HJSON 读取 · 值域规则(命名/目录/精灵/bundle)          │
│  SchemaRegistry(201 类 + 原版内容名录) · Validator            │
├── core.model ────────────────────────────────────────────────┤
│  ModProject / ContentRecord / Board / CanvasNode / Edge /     │
│  StringTable / AssetRef —— 导入与导出共用的唯一真相           │
└─────────────────────────────────────────────────────────────┘
```

**为什么这样分层**：导入、导出、校验都是纯粹的数据变换。把它们关在 `core` 里（零 JavaFX 依赖）意味着
可以在没有界面的情况下用 JUnit 跑真机模组的往返测试——这正是上一版 Electron 架构最薄弱的地方
（格式逻辑缠在渲染层，只能靠 CDP 冒烟）。界面层只做「模型 ↔ 视图」绑定。

---

## 二、五个关键设计决策

### 1. 项目文件夹 = 真实模组文件夹 + `modmaker/` 创作层

```
<项目>/
├── mod.json / mod.hjson        真实 meta 文件（未编辑则原样保留）
├── content/**                  真实内容文件（未编辑则原样保留）
├── bundles/**                  真实 bundle 文件
├── sprites/ scripts/ sounds/ music/ maps/ schematics/ icon.png
└── modmaker/
    ├── project.json            画板列表、记录 id ↔ 路径映射、来源
    └── boards/*.canvas         节点与连线（JSON Canvas 1.0）
```

好处是三件事同时成立：
- **无损**：磁盘上的模组文件就是真相，没动过的文件保持字节一致；
- **可实机跑**：项目文件夹本身就能直接放进 `mods/`，不需要"导出"才能测试；
- **构建 = 复制**：`ModExporter` 只是把项目里除 `modmaker/` 之外的一切复制/打包出去，没有第二套生成逻辑。

### 2. 未编辑的文件按字节回写

`ContentRecord` 同时持有解析后的字段树与原始文本：未编辑 → 导出时原文写出（注释、格式、自定义键全留）；
编辑过 → 输出规范 JSON。bundle 文件同理。于是"导入一个别人的模组改一行再导出"不会污染其余 900 多个文件。

### 3. 只有 9 类内容能文件化，其余是内联对象

游戏 `ContentParser` 的解析器表（`ContentParser.java:577`）只注册了 **block / unit / weather / item / liquid /
status / sector / planet / team** 九种。`bullet`、`unitCommand`、`unitStance` 没有解析器，独立成文件会直接抛
`No parsers for content type`。所以：

- 画布上这 9 类节点各自对应一个文件；
- 子弹这类节点只作为**内联对象**存在（炮塔 `ammoTypes` 里的那个对象），编辑它会写回宿主文件；
- 导入时自动识别嵌套对象并生成子弹节点（真实模组里识别出 45 个）。

### 4. 引用即边

schema 里类型为 `content` / `stack` / `stack[]` 的字段（`requirements`、`research`、`ammoTypes` …）在画布上就是连线，
`research` 单独处理成科技树边。导入真实模组自动产出 531 条边——画布于是不只是白板，而是科技树与配方关系图。

### 5. 验证手段适配"没有 DOM 的环境"

JavaFX 无法像 Electron 那样用 CDP 检查 DOM，因此改用两条路：
`scene.snapshot()` 存 PNG（我直接读图复核界面）+ JUnit 场景图断言；核心逻辑则完全靠离线单测与真机往返测试覆盖。

---

## 三、必须遵守的模组格式契约（逐条从 160.5 源码核实）

| 规则 | 依据 |
|---|---|
| 装载来源：`mods/` 下的 zip/jar 或文件夹 | `Mods.java:518` |
| meta 文件优先级 `mod.json → mod.hjson → plugin.json → plugin.hjson`，**四种都按 HJSON 解析** | `Mods.java:35`、`ContentParser.java:1026` |
| zip 内**恰好一层**单目录会被自动下沉 | `Mods.resolveRoot`，`Mods.java:1080` |
| 内容扫描 `content/` **递归**，类型由目录决定，含历史别名 `status`/`weathers` | `Mods.java:877-885` |
| 内容名 = 文件名；游戏内注册名 = `<内部模组名>-<文件名>` | `ContentParser.java:591` 等 |
| 内部模组名 = `name` 小写、空格转连字符 | `Mods.ModMeta.cleanup`，`Mods.java:1427` |
| 补丁：文件名与既有内容重名即修改它；**方块补丁必须省略 `type`** | `ContentParser.java:583-592, 1041` |
| 精灵：`sprites/` 下**仅小写 `.png`**，区域名 = 文件名 + 模组前缀（已带分类前缀则不加）；`sprites-override/` 不加前缀 | `Mods.java:168-169, 404-412` |
| bundle：`bundles/bundle*.properties`，基名即 locale；键 `<类型>.<模组名-内容名>.<name\|description\|details\|credit>`，bundle 优先于 JSON 内联字段 | `Mods.java:641-663`、`UnlockableContent.java:90` |
| 科技树：没有独立文件，靠内容 JSON 的 `research`（父节点解析失败只警告） | `ContentParser.java:1337-1418` |
| weather **必须**显式声明 `type` | `ContentParser.java:909-918` |
| 未知字段仅警告；未知 `consumes` 子键、未知 `type` 值是硬错误 | `ContentParser.java:58, 571, 1038` |
| 矿石名是派生的：`new OreBlock(Items.titanium)` → `ore-titanium` | `OreBlock.java:26-28` |

---

## 四、三条数据流

**导入已有模组**：`ModPackageReader`（zip/文件夹 → 原始字节 + 文本，识别单层包装与噪声）→
`ModImporter`（内容/补丁/内联子弹/bundle→字符串表+保留自定义键/资产/自动布局+连线）→
`ProjectIo.unpack`（原样落盘为项目文件夹 + 写 `modmaker/`）。

**编辑**：画布选中卡片 → `RecordInspector`（schema 驱动控件）或 `StringsPane` →
`ProjectController` 改模型并置 `dirty` → `ProjectIo.save` 只重写改动过的文件（改名保留原目录）。

**构建**：`Validator` 先跑一遍规则 → `ProjectIo.save` → `ModExporter` 复制到
`<目标>/<内部名>/` 或打成 zip（单层包装目录 + 固定时间戳，重复构建产物一致）。

---

## 五、构建与运行

```bash
# 需要 JDK 21（本机 C:/Program Files/Java/jdk-21.0.12.1，已写入 gradle.properties）
./gradlew schemaBootstrap    # 扫 Mindustry 源码 → schemas/fields.json + vanilla-content.json
./gradlew run                # 启动应用（首次会联网下载 JavaFX）
./gradlew test               # 45 个测试，含真机模组往返
./gradlew run --args="--import=../格雷工业v167W45d/格雷工业 --projectDir=build/ui-demo --snapshot=snapshots/x.png"
```

命令行参数（便于自动化验证）：`--open=<项目目录>`、`--import=<模组 zip 或文件夹>`、`--projectDir=<落盘位置>`、
`--snapshot=<png>`。

---

## 六、验证与实测数据

用真实模组「格雷工业 v1.6.7」（955 个文件、541 张精灵、267 个 JSON、中文文件名、缺逗号的 HJSON）作为金标：

| 项目 | 结果 |
|---|---|
| 内容导入 | 265 个内容文件，解析失败 0，警告 0 |
| 内联子弹识别 | 45 个 |
| 补丁识别（`原版修改/`） | 5 个 |
| 自动连线 | 531 条（含 `research` 科技树边） |
| 资产保留 | 688 个（含游戏不加载的 `aaa.properties`、`---bundle.properties---`） |
| **导入 → 项目 → 构建** | **955 个文件，缺失 0 / 内容不同 0 / 多余 0（字节级一致）** |
| zip 打包 | 955 个文件，单层包装目录，可回读，27,477,776 字节 |
| 单文件编辑 | 只有被编辑的那个文件变化，其余 954 个字节不变 |
| 内联对象编辑 | 值写回宿主文件，且不产生多余文件 |
| 校验器 | 0 错误、47 警告（全部是"该方块没有对应精灵"的真实发现） |

---

## 七、稳定性校验（2026-10-02）

在功能验收之外做了一轮专门的稳定性校验，新增 `StabilityTest`（10 项）与 `UiSmokeTest`（真实 JavaFX 工具链上的场景图断言 + 截图）。**过程中发现并修复了 7 个真实缺陷**——这正是"只测功能不测稳定性"会漏掉的东西：

| # | 缺陷 | 后果 | 修复 |
|---|---|---|---|
| 1 | 重开项目时恢复记录 id 的顺序破坏了内联记录→宿主的链接 | **重开后再编辑内联子弹会静默丢失**；画布出现"(missing content)" | 先恢复文件型记录并建立旧→新 id 映射，再重连内联链接、恢复内联 id |
| 2 | 导入未携带 meta/Bundle 的原始文本 | 重开+保存会把 mod.json 的注释格式化掉、bundle 重排 | 导入器携带原文，未编辑时字节回写 |
| 3 | zip 内 `x/../../evil.txt` 类路径穿越 | 解包可写到项目目录之外 | `safeResolve` 归一化校验，越界路径跳过并报告 |
| 4 | 保存直接覆写目标文件 | 写盘中途崩溃会留下半个文件 | 写临时文件后原子 move |
| 5 | 保存的剪枝逻辑会删掉"未知 content 子目录"里的文件 | 非标准目录的模组文件在保存时丢失 | 剪枝跳过项目资产 |
| 6 | HJSON 解析无嵌套深度上限 | 恶意文件可栈溢出 | 深度上限 256 |
| 7 | 「添加内容」只建记录不建画布卡片 | 点添加按钮画布无反应（UI 冒烟测试抓到） | 新记录同时创建节点并落位 |

**校验结论**（全部通过，连续 3 遍全绿无抖动）：

| 检查项 | 结果 |
|---|---|
| 重复保存幂等 | 项目树逐字节不变 |
| 重开→保存循环 | 逐字节不变（修复 #1/#2 后） |
| 重复打包 zip | 两次构建的 zip 字节相同（固定时间戳） |
| BOM 保留 | 带 BOM 的内容文件在解包与构建后 BOM 均保留 |
| 损坏输入 | 假 zip / 非 zip 文件抛 IOException；空目录、坏 meta 正常导入并告警，内容不受影响 |
| 恶意 zip | 路径穿越条目被拦截，不落盘 |
| 删除一条内容 | 只删它的文件与 modmaker/ 对应项，其余 955 文件字节不变 |
| 改名 | 文件在原目录内改名，不搬目录 |
| 未知目录文件 / 重开后外部新增文件 | 均保留 |
| 重名内容 | 校验器报 error |
| 性能 | 955 文件导入+解包 2.4~2.7 秒 |
| UI 冒烟 | 真实工具链上建项目+加内容，场景图卡片数正确，快照 45KB |
| 应用启动 | 导入 955 文件模组启动，stderr 无异常栈；重开后画布 310 节点/531 边，无 missing content |

校验期间测试夹具（格雷工业模组文件夹）被移动过位置，测试已改为**多候选自动探测**（`TestFixtures`），
夹具在 workspace 内任意常见位置都能找到。

---

## 八、交互层（2026-10-02 第二轮迭代）

首版只验证了"渲染正确"，用户实测发现**画布无法交互**。根因是一个典型的 JavaFX 陷阱：

> 鼠标事件从卡片内的子节点（标签、圆点）冒泡到画布时，`event.getX()/getY()` 始终是相对**事件源节点**的坐标，
> 不是相对画布的。点卡片时坐标是"卡片内部某标签的局部坐标"，点选/拖拽判定全部失效。

**修复与新增**：

- 交互层统一改为 `event.getSceneX/Y() → sceneToLocal → toWorld` 三步换算；交互核心拆成
  `pressAt / dragTo / releaseAt`（接收画布局部坐标），鼠标事件层只做坐标转换——**整个交互逻辑可以直接单测**，
  不需要模拟操作系统级输入。
- **越界裁剪**：JavaFX 的 Region 默认不裁剪子节点，卡片拖到负世界坐标后会绘制到工具栏/侧面板上方
  （用户实测报告）。画布加了绑定自身尺寸的 clip 矩形；并配了**像素级回归测试**——复现"顶栏+左面板+画布"
  布局，把卡片拖出边界后对截图采样，断言溢出区域仍是面板颜色。
- **端口拖拽连线**：卡片右缘绿色 ID 端口 + 左缘灰色输入端口（带字段名标签，最多 4 个）。从一个卡片的
  ID 端口拖到另一卡片的输入端口（反向亦可），落点命中端口即写入引用：标量字段写裸内容名（游戏会先按当前
  模组解析），`stack[]` 字段追加 `{item: 名, amount: 1}`；同一 (from,to,field) 重复连线被拒绝。
- **引用写入/回退进 core**：`ReferenceOps.apply/revert/remove` 纯数据操作，JUnit 直测。
- **撤销/重做**：命令栈覆盖 添加/删除内容、连线/断开、拖拽移动（Ctrl+Z / Ctrl+Y，工具栏与「编辑」菜单同步禁用态）。
- **边选择与删除**：每条边带 12px 宽透明拾取曲线，点击选中（红色高亮），Delete 断开并回退字段值。
- **面板拖放**：从内容类型面板拖到画布任意位置落卡；「添加」按钮把新卡放到**当前视口中心**（原来放到最低卡片
  下方，大项目里根本看不见）。

**验证**：`UiSmokeTest` 在真实 JavaFX 工具链上驱动同一套 pressAt/dragTo/releaseAt——拖卡片断言节点位移、
ID 端口拖到 requirements 端口断言字段写入+边生成、undo/redo 断言往返；另加 `ReferenceOpsTest` 与
`ProjectControllerInteractionTest`（连接/断开/移动/删除 × 撤销重做 × 落盘）。67 个测试全绿。
附带修复：UI 测试导致 Gradle 测试进程挂起（FX 非守护线程），用 `LauncherSessionListener` 在测试会话结束时
关闭工具链。

---

## 九、界面汉化（2026-10-02 第三轮迭代）

针对"属性面板与节点面板大量原始键值"的反馈，加了一层**标识符显示标签**：

- **`Labels` 助手 + 两个数据文件**：`field-labels_<locale>.properties`（字段名 → 中文标签，**全量覆盖
  schema 中全部 1258 个唯一字段名**）与 `class-labels_<locale>.properties`（类名 → 中文，**全量覆盖 201 个类**）。
  全局按名字映射——同名字段在所有类里含义一致；术语对照游戏官方中文本地化。未翻译的标识符仍会回退原键名，
  schema 更新（重新扫描源码）后新增字段也自动回退，可增量补翻译。
- 原始键名不丢：schema 字段行的**悬停提示**是 `craftTime — 合成时间` 形式（原始键 + 英文描述），
  因为导出文件里写的仍是原始键。
- 内容类型全部中文化（物品/方块/液体/状态/单位/天气/区块/行星/队伍/子弹/单位指令/单位姿态），
  用于调色板、工具栏下拉、卡片副标题、检查器头部。基类与类型同名时卡片副标题不再重复
  （"方块"而不是"方块 · Block"）。
- 检查器的固定文案（标识/本地化/字段分区标题、名称（文件名）、修补现有内容（补丁）、（新文件）、
  添加字段、原始 JSON 等）全部走 Messages 资源；字符串表列头"键"。
- 可编辑类下拉用 **StringConverter** 显示中文（这是可编辑 ComboBox 的按钮渲染路径，buttonCell 不生效），
  输入既接受中文标签也接受原始类名，写回文件的永远是原始类名。

**尚未汉化**：校验面板的消息（核心层产生英文句子，需要把 Issue 改成 key+args 结构再做显示层翻译）、
schema 字段描述 tooltip 的英文正文、构建日志的技术性行。

---

## 十、类自适应（实验性，2026-10-02 第四轮迭代）

按源码语义，不同方块子类有不同字段；此前类下拉把 155 个方块类全部列出，电池可以随时变成物品桥。
新增**实验性自适应**（工具栏琥珀色开关「自适应（实验）」，默认开）：

- **同族切换**：`SchemaRegistry.relatedClasses(type, current)` 沿真实 Java 继承链（`hierarchyParent`，
  来自源码扫描，不被精编 schema 的简化 `extends` 覆盖）判定"同分支"——选中电池后下拉只提供
  Battery → PowerDistributor → PowerBlock 这条链的类、其同门子类（电力节点等）以及基类 Block（可随时
  回退为通用方块）。ItemBridge、Wall 等其它分支不再出现。
- **实现要点**：精编 schema 的 `extends` 是当年为字段合并简化的（Battery 直接写 Block），会污染层级
  判定。因此 `ClassSchema` 拆成两个字段：`parent`（字段合并用，精编可覆盖）与 `hierarchyParent`
  （源码扫描的真实父类，层级判定专用）；祖先链在类型根处截断（`UnlockableContent`、`Content` 为所有类
  共有，否则整个类型都会被判为同族）。
- **字段自适应显示**本就由 `fieldsOf`（沿继承链合并）实现；字段区现在会标注来源
  （"通用合成器 的字段（含继承）"），让"字段随类变"可见。
- 关闭开关即恢复完整的 155 类下拉；手动输入类名仍可任意切换（下拉约束、输入自由）。
- 测试：`SchemaRegistryTest.adaptiveClassSwitchingStaysInTheSameBranch` 断言 电池↔电力节点互通、
  电池↛物品桥、炮塔族互通、炮塔↛钻头、基类时给出全列表；`UiSmokeTest.batteryRecordHasNoTransportClassesInItsDropdown`
  在真实场景图上断言电池记录的下拉候选项无 导管/物品桥/墙/钻头/路由器。
- **术语校准**：类名标签对照游戏官方中文包（`bundle_zh_CN.properties`）修正——物品管道（Duct，此前误译
  "导管"与液体导管 Conduit 撞车）、路由器（Router，此前误译"分导器"）、流体路由器/流体交叉器、装卸器、
  物品/液体/电力黑洞（*Void）等。
- **基类时显示全列表是有意的**：记录还是通用"方块"时没有任何分支信息可供收窄；选定子类（如电池）后
  列表立即收窄到同族。

---

## 十一、已知限制与后续

**已实现**：导入 zip/文件夹、完整打包（文件夹/zip）、可交互画布（缩放/平移/拖拽/框选/端口连线/边删除/
撤销重做/面板拖放）、schema 驱动检查器、字符串表、校验面板、中英界面、实机测试脚本。

**尚未实现**（按价值排序）：
1. **校验面板消息汉化**（Issue 需改为 key+args 结构）；
2. **精灵面板**——精灵只作为资产保留与校验，还不能在卡片上预览 PNG 或导入新图；
3. **迷你地图、便签与分组的新建入口**（分组只能从 JSON Canvas 文件带入）；
4. **打包分发**（jpackage 单文件 exe）；
5. 大规模项目的异步导入（目前 955 文件约 3 秒，同步在 UI 线程上执行）；
6. 连线时选择数量（stack 目前固定 amount=1，需在检查器里改）。
