package com.app.core;

import com.app.Config;

public class World {
    private final int size;
    private final int layers;
    private final String[][][] blocks;
    private final boolean[][][] signals;
    private int currentLayer;

    // КЭШ БЛОКОВ
    public final int[][][] blockTypes;
    public final int[][][] blockDirs;
    public final boolean[][][] blockStates;

    // Константы типов блоков
    public static final int TYPE_EMPTY = 0;
    public static final int TYPE_ARROW = 1;
    public static final int TYPE_AND = 2;
    public static final int TYPE_OR = 3;
    public static final int TYPE_XOR = 4;
    public static final int TYPE_NOT = 5;
    public static final int TYPE_BRIDGE = 6;
    public static final int TYPE_POWER = 7;
    public static final int TYPE_GETTER = 8;
    public static final int TYPE_PEG = 9;

    // Для сборки строки при saveMap
    public static final String[] TYPE_NAMES = {
            "0", "arrow", "and", "or", "xor", "not", "bridge", "power", "getter", "peg"
    };

    public World() {
        Config config = Config.getInstance();
        this.size = config.getWorldSize();
        this.layers = config.getLayers();
        this.blocks = new String[layers][size][size];
        this.signals = new boolean[layers][size][size];
        this.blockTypes = new int[layers][size][size];
        this.blockDirs = new int[layers][size][size];
        this.blockStates = new boolean[layers][size][size];
        this.currentLayer = 0;
        clear();
    }

    public void clear() {
        for (int l = 0; l < layers; l++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    blocks[l][y][x] = "0";
                    signals[l][y][x] = false;
                    blockTypes[l][y][x] = TYPE_EMPTY;
                    blockDirs[l][y][x] = 0;
                    blockStates[l][y][x] = false;
                }
            }
        }
    }

    public void setBlock(int layer, int x, int y, String blockType) {
        if (!isValid(layer, x, y)) return;

        blocks[layer][y][x] = blockType;

        if (blockType == null || blockType.equals("0")) {
            blockTypes[layer][y][x] = TYPE_EMPTY;
            blockDirs[layer][y][x] = 0;
            blockStates[layer][y][x] = false;
            signals[layer][y][x] = false;
            return;
        }

        int underscoreIndex = blockType.indexOf('_');

        if (underscoreIndex == -1) {
            if (blockType.equals("power")) {
                blockTypes[layer][y][x] = TYPE_POWER;
                blockDirs[layer][y][x] = 0;
                blockStates[layer][y][x] = true;
                signals[layer][y][x] = true;
                return;
            }
            if (blockType.equals("peg")) {
                blockTypes[layer][y][x] = TYPE_PEG;
                blockDirs[layer][y][x] = 0;
                blockStates[layer][y][x] = false;
                signals[layer][y][x] = false;
                return;
            }
            blockTypes[layer][y][x] = TYPE_EMPTY;
            blockDirs[layer][y][x] = 0;
            blockStates[layer][y][x] = false;
            signals[layer][y][x] = false;
            return;
        }

        if (underscoreIndex + 2 >= blockType.length()) {
            blockTypes[layer][y][x] = TYPE_EMPTY;
            blockDirs[layer][y][x] = 0;
            blockStates[layer][y][x] = false;
            signals[layer][y][x] = false;
            return;
        }

        String blockName = blockType.substring(0, underscoreIndex);
        char state = blockType.charAt(underscoreIndex + 1);
        char dir = blockType.charAt(underscoreIndex + 2);

        int type = switch (blockName) {
            case "arrow" -> TYPE_ARROW;
            case "and" -> TYPE_AND;
            case "or" -> TYPE_OR;
            case "xor" -> TYPE_XOR;
            case "not" -> TYPE_NOT;
            case "bridge" -> TYPE_BRIDGE;
            case "power" -> TYPE_POWER;
            case "getter" -> TYPE_GETTER;
            case "peg" -> TYPE_PEG;
            default -> TYPE_EMPTY;
        };

        int dirIndex = switch (dir) {
            case 'u' -> 0;
            case 'r' -> 1;
            case 'd' -> 2;
            case 'l' -> 3;
            default -> 0;
        };

        boolean blockState = (type == TYPE_POWER) || (state == 't');

        blockTypes[layer][y][x] = type;
        blockDirs[layer][y][x] = dirIndex;
        blockStates[layer][y][x] = blockState;
        signals[layer][y][x] = blockState;
    }

    /**
     * Обновляет только кэш. Строка blocks[][][] больше не пересобирается —
     * это убирает миллионы аллокаций String в секунду во время симуляции.
     * Строка собирается только при сохранении карты (MapManager.saveMap).
     */
    public void updateBlockState(int layer, int x, int y, boolean state, int dirIndex) {
        if (!isValid(layer, x, y)) return;

        int type = blockTypes[layer][y][x];
        if (type == TYPE_EMPTY) return;

        blockStates[layer][y][x] = state;
        blockDirs[layer][y][x] = dirIndex;
        signals[layer][y][x] = state;
    }

    public void removeBlock(int layer, int x, int y) {
        if (isValid(layer, x, y)) {
            blocks[layer][y][x] = "0";
            signals[layer][y][x] = false;
            blockTypes[layer][y][x] = TYPE_EMPTY;
            blockDirs[layer][y][x] = 0;
            blockStates[layer][y][x] = false;
        }
    }

    /** Прямой доступ к массиву сигналов — только для SimulationEngine. */
    public boolean[][][] getSignalsArray() {
        return signals;
    }

    public String getBlock(int layer, int x, int y) {
        if (!isValid(layer, x, y)) return null;
        return blocks[layer][y][x];
    }

    public void setSignal(int layer, int x, int y, boolean value) {
        if (isValid(layer, x, y)) {
            signals[layer][y][x] = value;
        }
    }

    public boolean getSignal(int layer, int x, int y) {
        if (!isValid(layer, x, y)) return false;
        return signals[layer][y][x];
    }

    public void setCurrentLayer(int layer) {
        if (layer >= 0 && layer < layers) {
            this.currentLayer = layer;
        }
    }

    public int getCurrentLayer() { return currentLayer; }

    public void nextLayer() {
        if (currentLayer < layers - 1) {
            currentLayer++;
        }
    }

    public void previousLayer() {
        if (currentLayer > 0) {
            currentLayer--;
        }
    }

    private boolean isValid(int layer, int x, int y) {
        return layer >= 0 && layer < layers && x >= 0 && x < size && y >= 0 && y < size;
    }

    public String getBlockForDisplay(int layer, int x, int y) {
        String block = getBlock(layer, x, y);
        if (block == null || block.equals("0")) return null;
        return block;
    }

    public boolean isPegConnected(int layer, int x, int y) {
        if (!isValid(layer, x, y)) return false;
        int type = blockTypes[layer][y][x];
        if (type != TYPE_PEG) return false;

        if (layer + 1 < layers) {
            if (blockTypes[layer + 1][y][x] == TYPE_PEG) return true;
        }

        if (layer - 1 >= 0) {
            if (blockTypes[layer - 1][y][x] == TYPE_PEG) return true;
        }

        return false;
    }

    public int getSize() { return size; }
    public int getLayers() { return layers; }
}