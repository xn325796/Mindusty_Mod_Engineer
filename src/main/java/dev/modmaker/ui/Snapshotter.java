package dev.modmaker.ui;

import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.IntBuffer;
import java.nio.file.Path;

/**
 * Renders a Scene to a PNG. This replaces the CDP screenshots the Electron build relied on: the
 * build can snapshot the UI and the resulting image is inspected as a normal file.
 */
public final class Snapshotter {
    private Snapshotter() {
    }

    public static BufferedImage write(Scene scene, Path target) throws Exception {
        WritableImage image = scene.snapshot(null);
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();

        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        WritablePixelFormat<IntBuffer> format = PixelFormat.getIntArgbInstance();
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            reader.getPixels(0, y, width, 1, format, row, 0, width);
            buffered.setRGB(0, y, width, 1, row, 0, width);
        }
        if (target != null) {
            java.nio.file.Files.createDirectories(target.toAbsolutePath().getParent());
            ImageIO.write(buffered, "png", target.toFile());
        }
        return buffered;
    }
}
