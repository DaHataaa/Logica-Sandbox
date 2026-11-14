package com.app.core;

import com.app.Config;

public class World {
    private final int size;
    private final int layers;
    public final int sizeSq;

    // Плоские массивы (1D)
    private final String[] blocks;
    private final boolean[] signals;
    public final String[] blockTexts;

    // КЭШ БЛОКОВ (1D)
    public final int[] blockTypes;
    public final int[] blockDirs;
    public final boolean[] blockStates;

    private int currentLayer;

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
    public static final int TYPE_TEXT = 10;

    public static final String[] TYPE_NAMES = {
            "0", "arrow", "and", "or", "xor", "not", "bridge", "power", "getter", "peg", "text"
    };

    public static final String DEFAULT_TEXT_TRUE = "TRUE";
    public static final String DEFAULT_TEXT_FALSE = "FALSE";

    public World() {
        Config config = Config.getInstance();
        this.size = config.getWorldSize();
        this.layers = config.getLayers();
        this.sizeSq = size * size;

        int capacity = layers * sizeSq;

        this.blocks = new String[capacity];
        this.signals = new boolean[capacity];
        this.blockTypes = new int[capacity];
        this.blockDirs = new int[capacity];
        this.blockStates = new boolean[capacity];
        this.blockTexts = new String[capacity];
        this.currentLayer = 0;
        clear();
    }

    public int getIdx(int layer, int x, int y) {
        return (layer * sizeSq) + (y * size) + x;
    }

    public void clear() {
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = "0";
            signals[i] = false;
            blockTypes[i] = TYPE_EMPTY;
            blockDirs[i] = 0;
            blockStates[i] = false;
            blockTexts[i] = null;
        }
    }

    public void setBlock(int layer, int x, int y, String blockType) {
        if (!isValid(layer, x, y)) return;
        int idx = getIdx(layer, x, y);

        blocks[idx] = blockType;
        blockTexts[idx] = null;

        if (blockType == null || blockType.equals("0")) {
            blockTypes[idx] = TYPE_EMPTY;
            blockDirs[idx] = 0;
            blockStates[idx] = false;
            signals[idx] = false;
            return;
        }

        String textsPart = null;
        int pipeIdx = blockType.indexOf('|');
        if (pipeIdx >= 0) {
            textsPart = blockType.substring(pipeIdx + 1);
            blockType = blockType.substring(0, pipeIdx);
        }

        int underscoreIndex = blockType.indexOf('_');

        if (underscoreIndex == -1) {
            if (blockType.equals("power")) {
                blockTypes[idx] = TYPE_POWER;
                blockDirs[idx] = 0;
                blockStates[idx] = true;
                signals[idx] = true;
                return;
            }
            if (blockType.equals("peg")) {
                blockTypes[idx] = TYPE_PEG;
                blockDirs[idx] = 0;
                blockStates[idx] = false;
                signals[idx] = false;
                return;
            }
            if (blockType.equals("text")) {
                blockTypes[idx] = TYPE_TEXT;
                blockDirs[idx] = 0;
                blockStates[idx] = false;
                signals[idx] = false;
                String raw = (textsPart != null) ? textsPart
                        : (DEFAULT_TEXT_TRUE + "|" + DEFAULT_TEXT_FALSE);
                raw = raw.replace('_', ' ');
                blockTexts[idx] = raw;
                return;
            }
            blockTypes[idx] = TYPE_EMPTY;
            blockDirs[idx] = 0;
            blockStates[idx] = false;
            signals[idx] = false;
            return;
        }

        if (underscoreIndex + 2 >= blockType.length()) {
            blockTypes[idx] = TYPE_EMPTY;
            blockDirs[idx] = 0;
            blockStates[idx] = false;
            signals[idx] = false;
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
            case "text" -> TYPE_TEXT;
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

        blockTypes[idx] = type;
        blockDirs[idx] = dirIndex;
        blockStates[idx] = blockState;
        signals[idx] = blockState;

        if (type == TYPE_TEXT) {
            String raw = (textsPart != null) ? textsPart
                    : (DEFAULT_TEXT_TRUE + "|" + DEFAULT_TEXT_FALSE);
            raw = raw.replace('_', ' ');
            blockTexts[idx] = raw;
        }
    }

    public void updateBlockState(int layer, int x, int y, boolean state, int dirIndex) {
        if (!isValid(layer, x, y)) return;
        int idx = getIdx(layer, x, y);
        int type = blockTypes[idx];
        if (type == TYPE_EMPTY) return;

        blockStates[idx] = state;
        blockDirs[idx] = dirIndex;
        signals[idx] = state;
    }

    public void removeBlock(int layer, int x, int y) {
        if (!isValid(layer, x, y)) return;
        int idx = getIdx(layer, x, y);
        blocks[idx] = "0";
        signals[idx] = false;
        blockTypes[idx] = TYPE_EMPTY;
        blockDirs[idx] = 0;
        blockStates[idx] = false;
        blockTexts[idx] = null;
    }

    public boolean[] getSignalsArray() {
        return signals;
    }

    public String getBlock(int layer, int x, int y) {
        if (!isValid(layer, x, y)) return null;
        int idx = getIdx(layer, x, y);

        int type = blockTypes[idx];
        if (type == TYPE_TEXT) {
            String texts = blockTexts[idx];
            if (texts == null) texts = DEFAULT_TEXT_TRUE + "|" + DEFAULT_TEXT_FALSE;
            char s = blockStates[idx] ? 't' : 'f';
            return "text_" + s + "f|" + texts;
        }
        return blocks[idx];
    }

    public void setSignal(int layer, int x, int y, boolean value) {
        if (isValid(layer, x, y)) {
            signals[getIdx(layer, x, y)] = value;
        }
    }

    public boolean getSignal(int layer, int x, int y) {
        if (!isValid(layer, x, y)) return false;
        return signals[getIdx(layer, x, y)];
    }

    public void setCurrentLayer(int layer) {
        if (layer >= 0 && layer < layers) {
            this.currentLayer = layer;
        }
    }

    public int getCurrentLayer() { return currentLayer; }

    public void nextLayer() {
        if (currentLayer < layers - 1) currentLayer++;
    }

    public void previousLayer() {
        if (currentLayer > 0) currentLayer--;
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
        int idx = getIdx(layer, x, y);
        if (blockTypes[idx] != TYPE_PEG) return false;

        if (layer + 1 < layers && blockTypes[getIdx(layer + 1, x, y)] == TYPE_PEG) return true;
        if (layer - 1 >= 0 && blockTypes[getIdx(layer - 1, x, y)] == TYPE_PEG) return true;

        return false;
    }

    public String getBlockTexts(int layer, int x, int y) {
        if (!isValid(layer, x, y)) return null;
        return blockTexts[getIdx(layer, x, y)];
    }

    public void setBlockTexts(int layer, int x, int y, String trueText, String falseText) {
        if (!isValid(layer, x, y)) return;
        int idx = getIdx(layer, x, y);
        if (blockTypes[idx] != TYPE_TEXT) return;

        if (trueText == null) trueText = "";
        if (falseText == null) falseText = "";

        blockTexts[idx] = trueText + "|" + falseText;
        rebuildBlockString(idx);
    }

    public void setBlockTextsRaw(int layer, int x, int y, String raw) {
        if (!isValid(layer, x, y)) return;
        int idx = getIdx(layer, x, y);
        if (blockTypes[idx] != TYPE_TEXT) return;
        if (raw != null) raw = raw.replace('_', ' ');
        blockTexts[idx] = raw;
        rebuildBlockString(idx);
    }

    private void rebuildBlockString(int idx) {
        if (blockTypes[idx] != TYPE_TEXT) return;
        String texts = blockTexts[idx];
        if (texts == null) texts = DEFAULT_TEXT_TRUE + "|" + DEFAULT_TEXT_FALSE;
        char s = blockStates[idx] ? 't' : 'f';
        blocks[idx] = "text_" + s + "f|" + texts;
    }

    public int getSize() { return size; }
    public int getLayers() { return layers; }
}