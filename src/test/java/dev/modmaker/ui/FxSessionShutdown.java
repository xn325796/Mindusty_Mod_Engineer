package dev.modmaker.ui;

import javafx.application.Platform;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Stops the JavaFX toolkit once the whole test session in this JVM is done. The FX application
 * thread is non-daemon, so without this the Gradle test worker never terminates after UI tests.
 */
public class FxSessionShutdown implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        // Nothing: the toolkit starts lazily inside the tests that need it.
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        if (UiSmokeTest.toolkitWasStarted()) {
            Platform.runLater(Platform::exit);
        }
    }
}
