package dev.modmaker.ui;

import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.PlanetGrids;
import dev.modmaker.core.fmt.PlanetMeshes;
import dev.modmaker.core.fmt.PlanetOrbits;
import dev.modmaker.core.fmt.Workspace;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The planet preview workbench, one canvas per workflow:
 *
 * <ul>
 *   <li>区块网格 - the sector grid with occupied indexes (previous behavior);</li>
 *   <li>星球外观 - the planet as the game builds it: MeshBuilder.buildHex tiles displaced and
 *       colored through the ported Simplex noise, clouds from the cloudMesh layers, atmosphere
 *       rim;</li>
 *   <li>星系位置 - a schematic of the solar system: the root star, vanilla siblings for context,
 *       the planet and its moons on their computed orbit radii.</li>
 * </ul>
 *
 * All three share the same drag-to-rotate view. Everything is derived from the record's JSON, so
 * edits show up without launching the game.
 */
public final class PlanetPreview extends VBox {

    private static final int SIZE = 360;

    private final Canvas gridCanvas = new Canvas(SIZE, SIZE);
    private final Canvas appearanceCanvas = new Canvas(SIZE, SIZE);
    private final Canvas orbitCanvas = new Canvas(SIZE, SIZE);
    private final javafx.scene.control.TabPane tabPane = new javafx.scene.control.TabPane();
    private final Label info = new Label();
    private final Label hint = new Label(Messages.t("cosmos.preview.hint"));

    private ModProject project;
    private int sectorSize = -1;
    private PlanetGrids.Grid grid;
    private PlanetMeshes.MeshModel surface;
    private final List<CloudLayer> clouds = new ArrayList<>();
    private PlanetMeshes.Rgb atmosphere;
    private String meshTypeInfo = "";
    private List<PlanetOrbits.Body> bodies = List.of();
    private Map<Integer, List<ContentRecord>> occupied = Map.of();
    private ContentRecord highlight;
    private boolean rendered;
    private double yaw = 0.7;
    private double pitch = 0.35;
    private double dragX;
    private double dragY;

    private record CloudLayer(PlanetMeshes.MeshModel model, PlanetMeshes.Rgb color) {
    }

    PlanetPreview() {
        getStyleClass().add("panel");
        info.getStyleClass().add("muted");
        info.setWrapText(true);
        hint.getStyleClass().add("muted");
        hint.setText(Messages.t("cosmos.preview.hint"));

        attachRotation(gridCanvas);
        attachRotation(appearanceCanvas);
        attachRotation(orbitCanvas);

        tabPane.getTabs().setAll(
            tab(Messages.t("cosmos.preview.tab.grid"), gridCanvas),
            tab(Messages.t("cosmos.preview.tab.appearance"), appearanceCanvas),
            tab(Messages.t("cosmos.preview.tab.system"), orbitCanvas));
        tabPane.getStyleClass().add("preview-tabs");
        tabPane.setTabClosingPolicy(javafx.scene.control.TabPane.TabClosingPolicy.UNAVAILABLE);

        setSpacing(6);
        getChildren().addAll(tabPane, info, hint);
        drawAll();
    }

    private Tab tab(String title, Canvas canvas) {
        Tab tab = new Tab(title, canvas);
        tab.setClosable(false);
        tab.setOnSelectionChanged(event -> {
            if (tab.isSelected()) {
                drawAll();
            }
        });
        return tab;
    }

    private void attachRotation(Canvas canvas) {
        canvas.setOnMousePressed(event -> {
            dragX = event.getX();
            dragY = event.getY();
        });
        canvas.setOnMouseDragged(event -> {
            yaw += (event.getX() - dragX) * 0.01;
            pitch = Math.max(-1.4, Math.min(1.4, pitch + (event.getY() - dragY) * 0.01));
            dragX = event.getX();
            dragY = event.getY();
            drawAll();
        });
    }

    /**
     * Full update from the selected record: resolves the planet (a sector resolves to its planet,
     * vanilla planets included), then builds the grid state, appearance model and orbit bodies.
     */
    void setSource(ContentRecord selected, ModProject project) {
        if (selected == null || project == null) {
            clear();
            return;
        }
        this.project = project;
        this.highlight = selected.type == dev.modmaker.core.fmt.ContentType.sector ? selected : null;

        ContentRecord planet;
        String bare;
        if (selected.type == dev.modmaker.core.fmt.ContentType.planet) {
            planet = selected;
            bare = selected.name;
        } else if (selected.type == dev.modmaker.core.fmt.ContentType.sector) {
            bare = barePlanetName(PlanetGrids.sectorPlanet(selected));
            planet = planetByName(bare);
        } else {
            clear();
            return;
        }

        int size;
        if (planet != null) {
            size = PlanetGrids.intField(planet, "sectorSize", 0);
        } else {
            Integer vanillaSize = PlanetGrids.VANILLA_SIZES.get(bare.toLowerCase(java.util.Locale.ROOT));
            size = vanillaSize == null ? 0 : vanillaSize;
        }
        this.sectorSize = size;
        this.grid = size > 0 ? PlanetGrids.create(size) : null;
        this.occupied = PlanetGrids.sectorsByIndex(project, bare, size);

        // appearance model from the planet record's mesh/cloudMesh JSON (vanilla planets have no
        // record, so they fall back to the default mesh look)
        JsonObject meshConfig = planet == null ? null
            : (planet.fields.get("mesh") != null && planet.fields.get("mesh").isJsonObject()
                ? planet.fields.get("mesh").getAsJsonObject() : null);
        JsonObject cloudConfig = planet == null ? null
            : (planet.fields.get("cloudMesh") != null && planet.fields.get("cloudMesh").isJsonObject()
                ? planet.fields.get("cloudMesh").getAsJsonObject() : null);
        float radius = planet == null ? 1f : radiusOf(planet);

        meshTypeInfo = meshConfig == null ? "" : PlanetMeshes.stringOr(meshConfig, "type", "NoiseMesh");
        rebuildAppearance(meshConfig, cloudConfig, radius, size);
        this.bodies = PlanetOrbits.systemOf(project, planet, planet == null ? bare : null);
        this.rendered = false;
        drawAll();
    }

    private void rebuildAppearance(JsonObject meshConfig, JsonObject cloudConfig, float radius,
        int size) {
        clouds.clear();
        atmosphere = null;
        if (size <= 0) {
            surface = null;
            return;
        }
        PlanetMeshes.Mesher mesher = meshConfig == null
            ? PlanetMeshes.defaultMesher()
            : PlanetMeshes.mesherFromConfig(meshConfig);
        surface = PlanetMeshes.buildModel(size, radius, mesher,
            meshConfig != null && "NoiseMesh".equals(PlanetMeshes.stringOr(meshConfig, "type", "NoiseMesh"))
                ? 0.2f : 0f);
        for (PlanetMeshes.Layer layer : PlanetMeshes.cloudLayersFromConfig(cloudConfig)) {
            clouds.add(new CloudLayer(
                PlanetMeshes.buildModel(size, radius, layer.mesher(), layer.intensity()),
                null));
        }
        if (meshConfig != null && meshConfig.get("atmosphereColor") != null
            && meshConfig.get("atmosphereColor").isJsonPrimitive()) {
            try {
                atmosphere = PlanetMeshes.Rgb.of(meshConfig.get("atmosphereColor").getAsString());
            } catch (NumberFormatException ignored) {
                atmosphere = null;
            }
        }
    }

    void clear() {
        sectorSize = -1;
        grid = null;
        surface = null;
        clouds.clear();
        atmosphere = null;
        bodies = List.of();
        occupied = Map.of();
        highlight = null;
        rendered = false;
        drawAll();
    }

    boolean rendered() {
        return rendered;
    }

    int previewCapacity() {
        return PlanetGrids.capacity(Math.max(sectorSize, 0));
    }

    int previewOccupiedCount() {
        return occupied.size();
    }

    boolean appearanceRendered() {
        return surface != null;
    }

    int systemBodies() {
        return bodies.size();
    }

    /** Selects one of the three preview tabs (test hook): 0 grid, 1 appearance, 2 system. */
    void selectPreviewTab(int index) {
        if (index >= 0 && index < tabPane.getTabs().size()) {
            tabPane.getSelectionModel().select(index);
        }
    }

    /**
     * Counts pixels that differ from the panel background on one tab's canvas (test hook) - guards
     * against "renders but blank" regressions. 0 grid, 1 appearance, 2 system.
     */
    long nonBackgroundPixels(int tabIndex) {
        Canvas canvas = switch (tabIndex) {
            case 0 -> gridCanvas;
            case 1 -> appearanceCanvas;
            default -> orbitCanvas;
        };
        javafx.scene.image.WritableImage image = canvas.snapshot(null, null);
        javafx.scene.image.PixelReader reader = image.getPixelReader();
        long count = 0;
        for (int y = 0; y < (int) image.getHeight(); y += 3) {
            for (int x = 0; x < (int) image.getWidth(); x += 3) {
                int argb = reader.getArgb(x, y);
                int r = argb >> 16 & 0xFF;
                int g = argb >> 8 & 0xFF;
                int b = argb & 0xFF;
                // background is #101014
                if (Math.abs(r - 0x10) + Math.abs(g - 0x10) + Math.abs(b - 0x14) > 12) {
                    count++;
                }
            }
        }
        return count;
    }

    // --- drawing ---------------------------------------------------------------------------------

    private void drawAll() {
        drawGrid();
        drawAppearance();
        drawOrbit();
    }

    private void drawGrid() {
        var g = gridCanvas.getGraphicsContext2D();
        paintBackground(g);
        if (sectorSize < 0) {
            info.setText(Messages.t("cosmos.preview.none"));
            return;
        }
        int capacity = PlanetGrids.capacity(sectorSize);
        if (grid == null) {
            info.setText(Messages.t("cosmos.preview.gridless"));
            return;
        }

        double scale = Math.min(gridCanvas.getWidth(), gridCanvas.getHeight()) * 0.42;
        double cx = gridCanvas.getWidth() / 2;
        double cy = gridCanvas.getHeight() / 2;
        g.setStroke(Color.web("#3f3f4a"));
        g.strokeOval(cx - scale, cy - scale, scale * 2, scale * 2);

        for (PlanetGrids.Tile tile : grid.tiles) {
            drawGridTile(g, tile, scale, cx, cy);
        }

        for (Map.Entry<Integer, List<ContentRecord>> entry : occupied.entrySet()) {
            int index = entry.getKey();
            if (index >= grid.tiles.length) {
                continue;
            }
            drawTileHighlight(g, grid.tiles[index], index,
                highlight != null && entry.getValue().contains(highlight));
        }

        rendered = true;
        info.setText(Messages.t("cosmos.preview.summary", capacity, occupied.size(), sectorSize)
            + (occupied.size() > 0 && occupied.size() < grid.tiles.length
                ? "" : ""));
    }

    private void drawGridTile(javafx.scene.canvas.GraphicsContext g, PlanetGrids.Tile tile, double scale, double cx, double cy) {
        double[] normal = rotate(tile.v.x, tile.v.y, tile.v.z);
        boolean front = normal[2] > 0;
        double[] xs = new double[tile.corners.length];
        double[] ys = new double[tile.corners.length];
        for (int k = 0; k < tile.corners.length; k++) {
            PlanetGrids.Corner corner = tile.corners[k];
            double[] p = rotate(corner.v.x, corner.v.y, corner.v.z);
            xs[k] = cx + p[0] * scale;
            ys[k] = cy - p[1] * scale;
        }
        if (front) {
            g.setFill(Color.web("#252a36"));
            g.fillPolygon(xs, ys, xs.length);
            g.setStroke(Color.web("#454b5a"));
            g.setLineWidth(1);
            g.strokePolygon(xs, ys, xs.length);
        } else {
            g.setStroke(Color.web("#1c202a"));
            g.strokePolygon(xs, ys, xs.length);
        }
    }

    private void drawTileHighlight(javafx.scene.canvas.GraphicsContext g, PlanetGrids.Tile tile, int index, boolean selected) {
        double[] normal = rotate(tile.v.x, tile.v.y, tile.v.z);
        double scale = Math.min(gridCanvas.getWidth(), gridCanvas.getHeight()) * 0.42;
        double cx = gridCanvas.getWidth() / 2;
        double cy = gridCanvas.getHeight() / 2;
        double[] xs = new double[tile.corners.length];
        double[] ys = new double[tile.corners.length];
        double cxTile = 0;
        double cyTile = 0;
        for (int k = 0; k < tile.corners.length; k++) {
            PlanetGrids.Corner corner = tile.corners[k];
            double[] p = rotate(corner.v.x, corner.v.y, corner.v.z);
            xs[k] = cx + p[0] * scale;
            ys[k] = cy - p[1] * scale;
            cxTile += xs[k] / xs.length;
            cyTile += ys[k] / ys.length;
        }
        g.setStroke(selected ? Color.WHITE : Color.web("#a78bfa"));
        g.setLineWidth(selected ? 2 : 1.2);
        g.strokePolygon(xs, ys, xs.length);
        if (normal[2] > 0) {
            g.setFill(selected ? Color.web("#7c3aed") : Color.web("#8b5cf6", 0.75));
            g.fillPolygon(xs, ys, xs.length);
            g.setFill(Color.WHITE);
            g.setFont(Font.font("System", FontWeight.BOLD, 12));
            g.fillText(String.valueOf(index), cxTile - 5, cyTile + 4);
        }
    }

    private void drawAppearance() {
        var g = appearanceCanvas.getGraphicsContext2D();
        paintBackground(g);
        if (surface == null) {
            info.setText(sectorSize >= 0 ? Messages.t("cosmos.preview.gridless") : "");
            return;
        }
        double scale = Math.min(appearanceCanvas.getWidth(), appearanceCanvas.getHeight()) * 0.42
            / maxSurfaceLength();
        double cx = appearanceCanvas.getWidth() / 2;
        double cy = appearanceCanvas.getHeight() / 2;
        double[] light = normalize(0.35, 0.3, 1);

        // atmosphere rim first, so the surface sits on top
        if (atmosphere != null) {
            g.setStroke(Color.color(atmosphere.r(), atmosphere.g(), atmosphere.b(), 0.5));
            g.setLineWidth(6);
            g.strokeOval(cx - scale, cy - scale, scale * 2, scale * 2);
        }

        for (int i = 0; i < surface.tileCorners.size(); i++) {
            if (surface.tileSkipped.get(i)) {
                continue;
            }
            double[] corners = surface.tileCorners.get(i);
            int points = corners.length / 3;
            double[] xs = new double[points];
            double[] ys = new double[points];
            double centroidZ = 0;
            for (int k = 0; k < points; k++) {
                double[] p = rotate(corners[k * 3], corners[k * 3 + 1], corners[k * 3 + 2]);
                xs[k] = cx + p[0] * scale;
                ys[k] = cy - p[1] * scale;
                centroidZ += p[2];
            }
            if (centroidZ / points <= 0) {
                continue; // back face
            }
            PlanetMeshes.Rgb color = surface.tileColors.get(i);
            double shade = 0.55 + 0.45 * lambert(corners, light);
            g.setFill(color(color.r() * shade, color.g() * shade, color.b() * shade, 1));
            g.fillPolygon(xs, ys, points);
            g.setStroke(Color.color(0, 0, 0, 0.25));
            g.strokePolygon(xs, ys, points);
        }

        // cloud layers on top
        for (CloudLayer cloud : clouds) {
            for (int i = 0; i < cloud.model().tileCorners.size(); i++) {
                if (cloud.model().tileSkipped.get(i)) {
                    continue;
                }
                double[] corners = cloud.model().tileCorners.get(i);
                int points = corners.length / 3;
                double[] xs = new double[points];
                double[] ys = new double[points];
                double centroidZ = 0;
                for (int k = 0; k < points; k++) {
                    double[] p = rotate(corners[k * 3], corners[k * 3 + 1], corners[k * 3 + 2]);
                    xs[k] = cx + p[0] * scale;
                    ys[k] = cy - p[1] * scale;
                    centroidZ += p[2];
                }
                if (centroidZ / points <= 0) {
                    continue;
                }
                PlanetMeshes.Rgb color = cloud.model().tileColors.get(i);
                if (color == null) {
                    continue;
                }
                g.setFill(color(color.r(), color.g(), color.b(), color.a()));
                g.fillPolygon(xs, ys, points);
            }
        }

        // occupied outlines for orientation
        g.setStroke(Color.web("#a78bfa"));
        g.setLineWidth(1.2);
        for (Integer index : occupied.keySet()) {
            if (index >= surface.tileIndexes.size()) {
                continue;
            }
            int modelIndex = surface.tileIndexes.indexOf(index);
            if (modelIndex < 0 || surface.tileSkipped.get(modelIndex)) {
                continue;
            }
            double[] corners = surface.tileCorners.get(modelIndex);
            int points = corners.length / 3;
            double[] xs = new double[points];
            double[] ys = new double[points];
            double centroidZ = 0;
            for (int k = 0; k < points; k++) {
                double[] p = rotate(corners[k * 3], corners[k * 3 + 1], corners[k * 3 + 2]);
                xs[k] = cx + p[0] * scale;
                ys[k] = cy - p[1] * scale;
                centroidZ += p[2];
            }
            if (centroidZ / points > 0) {
                g.strokePolygon(xs, ys, points);
            }
        }

        info.setText(Messages.t("cosmos.preview.appearance.info",
            meshTypeInfo.isEmpty() ? "NoiseMesh" : meshTypeInfo));
    }

    private void drawOrbit() {
        var g = orbitCanvas.getGraphicsContext2D();
        paintBackground(g);
        if (bodies.isEmpty()) {
            return;
        }
        double maxOrbit = 1;
        for (PlanetOrbits.Body body : bodies) {
            maxOrbit = Math.max(maxOrbit, body.orbitRadius());
        }
        double scale = Math.min(orbitCanvas.getWidth(), orbitCanvas.getHeight()) * 0.42 / maxOrbit;
        double cx = orbitCanvas.getWidth() / 2;
        double cy = orbitCanvas.getHeight() / 2;

        // orbit rings
        g.setStroke(Color.web("#3f3f4a"));
        g.setLineWidth(1);
        g.setLineDashes(4, 4);
        for (PlanetOrbits.Body body : bodies) {
            if (body.orbitRadius() > 0) {
                double r = body.orbitRadius() * scale;
                g.strokeOval(cx - r, cy - r, r * 2, r * 2);
            }
        }
        g.setLineDashes();

        // bodies on deterministic angles
        int index = 0;
        for (PlanetOrbits.Body body : bodies) {
            if (body.star()) {
                double r = Math.max(8, body.radius() / maxOrbit * scale);
                g.setFill(Color.web(PlanetOrbits.SUN_COLORS[2]));
                g.fillOval(cx - r, cy - r, r * 2, r * 2);
                label(g, body.name(), cx, cy - r - 6, body.selected());
                index++;
                continue;
            }
            double angle = 0.6 + index * Math.PI * 2 / 5;
            index++;
            double px = cx + Math.cos(angle) * body.orbitRadius() * scale;
            double py = cy - Math.sin(angle) * body.orbitRadius() * scale;
            double dot = Math.max(4, Math.min(10, body.radius() / maxOrbit * scale * 3));
            Color fill = body.color() != null
                ? Color.web(body.color())
                : (body.selected() ? Color.web("#8b5cf6") : Color.web("#8f8f9e"));
            g.setFill(fill);
            g.fillOval(px - dot, py - dot, dot * 2, dot * 2);
            if (body.selected()) {
                g.setStroke(Color.WHITE);
                g.setLineWidth(1.5);
                g.strokeOval(px - dot - 3, py - dot - 3, dot * 2 + 6, dot * 2 + 6);
            }
            label(g, body.name() + (body.vanilla() ? " (" + Messages.t("cosmos.vanilla") + ")" : ""),
                px, py - dot - 5, body.selected());
        }
        info.setText(Messages.t("cosmos.preview.system.info", bodies.size()));
    }

    private void label(javafx.scene.canvas.GraphicsContext g, String text, double x, double y, boolean emphasized) {
        g.setFill(emphasized ? Color.WHITE : Color.web("#9a9aa8"));
        g.setFont(Font.font("System", emphasized ? FontWeight.BOLD : FontWeight.NORMAL, 11));
        g.fillText(text, x - 18, y);
    }

    // --- small helpers --------------------------------------------------------------------------

    private void paintBackground(javafx.scene.canvas.GraphicsContext g) {
        g.clearRect(0, 0, g.getCanvas().getWidth(), g.getCanvas().getHeight());
        g.setFill(Color.web("#101014"));
        g.fillRect(0, 0, g.getCanvas().getWidth(), g.getCanvas().getHeight());
    }

    private double[] rotate(double x, double y, double z) {
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        double x1 = x * cosYaw + z * sinYaw;
        double z1 = -x * sinYaw + z * cosYaw;
        double y2 = y * cosPitch - z1 * sinPitch;
        double z2 = y * sinPitch + z1 * cosPitch;
        return new double[] {x1, y2, z2};
    }

    private double lambert(double[] corners, double[] light) {
        int points = corners.length / 3;
        // face normal from the first three corners (rotated space)
        double[] p0 = rotate(corners[0], corners[1], corners[2]);
        double[] p1 = rotate(corners[3], corners[4], corners[5]);
        double[] p2 = rotate(corners[6], corners[7], corners[8]);
        double ax = p1[0] - p0[0], ay = p1[1] - p0[1], az = p1[2] - p0[2];
        double bx = p2[0] - p0[0], by = p2[1] - p0[1], bz = p2[2] - p0[2];
        double nx = ay * bz - az * by;
        double ny = az * bx - ax * bz;
        double nz = ax * by - ay * bx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-9) {
            return 0.5;
        }
        double dot = (nx * light[0] + ny * light[1] + nz * light[2]) / length;
        return Math.abs(dot);
    }

    private double[] normalize(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return new double[] {x / length, y / length, z / length};
    }

    private double maxSurfaceLength() {
        double max = 1;
        for (double[] corners : surface.tileCorners) {
            for (int k = 0; k < corners.length; k += 3) {
                double length = Math.sqrt(corners[k] * corners[k] + corners[k + 1] * corners[k + 1]
                    + corners[k + 2] * corners[k + 2]);
                max = Math.max(max, length);
            }
        }
        return max;
    }

    private static Color color(double r, double g, double b, double a) {
        return Color.color(Math.max(0, Math.min(1, r)), Math.max(0, Math.min(1, g)),
            Math.max(0, Math.min(1, b)), Math.max(0, Math.min(1, a)));
    }

    private String barePlanetName(String target) {
        String internal = project == null ? "" : project.internalName();
        return target.startsWith(internal + "-") ? target.substring(internal.length() + 1) : target;
    }

    private ContentRecord planetByName(String bare) {
        if (project == null) {
            return null;
        }
        for (ContentRecord record : project.contents) {
            if (record.type == dev.modmaker.core.fmt.ContentType.planet && !record.isInline()
                && (record.name.equals(bare)
                    || record.fullName(project.internalName()).equals(bare))) {
                return record;
            }
        }
        return null;
    }

    private float radiusOf(ContentRecord planet) {
        var element = planet.fields.get("radius");
        if (element != null && element.isJsonPrimitive()) {
            try {
                return element.getAsFloat();
            } catch (NumberFormatException ignored) {
                return 1f;
            }
        }
        return 1f;
    }
}
