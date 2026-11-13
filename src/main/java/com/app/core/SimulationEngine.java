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

    private final int[][][] worldBlockTypes;
    private final int[][][] worldBlockDirs;
    private final boolean[][][] worldBlockStates;
    private final boolean[][][] worldSignals;

    private final int[][][] ns;

    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DY = {-1, 0, 1, 0};

    private static long pack(int layer, int x, int y) { return ((long) layer << 32) | ((long) y << 16) | (long) x; }
    private static int unpackLayer(long p) { return (int) (p >>> 32) & 0xFF; }
    private static int unpackY(long p) { return (int) (p >>> 16) & 0xFFFF; }
    private static int unpackX(long p) { return (int) p & 0xFFFF; }

    private long[] dirty;
    private int dirtyCount = 0;
    private boolean[][][] inDirty;

    private long[] nextDirty;
    private int nextDirtyCount = 0;
    private boolean[][][] inNextDirty;

    private long[] pendingChanges;
    private int pendingChangesCount = 0;

    private long[] activeCells;
    private int activeCellsCount = 0;
    private volatile boolean activeCellsDirty = true;

    private final AtomicInteger currentSpeed = new AtomicInteger(60);
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicBoolean unlimitedSpeed = new AtomicBoolean(false);

    private volatile int tickCounter = 0;
    private volatile int actualTps = 0;
    private volatile double tpsSmoothed = 0;
    private volatile long lastTpsUpdateNanos = 0;
    private Thread tpsThread;
    private volatile boolean tpsThreadRunning = false;

    // === DEBUG: профилирование TPS ===
    private static final int TICK_HISTORY = 60;
    private volatile int tickSampleEvery = 10;
    private volatile int avgWindow       = 100;

    private final long[] tickNanosHistory = new long[TICK_HISTORY];
    private volatile int tickHistoryPos = 0;
    private volatile int tickHistoryCount = 0;

    private volatile long lastTickNanos = 0L;
    private volatile int  lastDirtyCount = 0;
    private volatile int  lastPendingChangesCount = 0;

    private volatile long lastRebuildNanos   = 0L;
    private volatile long lastPhase1Nanos    = 0L;
    private volatile long lastPhase2Nanos    = 0L;
    private volatile long lastPropagateNanos = 0L;

    private volatile int lastRebuildCells   = 0;
    private volatile int lastPropagateCalls = 0;

    // === агрегаты за окно avgWindow сэмплов ===
    private volatile long aggRebuildNs, aggPhase1Ns, aggPhase2Ns, aggPropagateNs;
    private volatile long aggMinTickNs, aggMaxTickNs;
    private volatile long aggTickSumNs;
    private volatile int  aggDirtySum, aggActiveSum, aggChangesSum, aggPropsSum;
    private volatile int  aggRebuildCellsMax;
    private volatile int  aggSampleCount;

    // «снимок» для оверлея — обновляется раз в avgWindow сэмплов
    private volatile long snapAvgTickNs, snapMinTickNs, snapMaxTickNs;
    private volatile long snapAvgRebuildNs, snapAvgPhase1Ns, snapAvgPhase2Ns;
    private volatile long snapAvgPropagateNs;
    private volatile int  snapAvgDirty, snapAvgActive, snapAvgChanges, snapAvgProps;
    private volatile int  snapRebuildCellsMax;
    private volatile int  snapProgressDone, snapProgressTotal;

    private long tickSampleCounter = 0;
    private boolean samplingThisTick = false;
    private int propagateCallsThisTick = 0;

    private volatile boolean tickProfiling = false;

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
        this.dirty             = new long[capacity];
        this.nextDirty         = new long[capacity];
        this.pendingChanges    = new long[capacity];
        this.activeCells       = new long[capacity];
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

        for (int i = 0; i < n; i++) {
            long p = activeCells[i];
            int layer = unpackLayer(p);
            int x = unpackX(p);
            int y = unpackY(p);
            if (worldSignals[layer][y][x]) {
                contributeForwardDirect(layer, x, y, +1);
                contributeGettersDirect(layer, x, y, +1);
            }
        }

        for (int i = 0; i < n; i++) {
            long p = activeCells[i];
            int layer = unpackLayer(p);
            int x = unpackX(p);
            int y = unpackY(p);
            inDirty[layer][y][x] = true;
            dirty[i] = p;
        }
        dirtyCount = n;

        activeCellsDirty = false;
    }

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
        }
    }

    private void contributeGettersDirect(int layer, int x, int y, int delta) {
        if (y > 0
                && worldBlockTypes[layer][y-1][x] == World.TYPE_GETTER
                && worldBlockDirs[layer][y-1][x] == 0) {
            ns[layer][y-1][x] += delta;
            if (y-2 >= 0) ns[layer][y-2][x] += delta;
        }
        if (x+1 < size
                && worldBlockTypes[layer][y][x+1] == World.TYPE_GETTER
                && worldBlockDirs[layer][y][x+1] == 1) {
            ns[layer][y][x+1] += delta;
            if (x+2 < size) ns[layer][y][x+2] += delta;
        }
        if (y+1 < size
                && worldBlockTypes[layer][y+1][x] == World.TYPE_GETTER
                && worldBlockDirs[layer][y+1][x] == 2) {
            ns[layer][y+1][x] += delta;
            if (y+2 < size) ns[layer][y+2][x] += delta;
        }
        if (x > 0
                && worldBlockTypes[layer][y][x-1] == World.TYPE_GETTER
                && worldBlockDirs[layer][y][x-1] == 3) {
            ns[layer][y][x-1] += delta;
            if (x-2 >= 0) ns[layer][y][x-2] += delta;
        }
    }

    private void simulateTick() {
        boolean prof = samplingThisTick;

        long tRebuild = 0, tPhase1 = 0, tPhase2 = 0, tProp = 0;
        int rebuildCells = 0;

        if (activeCellsDirty) {
            long t0 = prof ? System.nanoTime() : 0L;
            rebuild();
            if (prof) tRebuild = System.nanoTime() - t0;
            rebuildCells = activeCellsCount;
        }

        if (dirtyCount == 0) {
            lastPendingChangesCount = 0;
            lastRebuildCells = rebuildCells;
            if (prof) {
                lastRebuildNanos   = tRebuild;
                lastPhase1Nanos    = 0;
                lastPhase2Nanos    = 0;
                lastPropagateNanos = 0;
                lastPropagateCalls = 0;
            }
            return;
        }

        // ===== ФАЗА 1 =====
        long t1 = prof ? System.nanoTime() : 0L;
        pendingChangesCount = 0;
        for (int i = 0; i < dirtyCount; i++) {
            long p = dirty[i];
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
        if (prof) tPhase1 = System.nanoTime() - t1;

        // ===== ФАЗА 2 =====
        long t2 = prof ? System.nanoTime() : 0L;
        long propAcc = 0;
        propagateCallsThisTick = 0;

        int pc = pendingChangesCount;
        for (int i = 0; i < pc; i++) {
            long p = pendingChanges[i];
            int layer = unpackLayer(p);
            int x = unpackX(p);
            int y = unpackY(p);

            boolean newState = !worldSignals[layer][y][x];
            worldSignals[layer][y][x] = newState;
            worldBlockStates[layer][y][x] = newState;

            int delta = newState ? +1 : -1;
            if (prof) {
                long tp = System.nanoTime();
                propagateChange(layer, x, y, delta);
                propAcc += System.nanoTime() - tp;
            } else {
                propagateChange(layer, x, y, delta);
            }
        }
        if (prof) tPhase2 = System.nanoTime() - t2;

        lastPendingChangesCount = pendingChangesCount;
        lastRebuildCells = rebuildCells;
        lastPropagateCalls = propagateCallsThisTick;
        pendingChangesCount = 0;

        long[] tmp = dirty;
        dirty = nextDirty;
        nextDirty = tmp;

        boolean[][][] tmpF = inDirty;
        inDirty = inNextDirty;
        inNextDirty = tmpF;

        dirtyCount = nextDirtyCount;
        nextDirtyCount = 0;

        if (prof) {
            lastRebuildNanos   = tRebuild;
            lastPhase1Nanos    = tPhase1;
            lastPhase2Nanos    = tPhase2;
            lastPropagateNanos = propAcc;
        }
    }

    private boolean computeNextState(int layer, int x, int y) {
        int[] typeRow = worldBlockTypes[layer][y];
        int type = typeRow[x];
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
                return value >= 1;
            }
            default:
                return value > 0;
        }
    }

    private void propagateChange(int layer, int x, int y, int delta) {
        int[][] typeLayer = worldBlockTypes[layer];
        int[][] dirLayer  = worldBlockDirs[layer];

        int type = typeLayer[y][x];
        int dirIndex = dirLayer[y][x];

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
        }

        if (y > 0
                && typeLayer[y-1][x] == World.TYPE_GETTER
                && dirLayer[y-1][x] == 0) {
            applyDelta(layer, x, y-1, delta);
            if (y-2 >= 0) applyDelta(layer, x, y-2, delta);
        }
        if (x+1 < size
                && typeLayer[y][x+1] == World.TYPE_GETTER
                && dirLayer[y][x+1] == 1) {
            applyDelta(layer, x+1, y, delta);
            if (x+2 < size) applyDelta(layer, x+2, y, delta);
        }
        if (y+1 < size
                && typeLayer[y+1][x] == World.TYPE_GETTER
                && dirLayer[y+1][x] == 2) {
            applyDelta(layer, x, y+1, delta);
            if (y+2 < size) applyDelta(layer, x, y+2, delta);
        }
        if (x > 0
                && typeLayer[y][x-1] == World.TYPE_GETTER
                && dirLayer[y][x-1] == 3) {
            applyDelta(layer, x-1, y, delta);
            if (x-2 >= 0) applyDelta(layer, x-2, y, delta);
        }
    }

    private void applyDelta(int layer, int x, int y, int delta) {
        propagateCallsThisTick++;

        int oldNs = ns[layer][y][x];
        int newNs = oldNs + delta;
        ns[layer][y][x] = newNs;

        addToNextDirty(layer, x, y);

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
            tickOnce();
        }
    }

    private void tickOnce() {
        int every = tickSampleEvery;
        if (every < 1) every = 1;

        samplingThisTick = tickProfiling
                && (tickSampleCounter++ % every) == 0;

        long t0 = samplingThisTick ? System.nanoTime() : 0L;
        int dirtyBefore = dirtyCount;
        simulateTick();
        lastDirtyCount = dirtyBefore;

        if (samplingThisTick) {
            long dt = System.nanoTime() - t0;
            lastTickNanos = dt;

            int pos = tickHistoryPos;
            tickNanosHistory[pos] = dt;
            if (++pos >= TICK_HISTORY) pos = 0;
            tickHistoryPos = pos;
            if (tickHistoryCount < TICK_HISTORY) tickHistoryCount++;

            accumulateSample(dt);
        }

        tickCounter++;

        if (onTickComplete != null) {
            javafx.application.Platform.runLater(onTickComplete);
        }
    }

    private void accumulateSample(long tickNs) {
        int window = avgWindow;
        if (window < 1) window = 1;

        aggTickSumNs   += tickNs;
        aggRebuildNs   += lastRebuildNanos;
        aggPhase1Ns    += lastPhase1Nanos;
        aggPhase2Ns    += lastPhase2Nanos;
        aggPropagateNs += lastPropagateNanos;

        if (aggSampleCount == 0) {
            aggMinTickNs = tickNs;
            aggMaxTickNs = tickNs;
        } else {
            if (tickNs < aggMinTickNs) aggMinTickNs = tickNs;
            if (tickNs > aggMaxTickNs) aggMaxTickNs = tickNs;
        }

        aggDirtySum   += lastDirtyCount;
        aggActiveSum  += activeCellsCount;
        aggChangesSum += lastPendingChangesCount;
        aggPropsSum   += lastPropagateCalls;

        if (lastRebuildCells > aggRebuildCellsMax) {
            aggRebuildCellsMax = lastRebuildCells;
        }

        aggSampleCount++;

        if (aggSampleCount >= window) {
            int n = aggSampleCount;

            snapAvgTickNs      = aggTickSumNs / n;
            snapMinTickNs      = aggMinTickNs;
            snapMaxTickNs      = aggMaxTickNs;
            snapAvgRebuildNs   = aggRebuildNs / n;
            snapAvgPhase1Ns    = aggPhase1Ns / n;
            snapAvgPhase2Ns    = aggPhase2Ns / n;
            snapAvgPropagateNs = aggPropagateNs / n;
            snapAvgDirty       = aggDirtySum / n;
            snapAvgActive      = aggActiveSum / n;
            snapAvgChanges     = aggChangesSum / n;
            snapAvgProps       = aggPropsSum / n;
            snapRebuildCellsMax = aggRebuildCellsMax;
            snapProgressDone   = n;
            snapProgressTotal  = window;

            aggTickSumNs = aggRebuildNs = aggPhase1Ns = aggPhase2Ns = 0;
            aggPropagateNs = 0;
            aggMinTickNs = aggMaxTickNs = 0;
            aggDirtySum = aggActiveSum = aggChangesSum = aggPropsSum = 0;
            aggRebuildCellsMax = 0;
            aggSampleCount = 0;
        }

        snapProgressDone  = aggSampleCount;
        snapProgressTotal = window;
    }

    public void setTickProfiling(boolean enabled) {
        this.tickProfiling = enabled;
        if (!enabled) resetDebugAggregates();
    }

    public void setTickSampleEvery(int every) {
        if (every < 1) every = 1;
        this.tickSampleEvery = every;
    }

    public void setAvgWindow(int window) {
        if (window < 1) window = 1;
        this.avgWindow = window;
        resetDebugAggregates();
    }

    public int getTickSampleEvery() { return tickSampleEvery; }
    public int getAvgWindow()       { return avgWindow; }

    private void resetDebugAggregates() {
        tickHistoryCount = 0;
        tickHistoryPos = 0;
        lastTickNanos = 0L;
        lastRebuildNanos = lastPhase1Nanos = lastPhase2Nanos = 0L;
        lastPropagateNanos = 0L;
        lastRebuildCells = 0;
        lastPropagateCalls = 0;

        aggTickSumNs = aggRebuildNs = aggPhase1Ns = aggPhase2Ns = 0;
        aggPropagateNs = 0;
        aggMinTickNs = aggMaxTickNs = 0;
        aggDirtySum = aggActiveSum = aggChangesSum = aggPropsSum = 0;
        aggRebuildCellsMax = 0;
        aggSampleCount = 0;

        snapAvgTickNs = snapMinTickNs = snapMaxTickNs = 0;
        snapAvgRebuildNs = snapAvgPhase1Ns = snapAvgPhase2Ns = 0;
        snapAvgPropagateNs = 0;
        snapAvgDirty = snapAvgActive = snapAvgChanges = snapAvgProps = 0;
        snapRebuildCellsMax = 0;
        snapProgressDone = snapProgressTotal = 0;

        java.util.Arrays.fill(tickNanosHistory, 0L);
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

                    if (intervalNanos < 0) {
                        tickOnce();
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

                    tickOnce();

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

                int ticks = tickCounter;
                tickCounter = 0;
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

    // === DEBUG: «живые» метрики последнего сэмпла ===
    public int getDirtyCount()          { return lastDirtyCount; }
    public int getActiveCellsCount()    { return activeCellsCount; }
    public int getPendingChangesCount() { return lastPendingChangesCount; }
    public int getLastPropagateCalls()  { return lastPropagateCalls; }
    public int getLastRebuildCells()    { return lastRebuildCells; }

    public double getLastTickNs() { return lastTickNanos; }
    public double getRebuildNs()  { return lastRebuildNanos; }
    public double getPhase1Ns()   { return lastPhase1Nanos; }
    public double getPhase2Ns()   { return lastPhase2Nanos; }
    public double getPropagateNs(){ return lastPropagateNanos; }

    // === DEBUG: агрегаты окна ===
    public double getSnapAvgTickNs()       { return snapAvgTickNs; }
    public double getSnapMinTickNs()       { return snapMinTickNs; }
    public double getSnapMaxTickNs()       { return snapMaxTickNs; }
    public double getSnapAvgRebuildNs()    { return snapAvgRebuildNs; }
    public double getSnapAvgPhase1Ns()     { return snapAvgPhase1Ns; }
    public double getSnapAvgPhase2Ns()     { return snapAvgPhase2Ns; }
    public double getSnapAvgPropagateNs()  { return snapAvgPropagateNs; }
    public int getSnapAvgDirty()           { return snapAvgDirty; }
    public int getSnapAvgActive()          { return snapAvgActive; }
    public int getSnapAvgChanges()         { return snapAvgChanges; }
    public int getSnapAvgProps()           { return snapAvgProps; }
    public int getSnapRebuildCellsMax()    { return snapRebuildCellsMax; }
    public int getSnapProgressDone()       { return snapProgressDone; }
    public int getSnapProgressTotal()      { return snapProgressTotal; }

    public void forceStop() {
        stop();
        stopTpsCounter();
    }

    public boolean isPaused() { return isPaused.get(); }
    public boolean isRunning() { return isRunning.get() && !isPaused.get(); }
    public int getSpeed() { return currentSpeed.get(); }
}