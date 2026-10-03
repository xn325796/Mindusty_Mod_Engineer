package dev.modmaker;

import dev.modmaker.core.schema.SchemaRegistry;
import dev.modmaker.ui.AppShell;
import dev.modmaker.ui.Messages;
import dev.modmaker.ui.ProjectController;
import dev.modmaker.ui.Snapshotter;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.Map;

public class App extends Application {

    @Override
    public void start(Stage stage) {
        SchemaRegistry registry;
        try {
            registry = SchemaRegistry.loadDefault();
        } catch (Exception failure) {
            new Alert(Alert.AlertType.ERROR,
                "Cannot load the schemas: " + failure.getMessage()
                    + "\nRun `gradlew schemaBootstrap` first.").showAndWait();
            Platform.exit();
            return;
        }

        ProjectController controller = new ProjectController(registry);
        AppShell shell = new AppShell(controller);
        Scene scene = new Scene(shell, 1500, 940);
        scene.getStylesheets().add(App.class.getResource("/app.css").toExternalForm());

        stage.setTitle(Messages.t("app.title"));
        stage.setScene(scene);
        stage.show();

        loadFromArguments(controller, shell, getParameters().getNamed());

        String workspace = getParameters().getNamed().get("workspace");
        if (workspace != null) {
            try {
                shell.showWorkspace(dev.modmaker.core.fmt.Workspace.valueOf(workspace));
            } catch (IllegalArgumentException unknown) {
                System.err.println("unknown workspace: " + workspace);
            }
        }

        String snapshot = getParameters().getNamed().get("snapshot");
        if (snapshot != null) {
            writeSnapshotThenExit(scene, Path.of(snapshot));
        }
    }

    /**
     * Command line entry points used for automated checks:
     * {@code --open=<project dir>}, {@code --import=<mod zip or folder>} with optional
     * {@code --projectDir=<where to unpack>}, and {@code --snapshot=<png>}.
     */
    private void loadFromArguments(ProjectController controller, AppShell shell, Map<String, String> named) {
        try {
            if (named.containsKey("import")) {
                Path source = Path.of(named.get("import")).toAbsolutePath();
                Path destination = named.containsKey("projectDir")
                    ? Path.of(named.get("projectDir")).toAbsolutePath()
                    : source.getParent();
                controller.importPackage(source, destination);
                // Fitting needs the canvas to have been laid out once.
                Platform.runLater(shell::justLoaded);
            } else if (named.containsKey("open")) {
                controller.openProject(Path.of(named.get("open")).toAbsolutePath());
                Platform.runLater(shell::justLoaded);
            }
        } catch (Exception failure) {
            failure.printStackTrace();
            controller.log("could not load from the command line: " + failure);
        }
    }

    private void writeSnapshotThenExit(Scene scene, Path target) {
        PauseTransition settle = new PauseTransition(Duration.millis(2500));
        settle.setOnFinished(event -> {
            try {
                java.nio.file.Files.createDirectories(target.toAbsolutePath().getParent());
                Snapshotter.write(scene, target);
                System.out.println("snapshot: " + target.toAbsolutePath());
            } catch (Exception error) {
                error.printStackTrace();
                Platform.exit();
                System.exit(1);
            }
            Platform.exit();
        });
        settle.play();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
