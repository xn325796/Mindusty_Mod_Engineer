package dev.modmaker.core.fmt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which workspace edits which content type. */
class WorkspaceTest {

    @Test
    void routesContentTypesToWorkspaces() {
        assertEquals(Workspace.assets, Workspace.of(ContentType.item));
        assertEquals(Workspace.assets, Workspace.of(ContentType.liquid));
        assertEquals(Workspace.cosmos, Workspace.of(ContentType.planet));
        assertEquals(Workspace.cosmos, Workspace.of(ContentType.sector));
        assertEquals(Workspace.canvas, Workspace.of(ContentType.block));
        assertEquals(Workspace.canvas, Workspace.of(ContentType.unit));
        // Statuses, weather, teams and inline-only types stay on the canvas: they are flow
        // participants (turret ammo applies statuses) rather than standalone assets.
        assertEquals(Workspace.canvas, Workspace.of(ContentType.status));
        assertEquals(Workspace.canvas, Workspace.of(ContentType.weather));
        assertEquals(Workspace.canvas, Workspace.of(ContentType.team));
        assertEquals(Workspace.canvas, Workspace.of(ContentType.bullet));
    }
}
