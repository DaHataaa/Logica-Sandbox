package com.app.graphics;

import com.app.core.Direction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SpriteManager {
    private static SpriteManager instance;
    private SpriteCache cache;
    private TexturepackManager texturepackManager;
    private int[] fallbackSprite;
    private String currentTexturepack;

    private final Map<String, int[]> rotatedCache = new HashMap<>();

    private SpriteManager() {
        this.texturepackManager = new TexturepackManager();
        this.currentTexturepack = texturepackManager.getCurrentTexturepack();
        reloadSprites();
        fallbackSprite = createFallbackSprite(16);
    }

    public void reloadSprites() {
        String texpack = texturepackManager.getCurrentTexturepack();
        Map<String, Sprite> originalSprites = SpriteLoader.loadAllSprites(texpack);
        this.cache = new SpriteCache(originalSprites, texturepackManager.getColors());
        this.currentTexturepack = texpack;
        this.rotatedCache.clear();
        System.out.println("Sprites reloaded from texturepack: " + texpack);
    }

    public void changeTexturepack(String name) {
        texturepackManager.loadTexturepack(name);
        reloadSprites();
    }

    public List<String> getAvailableTexturepacks() {
        return texturepackManager.getAvailableTexturepacks();
    }

    public String getCurrentTexturepack() {
        return currentTexturepack;
    }

    public ColorConfig getColors() {
        return texturepackManager.getColors();
    }

    public static SpriteManager getInstance() {
        if (instance == null) {
            instance = new SpriteManager();
        }
        return instance;
    }

    public int[] getSprite(String name, int targetSize) {
        return getSprite(name, targetSize, false);
    }

    public int[] getSprite(String name, int targetSize, boolean signalOn) {
        if (name == null) return fallbackSprite;
        int[] sprite = cache.getSprite(name, targetSize, signalOn);
        return sprite != null ? sprite : fallbackSprite;
    }

    public int[] getSpriteWithDirection(String name, int targetSize, Direction direction, boolean signalOn) {
        if (direction == Direction.UP || targetSize <= 0) {
            return getSprite(name, targetSize, signalOn);
        }
        if (name == null) return fallbackSprite;

        String key = name + "|" + targetSize + "|" + direction + "|" + signalOn;
        int[] cached = rotatedCache.get(key);
        if (cached != null) return cached;

        int[] original = getSprite(name, targetSize, signalOn);
        if (original == null) return fallbackSprite;

        int[] rotated = rotateSprite(original, targetSize, direction);
        rotatedCache.put(key, rotated);
        return rotated;
    }

    public int[] getRotatedSprite(String name, int targetSize, Direction direction) {
        return getSpriteWithDirection(name, targetSize, direction, false);
    }

    /** Вызывать при смене размера клетки (зум), чтобы кэш не пух. */
    public void trimRotatedCache() {
        rotatedCache.clear();
    }

    private int[] rotateSprite(int[] sprite, int size, Direction direction) {
        if (sprite == null) return null;
        if (sprite.length != size * size) return sprite;

        int[] rotated = new int[size * size];

        switch (direction) {
            case RIGHT:
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        rotated[x * size + (size - 1 - y)] = sprite[y * size + x];
                    }
                }
                break;
            case DOWN:
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        rotated[(size - 1 - y) * size + (size - 1 - x)] = sprite[y * size + x];
                    }
                }
                break;
            case LEFT:
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        rotated[(size - 1 - x) * size + y] = sprite[y * size + x];
                    }
                }
                break;
            default:
                return sprite;
        }
        return rotated;
    }

    private int[] createFallbackSprite(int size) {
        int[] pixels = new int[size * size];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFFFF00FF;
        }
        return pixels;
    }

    public int getBaseSize() {
        return 16;
    }
}