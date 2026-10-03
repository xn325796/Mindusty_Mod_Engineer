# Mindustry ModMaker Architecture Design

Node-based canvas-style Mindustry mod builder: Arrange the contents (items/blocks/liquids/units/turrets...) on the canvas, express references and technology tree through connections,
and **can directly read in existing mod packages to continue editing**, **can output complete mods that can be loaded by the game**.

The implementation language is Java + JavaFX (on the same stack as the game), not Electron. The architecture diagram can be found in the same directory as `architecture.canvas` (can be opened with Obsidian).

---

## 1. Hierarchical Overview

```
┌── ui (JavaFX) ───────────────────────────────────────────────┐
│ AppShell menu/tool bar/status bar · NodeCanvasPane node canvas │
│ ContentCard · RecordInspector (schema-driven) · StringsPane │
│ IssuesPane · GameLauncher (isolated directory for real-time testing) │
│ ProjectController = unique mutable entry point, views only subscribe to notifications │
├── core.io (pure Java) ─────────────────────────────────────────┤
│ ModPackageReader reads mod packages → ModImporter creates project → ProjectIo │
│ Saving → ModExporter builds/packs · AutoLayout · JsonCanvas │
├── core.fmt / core.json / core.schema / core.validate ────┤
│ Fault-tolerant HJSON reading · Value range rules (Naming/Directory/Sprite/Bundle)          │
│ SchemaRegistry (201 classes + original content list) · Validator            │
├── core.model ────────────────────────────────────────────────┤
│  ModProject / ContentRecord / Board / CanvasNode / Edge /     │
│ StringTable / AssetRef —— The sole common truth shared by import and export operations │
└─────────────────────────────────────────────────────────────┘
```

**Why this layering**: Importing, exporting, and validating are all pure data transformations. Keeping them within `core` (with no JavaFX dependencies) means
that you can run real-device module round-trip tests with JUnit without an interface - this was the weakest point of the previous Electron architecture
(Format logic was tangled in the rendering layer, and could only smoke out through CDP). The interface layer only does "model ↔ view" bindings.

---

## 2. Five Key Design Decisions

### 1. Project Folder = Real Mod Folder + `modmaker/` Creation Layer

```
<Project>/
├── mod.json / mod.hjson        The original meta file (unchanged if not edited)
├── content/**                  The original content files (unchanged if not edited)
├── bundles/**                  The original bundle files
├── sprites/ scripts/ sounds/ music/ maps/ schematics/ icon.png
└── modmaker/
├── project.json            List of boards, mapping of id ↔ path, source
└── boards/*.canvas         Nodes and connections (JSON Canvas 1.0)
```

The benefits are three things happening simultaneously:
- **Lossless**: The mod files on the disk are the truth; unmodified files retain their byte consistency;
- **Runnable in real-time**: The project folder itself can be directly placed in `mods/`, without the need for "exporting" to test;
- **Build = Copy**: `ModExporter` merely copies/packs everything in the project except `modmaker/` and does not have a second set of generation logic.

### 2. Unedited files are written back by bytes

`ContentRecord` holds both the parsed field tree and the original text at the same time: Unedited → Original text is written when exporting (annotations, formatting, custom keys are all retained);
Edited → Output is in standard JSON. The same applies to bundle files. Thus, "importing someone else's mod, making one change, and then exporting" will not contaminate the other 900+ files.

### 3. Only 9 types of content can be documented, and the rest are inline objects

The parser table of the game's `ContentParser` (at `ContentParser.java:577`) only registers **block / unit / weather / item / liquid /
status / sector / planet / team**. `bullet`, `unitCommand`, `unitStance` do not have parsers, and being independent as files would directly throw
`No parsers for content type`. So:

- These 9 types of nodes on the canvas correspond to one file each;
- Nodes of type "bullet" exist only as **inline objects** (the object in the turret `ammoTypes`), and editing it will write back to the host file;
- During import, nested objects are automatically identified and bullet nodes are generated (45 are identified in the real mod).

### 4. References as Edges

Fields of type `content` / `stack` / `stack[]` in the schema (such as `requirements`, `research`, `ammoTypes`...) on the canvas are connections,
`research` is separately processed as a technology tree edge. Importing the real mod automatically generates 531 edges - thus the canvas is no longer just a blank board, but a technology tree and recipe relationship diagram.

### 5. Validation Methods Adapt to "DOM-less Environments"

JavaFX cannot use CDP to check the DOM like Electron does, so two approaches are used:
`scene.snapshot()` saves as PNG (I directly read the image to review the interface) + JUnit scene diagram assertions; the core logic is completely covered by offline unit tests and real device round-trip testing.

---

## III. Mandatory Module Format Agreement (verified item by item from 160.5 source code)

| Rule                                                                                                                                                                                                                           | Basis                                     |         |                                                           |                                                  |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------- | ------- | --------------------------------------------------------- | ------------------------------------------------ |
| Source of loading: `mods/` directory containing zip/jar or folder                                                                                                                                                              | `Mods.java:518`                           |         |                                                           |                                                  |
| Priority of meta files: `mod.json → mod.hjson → plugin.json → plugin.hjson`, **all four are parsed using HJSON**                                                                                                               | `Mods.java:35`, `ContentParser.java:1026` |         |                                                           |                                                  |
| A single directory within the zip file that is exactly one level deep will be automatically flattened                                                                                                                          | `Mods.resolveRoot`, `Mods.java:1080`      |         |                                                           |                                                  |
| Content scanning: `content/` is recursively searched, the type is determined by the directory, including historical aliases `status`/`weathers`                                                                                | `Mods.java:877-885`                       |         |                                                           |                                                  |
| Content name = file name; game internal registration name = `<internal mod name>-<file name>`                                                                                                                                  | `ContentParser.java:591` etc.             |         |                                                           |                                                  |
| Internal mod name = `name` in lowercase, spaces replaced with hyphens                                                                                                                                                          | `Mods.ModMeta.cleanup`, `Mods.java:1427`  |         |                                                           |                                                  |
| Patch: If the file name has the same name as the existing content, it will be modified; **block patches must omit `type`**                                                                                                     | `ContentParser.java:583-592, 1041`        |         |                                                           |                                                  |
| Sprite: In `sprites/` directory, only `.png` files in lowercase, region name = file name + module prefix (if already prefixed with category prefix, no additional prefix is added); `sprites-override/` does not have a prefix | `Mods.java:168-169, 404-412`              |         |                                                           |                                                  |
| Bundle: `bundles/bundle*.properties`, base name is locale; key `<type>.<mod name-content name>.<name                                                                                                                           | description                               | details | credit>`; bundle takes precedence over JSON inline fields | `Mods.java:641-663`, `UnlockableContent.java:90` |
| Technology tree: No independent file, relies on the `research` field in the content JSON (parent node parsing failure only warns)                                                                                              | `ContentParser.java:1337-1418`            |         |                                                           |                                                  |
| Weather: `type` must be explicitly declared                                                                                                                                                                                    | `ContentParser.java:909-918`              |         |                                                           |                                                  |
| Unknown fields only generate warnings; unknown `consumes` sub-key, unknown `type` value is a hard error                                                                                                                        | `ContentParser.java:58, 571, 1038`        |         |                                                           |                                                  |
| Mineral name is derived: `new OreBlock(Items.titanium)` → `ore-titanium`                                                                                                                                                       | `OreBlock.java:26-28`                     |         |                                                           |                                                  |

---

## Four, Three Data Streams

**Importing Existing Modules**: `ModPackageReader` (zip/folder → raw bytes + text, identifying single-layer packaging and noise) →
`ModImporter` (content/patch/inline bullet/bundle → string table + retained custom keys/assets/automatic layout + connections) →
`ProjectIo.unpack` (write to project folder in its original state + write `modmaker/`).

**Editing**: Select a card on the canvas → `RecordInspector` (schema-driven controls) or `StringsPane` →
`ProjectController` modifies the model and sets it as `dirty` → `ProjectIo.save` only rewrites the modified files (renaming while keeping the original directory).

**Building**: `Validator` runs the rules first → `ProjectIo.save` → `ModExporter` copies to
`<target>/<internal name>/` or creates a zip (single-layer packaging directory + fixed timestamp, consistent build results for repeated builds).

---

## V. Construction and Operation

```bash
# Required JDK 21 (local C:/Program Files/Java/jdk-21.0.12.1, already written in gradle.properties)
./gradlew schemaBootstrap    # Scan Mindustry source code → schemas/fields.json + vanilla-content.json
./gradlew run                # Start the application (will download JavaFX over the internet for the first time)
./gradlew test               # 45 tests, including real device module round trips
./gradlew run --args="--import=.. "Gray Industry v167W45d / Gray Industry --projectDir=build/ui-demo --snapshot=snapshots/x.png"
```

Command-line parameters (for facilitating automated verification): `--open=<project directory>`, `--import=<module zip or folder>`, `--projectDir=<output location>`, `--snapshot=<png>`.

---
## VI. Verification and Measured Data

Using the real module "Gray Industry v1.6.7" (955 files, 541 sprites, 267 JSONs, Chinese file names, HJSON without commas) as the gold standard:

| Project | Result |
|---|---|
| Content Import | 265 content files, parsing failed 0, warnings 0 |
| Inline Bullet Recognition | 45 |
| Patch Recognition (`Original Modification/`) | 5 |
| Automatic Connection | 531 items (including `research` technology tree edges) |
| Asset Retention | 688 items (including game files not loaded `aaa.properties`, `---bundle.properties---`) |
| **Import → Project → Build** | **955 files, 0 missing / 0 content differences / 0 redundant (byte-level consistency)** |
| Zip Packaging | 955 files, single-layer packaging directory, readable, 27,477,776 bytes |
| Single File Editing | Only the edited file changes, the remaining 954 bytes remain unchanged |
| Inline Object Editing | Values are written back to the host file, and no redundant files are generated |
| Validator | 0 errors, 47 warnings (all are 'This block has no corresponding sprite' real findings) |

---

## VII. Stability Verification (2026-10-02)

An additional round of stability verification was conducted beyond the functional acceptance testing. New tests, `StabilityTest` (10 items) and `UiSmokeTest` (scene assertion and screenshots on the real JavaFX toolchain), were added. During the process, 7 real defects were identified and fixed - this is precisely what "testing only functionality and not stability" would miss:

| # | Defect | Consequence | Fix |
|---|---|---|---|
| 1 | Restoring the sequence of record IDs when reopening the project disrupts the inline records → host links | **Editing inline bullets again after reopening results in silent loss**; the canvas shows "(missing content)" | First restore the file-type records and establish the old → new ID mapping, then reconnect the inline links and restore the inline IDs |
| 2 | Importing the original text without carrying meta/Bundle | Reopening + saving will format the comments in mod.json and rearrange the bundle | The importer carries the original text, and byte rewrites when not edited |
| 3 | In the zip file, `x/.. /.. /Path traversal in class path | Unpackable to directories outside the project | `safeResolve` normalizes and validates, skips out-of-bounds paths and reports |
| 4 | Save and overwrite the target file directly | Disk write failure during saving leaves a half-written file | Atomic move after writing a temporary file |
| 5 | Pruning logic for saved files deletes files in the "Unknown content subdirectory" | Module files in non-standard directories are lost during saving | Pruning skips project assets |
| 6 | HJSON parsing has no nested depth limit | Malicious files can cause stack overflow | Depth limit 256 |
| 7 | "Add content" only records without creating canvas cards | Canvas has no reaction when the "Add" button is clicked (UI smoke test captured) | New records create nodes and position them simultaneously |

**Verification Conclusion** (All passed, 3 consecutive rounds all green without jitter): 

| Check item | Result |
|---|---|
| Duplicate Save Idempotent | Project tree remains byte-by-byte unchanged |
| Reopen → Save Loop | Byte-by-byte remains unchanged (after fixing #1/#2) |
| Duplicate Packaging ZIP | The ZIP files from two builds have the same bytes (with fixed timestamp) |
| BOM Preservation | Content files with BOM retain the BOM after unpacking and building |
| Damaged Input | False zip / non-zip files throw IOException; empty directories, bad meta are imported normally and alert, content is unaffected |
| Malicious ZIP | Path traversal entries are intercepted and not written to disk |
| Delete a Content Item | Only delete its file and corresponding item in modmaker/, other 955 files have the same bytes |
| Rename | Files are renamed in the original directory, no directory relocation |
| Unknown Directory Files / Newly Added Files after Reopen | All are retained |
| Renamed Content | Verifier reports error |
| Performance | Import + unpacking of 955 files takes 2.4~2.7 seconds |
| UI Smoke Test | Build a project on the real toolchain + add content, the number of scene graph cards is correct, snapshot is 45KB |
| Application Startup | Import 955 file mod and start, stderr has no abnormal stack; after reopening, the canvas has 310 nodes/531 edges, no missing content |

The test fixture (Gray Industrial mod folder) was moved during the verification period. The test has been changed to **Multi-Candidate Automatic Detection** (`TestFixtures`), and the fixture can be found in any common location within the workspace.

---

## VIII. Interaction Layer (2026-10-02 Second Iteration)

The initial version only verified "Rendering Correct". Users found that the canvas was **unable to be interacted with**. The root cause was a typical JavaFX pitfall:

> When mouse events bubble from a child node (label, dot) within the card to the canvas, `event.getX()/getY()` is always relative to the **event source node**, not relative to the canvas. When clicking on a point card, the coordinates are "local coordinates of a certain label within the card", and clicking/selecting/dragging all operations become invalid.

**Fixes and Additions**:

- The interaction layer was unified to use `event.getSceneX/Y() → sceneToLocal → toWorld` three-step conversion; the core interaction was split into
`pressAt / dragTo / releaseAt` (receiving the layout coordinates of the card), and the mouse event layer only performed coordinate conversion - **the entire interaction logic can be directly unit tested**,
without the need for simulating operating system-level input.
- **Out-of-Bounds Clipping**: By default, Regions in JavaFX do not clip child nodes. When dragging the card to a negative world coordinate, it will be drawn above the toolbar/side panel (as reported by users). The canvas was added with a bound rectangle of its own size; and pixel-level regression tests were provided - to reproduce the "top bar + left panel + canvas" layout, take a screenshot and sample the overflow area, asserting that the overflow area remains the panel color.
- **Port Dragging Connections**: Green ID ports on the right edge of the card + gray input ports (with field name labels, up to 4). Dragging an ID port from one card to another (the reverse is also possible), and if the drop point hits the port, it writes the reference: scalar fields write the bare content name (the game will first parse based on the current module), `stack[]` fields append `{item: name, amount: 1}`; duplicate (from, to, field) connections of the same are rejected.
- **Reference Writing/Retracting into Core**: `ReferenceOps.apply/revert/remove` are pure data operations, tested directly with JUnit.
- **Undo/Redo**: Command stack overrides. Adding/removing content, connecting/disconnecting, dragging moving (Ctrl+Z / Ctrl+Y, the toolbar and "Edit" menu are disabled in the synchronization state).
- **Edge Selection and Deletion**: Each edge has a 12px-wide transparent pick curve, clicking selects (red highlighting), Delete disconnects and reverts the field value.
- **Panel Dragging**: Dragging from the content type panel to any position on the canvas; the "Add" button places the new card at the **current viewport center** (originally placed below the lowest card, hardly visible in large projects).

**Verification**: `UiSmokeTest` drives the same `pressAt/dragTo/releaseAt` in the real JavaFX toolchain - asserting node displacement during dragging, ID port dragging to requirements port asserting field writing + edge generation, undo/redo asserting往返； in addition， `ReferenceOpsTest` and `ProjectControllerInteractionTest` (connection/disconnection/movement/deletion × undo/redo × drop) (× connection of the same (from， to， field) are rejected). 67 tests were all green.
Additional fix: UI testing caused the Gradle test process to hang (FX is not a daemon thread). Use `LauncherSessionListener` to close the tool chain when the test session ends.

---

## IX. Interface Localization (2026-10-02 Third Iteration)

In response to the feedback regarding "a large number of original key-value pairs in the attribute panel and node panel", an additional **identifier display label** was added:

- **`Labels` Helper + Two Data Files**: `field-labels_<locale>.properties` (Field Name → Chinese Label, **fully covers all 1258 unique field names in the schema**) and `class-labels_<locale>.properties` (Class Name → Chinese, **fully covers 201 classes**).
Global mapping by name - fields with the same name have the same meaning in all classes; term comparison with the official Chinese localization of the game. Untranslated identifiers will revert to the original key names,
new fields added after schema update will also automatically revert, and incremental translation is possible.
- The original key names are not lost: the **hover tooltip** for schema field rows is in the form of `craftTime — Synthesis Time` (original key + English description),
because the exported file still uses the original key.
- All content types are fully localized (Items/Blocks/Liquids/States/Units/Weather/Blocks/Planets/Teams/Bullets/Unit Instructions/Unit Postures),
used for palettes, toolbar dropdowns, card sub-titles, inspectors' headers. When the base class and type have the same name, the card sub-title no longer repeats
("Block" instead of "Block · Block").
- Fixed text in the inspector (Identifiers/Localization/Field Partition Title, Name (File Name), Patching Existing Content (Patch), (New File), Add Field, Original JSON, etc.) all follow the Messages resource; the column header of the string table is "Key".
- Editable dropdowns for classes use **StringConverter** to display Chinese (This is the rendering path for the editable ComboBox button, buttonCell is not effective),
input accepts both Chinese labels and original class names, and the file written back is always the original class name.

**Not yet localized**: Messages in the verification panel (Core layer generates English sentences, needs to change the Issue to key+args structure for display layer translation), 
English text of the schema field description tooltip, technical lines in the build log.

---

## 10. Adaptive Class (Experimental, 2026-10-02 Fourth Iteration)

According to the source code semantics, different block subclasses have different fields; previously, the class dropdown listed all 155 block classes, and the battery could be transformed into an item bridge at any time.
New **Experimental Adaptive** (tool bar amber switch "Adaptive (Experiment)" with default enabled):

- **Same Family Switching**: `SchemaRegistry.relatedClasses(type, current)` determines "same branch" along the real Java inheritance chain (hierarchyParent, derived from source code scanning, not covered by the simplified `extends` of the compiled schema) - after selecting the battery, the dropdown only provides the chain of classes from Battery → PowerDistributor → PowerBlock, their affiliated subclasses (electricity nodes, etc.) and the base class Block (which can always revert to a general block). Other branches such as ItemBridge and Wall no longer appear.
- **Key Points**: The `extends` in the compiled schema was simplified for field merging (Battery directly written as Block), which can pollute the hierarchy determination. Therefore, `ClassSchema` is split into two fields: `parent` (used for field merging, can be covered by compiled schema) and `hierarchyParent` (the real parent class from source code scanning, dedicated for hierarchy determination); the ancestor chain is truncated at the type root (UnlockableContent, Content are shared by all classes, otherwise the entire type will be judged as the same family).
- **Field Adaptive Display**: This was already implemented by `fieldsOf` (merging along the inheritance chain); the field area now labels the source (Fields of the General Synthesizer (including inheritance)), making "fields change with the class" visible.
- Turning off the switch restores the full 155-class dropdown; manual input of class names still allows for arbitrary switching (dropdown constraints, free input).
- Test: `SchemaRegistryTest.adaptiveClassSwitchingStaysInTheSameBranch` asserts that battery ↔ power node are intercommunicable, battery → item bridge, gun tribe intercommunicable, gun → drill, and the base class gives a full list; `UiSmokeTest.batteryRecordHasNoTransportClassesInItsDropdown`
Assert on the real scene image that the dropdown options recorded by the battery do not include conduit/item bridge/wall/drill/router.
- **Terminology Correction**: The class name labels have been corrected according to the official Chinese package of the game (`bundle_zh_CN.properties`) - item pipeline (Duct, previously wrongly translated as "conduit" and confused with liquid conduit Conduit), router (Router, previously wrongly translated as "splitter"), fluid router/fluid crossover, loader, item/liquid/electricity void (*Void), etc.
- **Displaying the full list at base class time is intentional**: When recording or using a general "block", there is no branch information available to narrow down; after selecting a sub-class (such as battery), the list immediately narrows down to the same family.

---

## XI. Known Limitations and Future Plans

**Achieved**: Importing zip/folder, complete packaging (folder/zip), interactive canvas (zoom/pan/drag/select/connector ports/delete edges/undo/redo/dragging panels), schema-driven checker, string table, validation panel, Chinese/English interface, real-time testing script.

**Not yet achieved** (sorted by value): 
1. **Chinese translation of validation panel messages** (Issue should be changed to key+args structure);
2. **Sprite panel** - Sprites are only retained and validated as assets, and cannot be previewed in cards or imported new images;
3. **New entry for mini-map, sticky notes, and grouping** (Grouping can only be brought in from the JSON Canvas file);
4. **Packaging distribution** (jpackage single-file exe);
5. Asynchronous import for large-scale projects (currently 955 files take approximately 3 seconds, and the operation is executed on the UI thread);
6. Number of connections when selecting (stack currently fixed amount=1, needs to be changed in the checker).