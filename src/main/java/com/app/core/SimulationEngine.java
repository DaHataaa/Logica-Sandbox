package com.app.core;

import com.app.Config;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class SimulationEngine {
    private final World world;
    private final int size;
    private final int layers;
    private Runnable onTickComplete;

    // ===== Кэш ссылок на массивы World =====
    private final int[][][] worldBlockTypes;
    private final int[][][] worldBlockDirs;
    private final boolean[][][] worldBlockStates;
    private final boolean[][][] worldSignals;

    // ===== Аккумулятор входов каждой клетки =====
    private final int[][][] ns;

    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DY = {-1, 0, 1, 0};

    // ===== Упаковка (layer, x, y) в int. Требует size ≤ 256, layers ≤ 255. =====
    private static int pack(int layer, int x, int y) {
        return (layer << 16) | (y << 8) | x;
    }
    private static int unpackLayer(int p) { return (p >>> 16) & 0xFF; }
    private static int unpackY(int p) { return (p >>> 8) & 0xFF; }
    private static int unpackX(int p) { return p & 0xFF; }

    // ===== Event-driven: dirty-наборы =====
    private int[] dirty;
    private int dirtyCount = 0;
    private boolean[][][] inDirty;

    private int[] nextDirty;
    private int nextDirtyCount = 0;
    private boolean[][][] inNextDirty;

    private int[] pendingChanges;
    private int pendingChangesCount = 0;

    // ===== Активные клетки (для rebuild) =====
    private int[] activeCells;
    private int activeCellsCount = 0;
    private volatile boolean activeCellsDirty = true;

    // ===== Скорость / потоки / TPS =====
    private final AtomicInteger currentSpeed = new AtomicInteger(60);
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicBoolean unlimitedSpeed = new AtomicBoolean(false);

    private final AtomicInteger tickCounter = new AtomicInteger(0);
    private volatile int actualTps = 0;
    private volatile double tpsSmoothed = 0;
    private volatile long lastTpsUpdateNanos = 0;
    private Thread tpsThread;
    private volatile boolean tpsThreadRunning = false;

    private Thread simThread;
    private volatile boolean simThreadRunning = false;
    private final Object loopLock = new Object();

    private volatile long intervalNanos = 16_666_667L;
    private volatile boolean configDirty = false;

    public SimulationEngine(World world) {
        this.world = world;
        this.size = world.getSize();
        this.layers = world.getLayers();
        this.ns = new int[layers][size][size];

        this.worldBlockTypes  = world.blockTypes;
        this.worldBlockDirs   = world.blockDirs;
        this.worldBlockStates = world.blockStates;
        this.worldSignals     = world.getSignalsArray();

        int capacity = layers * size * size;
        this.dirty             = new int[capacity];
        this.nextDirty         = new int[capacity];
        this.pendingChanges    = new int[capacity];
        this.activeCells       = new int[capacity];
        this.inDirty           = new boolean[layers][size][size];
        this.inNextDirty       = new boolean[layers][size][size];

        this.currentSpeed.set(Config.getInstance().getSimulationSpeed());
        updateIntervalFromSpeed();
        startSimThread();
        startTpsCounter();
    }

    public void markWorldChanged() {
        activeCellsDirty = true;
    }

    // ================================================================
    // REBUILD: полный пересчёт ns из текущих signals
    // ================================================================
    private void rebuild() {
        int n = 0;
        for (int layer = 0; layer < layers; layer++) {
            int[][] typeLayer = worldBlockTypes[layer];
            for (int y = 0; y < size; y++) {
                int[] row = typeLayer[y];
                for (int x = 0; x < size; x++) {
                    if (row[x] != World.TYPE_EMPTY) {
                        activeCells[n++] = pack(layer, x, y);
                    }
                }
            }
        }
        activeCellsCount = n;

        // Полная очистка
        for (int layer = 0; layer < layers; layer++) {
            for (int y = 0; y < size; y++) {
                Arrays.fill(ns[layer][y], 0);
                Arrays.fill(inDirty[layer][y], false);
                Arrays.fill(inNextDirty[layer][y], false);
            }
        }
        dirtyCount = 0;
        nextDirtyCount = 0;
        pendingChangesCount = 0;

        // Пересчёт ns со всех активных
        for (int i = 0; i < n; i++) {
            int p = activeCells[i];
            int layer = unpackLayer(p);
            int x = unpackX(p);
            int y = unpackY(p);
            if (worldSignals[layer][y][x]) {
                contributeForwardDirect(layer, x, y, +1);
                contributeGettersDirect(layer, x, y, +1);
            }
        }

        // Все активные — dirty на первый тик
        for (int i = 0; i < n; i++) {
            int p = activeCells[i];
            int layer = unpackLayer(p);
            int x = unpackX(p);
            int y = unpackY(p);
            inDirty[layer][y][x] = true;
            dirty[i] = p;
        }
        dirtyCount = n;

        activeCellsDirty = false;
    }

    // Прямой вклад в ns (без dirty) — для rebuild
    private void contributeForwardDirect(int layer, int x, int y, int delta) {
        int type = worldBlockTypes[layer][y][x];
        int dirIndex = worldBlockDirs[layer][y][x];

        switch (type) {
            case World.TYPE_ARROW, World.TYPE_AND, World.TYPE_OR,
                 World.TYPE_XOR, World.TYPE_NOT -> {
                int nx = x + DX[dirIndex];
                int ny = y + DY[dirIndex];
                if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                    ns[layer][ny][nx] += delta;
                }
            }
            case World.TYPE_BRIDGE -> {
                int nx = x + DX[dirIndex] * 2;
                int ny = y + DY[dirIndex] * 2;
                if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                    ns[layer][ny][nx] += delta;
                }
            }
            case World.TYPE_POWER -> {
                if (y > 0 && worldBlockTypes[layer][y-1][x] != World.TYPE_EMPTY)
                    ns[layer][y-1][x] += delta;
                if (y+1 < size && worldBlockTypes[layer][y+1][x] != World.TYPE_EMPTY)
                    ns[layer][y+1][x] += delta;
                if (x > 0 && worldBlockTypes[layer][y][x-1] != World.TYPE_EMPTY)
                    ns[layer][y][x-1] += delta;
                if (x+1 < size && worldBlockTypes[layer][y][x+1] != World.TYPE_EMPTY)
                    ns[layer][y][x+1] += delta;
            }
            // GETTER, PEG, TEXT не дают прямой forward-вклад здесь
        }
    }

    // GETTER за нашей спиной: пишем и в ns[G], и в forward-цель G
    private void contributeGettersDirect(int layer, int x, int y, int delta) {
        // GETTER сзади сверху (dir UP читает нас снизу)
        if (y > 0
                && worldBlockTypes[layer][y-1][x] == World.TYPE_GETTER
                && worldBlockDirs[layer][y-1][x] == 0) {
            ns[layer][y-1][x] += delta;                 // ns GETTER'а
            if (y-2 >= 0) ns[layer][y-2][x] += delta;   // forward GETTER'а
        }
        // GETTER справа (dir RIGHT читает нас слева)
        if (x+1 < size
                && worldBlockTypes[layer][y][x+1] == World.TYPE_GETTER
                && worldBlockDirs[layer][y][x+1] == 1) {
            ns[layer][y][x+1] += delta;
            if (x+2 < size) ns[layer][y][x+2] += delta;
        }
        // GETTER снизу (dir DOWN читает нас сверху)
        if (y+1 < size
                && worldBlockTypes[layer][y+1][x] == World.TYPE_GETTER
                && worldBlockDirs[layer][y+1][x] == 2) {
            ns[layer][y+1][x] += delta;
            if (y+2 < size) ns[layer][y+2][x] += delta;
        }
        // GETTER слева (dir LEFT читает нас справа)
        if (x > 0
                && worldBlockTypes[layer][y][x-1] == World.TYPE_GETTER
                && worldBlockDirs[layer][y][x-1] == 3) {
            ns[layer][y][x-1] += delta;
            if (x-2 >= 0) ns[layer][y][x-2] += delta;
        }
    }

    // ================================================================
    // ОСНОВНОЙ ТИК
    // ================================================================
    private void simulateTick() {
        if (activeCellsDirty) {
            rebuild();
        }

        // Если нечего обрабатывать — пустой тик
        if (dirtyCount == 0) {
            return;
        }

        // --- Шаг 1: вычислить новое состояние для dirty-клеток ---
        pendingChangesCount = 0;
        for (int i = 0; i < dirtyCount; i++) {
            int p = dirty[i];
            int layer = unpackLayer(p);
            int x = unpackX(p);
            int y = unpackY(p);

            inDirty[layer][y][x] = false;

            boolean newState = computeNextState(layer, x, y);
            if (newState != worldSignals[layer][y][x]) {
                pendingChanges[pendingChangesCount++] = p;
            }
        }
        dirtyCount = 0;

        // --- Шаг 2: применить изменения и распространить ---
        for (int i = 0; i < pendingChangesCount; i++) {
            int p = pendingChanges[i];
            int layer = unpackLayer(p);
            int x = unpackX(p);
            int y = unpackY(p);

            boolean newState = !worldSignals[layer][y][x];
            worldSignals[layer][y][x] = newState;
            worldBlockStates[layer][y][x] = newState;

            int delta = newState ? +1 : -1;
            propagateChange(layer, x, y, delta);
        }
        pendingChangesCount = 0;

        // --- Свап dirty / nextDirty ---
        int[] tmp = dirty;
        dirty = nextDirty;
        nextDirty = tmp;

        boolean[][][] tmpF = inDirty;
        inDirty = inNextDirty;
        inNextDirty = tmpF;

        dirtyCount = nextDirtyCount;
        nextDirtyCount = 0;
    }

    // ================================================================
    // Вычисление нового состояния из ns (и спец-случаев)
    // ================================================================
    private boolean computeNextState(int layer, int x, int y) {
        int type = worldBlockTypes[layer][y][x];
        if (type == World.TYPE_EMPTY) return false;
        if (type == World.TYPE_POWER) return true;

        int value = ns[layer][y][x];

        switch (type) {
            case World.TYPE_AND: return value > 1;
            case World.TYPE_OR:  return value >= 1;
            case World.TYPE_XOR: return (value & 1) == 1;
            case World.TYPE_NOT: return value == 0;
            case World.TYPE_PEG: {
                if (value >= 1) return true;
                for (int ol = 0; ol < layers; ol++) {
                    if (ol != layer
                            && worldBlockTypes[ol][y][x] == World.TYPE_PEG
                            && ns[ol][y][x] >= 1) {
                        return true;
                    }
                }
                return false;
            }
            case World.TYPE_TEXT: {
                // Как PEG, но строго в пределах одного слоя
                return value >= 1;
            }
            default: // ARROW, BRIDGE, GETTER
                return value > 0;
        }
    }

    // ================================================================
    // Распространение изменения состояния
    // ================================================================
    private void propagateChange(int layer, int x, int y, int delta) {
        int type = worldBlockTypes[layer][y][x];
        int dirIndex = worldBlockDirs[layer][y][x];

        // === Forward-вклад от этой клетки (кроме GETTER — он в блоке ниже) ===
        switch (type) {
            case World.TYPE_ARROW, World.TYPE_AND, World.TYPE_OR,
                 World.TYPE_XOR, World.TYPE_NOT -> {
                int nx = x + DX[dirIndex];
                int ny = y + DY[dirIndex];
                if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                    applyDelta(layer, nx, ny, delta);
                }
            }
            case World.TYPE_BRIDGE -> {
                int nx = x + DX[dirIndex] * 2;
                int ny = y + DY[dirIndex] * 2;
                if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                    applyDelta(layer, nx, ny, delta);
                }
            }
            // POWER не меняет состояние
            // PEG не даёт forward-вклада
            // TEXT не даёт forward-вклада (принимает, но не отдаёт)
            // GETTER обрабатывается ниже
        }

        // === GETTER за нашей спиной: транслируем delta напрямую ===
        // GETTER сзади сверху (dir UP) — читает нас, forward к (x, y-2)
        if (y > 0
                && worldBlockTypes[layer][y-1][x] == World.TYPE_GETTER
                && worldBlockDirs[layer][y-1][x] == 0) {
            applyDelta(layer, x, y-1, delta);           // ns GETTER'а
            if (y-2 >= 0) applyDelta(layer, x, y-2, delta);  // forward GETTER'а
        }
        // GETTER справа (dir RIGHT) — читает нас, forward к (x+2, y)
        if (x+1 < size
                && worldBlockTypes[layer][y][x+1] == World.TYPE_GETTER
                && worldBlockDirs[layer][y][x+1] == 1) {
            applyDelta(layer, x+1, y, delta);
            if (x+2 < size) applyDelta(layer, x+2, y, delta);
        }
        // GETTER снизу (dir DOWN) — читает нас, forward к (x, y+2)
        if (y+1 < size
                && worldBlockTypes[layer][y+1][x] == World.TYPE_GETTER
                && worldBlockDirs[layer][y+1][x] == 2) {
            applyDelta(layer, x, y+1, delta);
            if (y+2 < size) applyDelta(layer, x, y+2, delta);
        }
        // GETTER слева (dir LEFT) — читает нас, forward к (x-2, y)
        if (x > 0
                && worldBlockTypes[layer][y][x-1] == World.TYPE_GETTER
                && worldBlockDirs[layer][y][x-1] == 3) {
            applyDelta(layer, x-1, y, delta);
            if (x-2 >= 0) applyDelta(layer, x-2, y, delta);
        }
    }

    // Изменение ns с пометкой dirty и обработкой PEG-кросс-слоя
    private void applyDelta(int layer, int x, int y, int delta) {
        int oldNs = ns[layer][y][x];
        int newNs = oldNs + delta;
        ns[layer][y][x] = newNs;

        addToNextDirty(layer, x, y);

        // PEG: если ns пересёк порог 0↔1 — будим PEG в других слоях
        if (worldBlockTypes[layer][y][x] == World.TYPE_PEG) {
            boolean wasPos = oldNs >= 1;
            boolean isPos  = newNs >= 1;
            if (wasPos != isPos) {
                for (int ol = 0; ol < layers; ol++) {
                    if (ol != layer && worldBlockTypes[ol][y][x] == World.TYPE_PEG) {
                        addToNextDirty(ol, x, y);
                    }
                }
            }
        }
    }

    private void addToNextDirty(int layer, int x, int y) {
        if (inNextDirty[layer][y][x]) return;
        inNextDirty[layer][y][x] = true;
        nextDirty[nextDirtyCount++] = pack(layer, x, y);
    }

    // ================================================================
    // Поток симуляции
    // ================================================================
    public void setOnTickComplete(Runnable callback) { this.onTickComplete = callback; }

    public void start() {
        isPaused.set(false);
        isRunning.set(true);
        signalConfigChange();
    }

    public void pause() {
        isPaused.set(true);
        tpsSmoothed = 0;
        actualTps = 0;
    }

    public void stop() {
        isRunning.set(false);
        isPaused.set(false);
        synchronized (loopLock) {
            simThreadRunning = false;
            if (simThread != null) {
                simThread.interrupt();
                simThread = null;
            }
        }
    }

    public void step() {
        if (isPaused.get()) {
            simulateTick();
            tickCounter.incrementAndGet();
            if (onTickComplete != null) {
                javafx.application.Platform.runLater(onTickComplete);
            }
        }
    }

    public void setUnlimitedSpeed(boolean unlimited) {
        boolean was = unlimitedSpeed.getAndSet(unlimited);
        if (was != unlimited) {
            updateIntervalFromSpeed();
            signalConfigChange();
        }
    }

    public void setSpeed(int speed) {
        currentSpeed.set(speed);
        updateIntervalFromSpeed();
        signalConfigChange();
    }

    private void updateIntervalFromSpeed() {
        if (unlimitedSpeed.get()) {
            intervalNanos = -1;
            return;
        }
        int s = currentSpeed.get();
        intervalNanos = (s <= 0) ? 0 : (1_000_000_000L / s);
    }

    private void signalConfigChange() {
        configDirty = true;
        if (simThread != null) simThread.interrupt();
    }

    private void startSimThread() {
        synchronized (loopLock) {
            if (simThreadRunning) return;
            simThreadRunning = true;

            simThread = new Thread(() -> {
                long nextTickNanos = System.nanoTime();

                while (simThreadRunning) {
                    if (configDirty) {
                        configDirty = false;
                        long iv = intervalNanos;
                        if (iv > 0) nextTickNanos = System.nanoTime() + iv;
                    }

                    if (!isRunning.get() || isPaused.get() || intervalNanos == 0) {
                        try { Thread.sleep(1); } catch (InterruptedException e) {}
                        nextTickNanos = System.nanoTime();
                        continue;
                    }

                    // MAX-режим
                    if (intervalNanos < 0) {
                        simulateTick();
                        tickCounter.incrementAndGet();
                        if (onTickComplete != null) {
                            javafx.application.Platform.runLater(onTickComplete);
                        }
                        nextTickNanos = System.nanoTime();
                        continue;
                    }

                    long now = System.nanoTime();
                    long waitNanos = nextTickNanos - now;

                    if (waitNanos > 2_000_000L) {
                        long sleepMs = (waitNanos - 1_000_000L) / 1_000_000L;
                        if (sleepMs > 0) {
                            try { Thread.sleep(sleepMs); } catch (InterruptedException e) {}
                        }
                    } else if (waitNanos > 100_000L) {
                        Thread.yield();
                    }

                    now = System.nanoTime();
                    if (now < nextTickNanos) continue;

                    simulateTick();
                    tickCounter.incrementAndGet();
                    if (onTickComplete != null) {
                        javafx.application.Platform.runLater(onTickComplete);
                    }

                    long iv = intervalNanos;
                    if (iv <= 0) {
                        nextTickNanos = System.nanoTime();
                        continue;
                    }
                    nextTickNanos += iv;

                    long lateBy = System.nanoTime() - nextTickNanos;
                    if (lateBy > iv * 2) nextTickNanos = System.nanoTime() + iv;
                }
            }, "sim-thread");
            simThread.setDaemon(true);
            simThread.start();
        }
    }

    // ================================================================
    // TPS-счётчик
    // ================================================================
    private void startTpsCounter() {
        if (tpsThreadRunning) return;
        tpsThreadRunning = true;
        lastTpsUpdateNanos = System.nanoTime();

        tpsThread = new Thread(() -> {
            while (tpsThreadRunning) {
                long iv = intervalNanos;
                long windowNanos;
                if (iv <= 0) {
                    windowNanos = 200_000_000L;
                } else {
                    windowNanos = iv * 5;
                    if (windowNanos < 200_000_000L) windowNanos = 200_000_000L;
                    if (windowNanos > 2_000_000_000L) windowNanos = 2_000_000_000L;
                }
                try { Thread.sleep(windowNanos / 1_000_000L); }
                catch (InterruptedException e) { break; }

                long now = System.nanoTime();
                double elapsedSec = (now - lastTpsUpdateNanos) / 1_000_000_000.0;
                lastTpsUpdateNanos = now;
                if (elapsedSec <= 0.001) continue;

                int ticks = tickCounter.getAndSet(0);
                double instantaneous = ticks / elapsedSec;

                if (!isRunning.get() || isPaused.get() || intervalNanos == 0) {
                    tpsSmoothed = 0;
                    actualTps = 0;
                } else {
                    if (tpsSmoothed == 0) tpsSmoothed = instantaneous;
                    else tpsSmoothed = tpsSmoothed * 0.7 + instantaneous * 0.3;
                    actualTps = (int) Math.round(tpsSmoothed);
                }
            }
        }, "tps-counter");
        tpsThread.setDaemon(true);
        tpsThread.start();
    }

    private void stopTpsCounter() {
        tpsThreadRunning = false;
        if (tpsThread != null) {
            tpsThread.interrupt();
            tpsThread = null;
        }
        actualTps = 0;
        tpsSmoothed = 0;
    }

    public int getActualTps() { return actualTps; }

    public void forceStop() {
        stop();
        stopTpsCounter();
    }

    public boolean isPaused() { return isPaused.get(); }
    public boolean isRunning() { return isRunning.get() && !isPaused.get(); }
    public int getSpeed() { return currentSpeed.get(); }
}