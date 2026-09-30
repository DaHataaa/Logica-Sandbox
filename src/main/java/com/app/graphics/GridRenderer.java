package com.app.graphics;

import com.app.core.Direction;
import com.app.core.World;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

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
    private final Map<String, Color> blockColorCache = new HashMap<>();

    // КЭШ ШРИФТА
    private Font simplifiedFont;
    private int simplifiedFontSize = -1;

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
            "peg"      // TYPE_PEG
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
        this.blockColorCache.clear();
    }

    /** Вызывать при смене текстурпака, чтобы не держать старые int[] в IdentityHashMap. */
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

        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                int typeId = world.blockTypes[currentLayer][y][x];
                int dirIdx = world.blockDirs[currentLayer][y][x];
                boolean hasSignal = world.getSignal(currentLayer, x, y);

                double screenX = camera.worldToScreenX(x * baseCellSize);
                double screenY = camera.worldToScreenY(y * baseCellSize);
                int ix = (int) Math.round(screenX);
                int iy = (int) Math.round(screenY);

                // 1. ФОН КЛЕТКИ — signalOn у активных, bg у остальных
                gc.setFill(hasSignal ? signalOnColor : bgColor);
                gc.fillRect(ix, iy, cellScreenSize, cellScreenSize);

                // 2. СПРАЙТ ПОВЕРХ
                if (typeId != World.TYPE_EMPTY) {
                    String blockName = (typeId >= 0 && typeId < TYPE_NAMES.length)
                            ? TYPE_NAMES[typeId] : "0";

                    // Спрайт всегда красится в цвет блока; активность видна по фону
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

        // РИСУЕМ СЕТКУ ИЛИ РАМКУ ПОЛЯ
        double firstCellScreenX = camera.worldToScreenX(startX * baseCellSize);
        double lastCellScreenX = camera.worldToScreenX(endX * baseCellSize);
        double firstCellScreenY = camera.worldToScreenY(startY * baseCellSize);
        double lastCellScreenY = camera.worldToScreenY(endY * baseCellSize);

        gc.setStroke(gridColor);

        if (cellScreenSize >= 8) {
            gc.setLineWidth(1.6);
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
    }

    public Canvas getCanvas() {
        return canvas;
    }

    /** Кэширует WritableImage по самому массиву int[], чтобы не создавать его каждый кадр. */
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