package com.app.graphics;

import com.app.core.Direction;
import com.app.core.World;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

public class GridRenderer {
    private final World world;
    private final Camera camera;
    private final SpriteManager spriteManager;
    private final ColorConfig colors;
    private final int baseCellSize;
    private final int worldSizeCells;

    private Canvas canvas;
    private GraphicsContext gc;

    private final Map<String, Map<Integer, Map<String, WritableImage>>> spriteImageCache = new HashMap<>();

    // КЭШ WritableImage ДЛЯ drawSprite (по идентичности int[])
    private final IdentityHashMap<int[], WritableImage> spriteArrayImageCache = new IdentityHashMap<>();

    // КЭШ ЦВЕТОВ
    private Color bgColor;
    private Color signalOnColor;
    private Color gridColor;
    private Color textColor;
    private final Map<String, Color> blockColorCache = new HashMap<>();

    // КЭШ ШРИФТА
    private Font simplifiedFont;
    private int simplifiedFontSize = -1;

    private Font textFont;
    private double textFontSize = -1;

    // id → имя (совпадает с TYPE_* в World)
    private static final String[] TYPE_NAMES = {
            "0",       // TYPE_EMPTY
            "arrow",   // TYPE_ARROW
            "and",     // TYPE_AND
            "or",      // TYPE_OR
            "xor",     // TYPE_XOR
            "not",     // TYPE_NOT
            "bridge",  // TYPE_BRIDGE
            "power",   // TYPE_POWER
            "getter",  // TYPE_GETTER
            "peg",     // TYPE_PEG
            "text"     // TYPE_TEXT
    };

    // dirIndex (0=u,1=r,2=d,3=l) → Direction
    private static final Direction[] DIR_BY_INDEX = {
            Direction.UP, Direction.RIGHT, Direction.DOWN, Direction.LEFT
    };

    public GridRenderer(World world, Camera camera) {
        this.world = world;
        this.camera = camera;
        this.spriteManager = SpriteManager.getInstance();
        this.colors = spriteManager.getColors();
        this.baseCellSize = spriteManager.getBaseSize();
        this.worldSizeCells = world.getSize();

        this.canvas = new Canvas(800, 600);
        this.gc = canvas.getGraphicsContext2D();

        canvas.setStyle("-fx-scale-shape: false;");

        refreshColorCache();

        canvas.widthProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.intValue() > 0) {
                camera.setViewportSize(newVal.intValue(), (int) canvas.getHeight());
                render();
            }
        });
        canvas.heightProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.intValue() > 0) {
                camera.setViewportSize((int) canvas.getWidth(), newVal.intValue());
                render();
            }
        });
    }

    public void refreshColorCache() {
        this.bgColor = Color.web(colors.getBackground());
        this.signalOnColor = Color.web(colors.getSignalOn());
        this.gridColor = Color.web(colors.getGrid());
        this.textColor = Color.web(colors.getText());
        this.blockColorCache.clear();

        this.textFont = null;
        this.textFontSize = -1;
        this.simplifiedFont = null;
        this.simplifiedFontSize = -1;
    }

    public void clearSpriteArrayCache() {
        spriteArrayImageCache.clear();
    }

    private Color getBlockColor(String name) {
        Color c = blockColorCache.get(name);
        if (c == null) {
            c = Color.web(colors.getColorForBlock(name));
            blockColorCache.put(name, c);
        }
        return c;
    }

    private Font getSimplifiedFont(int size) {
        if (simplifiedFont == null || simplifiedFontSize != size) {
            simplifiedFont = Font.font(size);
            simplifiedFontSize = size;
        }
        return simplifiedFont;
    }

    private Font getTextFont(double size) {
        if (textFont == null || Math.abs(textFontSize - size) > 0.01) {
            textFont = Font.font("Monospaced", FontWeight.BOLD, size);
            textFontSize = size;
        }
        return textFont;
    }

    private String getCacheKey(Direction dir, boolean signalOn) {
        return dir.toString() + "_" + signalOn;
    }

    private WritableImage getCachedSprite(String spriteName, int size, Direction dir, boolean signalOn) {
        Map<Integer, Map<String, WritableImage>> sizeMap = spriteImageCache.get(spriteName);
        if (sizeMap == null) {
            sizeMap = new HashMap<>();
            spriteImageCache.put(spriteName, sizeMap);
        }

        Map<String, WritableImage> dirMap = sizeMap.get(size);
        if (dirMap == null) {
            dirMap = new HashMap<>();
            sizeMap.put(size, dirMap);
        }

        String key = getCacheKey(dir, signalOn);
        WritableImage cached = dirMap.get(key);

        if (cached != null) {
            return cached;
        }

        int[] sprite = spriteManager.getSpriteWithDirection(spriteName, size, dir, signalOn);
        if (sprite == null) return null;

        WritableImage image = new WritableImage(size, size);
        PixelWriter pw = image.getPixelWriter();

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int color = sprite[y * size + x];
                if ((color & 0xFF000000) != 0) {
                    pw.setArgb(x, y, color);
                }
            }
        }

        dirMap.put(key, image);
        return image;
    }

    public void render() {
        if (canvas.getWidth() <= 0 || canvas.getHeight() <= 0) return;

        int cellScreenSize = camera.getCellScreenSize();
        int currentLayer = world.getCurrentLayer();

        int startX = Math.max(0, camera.getVisibleStartX(worldSizeCells));
        int startY = Math.max(0, camera.getVisibleStartY(worldSizeCells));
        int endX = Math.min(worldSizeCells, camera.getVisibleEndX(worldSizeCells));
        int endY = Math.min(worldSizeCells, camera.getVisibleEndY(worldSizeCells));

        int visibleCells = (endX - startX) * (endY - startY);
        boolean useSimplifiedRendering = cellScreenSize > 64 && visibleCells > 5000;

        gc.setFill(bgColor);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        // ===== ПРОХОД 1: фоны + спрайты =====
        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                int typeId = world.blockTypes[currentLayer][y][x];
                int dirIdx = world.blockDirs[currentLayer][y][x];
                boolean hasSignal = world.getSignal(currentLayer, x, y);

                // Каждая клетка — от своей world-координаты через worldToScreenX
                double screenX = camera.worldToScreenX(x * baseCellSize);
                double screenY = camera.worldToScreenY(y * baseCellSize);
                int ix = (int) Math.round(screenX);
                int iy = (int) Math.round(screenY);

                if (hasSignal) {
                    gc.setFill(signalOnColor);
                    gc.fillRect(ix, iy, cellScreenSize, cellScreenSize);
                }

                if (typeId != World.TYPE_EMPTY) {
                    String blockName = (typeId >= 0 && typeId < TYPE_NAMES.length)
                            ? TYPE_NAMES[typeId] : "0";

                    Direction spriteDir = DIR_BY_INDEX[dirIdx & 3];
                    boolean spriteSignal = false;

                    if (useSimplifiedRendering && cellScreenSize > 32) {
                        gc.setFill(getBlockColor(blockName));
                        gc.fillRect(ix, iy, cellScreenSize, cellScreenSize);
                        gc.setFill(Color.WHITE);
                        gc.setFont(getSimplifiedFont(cellScreenSize / 2));
                        gc.fillText(blockName.substring(0, 1).toUpperCase(),
                                ix + cellScreenSize / 3.0, iy + cellScreenSize / 1.5);
                    } else {
                        WritableImage spriteImage = getCachedSprite(blockName, cellScreenSize, spriteDir, spriteSignal);
                        if (spriteImage != null) {
                            gc.drawImage(spriteImage, ix, iy);
                        }
                    }
                }
            }
        }

        // ===== ПРОХОД 2: сетка =====
        double firstCellScreenX = camera.worldToScreenX(startX * baseCellSize);
        double lastCellScreenX = camera.worldToScreenX(endX * baseCellSize);
        double firstCellScreenY = camera.worldToScreenY(startY * baseCellSize);
        double lastCellScreenY = camera.worldToScreenY(endY * baseCellSize);

        gc.setStroke(gridColor);

        if (cellScreenSize >= 10) {
            gc.setLineWidth(1.2);
            gc.beginPath();
            for (int x = startX; x <= endX; x++) {
                double screenX = camera.worldToScreenX(x * baseCellSize);
                if (screenX >= firstCellScreenX - 10 && screenX <= lastCellScreenX + 10) {
                    gc.moveTo(screenX, firstCellScreenY);
                    gc.lineTo(screenX, lastCellScreenY);
                }
            }
            for (int y = startY; y <= endY; y++) {
                double screenY = camera.worldToScreenY(y * baseCellSize);
                if (screenY >= firstCellScreenY - 10 && screenY <= lastCellScreenY + 10) {
                    gc.moveTo(firstCellScreenX, screenY);
                    gc.lineTo(lastCellScreenX, screenY);
                }
            }
            gc.stroke();
        } else {
            gc.setLineWidth(2.0);
            gc.strokeRect(firstCellScreenX, firstCellScreenY,
                    lastCellScreenX - firstCellScreenX,
                    lastCellScreenY - firstCellScreenY);
        }

        // ===== ПРОХОД 3: текст поверх всего =====
        renderTextBlocks(currentLayer, startX, startY, endX, endY, cellScreenSize);
    }

    private void renderTextBlocks(int layer, int startX, int startY, int endX, int endY, int cellScreenSize) {
        if (cellScreenSize < 6) return;

        int textStartX = Math.max(0, startX - 16);
        int textStartY = Math.max(0, startY);
        int textEndX = Math.min(worldSizeCells, endX + 16);
        int textEndY = Math.min(worldSizeCells, endY);

        double fontSize = Math.max(6.0, cellScreenSize / 1);
        gc.setFont(getTextFont(fontSize));
        gc.setFill(textColor);

        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);

        for (int y = textStartY; y < textEndY; y++) {
            for (int x = textStartX; x < textEndX; x++) {
                if (world.blockTypes[layer][y][x] != World.TYPE_TEXT) continue;

                String raw = world.blockTexts[layer][y][x];
                if (raw == null || raw.isEmpty()) continue;

                boolean signalOn = world.getSignal(layer, x, y);
                String text = extractText(raw, signalOn);
                if (text == null || text.isEmpty()) continue;

                int len = text.length();

                // Каждый символ — от своей world-координаты (без накопления)
                double cellScreenY = camera.worldToScreenY(y * baseCellSize);

                for (int c = 0; c < len; c++) {
                    char ch = text.charAt(c);

                    // Точная screen-координата клетки (x + c)
                    double cellScreenX = camera.worldToScreenX((x + c) * baseCellSize);

                    double centerX = cellScreenX + cellScreenSize * 0.5;
                    double centerY = cellScreenY + cellScreenSize * 0.52;

                    gc.fillText(String.valueOf(ch), centerX, centerY);
                }
            }
        }

        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BASELINE);
    }

    private String extractText(String raw, boolean signalOn) {
        int pipe = raw.indexOf('|');
        if (pipe < 0) return raw;
        if (signalOn) {
            return raw.substring(0, pipe);
        } else {
            return raw.substring(pipe + 1);
        }
    }

    public Canvas getCanvas() {
        return canvas;
    }

    public void drawSprite(GraphicsContext gc, int[] sprite, int size, int x, int y) {
        if (sprite == null || size <= 0) return;

        WritableImage image = spriteArrayImageCache.get(sprite);
        if (image == null || (int) image.getWidth() != size) {
            image = new WritableImage(size, size);
            PixelWriter pw = image.getPixelWriter();
            for (int py = 0; py < size; py++) {
                for (int px = 0; px < size; px++) {
                    int color = sprite[py * size + px];
                    if ((color & 0xFF000000) != 0) {
                        pw.setArgb(px, py, color);
                    }
                }
            }
            spriteArrayImageCache.put(sprite, image);
        }
        gc.drawImage(image, x, y);
    }
}