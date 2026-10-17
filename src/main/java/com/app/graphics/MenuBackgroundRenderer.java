package com.app.graphics;

import com.app.core.Direction;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class MenuBackgroundRenderer {
    private static final int CELL_SIZE = 32;
    private static final String[] BLOCK_NAMES = {
            "arrow", "getter", "bridge", "peg", "power", "not", "or", "and", "xor"
    };
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final Map<String, WritableImage> IMAGE_CACHE = new HashMap<>();

    private static final double FILL_CHANCE = 0.3;
    private static final double ALPHA = 0.18;


    public static void draw(Canvas canvas, ColorConfig colors) {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = canvas.getGraphicsContext2D();

        gc.setGlobalAlpha(1.0);
        gc.setFill(Color.web(colors.getBackground()));
        gc.fillRect(0, 0, w, h);

        gc.setGlobalAlpha(ALPHA);

        int cols = (int) Math.ceil(w / CELL_SIZE) + 1;
        int rows = (int) Math.ceil(h / CELL_SIZE) + 1;

        gc.setStroke(Color.web(colors.getGrid()));
        gc.setLineWidth(1);
        for (int x = 0; x <= cols; x++) {
            double px = x * CELL_SIZE;
            gc.strokeLine(px, 0, px, h);
        }
        for (int y = 0; y <= rows; y++) {
            double py = y * CELL_SIZE;
            gc.strokeLine(0, py, w, py);
        }

        SpriteManager sm = SpriteManager.getInstance();
        String texpack = sm.getCurrentTexturepack();
        Random rng = new Random();

        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                if (rng.nextDouble() < FILL_CHANCE) {
                    String name = BLOCK_NAMES[rng.nextInt(BLOCK_NAMES.length)];
                    Direction dir = DIRECTIONS[rng.nextInt(DIRECTIONS.length)];
                    WritableImage img = getImage(sm, texpack, name, dir, CELL_SIZE);
                    if (img != null) {
                        gc.drawImage(img, x * CELL_SIZE, y * CELL_SIZE);
                    }
                }
            }
        }

        gc.setGlobalAlpha(1.0);
    }

    private static WritableImage getImage(SpriteManager sm, String texpack, String name,
                                          Direction dir, int size) {
        String key = texpack + ":" + name + "|" + dir + "|" + size;
        WritableImage cached = IMAGE_CACHE.get(key);
        if (cached != null) return cached;

        int[] sprite = sm.getSpriteWithDirection(name, size, dir, false);
        if (sprite == null) return null;

        WritableImage img = new WritableImage(size, size);
        PixelWriter pw = img.getPixelWriter();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int color = sprite[y * size + x];
                if ((color & 0xFF000000) != 0) {
                    pw.setArgb(x, y, color);
                }
            }
        }
        IMAGE_CACHE.put(key, img);
        return img;
    }
}