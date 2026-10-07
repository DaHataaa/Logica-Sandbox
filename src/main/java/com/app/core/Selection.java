package com.app.core;

import java.util.ArrayList;
import java.util.List;

public class Selection {
    // ===== СТАТИЧЕСКИЙ БУФЕР ОБМЕНА (общий для всех карт) =====
    private static List<List<String>> clipboardBlocks = new ArrayList<>();
    private static boolean clipboardHasData = false;

    // ===== Экземплярные поля (текущее выделение на этой карте) =====
    private int startX, startY;
    private int endX, endY;
    private int layer;

    // Локальное выделение (для cut/copy/delete)
    private List<List<String>> blocks;
    private boolean hasSelection;

    public Selection() {
        blocks = new ArrayList<>();
        hasSelection = false;
    }

    // ============================================================
    // ТЕКУЩЕЕ ВЫДЕЛЕНИЕ
    // ============================================================
    public void startSelection(int x, int y, int layer) {
        this.startX = x;
        this.startY = y;
        this.layer = layer;
        hasSelection = false;
    }

    public void updateSelection(int x, int y) {
        this.endX = x;
        this.endY = y;
    }

    public void finishSelection(World world) {
        if (startX > endX) { int tmp = startX; startX = endX; endX = tmp; }
        if (startY > endY) { int tmp = startY; startY = endY; endY = tmp; }

        int width = endX - startX + 1;
        int height = endY - startY + 1;

        blocks.clear();
        for (int dy = 0; dy < height; dy++) {
            List<String> row = new ArrayList<>();
            for (int dx = 0; dx < width; dx++) {
                int x = startX + dx;
                int y = startY + dy;
                String block = world.getBlock(layer, x, y);
                row.add(block != null ? block : "0");
            }
            blocks.add(row);
        }
        hasSelection = true;
    }

    public void clear() {
        blocks.clear();
        hasSelection = false;
        clipboardBlocks = new ArrayList<>();
    }

    // ============================================================
    // КОПИРОВАНИЕ / ВЫРЕЗАНИЕ → в статический буфер
    // ============================================================
    public void copyToClipboard() {
        if (!hasSelection || blocks.isEmpty()) return;

        // Глубокая копия, чтобы данные не зависели от этого экземпляра
        clipboardBlocks = new ArrayList<>();
        for (List<String> row : blocks) {
            clipboardBlocks.add(new ArrayList<>(row));
        }
        clipboardHasData = true;
    }

    public void cut(World world, int layer) {
        if (!hasSelection) return;

        // Сначала копируем в буфер
        copyToClipboard();

        // Потом удаляем с карты
        for (int dy = 0; dy < blocks.size(); dy++) {
            for (int dx = 0; dx < blocks.get(dy).size(); dx++) {
                int x = startX + dx;
                int y = startY + dy;
                if (x >= 0 && x < world.getSize() && y >= 0 && y < world.getSize()) {
                    world.removeBlock(layer, x, y);
                    world.setSignal(layer, x, y, false);
                }
            }
        }
    }

    public void deleteSelected(World world, int layer) {
        if (!hasSelection) return;

        int startX = Math.min(this.startX, this.endX);
        int endX = Math.max(this.startX, this.endX);
        int startY = Math.min(this.startY, this.endY);
        int endY = Math.max(this.startY, this.endY);

        for (int y = startY; y <= endY; y++) {
            for (int x = startX; x <= endX; x++) {
                if (x >= 0 && x < world.getSize() && y >= 0 && y < world.getSize()) {
                    world.removeBlock(layer, x, y);
                    world.setSignal(layer, x, y, false);
                }
            }
        }
    }

    // ============================================================
    // ВСТАВКА ИЗ СТАТИЧЕСКОГО БУФЕРА
    // ============================================================
    public void paste(World world, int targetLayer, int mouseX, int mouseY) {
        if (!clipboardHasData || clipboardBlocks.isEmpty()) return;

        int width = clipboardBlocks.get(0).size();
        int height = clipboardBlocks.size();

        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                int x = mouseX + dx;
                int y = mouseY + dy;
                if (x >= 0 && x < world.getSize() && y >= 0 && y < world.getSize()) {
                    String block = clipboardBlocks.get(dy).get(dx);
                    if (!block.equals("0")) {
                        world.setBlock(targetLayer, x, y, block);
                    }
                }
            }
        }

        // Восстанавливаем сигналы
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                int x = mouseX + dx;
                int y = mouseY + dy;
                if (x >= 0 && x < world.getSize() && y >= 0 && y < world.getSize()) {
                    String block = world.getBlock(targetLayer, x, y);
                    if (block != null && !block.equals("0")) {
                        String[] parts = block.split("_");
                        if (parts.length > 1 && parts[1].length() == 2) {
                            char state = parts[1].charAt(0);
                            world.setSignal(targetLayer, x, y, state == 't');
                        }
                    }
                }
            }
        }
    }

    // ============================================================
    // ГЕТТЕРЫ ЛОКАЛЬНОГО ВЫДЕЛЕНИЯ
    // ============================================================
    public boolean hasSelection() { return hasSelection; }
    public int getStartX() { return startX; }
    public int getStartY() { return startY; }
    public int getEndX() { return endX; }
    public int getEndY() { return endY; }
    public List<List<String>> getBlocks() { return blocks; }
    public int getWidth() { return blocks.isEmpty() ? 0 : blocks.get(0).size(); }
    public int getHeight() { return blocks.size(); }

    // ===== Геттеры статического буфера обмена =====
    public static boolean hasClipboard() { return clipboardHasData; }
    public static List<List<String>> getClipboardBlocks() { return clipboardBlocks; }
    public static int getClipboardWidth() {
        return clipboardBlocks.isEmpty() ? 0 : clipboardBlocks.get(0).size();
    }
    public static int getClipboardHeight() { return clipboardBlocks.size(); }
}