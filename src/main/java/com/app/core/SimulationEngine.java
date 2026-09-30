package com.app.core;

import com.app.Config;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class SimulationEngine {
    private final World world;
    private final int size;
    private final int layers;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> currentTask;
    private Runnable onTickComplete;

    private int[][][] newSignals;
    private boolean[][][] verticalInput;        // вход от PEG другого слоя
    private boolean[][][] pegHorizontalInput;   // вход от блока с направления dir

    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DY = {-1, 0, 1, 0};

    private final AtomicInteger currentSpeed = new AtomicInteger(60);
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicBoolean unlimitedSpeed = new AtomicBoolean(false);

    public SimulationEngine(World world) {
        this.world = world;
        this.size = world.getSize();
        this.layers = world.getLayers();
        this.newSignals = new int[layers][size][size];
        this.verticalInput = new boolean[layers][size][size];
        this.pegHorizontalInput = new boolean[layers][size][size];
        this.currentSpeed.set(Config.getInstance().getSimulationSpeed());
        startLoop();
    }

    public void setOnTickComplete(Runnable callback) {
        this.onTickComplete = callback;
    }

    public void start() {
        isPaused.set(false);
        isRunning.set(true);
        if (currentTask == null || currentTask.isCancelled()) {
            startLoop();
        }
    }

    public void pause() {
        isPaused.set(true);
    }

    public void stop() {
        isRunning.set(false);
        isPaused.set(false);
        if (currentTask != null) {
            currentTask.cancel(false);
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    public void step() {
        if (isPaused.get()) {
            simulateTick();
            if (onTickComplete != null) {
                javafx.application.Platform.runLater(onTickComplete);
            }
        }
    }

    public void setUnlimitedSpeed(boolean unlimited) {
        unlimitedSpeed.set(unlimited);
        restartLoop();
    }

    public void setSpeed(int speed) {
        currentSpeed.set(speed);
        restartLoop();
    }

    private void restartLoop() {
        if (currentTask != null) {
            currentTask.cancel(false);
        }
        startLoop();
    }

    private void startLoop() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor();
        }

        long delay;
        if (unlimitedSpeed.get()) {
            delay = 1;
        } else {
            int speed = currentSpeed.get();
            if (speed <= 0) {
                delay = 1000;
            } else {
                delay = 1000 / speed;
            }
        }

        currentTask = executor.scheduleAtFixedRate(() -> {
            if (!isRunning.get() || isPaused.get()) {
                return;
            }
            if (currentSpeed.get() == 0 && !unlimitedSpeed.get()) {
                return;
            }

            simulateTick();
            if (onTickComplete != null) {
                javafx.application.Platform.runLater(onTickComplete);
            }
        }, 0, Math.max(1, delay), TimeUnit.MILLISECONDS);
    }

    private void simulateTick() {
        // Обнуляем массивы
        for (int l = 0; l < layers; l++) {
            for (int y = 0; y < size; y++) {
                Arrays.fill(newSignals[l][y], 0);
                Arrays.fill(verticalInput[l][y], false);
                Arrays.fill(pegHorizontalInput[l][y], false);
            }
        }

        // =====================================================================
        // ПРОХОД 1: первичные источники (ARROW, AND, OR, XOR, NOT, BRIDGE,
        //           POWER, GETTER) — распространение сигнала в направлении dir
        // =====================================================================
        for (int layer = 0; layer < layers; layer++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int blockType = world.blockTypes[layer][y][x];
                    if (blockType == World.TYPE_EMPTY) continue;
                    if (blockType == World.TYPE_PEG) continue; // PEG обрабатывается отдельно

                    int dirIndex = world.blockDirs[layer][y][x];
                    boolean currentSignal = world.getSignal(layer, x, y);
                    int value = currentSignal ? 1 : 0;

                    int nx = x + DX[dirIndex];
                    int ny = y + DY[dirIndex];

                    switch (blockType) {
                        case World.TYPE_ARROW, World.TYPE_AND, World.TYPE_OR,
                             World.TYPE_XOR, World.TYPE_NOT -> {
                            if (value == 1 && nx >= 0 && nx < size && ny >= 0 && ny < size) {
                                newSignals[layer][ny][nx] += 1;
                            }
                        }
                        case World.TYPE_BRIDGE -> {
                            if (value == 1) {
                                int bx = x + DX[dirIndex] * 2;
                                int by = y + DY[dirIndex] * 2;
                                if (bx >= 0 && bx < size && by >= 0 && by < size) {
                                    newSignals[layer][by][bx] += 1;
                                }
                            }
                        }
                        case World.TYPE_POWER -> {
                            newSignals[layer][y][x] = 1;

                            if (y > 0 && world.blockTypes[layer][y - 1][x] != World.TYPE_EMPTY)
                                newSignals[layer][y - 1][x] = 1;
                            if (y + 1 < size && world.blockTypes[layer][y + 1][x] != World.TYPE_EMPTY)
                                newSignals[layer][y + 1][x] = 1;
                            if (x > 0 && world.blockTypes[layer][y][x - 1] != World.TYPE_EMPTY)
                                newSignals[layer][y][x - 1] = 1;
                            if (x + 1 < size && world.blockTypes[layer][y][x + 1] != World.TYPE_EMPTY)
                                newSignals[layer][y][x + 1] = 1;
                        }
                        case World.TYPE_GETTER -> {
                            int backIndex = (dirIndex + 2) % 4;
                            int bx = x + DX[backIndex];
                            int by = y + DY[backIndex];
                            boolean backSignal = bx >= 0 && bx < size && by >= 0 && by < size
                                    && world.getSignal(layer, bx, by);
                            if (backSignal) {
                                newSignals[layer][y][x] = 1;
                                if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                                    newSignals[layer][ny][nx] += 1;
                                }
                            }
                        }
                    }
                }
            }
        }

        // =====================================================================
        // ПРОХОД 2A: PEG — приём сигнала с направления dir (сзади)
        //   Если в клетку PEG пришёл сигнал от блока, стоящего сзади
        //   (на клетке x - DX[dir], y - DY[dir]), то PEG активируется
        //   и передаёт сигнал ВЕРТИКАЛЬНО на PEG'и других слоёв в той же (x,y).
        // =====================================================================
        for (int layer = 0; layer < layers; layer++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (world.blockTypes[layer][y][x] != World.TYPE_PEG) continue;

                    int dirIndex = world.blockDirs[layer][y][x];

                    // Клетка сзади (откуда PEG должен принимать сигнал)
                    int backIndex = (dirIndex + 2) % 4;
                    int srcX = x + DX[backIndex];
                    int srcY = y + DY[backIndex];

                    if (srcX < 0 || srcX >= size || srcY < 0 || srcY >= size) continue;

                    // Проверяем: сигнал пришёл в PEG именно с направления backIndex,
                    // т.е. блок сзади активен и шлёт сигнал в нашу клетку.
                    boolean hasHorizontalInput = false;
                    if (world.getSignal(layer, srcX, srcY)) {
                        // Блок сзади активен — проверим, что он действительно
                        // шлёт сигнал в нашу клетку (направление блока сзади
                        // должно указывать на PEG).
                        int srcType = world.blockTypes[layer][srcY][srcX];
                        int srcDir = world.blockDirs[layer][srcY][srcX];
                        int srcNx = srcX + DX[srcDir];
                        int srcNy = srcY + DY[srcDir];
                        // BRIDGE бьёт на 2 клетки
                        boolean bridgeHits = (srcType == World.TYPE_BRIDGE)
                                && (srcX + DX[srcDir] * 2 == x)
                                && (srcY + DY[srcDir] * 2 == y);
                        boolean normalHits = (srcNx == x) && (srcNy == y);
                        // POWER шлёт во все стороны
                        boolean powerHits = (srcType == World.TYPE_POWER);

                        if (normalHits || bridgeHits || powerHits) {
                            hasHorizontalInput = true;
                        }
                    }

                    // Резервный вариант: сигнал уже посчитан в newSignals этой клетки
                    // (например, от POWER в этом же такте)
                    if (!hasHorizontalInput && newSignals[layer][y][x] >= 1) {
                        hasHorizontalInput = true;
                    }

                    if (!hasHorizontalInput) continue;

                    pegHorizontalInput[layer][y][x] = true;

                    // Передаём ВЕРТИКАЛЬНО только PEG'ам других слоёв
                    for (int otherLayer = 0; otherLayer < layers; otherLayer++) {
                        if (otherLayer == layer) continue;
                        if (world.blockTypes[otherLayer][y][x] == World.TYPE_PEG) {
                            verticalInput[otherLayer][y][x] = true;
                        }
                    }
                }
            }
        }

        // =====================================================================
        // ПРОХОД 2B: PEG — передача сигнала
        //   - Горизонтальный вход → вертикальная передача уже сделана в 2A.
        //   - Вертикальный вход (от PEG другого слоя) → передаём сигнал
        //     ВПЕРЁД по направлению dir (на клетку x + DX[dir], y + DY[dir]).
        //   - Также ставим сигнал САМОЙ PEG, чтобы GETTER на ней же мог забрать.
        // =====================================================================
        for (int layer = 0; layer < layers; layer++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (world.blockTypes[layer][y][x] != World.TYPE_PEG) continue;

                    boolean hasVertical = verticalInput[layer][y][x];
                    boolean hasHorizontal = pegHorizontalInput[layer][y][x];

                    if (!hasVertical && !hasHorizontal) continue;

                    // Ставим сигнал самой PEG (для GETTER'а на этой же клетке)
                    newSignals[layer][y][x] = 1;

                    // Если есть ВЕРТИКАЛЬНЫЙ вход — передаём вперёд по направлению
                    if (hasVertical) {
                        int dirIndex = world.blockDirs[layer][y][x];
                        int outX = x + DX[dirIndex];
                        int outY = y + DY[dirIndex];

                        if (outX >= 0 && outX < size && outY >= 0 && outY < size) {
                            // Не передаём другим PEG'ам «вперёд» — PEG принимает
                            // только сзади (backIndex). Иначе получится петля.
                            if (world.blockTypes[layer][outY][outX] != World.TYPE_PEG) {
                                newSignals[layer][outY][outX] += 1;
                            }
                        }
                    }
                }
            }
        }

        // =====================================================================
        // ПРОХОД 3: обновление состояний всех блоков по newSignals
        // =====================================================================
        for (int layer = 0; layer < layers; layer++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int blockType = world.blockTypes[layer][y][x];

                    if (blockType == World.TYPE_EMPTY) {
                        world.setSignal(layer, x, y, false);
                        continue;
                    }

                    if (blockType == World.TYPE_POWER) {
                        world.setSignal(layer, x, y, true);
                        world.updateBlockState(layer, x, y, true, world.blockDirs[layer][y][x]);
                        continue;
                    }

                    int dirIndex = world.blockDirs[layer][y][x];
                    int value = newSignals[layer][y][x];
                    boolean newSignal;

                    switch (blockType) {
                        case World.TYPE_AND -> newSignal = value > 1;
                        case World.TYPE_OR -> newSignal = value >= 1;
                        case World.TYPE_XOR -> newSignal = (value & 1) == 1;
                        case World.TYPE_NOT -> newSignal = value == 0;
                        case World.TYPE_PEG -> newSignal = value >= 1;   // ← исправлено
                        default -> newSignal = value > 0;
                    }

                    world.setSignal(layer, x, y, newSignal);
                    world.updateBlockState(layer, x, y, newSignal, dirIndex);
                }
            }
        }
    }

    public void forceStop() {
        stop();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    public boolean isPaused() { return isPaused.get(); }
    public boolean isRunning() { return isRunning.get() && !isPaused.get(); }
    public int getSpeed() { return currentSpeed.get(); }
}