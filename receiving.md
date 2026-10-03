# Mindustry ModMaker — Handoff Documentation

> This document is a **self-contained distillation** of the project: any developer or large model without historical context can take over development, verification, and delivery by reading only this.
> For detailed derivation, refer to `Architecture Design.md` (layered decisions, line-by-line source code justification for format contracts, iteration logs). This document does not repeat derivations—only executable facts are retained.
> Last updated: 2026-10-02 (after fourth iteration, all 70 tests passed).

---

## 1. What is the project

A node-based canvas Mindustry mod builder (Java + JavaFX desktop application, **not Electron**):

- **Import** existing mod packages (.zip / folders) → unpack into editable projects, with cards grouped by type on the canvas and connected via reference relationships;
- **Edit**: schema-driven field inspector (fully in Chinese), string tables, validation panel, undo/redo, class-adaptive dropdowns;
- **Export**: complete, loadable mods (folder or .zip), with unmodified files preserved byte-for-byte.

Project location: `C:\Users\XN325\Desktop\mindustry\Mindustry-modmaker` (also a subdirectory of an Obsidian vault, with the vault root at `C:\Users\XN325\Desktop\mindustry`).
## 2. Environment and Commands (Hardcoded Facts of the Local Machine)

| Fact                | Value                                                                                                                                                    |
| ------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| JDK                 | `C:\Program Files\Java\jdk-21.0.12.1` (fixed in `gradle.properties` under `org.gradle.java.home`)                                                        |
| Schema regeneration | `./gradlew schemaBootstrap` (scans `../Mindustry-160.5/core/src/mindustry` → `schemas/fields.json` + `vanilla-content.json`)                             |
| Build               | Gradle 9.3.1 wrapper (cached for offline use); JavaFX 21.0.4 win + gson 2.11 from Maven Central (requires internet connection for the first time)        |
| Run                 | `JAVA_HOME="C:/Program Files/Java/jdk-21.0.12.1" ./gradlew run`                                                                                          |
| Test                | `./gradlew test` (70 tests, must all pass before delivery)                                                                                               |
| Startup script      | `启动ModMaker.bat` (content must be pure ASCII + CRLF - cmd in Chinese Windows uses GBK to parse .bat, Chinese comments in UTF-8 will swallow line breaks) |
| Real game           | `../Mindustry/Mindustry.jar` (160.5); supports `MINDUSTRY_DATA_DIR` to isolate data directory (in `ClientLauncher.java:42`)                              |
Command line hook (for automated verification): `--open=<project directory>` , `--import=<module package>` , `--projectDir=<output location>` , `--snapshot=<png>` (automatically exits after taking screenshot). 
## 3. Architecture (Two layers, format core with zero JavaFX dependencies)

```
core/ (Pure Java, offline single testing)
json/    HjsonReader (Fault-tolerant reading: comments/missing commas/unnamed keys/single quotes/BOM, depth limit 256) Json (Standard format) JsonPath
fmt/     ContentType (9 types of fileization + 3 inline, historical aliases status/weathers) NameRules (Name/Suffix/Sprite area)
PropertiesFile (bundle encoding/decoding, UTF-8 original text) BundleCodec (Key conventions + locale) TechTreeRules ReferenceOps
model/   ModProject (Unique truth) ContentRecord (raw + dirty + inline*) Board/CanvasNode/CanvasEdge (DETERMINISTIC_ORDER)
StringTable (bundleRaw original text + dirty) AssetRef
schema/  SchemaRegistry (Three-layer merging: refined schema.json > scanning fields.json; dual parent classes! See §5) FieldDef/FieldType
io/      ModPackageReader (Read mod package: zip/folder/single-layer packaging/noise filtering/safeResolve anti-penetration)
ModImporter (Patch/inline bullet recognition, automatic layout + connections, deterministic sorting) ProjectIo (Save/Reopen, atomic write, asset protection pruning)
ModExporter (Build = Copy, zip single-layer packaging + fixed timestamp, contains empty directories) JsonCanvas (Obsidian compatible) AutoLayout
validate/Validator (Error = game will crash, warning = suspicious; Messages currently in English → To-do)
ui/ (JavaFX)
ProjectController  The only variable entry point (Open/Import/Add/Remove/Connect/Undo stack/Build), view only subscribes to notifications
Main area tabs         TabPane (Node canvas/Items & Liquids/Planets & Blocks) Workspace.of(type) Routing "Add" action
NodeCanvasPane     Canvas: world Group (pan + zoom transform) + clip + pressAt/dragTo/releaseAt measurable interaction core
ItemsPane          Items/Liquids management table (Type/Name/File/Class/Color blocks/Description)
CosmosPane         Planet → Block arrangement tree (Blocks grouped by planet field, original planets only read grouped)
PlanetPreview      Planet preview workstation (Three tabs): ① Block grid (Geodesic grid + occupancy highlighting + index, capacity = 10 * 3^size + 2)
② Planet appearance (MeshBuilder.buildHex parameter transplantation: SimplexNoise and arc reflection comparison bit by bit consistent)
Noise coloring / height elevation, cloudMesh clouds, atmospheric edges, drag rotation) ③ Galaxy position (orbit radius calculated according to the Planet construction formula: star + original planet context + this planet + satellite)
PlanetOrbits       orbitRadius = parent.totalRadius + spacing + totalRadius  formula implementation (original: Sun r = 4 / spacing = 2)
SimplexNoise       arc.util.noise.Simplex.noise3d  line-by-line transplantation (seed hash/skew are completely consistent, reflection comparison test locks)
PlanetMeshes       buildHex family transplantation: NoiseMesh/SunMesh/HexSkyMesh's getHeight/getColor/skip semantics
PlanetTemplates    New planet automatically applies serpulo template (/templates/planet-serpulo.json):
grid = NoiseMesh (the game JSON parser does not support serpulo's built-in HexMesh! ), atmosphere, clouds,
campaign switch, startSector = 170, etc.; each application is a deep copy; sectorSize=3
MeshTypes/MeshEditor mesh/cloudMesh structured sub-tables: Only provide 5 types that can be parsed by parseMesh, each type has a fixed field set (matched with the source code parameter by parameter), only write the keys that have been edited by the user, and the keys that have not been touched use the default values of the parser
ContentCard  Card: Left and right ports (anchor points are constants, not dependent on layout)  RecordInspector  StringsPane  IssuesPane
Labels/Messages  Identifier Chinese labels (data file driven, not translated back to the original name) / UI copy text
GameLauncher  Real machine verification: Build into the temporary data directory + MINDUSTRY_DATA_DIR to start the game + parse last_log.txt
```

Core invariant: Project folder = Real mod folder + `modmaker/` creation layer (`modmaker/project.json` drawing board and ID mapping + `boards/*.canvas` JSON Canvas 1.0). From this, three things can be deduced:
1. Unedited records / meta / bundle are rewritten back in **original bytes** (including BOM in `raw` / `metaText` / `bundleRaw`);
2. Build = Copy (excluding `modmaker/`), there is no second set of generation logic;
3. The project folder itself can be directly placed in the game's `mods/` for real-time running.
## 4. Mindustry Format Contract (Must be followed when writing core; source code is available for reference, see `Architecture Design.md` §3)

- **Only 9 types of content can be documented**: block/unit/weather/item/liquid/status/sector/planet/team (`ContentParser.java:577` parser table). **Bullet/unitCommand/unitStance have no parser**, they can only be embedded in the host file (such as turret `ammoTypes`) → edit inline objects and write back to the host, without generating independent files.
- Content types are determined by **directories** (`content/<folder>`, historical alias status ↔ statuses, weathers ↔ weather), **recursively** scan `.json|.hjson` (both are HJSON dialects).
- Content name = file name (allows Chinese, prohibits spaces/illegal characters); registration name = `<internal name>-<file name>`; internal name = meta.name in lowercase + space to hyphen.
- Patch: If the file name has the same name as an existing content, it is "modify it"; **block patches must omit `type`**. Ore names are derived (`new OreBlock(Items.x)` → `ore-x`).
- Sprite: `sprites/` only lowercase `.png`, region name = file name + module prefix (except for category prefix such as `block-x-full`); `sprites-override/` without prefix.
- Bundle: `bundles/bundle*.properties` (games that do not load but must be kept as assets in their original form); key `<type>.<module name-content name>.<name|description|details|credit>`; `bundle.properties` is an English base package (**no bundle_en.properties**); bundle takes precedence over JSON inline fields.
- Weather must have an explicit `type`; unknown fields only generate a warning; unknown `consumes` sub-key/unknown `type` is a hard error.
- Technology tree has no independent file, it relies on the `research` field in the content JSON.
- Terminology translation follows the official game `Mindustry-160.5/core/assets/bundles/bundle_zh_CN.properties` (once encountered issues due to self-translation: Duct = item pipe is not duct, Router = router is not splitter).

## 5. Five Key Implementation Decisions That Are Easy to Get Wrong

1. **Dual Parent Classes** (`ClassSchema.parent` vs `hierarchyParent`): The simplified `extends` in schema.json is designed for field merging (Battery directly writes Block), but it pollutes hierarchy determination. Field merging uses `parent` (which can be overridden in the compiled version), while lineage and ancestor chain checks use `hierarchyParent` (based on actual source code scanning—**ancestor chains must be truncated at the type root**, otherwise UnlockableContent would make all types "related"). See `SchemaRegistry.relatedClasses`.

2. **Coordinate Conversion**: In JavaFX, during mouse event bubbling, `event.getX()` returns coordinates relative to the event source's child node. Canvas interactions require `sceneToLocal → toWorld`. The interaction core should be split into `pressAt/dragTo/releaseAt(localX, localY)` pure entry points, allowing direct test-driven invocation (without simulating OS input).

3. **Restoring IDs When Reopening a Project**: First restore file-based records and build an old-to-new ID mapping, then reconnect inline records' `inlineOwnerId` and recover inline IDs. Doing this in reverse order causes inline bullets edited after reopening to **silently disappear**.

4. **Asset Protection During Save Pruning**: `pruneManaged` deletes .json files under content/ that are no longer referenced by any record—but unknown files in content subdirectories are considered **assets** (`project.assetPaths()`), and must never be pruned. Use temporary files with atomic move operations when writing to ensure no partial files remain after crashes.

5. **Determinism**: Content records are sorted using `DETERMINISTIC_ORDER`, zip timestamps are fixed, and canvas names are stored in project.json rather than canvas itself—ensuring byte-for-byte consistency across repeated saves, reopenings, and builds (with tests locked in place).
## 6. Validation Protocol (Run Full Suite Before Delivery)

1. `./gradlew test` must pass completely (70 tests). Test layers: formatting rules / fault-tolerant parsing (including real mod file parsing, 265 files) → import report → **golden round-trip** (955 files: import → save to disk → build, byte-by-byte comparison; missing/different/extraneous = 0) → edit/in-line/delete/rename idempotence → malicious input (fake zip / path traversal / invalid metadata) → **UI scenario + pixel assertions** (card count, clip out-of-bounds, battery dropdown without pipeline class, drag-and-drop + connection + undo full workflow).
2. UI changes: in `UiSmokeTest`, after `controller.select(...)`, use `Snapshotter.write(scene, snapshots/ui-smoke.png)`; **manually inspect the image with Read tool** (correct rendering ≠ functional interaction; screenshots are the only reliable visual verification channel).
3. On-device testing: run `GameLauncher.buildAndLaunch` (with isolated data directory) or manually place mods/; parse game logs for errors like `Error loading content` or `Sprite not found`.
4. Every bug fix must include a corresponding regression test (all 7+2 defects fixed in this round have their respective tests).
## 7. Status List

**Completed**: Import (zip/folder, real mod 265 content + 45 inline + 6 patches + 531 edges + 688 assets, 0 failures, 0 warnings); Build/Pack (955 file bytes consistent round trip); **Main Area Workflow Tab** (`Workspace.of(type)` routing: node canvas = block/unit flow editing, items and liquids = management table, planets and blocks = orchestration tree (planet as root, blocks grouped by planet field, original planets as read-only group root); items/liquids still retain canvas nodes - they are the targets of requirements/research connections); **Planet Preview Workbench Three Tabs** (block grid/planet appearance/solar system position - appearance rendering transplanted MeshBuilder.buildHex + arc Simplex noise (reflection ratio consistent bit by bit), solar system calculated orbit according to Planet construction formula; all three pages drag and rotate, pixel guard test for blank regression prevention); **Planet Serpulo Template** (new planet automatically pre-fills serpulo default data: sectorSize=3/atmosphere/clouds/battle switch etc., see `templates/planet-serpulo.json`); **Mesh/CloudMesh Structured Sub-table** (`MeshTypes` fixed field list + `MeshEditor`: type dropdown limited to 5 parseable types, fixed field set, only written edited keys, MultiMesh sub-grid list, remove button); Canvas (zoom/pan/drag/box select/connector drag connection/edge selection delete/undo+redo/panel drag drop/overlapping clipping); Schema Checker (full Chinese: 1258 fields + 201 class names); String Table; Sprite Tab (asset list/previews/missing list); Experimental Class Adaptive (same branch narrowing); Chinese-English UI; Real-time Testing Loop; Stability Enhancement.

**Not Done (sorted by value)**: ① Localize the issue panel messages (need to change `Issue` to key+args structure, core does not rely on Messages); ② Continue building the sprite panel: import PNG into the project, preview the sprite on the card, drag and associate content; ③ Deepen the workspace: inline editing of items/liquids table rows, drag and assign ownership of planet and block trees/visualization of block research chain, preview clicking on a block to directly edit the block record; ④ Mini map, notes/group new entry; ⑤ jpackage single-file distribution; ⑥ Asynchronous import of large projects (955 files approximately 2.7 seconds, synchronous in UI thread); ⑦ Specify stack quantity when connecting (currently fixed amount=1).

**Known Limitations**: Host file of inline records will be re-serialized when edited (other comments in the host lost); "Same family" for class adaptive "same family" follows inheritance chain, cross-family requirements need to manually input class names; single-person maintenance, no git repository (suggest `git init`).

## 8. Trap Memorandum (each item is something actually experienced)

- **.bat file**: Content must be pure ASCII; UTF-8 Chinese comments parsed as GBK will swallow line breaks, making the comments execute as commands.
- **JavaFX**: By default, regions do not clip child nodes (the canvas must be `setClip`); editable ComboBox buttons display using **StringConverter** instead of buttonCell; the FX application thread is not daemon and testing the JVM will hang → use JUnit `LauncherSessionListener` (requires `testImplementation` of junit-platform-launcher) to call `Platform.exit()` at the end of the session, and **the toolchain should only start once per JVM, and Platform.exit is strictly prohibited between tests**.
- **Chinese properties file**: Read/write in UTF-8 format (the game's official bundle is also in UTF-8 Chinese, not \u escape sequences); heredoc/python embedded Chinese in bash once failed silently - prefer to use a file editor.
- After modifying the resource files, `gradlew run` must be used (processResources will repackage); do not use the old process to check the effect.
- If users move files (fixtures were moved, README was deleted): the path detection should be tolerant, and confirm the existence of important deliverables before making any changes.
- **Gold tests rely on fixtures**: `TestFixtures.realModRoot()` detects candidate locations such as `../Gray Industry`; when the fixture is missing, related tests will automatically skip (do not mistake skip as always green; check the total number of "tests completed").

## 9. Handover Agreement

- Modifying core must be accompanied by JUnit; modifying UI must include scene graph assertion or pixel assertion (refer to the existing three examples in `UiSmokeTest`).
- Message keys: UI text goes into `messages(_zh_CN).properties`; identifier labels go into `field-labels/class-labels(_zh_CN).properties` (untranslated keys will revert to the original key name; new fields in the schema after a re-scan do not need to be immediately translated).
- Update the "status list" and `Architecture Design.md` of this document after each iteration (add new sections, do not rewrite historical chapters - it is a decision record).
- User preferences: Chinese communication and Chinese interface; experimental features should be made into **visible switches** (default on + amber label); before delivery, actively verify and provide screenshots/data, do not make "should be able to" verbal promises.