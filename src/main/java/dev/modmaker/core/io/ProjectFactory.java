package dev.modmaker.core.io;

import dev.modmaker.core.fmt.Ids;
import dev.modmaker.core.model.Board;
import dev.modmaker.core.model.ModProject;

import java.io.IOException;
import java.nio.file.Path;

/** Creates an empty project: metadata, one board, no content. */
public final class ProjectFactory {

    private ProjectFactory() {
    }

    public static ModProject create(String modName, Path root) throws IOException {
        ModProject project = new ModProject();
        project.meta.name = modName;
        project.meta.displayName = modName;
        project.meta.author = "";
        project.meta.version = "1.0";
        project.meta.minGameVersion = "146";
        project.metaFile = "mod.hjson";
        project.metaDirty = true;

        Board board = new Board();
        board.id = Ids.uid("b");
        board.name = "Main";
        board.file = "main.canvas";
        project.boards.add(board);

        project.root = root;
        ProjectIo.save(project, root);
        return project;
    }
}
