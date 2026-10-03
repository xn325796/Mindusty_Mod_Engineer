package dev.modmaker;

/**
 * JVM entry point.
 *
 * <p>The main class deliberately does not extend Application: the JDK launcher only demands the
 * javafx.graphics module when the class named on the command line is an Application subclass.
 * Going through this class keeps JavaFX working with plain classpath jars.
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        App.main(args);
    }
}
