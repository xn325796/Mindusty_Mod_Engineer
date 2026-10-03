package dev.modmaker.core.validate;

/**
 * One finding from {@link Validator}. Levels match what the UI shows: errors are things the game
 * will refuse or misread, warnings are things it tolerates but the author probably did not intend.
 */
public record Issue(Level level, String message, String contentId) {

    public enum Level {
        error,
        warning
    }

    public static Issue error(String message) {
        return new Issue(Level.error, message, null);
    }

    public static Issue error(String message, String contentId) {
        return new Issue(Level.error, message, contentId);
    }

    public static Issue warning(String message) {
        return new Issue(Level.warning, message, null);
    }

    public static Issue warning(String message, String contentId) {
        return new Issue(Level.warning, message, contentId);
    }

    public boolean isError() {
        return level == Level.error;
    }
}
