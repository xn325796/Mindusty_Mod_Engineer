# Mindustry ModMaker

Node-based canvas-style Mindustry mod builder (Java + JavaFX desktop application).

Arrange content cards like on a whiteboard and express references and technology tree through connections; **it can read in existing mod packages for further editing**,
**it can output complete mods that can be loaded by the game** (the file bytes remain unchanged as is).

**If you take over the development/delivery, please read  [receiving_EN.md](receiving.md)** first** - self-contained distilled document: environment, architecture, format contract, verification protocol, trap memo.
## Run

**The simplest way: double-click `run.bat`** (automatically locates JDK, automatically generates schema on the first run, and a window appears in about 10 seconds).

You can also start with parameters to directly observe the effect of importing real mods:

```bat
Start ModMaker.bat --import=..\Gray Industry --projectDir=build\my-import
```

Command line mode (equivalent):

```bash
./gradlew run      # Start the application (will download JavaFX via the internet for the first time)
./gradlew test     # 57 tests: formatting rules, error handling parsing, real device module round-trip, stability verification
```

Generate schema before the first use (scan the source code of Mindustry; the startup script will do it automatically):

```bash
./gradlew schemaBootstrap
```

- **Create New Project** → Select a directory and you will get `<Directory>/<Mod Name>/`: This is the complete skeleton of the mod folder.
- **Import Mod Package** → Select `.zip` or an existing mod folder. The application will unpack it completely into an editable project (content, sprites, scripts, maps, bundle all retained), and arrange them by type on the canvas and connect them according to the reference relationships.
- **Edit** → Click the card, on the right is the schema-driven field editor (number/color/enum/content reference/stacking/raw JSON), and the string table at the bottom is edited by "key × language" for names and descriptions.
- **Build** → Output to a folder or create a `.zip` (single-layer packaged directory, decompressing results in `mods/<Name>/`).
- **Playtest** → Put the mod into a temporary data directory and start Mindustry (`MINDUSTRY_DATA_DIR`, do not touch the real save file), exit and read the game log to report any content errors.

## Project Structure

```
src/main/java/dev/modmaker/
core/json/      Fault-tolerant HJSON reading, standardized JSON writing, JSON path
core/fmt/       ContentType (9 types of file-able), naming rules, sprite area, bundle encoding and decoding
core/model/     ModProject / ContentRecord / Board / CanvasNode / Edge / StringTable / AssetRef
core/schema/    SchemaRegistry (optimized + source code scanning + original content list)
core/io/        Reading mod package → Creating project → Saving disk → Building/packaging, automatic layout, JSON Canvas
core/validate/  Validator (duplicate names/illegal names/missing type/missing references/missing sprites/orphan nodes)
tools/          SchemaBootstrap (scanning Mindustry source code to generate schema and main list)
ui/             AppShell, NodeCanvasPane, ContentCard, RecordInspector, StringsPane, IssuesPane, GameLauncher
src/test/java/    Formatting rules, fault-tolerant parsing, imports, round-trip, creation, stability, game startup, UI smoke testing
schemas/          schema.json (optimized) + fields.json / vanilla-content.json (generated content)
```