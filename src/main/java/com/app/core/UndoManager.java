package com.app.core;

import com.app.Config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * История изменений мира по дельтам (без полных снимков).
 *
 * Один "шаг" = список изменённых клеток (CellDelta). При undo/redo
 * клетки восстанавливаются в обратном/прямом порядке.
 *
 * Глубина ограничена Config.getUndoDepth().
 */
public class UndoManager {

    /** Изменение одной клетки: oldState — до, newState — после. */
    public static final class CellDelta {
        final int layer;
        final int x;
        final int y;
        final String oldBlock;   // как в world.getBlock() — с "|texts" при необходимости
        final String newBlock;
        final boolean oldSignal;
        final boolean newSignal;

        CellDelta(int layer, int x, int y,
                  String oldBlock, String newBlock,
                  boolean oldSignal, boolean newSignal) {
            this.layer = layer;
            this.x = x;
            this.y = y;
            this.oldBlock = oldBlock;
            this.newBlock = newBlock;
            this.oldSignal = oldSignal;
            this.newSignal = newSignal;
        }
    }

    /** Один шаг = набор дельт. */
    public static final class Step {
        final String label;
        final List<CellDelta> deltas = new ArrayList<>();

        Step(String label) { this.label = label; }
    }

    private final World world;
    private final int maxDepth;

    private final Deque<Step> undoStack = new ArrayDeque<>();
    private final Deque<Step> redoStack = new ArrayDeque<>();

    // Текущий открытый шаг (null — нет)
    private Step currentStep = null;

    public UndoManager(World world) {
        this.world = world;
        this.maxDepth = Math.max(1, Config.getInstance().getUndoDepth());
    }

    // ============================================================
    // Запись изменений
    // ============================================================

    /**
     * Открывает шаг. Все последующие recordChange() попадут в него
     * до вызова commitStep(). Если шаг уже открыт — повторный begin игнорируется.
     */
    public void beginStep(String label) {
        if (currentStep != null) return;
        currentStep = new Step(label);
    }

    /**
     * Записывает изменение клетки в текущий шаг.
     * ВАЖНО: вызывать ДО фактического изменения мира — нужен oldBlock.
     */
    public void recordChange(int layer, int x, int y,
                             String oldBlock, String newBlock,
                             boolean oldSignal, boolean newSignal) {
        if (currentStep == null) {
            // Изменение вне явного шага — не пишем (обычно не должно случаться)
            return;
        }
        if (oldBlock == null && newBlock == null) return;
        if (eq(oldBlock, newBlock) && oldSignal == newSignal) return;

        currentStep.deltas.add(new CellDelta(
                layer, x, y, oldBlock, newBlock, oldSignal, newSignal));
    }

    /** Закрывает шаг и кладёт его в undo-стек. Пустой шаг отбрасывается. */
    public void commitStep() {
        if (currentStep == null) return;
        Step s = currentStep;
        currentStep = null;

        if (s.deltas.isEmpty()) return;

        undoStack.push(s);
        while (undoStack.size() > maxDepth) {
            undoStack.removeLast();
        }
        redoStack.clear();
    }

    /** Отменяет открытый шаг без сохранения (например, при отмене операции). */
    public void cancelStep() {
        currentStep = null;
    }

    public boolean isStepOpen() { return currentStep != null; }

    // ============================================================
    // Undo / Redo
    // ============================================================

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    /**
     * Откатывает последний шаг. Возвращает true, если что-то откатили.
     */
    public boolean undo() {
        if (undoStack.isEmpty()) return false;
        Step step = undoStack.pop();

        // применяем дельты в обратном порядке
        for (int i = step.deltas.size() - 1; i >= 0; i--) {
            CellDelta d = step.deltas.get(i);
            applyBlock(d.layer, d.x, d.y, d.oldBlock, d.oldSignal);
        }
        redoStack.push(step);
        return true;
    }

    public boolean redo() {
        if (redoStack.isEmpty()) return false;
        Step step = redoStack.pop();

        for (CellDelta d : step.deltas) {
            applyBlock(d.layer, d.x, d.y, d.newBlock, d.newSignal);
        }
        undoStack.push(step);
        return true;
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
        currentStep = null;
    }

    // ============================================================
    // Внутреннее
    // ============================================================

    private void applyBlock(int layer, int x, int y, String block, boolean signal) {
        if (block == null || block.equals("0")) {
            world.removeBlock(layer, x, y);
        } else {
            world.setBlock(layer, x, y, block);
        }
        world.setSignal(layer, x, y, signal);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}