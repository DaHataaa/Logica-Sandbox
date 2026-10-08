package com.app.graphics;

import com.app.Config;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class SpriteLoader {

    public static Sprite loadSprite(String texpackName, String spriteName) {
        Path path = Config.getDataDir()
                .resolve("texturepacks")
                .resolve(texpackName)
                .resolve(spriteName + ".png");

        try {
            File file = path.toFile();
            if (!file.exists()) {
                System.err.println("Sprite not found: " + path);
                return createPlaceholder(spriteName, 16);
            }

            Image image = new Image("file:" + path);
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();

            PixelReader reader = image.getPixelReader();
            int[] pixels = new int[width * height];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = reader.getArgb(x, y);
                }
            }

            return new Sprite(spriteName, pixels, width, height);

        } catch (Exception e) {
            System.err.println("Failed to load sprite: " + path + " (" + e.getMessage() + ")");
            return createPlaceholder(spriteName, 16);
        }
    }

    private static Sprite createPlaceholder(String name, int size) {
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean isEven = (x / 4 + y / 4) % 2 == 0;
                pixels[y * size + x] = isEven ? 0xFFFF00FF : 0xFF000000;
            }
        }
        return new Sprite(name, pixels, size, size);
    }

    public static Map<String, Sprite> loadAllSprites(String texpackName) {
        Map<String, Sprite> sprites = new HashMap<>();

        // ← добавлен "text"
        String[] names = {"arrow", "getter", "bridge", "peg", "power", "not", "or", "and", "xor", "text"};

        for (String name : names) {
            Sprite sprite = loadSprite(texpackName, name);
            sprites.put(name, sprite);
        }

        System.out.println("Loaded " + sprites.size() + " sprites from texpack: " + texpackName);
        return sprites;
    }
}