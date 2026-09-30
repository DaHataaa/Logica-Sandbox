package com.app.core;

public enum BlockType {
    ARROW("arrow", true),
    GETTER("getter", true),
    BRIDGE("bridge", true),
    PEG("peg", true),            // ← ИСПРАВЛЕНО: был false
    POWER("power", false),
    NOT("not", true),
    OR("or", true),
    AND("and", true),
    XOR("xor", true);

    private final String name;
    private final boolean hasDirection;

    BlockType(String name, boolean hasDirection) {
        this.name = name;
        this.hasDirection = hasDirection;
    }

    public String getName() { return name; }
    public boolean hasDirection() { return hasDirection; }

    public static BlockType fromIndex(int index) {
        BlockType[] values = values();
        if (index >= 0 && index < values.length) {
            return values[index];
        }
        return ARROW;
    }

    public static int size() { return values().length; }
}