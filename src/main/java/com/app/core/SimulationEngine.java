package com.app.core;

import com.app.Config;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class SimulationEngine {
    private final World world;
    private final int size;
    private final int layers;
    private final int layerSize;
    private Runnable onTickComplete;

    private final int[] worldBlockTypes;
    private final int[] worldBlockDirs;
    private final boolean[] worldBlockStates;
    private final boolean[] worldSignals;

    private final int[] ns;
    private boolean[] inDirty;
    private boolean[] inNextDirty;

    private final int[] outNeighbor;
    private final int[] bridgeNeighbor;

    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DY = {-1, 0, 1, 0};

    private int[] dirty;
    private int dirtyCount = 0;
    private int[] nextDirty;
    private int nextDirtyCount = 0;
    private int[] pendingChanges;
    private int pendingChangesCount = 0;
    private int[] activeCells;
    private int activeCellsCount = 0;

    private volatile boolean activeCellsDirty = true;
    private boolean hasPegs = false;

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

    // === DEBUG ===
    private static final int TICK_HISTORY = 60;
    private volatile int tickSampleEvery = 10;
    private volatile int avgWindow = 100;
    private final long[] tickNanosHistory = new long[TICK_HISTORY];
    private volatile int tickHistoryPos = 0;
    private volatile int tickHistoryCount = 0;

    private volatile long lastTickNanos = 0L;
    private volatile int lastDirtyCount = 0;
    private volatile int lastPendingChangesCount = 0;

    private volatile long lastRebuildNanos = 0L;
    private volatile long lastPhase1Nanos = 0L;
    private volatile long lastPhase2Nanos = 0L;
    private volatile long lastPropagateNanos = 0L;

    private volatile int lastRebuildCells = 0;
    private volatile int lastPropagateCalls = 0;

    private volatile long aggRebuildNs, aggPhase1Ns, aggPhase2Ns, aggPropagateNs;
    private volatile long aggMinTickNs, aggMaxTickNs, aggTickSumNs;
    private volatile int aggDirtySum, aggActiveSum, aggChangesSum, aggPropsSum;
    private volatile int aggRebuildCellsMax;
    private volatile int aggSampleCount;

    private volatile long snapAvgTickNs, snapMinTickNs, snapMaxTickNs;
    private volatile long snapAvgRebuildNs, snapAvgPhase1Ns, snapAvgPhase2Ns, snapAvgPropagateNs;
    private volatile int snapAvgDirty, snapAvgActive, snapAvgChanges, snapAvgProps;
    private volatile int snapRebuildCellsMax;
    private volatile int snapProgressDone, snapProgressTotal;

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
        this.layerSize = size * size;

        this.worldBlockTypes = world.blockTypes;
        this.worldBlockDirs = world.blockDirs;
        this.worldBlockStates = world.blockStates;
        this.worldSignals = world.getSignalsArray();

        int capacity = layers * layerSize;
        this.ns = new int[capacity];
        this.inDirty = new boolean[capacity];
        this.inNextDirty = new boolean[capacity];
        this.outNeighbor = new int[capacity];
        this.bridgeNeighbor = new int[capacity];

        this.dirty = new int[capacity];
        this.nextDirty = new int[capacity];
        this.pendingChanges = new int[capacity];
        this.activeCells = new int[capacity];

        Arrays.fill(outNeighbor, -1);
        Arrays.fill(bridgeNeighbor, -1);

        this.currentSpeed.set(Config.getInstance().getSimulationSpeed());
        updateIntervalFromSpeed();
        startSimThread();
        startTpsCounter();
    }

    private int idx(int layer, int x, int y) {
        return layer * layerSize + y * size + x;
    }

    private int getX(int idx) { return idx % size; }
    private int getY(int idx) { return (idx / size) % size; }
    private int getLayer(int idx) { return idx / layerSize; }

    public void markWorldChanged() {
        activeCellsDirty = true;
        configDirty = true;
    }

    private void rebuild() {
        int n = 0;
        hasPegs = false;

        for (int i = 0; i < worldBlockTypes.length; i++) {
            int type = worldBlockTypes[i];
            if (type != World.TYPE_EMPTY) {
                activeCells[n++] = i;
                if (type == World.TYPE_PEG) hasPegs = true;
            }
        }
        activeCellsCount = n;

        Arrays.fill(ns, 0);
        Arrays.fill(inDirty, false);
        Arrays.fill(inNextDirty, false);
        Arrays.fill(outNeighbor, -1);
        Arrays.fill(bridgeNeighbor, -1);

        dirtyCount = 0;
        nextDirtyCount = 0;
        pendingChangesCount = 0;

        for (int i = 0; i < n; i++) {
            int id = activeCells[i];
            int type = worldBlockTypes[id];
            int dir = worldBlockDirs[id];

            if (type == World.TYPE_ARROW || type == World.TYPE_AND ||
                    type == World.TYPE_OR || type == World.TYPE_XOR || type == World.TYPE_NOT) {
                int x = getX(id), y = getY(id), layer = getLayer(id);
                int nx = x + DX[dir];
                int ny = y + DY[dir];
                if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                    outNeighbor[id] = idx(layer, nx, ny);
                }
            } else if (type == World.TYPE_BRIDGE) {
                int x = getX(id), y = getY(id), layer = getLayer(id);
                int nx = x + DX[dir] * 2;
                int ny = y + DY[dir] * 2;
                if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                    bridgeNeighbor[id] = idx(layer, nx, ny);
                }
            }
        }

        for (int i = 0; i < n; i++) {
            int id = activeCells[i];
            if (worldSignals[id]) {
                contributeForwardDirect(id, +1);
                contributeGettersDirect(id, +1);
            }
        }

        for (int i = 0; i < n; i++) {
            int id = activeCells[i];
            inDirty[id] = true;
            dirty[i] = id;
        }
        dirtyCount = n;
        activeCellsDirty = false;
    }

    private void contributeForwardDirect(int id, int delta) {
        int type = worldBlockTypes[id];

        if (type == World.TYPE_ARROW || type == World.TYPE_AND ||
                type == World.TYPE_OR || type == World.TYPE_XOR || type == World.TYPE_NOT) {
            int target = outNeighbor[id];
            if (target != -1) ns[target] += delta;
        } else if (type == World.TYPE_BRIDGE) {
            int target = bridgeNeighbor[id];
            if (target != -1) ns[target] += delta;
        } else if (type == World.TYPE_POWER) {
            int x = getX(id), y = getY(id), layer = getLayer(id);
            int base = layer * layerSize;
            if (y > 0 && worldBlockTypes[base + (y-1)*size + x] != World.TYPE_EMPTY) ns[base + (y-1)*size + x] += delta;
            if (y+1 < size && worldBlockTypes[base + (y+1)*size + x] != World.TYPE_EMPTY) ns[base + (y+1)*size + x] += delta;
            if (x > 0 && worldBlockTypes[base + y*size + (x-1)] != World.TYPE_EMPTY) ns[base + y*size + (x-1)] += delta;
            if (x+1 < size && worldBlockTypes[base + y*size + (x+1)] != World.TYPE_EMPTY) ns[base + y*size + (x+1)] += delta;
        }
    }

    private void contributeGettersDirect(int id, int delta) {
        int x = getX(id), y = getY(id), layer = getLayer(id);
        int base = layer * layerSize;

        if (y > 0) {
            int target = base + (y-1)*size + x;
            if (worldBlockTypes[target] == World.TYPE_GETTER && worldBlockDirs[target] == 0) {
                ns[target] += delta;
                if (y-2 >= 0) ns[base + (y-2)*size + x] += delta;
            }
        }
        if (x+1 < size) {
            int target = base + y*size + (x+1);
            if (worldBlockTypes[target] == World.TYPE_GETTER && worldBlockDirs[target] == 1) {
                ns[target] += delta;
                if (x+2 < size) ns[base + y*size + (x+2)] += delta;
            }
        }
        if (y+1 < size) {
            int target = base + (y+1)*size + x;
            if (worldBlockTypes[target] == World.TYPE_GETTER && worldBlockDirs[target] == 2) {
                ns[target] += delta;
                if (y+2 < size) ns[base + (y+2)*size + x] += delta;
            }
        }
        if (x > 0) {
            int target = base + y*size + (x-1);
            if (worldBlockTypes[target] == World.TYPE_GETTER && worldBlockDirs[target] == 3) {
                ns[target] += delta;
                if (x-2 >= 0) ns[base + y*size + (x-2)] += delta;
            }
        }
    }

    private void simulateTick() {
        boolean prof = samplingThisTick;
        long tRebuild = 0, tPhase1 = 0, tPhase2 = 0;
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
                lastRebuildNanos = tRebuild;
                lastPhase1Nanos = 0; lastPhase2Nanos = 0; lastPropagateNanos = 0; lastPropagateCalls = 0;
            }
            return;
        }

        long t1 = prof ? System.nanoTime() : 0L;
        pendingChangesCount = 0;
        for (int i = 0; i < dirtyCount; i++) {
            int id = dirty[i];
            inDirty[id] = false;
            boolean newState = computeNextState(id);
            if (newState != worldSignals[id]) {
                pendingChanges[pendingChangesCount++] = id;
            }
        }
        dirtyCount = 0;
        if (prof) tPhase1 = System.nanoTime() - t1;

        long t2 = prof ? System.nanoTime() : 0L;
        long propAcc = 0;
        propagateCallsThisTick = 0;

        int pc = pendingChangesCount;
        for (int i = 0; i < pc; i++) {
            int id = pendingChanges[i];
            boolean newState = !worldSignals[id];

            worldSignals[id] = newState;
            worldBlockStates[id] = newState;

            int delta = newState ? +1 : -1;
            if (prof) {
                long tp = System.nanoTime();
                propagateChange(id, delta);
                propAcc += System.nanoTime() - tp;
            } else {
                propagateChange(id, delta);
            }
        }
        if (prof) tPhase2 = System.nanoTime() - t2;

        lastPendingChangesCount = pendingChangesCount;
        lastRebuildCells = rebuildCells;
        lastPropagateCalls = propagateCallsThisTick;
        pendingChangesCount = 0;

        int[] tmp = dirty;
        dirty = nextDirty;
        nextDirty = tmp;

        boolean[] tmpF = inDirty;
        inDirty = inNextDirty;
        inNextDirty = tmpF;

        dirtyCount = nextDirtyCount;
        nextDirtyCount = 0;

        if (prof) {
            lastRebuildNanos = tRebuild;
            lastPhase1Nanos = tPhase1;
            lastPhase2Nanos = tPhase2;
            lastPropagateNanos = propAcc;
        }
    }

    private boolean computeNextState(int idx) {
        int type = worldBlockTypes[idx];
        if (type == World.TYPE_EMPTY) return false;
        if (type == World.TYPE_POWER) return true;

        int value = ns[idx];

        switch (type) {
            case World.TYPE_AND: return value > 1;
            case World.TYPE_OR:  return value >= 1;
            case World.TYPE_XOR: return (value & 1) == 1;
            case World.TYPE_NOT: return value == 0;
            case World.TYPE_PEG: {
                if (value >= 1) return true;
                if (!hasPegs) return false;

                int x = getX(idx), y = getY(idx), layer = getLayer(idx);
                for (int ol = 0; ol < layers; ol++) {
                    if (ol != layer) {
                        int otherIdx = ol * layerSize + y * size + x;
                        if (worldBlockTypes[otherIdx] == World.TYPE_PEG && ns[otherIdx] >= 1) return true;
                    }
                }
                return false;
            }
            case World.TYPE_TEXT: return value >= 1;
            default: return value > 0;
        }
    }

    private void propagateChange(int id, int delta) {
        int type = worldBlockTypes[id];

        if (type == World.TYPE_ARROW || type == World.TYPE_AND || type == World.TYPE_OR || type == World.TYPE_XOR || type == World.TYPE_NOT) {
            int target = outNeighbor[id];
            if (target != -1) applyDelta(target, delta);
        }
        else if (type == World.TYPE_BRIDGE) {
            int target = bridgeNeighbor[id];
            if (target != -1) applyDelta(target, delta);
        }
        else if (type == World.TYPE_POWER) {
            int x = getX(id), y = getY(id), layer = getLayer(id);
            int base = layer * layerSize;
            if (y > 0 && worldBlockTypes[base + (y-1)*size + x] != World.TYPE_EMPTY) applyDelta(base + (y-1)*size + x, delta);
            if (y+1 < size && worldBlockTypes[base + (y+1)*size + x] != World.TYPE_EMPTY) applyDelta(base + (y+1)*size + x, delta);
            if (x > 0 && worldBlockTypes[base + y*size + (x-1)] != World.TYPE_EMPTY) applyDelta(base + y*size + (x-1), delta);
            if (x+1 < size && worldBlockTypes[base + y*size + (x+1)] != World.TYPE_EMPTY) applyDelta(base + y*size + (x+1), delta);
        }

        int x = getX(id), y = getY(id), layer = getLayer(id);
        int base = layer * layerSize;

        if (y > 0) {
            int target = base + (y-1)*size + x;
            if (worldBlockTypes[target] == World.TYPE_GETTER && worldBlockDirs[target] == 0) {
                applyDelta(target, delta);
                if (y-2 >= 0) applyDelta(base + (y-2)*size + x, delta);
            }
        }
        if (x+1 < size) {
            int target = base + y*size + (x+1);
            if (worldBlockTypes[target] == World.TYPE_GETTER && worldBlockDirs[target] == 1) {
                applyDelta(target, delta);
                if (x+2 < size) applyDelta(base + y*size + (x+2), delta);
            }
        }
        if (y+1 < size) {
            int target = base + (y+1)*size + x;
            if (worldBlockTypes[target] == World.TYPE_GETTER && worldBlockDirs[target] == 2) {
                applyDelta(target, delta);
                if (y+2 < size) applyDelta(base + (y+2)*size + x, delta);
            }
        }
        if (x > 0) {
            int target = base + y*size + (x-1);
            if (worldBlockTypes[target] == World.TYPE_GETTER && worldBlockDirs[target] == 3) {
                applyDelta(target, delta);
                if (x-2 >= 0) applyDelta(base + y*size + (x-2), delta);
            }
        }
    }

    private void applyDelta(int targetIdx, int delta) {
        propagateCallsThisTick++;

        int oldNs = ns[targetIdx];
        int newNs = oldNs + delta;
        ns[targetIdx] = newNs;

        if (!inNextDirty[targetIdx]) {
            inNextDirty[targetIdx] = true;
            nextDirty[nextDirtyCount++] = targetIdx;
        }

        if (hasPegs && worldBlockTypes[targetIdx] == World.TYPE_PEG) {
            boolean wasPos = oldNs >= 1;
            boolean isPos  = newNs >= 1;
            if (wasPos != isPos) {
                int x = getX(targetIdx), y = getY(targetIdx), layer = getLayer(targetIdx);
                for (int ol = 0; ol < layers; ol++) {
                    if (ol != layer) {
                        int otherIdx = ol * layerSize + y * size + x;
                        if (worldBlockTypes[otherIdx] == World.TYPE_PEG) {
                            if (!inNextDirty[otherIdx]) {
                                inNextDirty[otherIdx] = true;
                                nextDirty[nextDirtyCount++] = otherIdx;
                            }
                        }
                    }
                }
            }
        }
    }

    public void setOnTickComplete(Runnable callback) { this.onTickComplete = callback; }
    public void start() { isPaused.set(false); isRunning.set(true); signalConfigChange(); }
    public void pause() { isPaused.set(true); tpsSmoothed = 0; actualTps = 0; }
    public void stop() {
        isRunning.set(false); isPaused.set(false);
        synchronized (loopLock) {
            simThreadRunning = false;
            if (simThread != null) { simThread.interrupt(); simThread = null; }
        }
    }

    public void step() { if (isPaused.get()) tickOnce(); }

    private void tickCore() {
        int every = tickSampleEvery;
        if (every < 1) every = 1;

        samplingThisTick = tickProfiling && (tickSampleCounter++ % every) == 0;

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
    }

    private void tickOnce() {
        tickCore();
        if (onTickComplete != null) javafx.application.Platform.runLater(onTickComplete);
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

        if (lastRebuildCells > aggRebuildCellsMax) aggRebuildCellsMax = lastRebuildCells;

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

    public void setTickProfiling(boolean enabled) { this.tickProfiling = enabled; if (!enabled) resetDebugAggregates(); }
    public void setTickSampleEvery(int every) { if (every < 1) every = 1; this.tickSampleEvery = every; }
    public void setAvgWindow(int window) { if (window < 1) window = 1; this.avgWindow = window; resetDebugAggregates(); }
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
        Arrays.fill(tickNanosHistory, 0L);
    }

    public void setUnlimitedSpeed(boolean unlimited) {
        boolean was = unlimitedSpeed.getAndSet(unlimited);
        if (was != unlimited) { updateIntervalFromSpeed(); signalConfigChange(); }
    }

    public void setSpeed(int speed) {
        currentSpeed.set(speed);
        updateIntervalFromSpeed();
        signalConfigChange();
    }

    private void updateIntervalFromSpeed() {
        if (unlimitedSpeed.get()) { intervalNanos = -1; return; }
        int s = currentSpeed.get();
        intervalNanos = (s <= 0) ? 0 : (1_000_000_000L / s);
    }

    private void signalConfigChange() { configDirty = true; if (simThread != null) simThread.interrupt(); }

    private void startSimThread() {
        synchronized (loopLock) {
            if (simThreadRunning) return;
            simThreadRunning = true;
            simThread = new Thread(() -> {
                long nextTickNanos = System.nanoTime();
                while (simThreadRunning) {
                    if (configDirty) { configDirty = false; long iv = intervalNanos; if (iv > 0) nextTickNanos = System.nanoTime() + iv; }
                    if (!isRunning.get() || isPaused.get() || intervalNanos == 0) {
                        try { Thread.sleep(1); } catch (InterruptedException e) {}
                        nextTickNanos = System.nanoTime(); continue;
                    }

                    if (intervalNanos < 0) { tickOnce(); nextTickNanos = System.nanoTime(); continue; }
                    long now = System.nanoTime(); long waitNanos = nextTickNanos - now;
                    if (waitNanos > 2_000_000L) {
                        long sleepMs = (waitNanos - 1_000_000L) / 1_000_000L;
                        if (sleepMs > 0) { try { Thread.sleep(sleepMs); } catch (InterruptedException e) {} }
                    } else if (waitNanos > 100_000L) { Thread.yield(); }
                    now = System.nanoTime(); if (now < nextTickNanos) continue;
                    tickOnce();
                    long iv = intervalNanos; if (iv <= 0) { nextTickNanos = System.nanoTime(); continue; }
                    nextTickNanos += iv;
                    long lateBy = System.nanoTime() - nextTickNanos;
                    if (lateBy > iv * 2) nextTickNanos = System.nanoTime() + iv;
                }
            }, "sim-thread");
            simThread.setDaemon(true); simThread.start();
        }
    }

    private void startTpsCounter() {
        if (tpsThreadRunning) return;
        tpsThreadRunning = true; lastTpsUpdateNanos = System.nanoTime();
        tpsThread = new Thread(() -> {
            while (tpsThreadRunning) {
                long iv = intervalNanos; long windowNanos;
                if (iv <= 0) { windowNanos = 200_000_000L; }
                else { windowNanos = iv * 5; if (windowNanos < 200_000_000L) windowNanos = 200_000_000L; if (windowNanos > 2_000_000_000L) windowNanos = 2_000_000_000L; }
                try { Thread.sleep(windowNanos / 1_000_000L); } catch (InterruptedException e) { break; }
                long now = System.nanoTime();
                double elapsedSec = (now - lastTpsUpdateNanos) / 1_000_000_000.0;
                lastTpsUpdateNanos = now; if (elapsedSec <= 0.001) continue;
                int ticks = tickCounter; tickCounter = 0;
                double instantaneous = ticks / elapsedSec;
                if (!isRunning.get() || isPaused.get() || intervalNanos == 0) { tpsSmoothed = 0; actualTps = 0; }
                else {
                    if (tpsSmoothed == 0) tpsSmoothed = instantaneous;
                    else tpsSmoothed = tpsSmoothed * 0.7 + instantaneous * 0.3;
                    actualTps = (int) Math.round(tpsSmoothed);
                }
            }
        }, "tps-counter");
        tpsThread.setDaemon(true); tpsThread.start();
    }

    private void stopTpsCounter() {
        tpsThreadRunning = false;
        if (tpsThread != null) { tpsThread.interrupt(); tpsThread = null; }
        actualTps = 0; tpsSmoothed = 0;
    }

    public int getActualTps() { return actualTps; }
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
    public void forceStop() { stop(); stopTpsCounter(); }
    public boolean isPaused() { return isPaused.get(); }
    public boolean isRunning() { return isRunning.get() && !isPaused.get(); }
    public int getSpeed() { return currentSpeed.get(); }
}