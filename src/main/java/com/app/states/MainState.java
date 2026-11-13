package com.app.states;

import com.app.Config;
import com.app.audio.AudioManager;
import com.app.core.BlockType;
import com.app.core.Direction;
import com.app.core.World;
import com.app.core.SimulationEngine;
import com.app.core.Selection;
import com.app.core.MapManager;
import com.app.core.UndoManager;
import com.app.graphics.Camera;
import com.app.graphics.CameraFollower;
import com.app.graphics.GridRenderer;
import com.app.graphics.SpriteManager;
import com.app.graphics.ColorConfig;
import com.app.ui.BlockPalette;
import com.app.ui.HelpDialog;
import com.app.ui.TextEditorDialog;
import javafx.animation.AnimationTimer;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.canvas.GraphicsContext;
import javafx.stage.Stage;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.List;
import java.util.Optional;

public class MainState implements State {
    private BorderPane root;
    private State nextState;

    private World world;
    private Camera camera;
    private CameraFollower cameraFollower;
    private GridRenderer renderer;
    private BlockPalette palette;
    private SimulationEngine simulationEngine;
    private Selection selection;
    private UndoManager undoManager;

    private String currentMapName;

    private boolean panning = false;
    private double lastMouseX;
    private double lastMouseY;
    private double lastSceneMouseX = 0;
    private double lastSceneMouseY = 0;

    private boolean selecting = false;
    private int selectionStartX = 0;
    private int selectionStartY = 0;

    private boolean previewMode = false;

    private String powerColor;
    private String gridColor;

    private boolean deleteMode = false;

    private AnimationTimer deleteTimer;
    private long lastDeleteTickNanos = 0;
    private static final long DELETE_INTERVAL_NANOS = 10_000_000L;

    private int lastPlacedCellX = -1;
    private int lastPlacedCellY = -1;

    private int lastSelectionSoundWidth = -1;
    private int lastSelectionSoundHeight = -1;
    private long lastSelectionSoundNanos = 0;
    private static final long SELECTION_SOUND_MIN_INTERVAL = 25_000_000L;

    private boolean keyboardCursorActive = false;
    private int keyboardCursorX = 0;
    private int keyboardCursorY = 0;

    private boolean fHandledOnPress = false;

    private boolean needsRender = true;

    private long lastFrameNanos = 0;

    private boolean filterInstalled = false;

    // === DEBUG ===
    private boolean debugOverlay = false;
    private long lastDebugUpdateNanos = 0;
    private static final long DEBUG_UPDATE_INTERVAL_NANOS = 250_000_000L;
    private String[] debugLines = new String[5];
    private double debugBoxW = 640;
    private double debugBoxH = 240;

    public MainState() {
        this(null);
    }

    public MainState(String mapName) {
        Config config = Config.getInstance();

        this.currentMapName = mapName;
        this.world = new World();

        if (mapName != null && !mapName.isEmpty()) {
            System.out.println("Loading map: " + mapName);
            MapManager.loadMap(mapName, world);
        }

        this.camera = new Camera(
                config.getWorldSize(),
                SpriteManager.getInstance().getBaseSize(),
                config.getWindowWidth(),
                config.getWindowHeight()
        );
        this.cameraFollower = new CameraFollower(camera);
        this.renderer = new GridRenderer(world, camera);
        this.palette = new BlockPalette();
        this.simulationEngine = new SimulationEngine(world);
        this.selection = new Selection();
        this.undoManager = new UndoManager(world);

        ColorConfig colors = SpriteManager.getInstance().getColors();
        this.powerColor = colors.getPower();
        this.gridColor = colors.getGrid();

        simulationEngine.setOnTickComplete(null);

        this.root = new BorderPane();
        root.setCenter(renderer.getCanvas());
        root.setRight(palette);

        renderer.getCanvas().widthProperty().bind(
                root.widthProperty().subtract(palette.widthProperty())
        );
        renderer.getCanvas().heightProperty().bind(root.heightProperty());

        palette.setOnSelectionChange(this::onBlockSelected);
        palette.setOnSpeedChange(this::onSpeedChange);

        palette.setCurrentSpeed(simulationEngine.getSpeed());
        palette.updateSimulationStatus(simulationEngine.isRunning());

        renderer.getCanvas().setFocusTraversable(true);
        renderer.getCanvas().setOnMousePressed(e -> renderer.getCanvas().requestFocus());

        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                installKeyboardFilter(newScene);
            }
        });

        javafx.application.Platform.runLater(() -> {
            if (root.getScene() != null) {
                Stage stage = (Stage) root.getScene().getWindow();
                if (stage != null && currentMapName != null) {
                    stage.setTitle("Logica-Sandbox - " + currentMapName);
                }
                renderer.getCanvas().requestFocus();
            }
        });

        // === DEBUG ===
        this.debugOverlay = Config.getInstance().isDebugOverlay();
        simulationEngine.setTickProfiling(debugOverlay);
        simulationEngine.setTickSampleEvery(Config.getInstance().getDebugSampleEvery());
        simulationEngine.setAvgWindow(Config.getInstance().getDebugAvgWindow());

        simulationEngine.start();
        initDeleteTimer();
    }

    private void requestRender() { needsRender = true; }

    private void installKeyboardFilter(Scene scene) {
        if (filterInstalled) return;
        filterInstalled = true;

        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getTarget() instanceof TextInputControl) return;

            KeyCode code = event.getCode();

            boolean isArrow = code == KeyCode.LEFT || code == KeyCode.RIGHT
                    || code == KeyCode.UP || code == KeyCode.DOWN;

            if (isArrow) {
                handleKeyboardCursorMove(code);
                event.consume();
                return;
            }

            if (code == KeyCode.EQUALS || code == KeyCode.ADD
                    || code == KeyCode.MINUS || code == KeyCode.SUBTRACT) {

                boolean zoomIn = code == KeyCode.EQUALS || code == KeyCode.ADD;

                double zx, zy;
                if (keyboardCursorActive) {
                    zx = camera.worldToScreenX((keyboardCursorX + 0.5) * camera.getBaseCellSize());
                    zy = camera.worldToScreenY((keyboardCursorY + 0.5) * camera.getBaseCellSize());
                } else if (!isMouseOverPalette(lastSceneMouseX, lastSceneMouseY)) {
                    Point2D canvasCoords = getCanvasCoordinates(lastSceneMouseX, lastSceneMouseY);
                    zx = canvasCoords.getX();
                    zy = canvasCoords.getY();
                    if (zx < 0 || zx > renderer.getCanvas().getWidth()
                            || zy < 0 || zy > renderer.getCanvas().getHeight()) {
                        zx = renderer.getCanvas().getWidth() / 2.0;
                        zy = renderer.getCanvas().getHeight() / 2.0;
                    }
                } else {
                    zx = renderer.getCanvas().getWidth() / 2.0;
                    zy = renderer.getCanvas().getHeight() / 2.0;
                }

                if (zoomIn) {
                    camera.zoomAt(1.1, zx, zy);
                } else {
                    camera.zoomAt(0.9, zx, zy);
                }
                SpriteManager.getInstance().trimRotatedCache();
                requestRender();
                event.consume();
                return;
            }

            if (code == KeyCode.F3) {
                debugOverlay = !debugOverlay;
                simulationEngine.setTickProfiling(debugOverlay);
                Config.getInstance().setDebugOverlay(debugOverlay);
                Config.getInstance().save();
                requestRender();
                event.consume();
                return;
            }

            if (code == KeyCode.F && !event.isControlDown()) {
                handleFAction();
                fHandledOnPress = true;
                event.consume();
                return;
            }

            if (code == KeyCode.R && !event.isControlDown()) {
                handleRAction();
                event.consume();
            }
        });

        scene.addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            if (event.getTarget() instanceof TextInputControl) return;
            if (event.getCode() == KeyCode.F) {
                if (!fHandledOnPress) {
                    handleFAction();
                }
                fHandledOnPress = false;
                event.consume();
            }
            if (event.getCode() == KeyCode.R) {
                if (deleteMode) {
                    deleteMode = false;
                    deleteTimer.stop();
                }
                event.consume();
            }
        });
    }

    // ============================================================
    // UNDO / REDO helpers
    // ============================================================

    private void changeCellWithUndo(int layer, int x, int y,
                                    String newBlock,
                                    String stepLabel) {
        if (!isValidCell(x, y)) return;

        String oldBlock = world.getBlock(layer, x, y);
        boolean oldSignal = world.getSignal(layer, x, y);

        if (oldBlock == null) oldBlock = "0";
        if (newBlock == null || newBlock.isEmpty()) newBlock = "0";

        if (oldBlock.equals(newBlock)) return;

        world.setBlock(layer, x, y, newBlock);
        boolean newSignal = world.getSignal(layer, x, y);

        String actualNew = world.getBlock(layer, x, y);
        if (actualNew == null) actualNew = "0";

        undoManager.beginStep(stepLabel);
        undoManager.recordChange(layer, x, y, oldBlock, actualNew, oldSignal, newSignal);
        undoManager.commitStep();
    }

    // ============================================================
    // ACTIONS
    // ============================================================

    private void handleRAction() {
        if (selecting || previewMode || panning) return;

        if (keyboardCursorActive) {
            removeBlockAtCell(keyboardCursorX, keyboardCursorY);
            return;
        }

        if (selection.hasSelection()) {
            deleteSelectionWithUndo();
            selection.clear();
            simulationEngine.markWorldChanged();
            AudioManager.getInstance().playCopy();
            requestRender();
            System.out.println("Deleted selected area");
            return;
        }

        deleteMode = true;
        lastDeleteTickNanos = 0;
        deleteTimer.start();
        removeBlockAtSceneCoords(lastSceneMouseX, lastSceneMouseY);
    }

    private void deleteSelectionWithUndo() {
        int layer = world.getCurrentLayer();
        int minX = Math.min(selection.getStartX(), selection.getEndX());
        int maxX = Math.max(selection.getStartX(), selection.getEndX());
        int minY = Math.min(selection.getStartY(), selection.getEndY());
        int maxY = Math.max(selection.getStartY(), selection.getEndY());

        undoManager.beginStep("delete-selection");

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (!isValidCell(x, y)) continue;
                String oldBlock = world.getBlock(layer, x, y);
                if (oldBlock == null || oldBlock.equals("0")) continue;
                boolean oldSignal = world.getSignal(layer, x, y);
                world.removeBlock(layer, x, y);
                world.setSignal(layer, x, y, false);
                undoManager.recordChange(layer, x, y, oldBlock, "0", oldSignal, false);
            }
        }

        undoManager.commitStep();
    }

    private void cutSelectionWithUndo() {
        selection.copyToClipboard();

        int layer = world.getCurrentLayer();
        int minX = Math.min(selection.getStartX(), selection.getEndX());
        int maxX = Math.max(selection.getStartX(), selection.getEndX());
        int minY = Math.min(selection.getStartY(), selection.getEndY());
        int maxY = Math.max(selection.getStartY(), selection.getEndY());

        undoManager.beginStep("cut");

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (!isValidCell(x, y)) continue;
                String oldBlock = world.getBlock(layer, x, y);
                if (oldBlock == null || oldBlock.equals("0")) continue;
                boolean oldSignal = world.getSignal(layer, x, y);
                world.removeBlock(layer, x, y);
                world.setSignal(layer, x, y, false);
                undoManager.recordChange(layer, x, y, oldBlock, "0", oldSignal, false);
            }
        }

        undoManager.commitStep();
    }

    private void handleKeyboardCursorMove(KeyCode code) {
        ensureKeyboardCursor();

        switch (code) {
            case LEFT:  keyboardCursorX--; break;
            case RIGHT: keyboardCursorX++; break;
            case UP:    keyboardCursorY--; break;
            case DOWN:  keyboardCursorY++; break;
            default: return;
        }

        if (keyboardCursorX < 0) keyboardCursorX = 0;
        if (keyboardCursorY < 0) keyboardCursorY = 0;
        if (keyboardCursorX >= world.getSize()) keyboardCursorX = world.getSize() - 1;
        if (keyboardCursorY >= world.getSize()) keyboardCursorY = world.getSize() - 1;

        requestRender();
    }

    private void ensureKeyboardCursor() {
        if (!keyboardCursorActive) {
            int[] cell = getCellUnderSceneCoords(lastSceneMouseX, lastSceneMouseY);
            if (!isValidCell(cell[0], cell[1])) {
                cell[0] = Math.max(0, Math.min(world.getSize() - 1, cell[0]));
                cell[1] = Math.max(0, Math.min(world.getSize() - 1, cell[1]));
            }
            keyboardCursorX = cell[0];
            keyboardCursorY = cell[1];
            keyboardCursorActive = true;
        }
    }

    private int[] getActiveCursorCell() {
        if (keyboardCursorActive) {
            return new int[]{keyboardCursorX, keyboardCursorY};
        }
        return getCellUnderSceneCoords(lastSceneMouseX, lastSceneMouseY);
    }

    private int[] getCellUnderSceneCoords(double sceneX, double sceneY) {
        Point2D canvasCoords = getCanvasCoordinates(sceneX, sceneY);
        double worldX = camera.screenToWorldX(canvasCoords.getX());
        double worldY = camera.screenToWorldY(canvasCoords.getY());
        int base = camera.getBaseCellSize();
        return new int[]{
                (int) Math.floor(worldX / base),
                (int) Math.floor(worldY / base)
        };
    }

    private boolean isValidCell(int cx, int cy) {
        return cx >= 0 && cx < world.getSize() && cy >= 0 && cy < world.getSize();
    }

    private void handleFAction() {
        int[] cell = getActiveCursorCell();
        int cx = cell[0];
        int cy = cell[1];

        if (!isValidCell(cx, cy)) return;

        int layer = world.getCurrentLayer();
        if (world.blockTypes[layer][cy][cx] == World.TYPE_TEXT) {
            openTextEditor(cx, cy);
        } else {
            placeBlockAtCell(cx, cy);
        }
    }

    private void openTextEditor(int cellX, int cellY) {
        int layer = world.getCurrentLayer();
        String raw = world.getBlockTexts(layer, cellX, cellY);

        String currentTrue = "";
        String currentFalse = "";
        if (raw != null) {
            int pipe = raw.indexOf('|');
            if (pipe >= 0) {
                currentTrue = raw.substring(0, pipe);
                currentFalse = raw.substring(pipe + 1);
            } else {
                currentTrue = raw;
            }
        } else {
            currentTrue = World.DEFAULT_TEXT_TRUE;
            currentFalse = World.DEFAULT_TEXT_FALSE;
        }

        Stage owner = null;
        if (root.getScene() != null && root.getScene().getWindow() instanceof Stage) {
            owner = (Stage) root.getScene().getWindow();
        }

        TextEditorDialog.Result result =
                TextEditorDialog.show(owner, currentTrue, currentFalse);

        if (result != null) {
            String oldBlock = world.getBlock(layer, cellX, cellY);
            boolean oldSignal = world.getSignal(layer, cellX, cellY);

            world.setBlockTexts(layer, cellX, cellY, result.trueText, result.falseText);

            String newBlock = world.getBlock(layer, cellX, cellY);
            boolean newSignal = world.getSignal(layer, cellX, cellY);

            undoManager.beginStep("edit-text");
            undoManager.recordChange(layer, cellX, cellY, oldBlock, newBlock, oldSignal, newSignal);
            undoManager.commitStep();

            requestRender();
            System.out.println("Text block updated at (" + cellX + ", " + cellY
                    + "): TRUE=\"" + result.trueText + "\" FALSE=\"" + result.falseText + "\"");
        }

        javafx.application.Platform.runLater(() -> renderer.getCanvas().requestFocus());
    }

    private void placeBlockAtCell(int cellX, int cellY) {
        if (!isValidCell(cellX, cellY)) return;

        BlockType type = palette.getSelectedBlock();
        Direction dir = palette.getCurrentDirection();

        String blockStr = type.getName();
        if (type.hasDirection()) {
            blockStr += "_f" + dir.getCode();
        }

        changeCellWithUndo(world.getCurrentLayer(), cellX, cellY, blockStr, "place");
        simulationEngine.markWorldChanged();
        AudioManager.getInstance().playPlacement();
        System.out.println("Placed (F): " + blockStr + " at (" + cellX + ", " + cellY
                + ") on layer " + world.getCurrentLayer());
        requestRender();
    }

    private void removeBlockAtCell(int cellX, int cellY) {
        if (!isValidCell(cellX, cellY)) return;
        String block = world.getBlock(world.getCurrentLayer(), cellX, cellY);
        if (block != null && !block.equals("0")) {
            changeCellWithUndo(world.getCurrentLayer(), cellX, cellY, "0", "remove");
            simulationEngine.markWorldChanged();
            AudioManager.getInstance().playRemove();
            System.out.println("Removed (kb) at (" + cellX + ", " + cellY + ")");
            requestRender();
        }
    }

    private void initDeleteTimer() {
        deleteTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (!deleteMode) return;
                if (now - lastDeleteTickNanos < DELETE_INTERVAL_NANOS) return;
                lastDeleteTickNanos = now;
                removeBlockAtSceneCoords(lastSceneMouseX, lastSceneMouseY);
            }
        };
    }

    private Point2D getCanvasCoordinates(double sceneX, double sceneY) {
        double canvasX = sceneX - renderer.getCanvas().localToScene(0, 0).getX();
        double canvasY = sceneY - renderer.getCanvas().localToScene(0, 0).getY();
        return new Point2D(canvasX, canvasY);
    }

    private void onSpeedChange() {
        int speed = palette.getCurrentSpeed();

        if (palette.isMaxSpeed()) {
            boolean wasPaused = simulationEngine.isPaused();
            simulationEngine.setUnlimitedSpeed(true);
            if (!wasPaused && !simulationEngine.isRunning()) {
                simulationEngine.start();
                palette.updateSimulationStatus(true);
            } else if (wasPaused) {
                palette.updateSimulationStatus(false);
            }
        } else if (speed == 0) {
            simulationEngine.pause();
            palette.updateSimulationStatus(false);
        } else {
            boolean wasPaused = simulationEngine.isPaused();
            simulationEngine.setUnlimitedSpeed(false);
            simulationEngine.setSpeed(speed);

            if (!wasPaused) {
                if (!simulationEngine.isRunning()) {
                    simulationEngine.start();
                }
                palette.updateSimulationStatus(true);
            } else {
                palette.updateSimulationStatus(false);
            }
        }
        javafx.application.Platform.runLater(() -> renderer.getCanvas().requestFocus());
    }

    private void onBlockSelected() {
        BlockType type = palette.getSelectedBlock();
        System.out.println("Selected: " + type.getName());
    }

    private boolean isMouseOverPalette(double sceneX, double sceneY) {
        double paletteSceneX = palette.localToScene(0, 0).getX();
        double paletteSceneY = palette.localToScene(0, 0).getY();
        double paletteWidth = palette.getWidth();
        double paletteHeight = palette.getHeight();

        return (sceneX >= paletteSceneX && sceneX <= paletteSceneX + paletteWidth &&
                sceneY >= paletteSceneY && sceneY <= paletteSceneY + paletteHeight);
    }

    private void placeBlock(double sceneX, double sceneY) {
        if (isMouseOverPalette(sceneX, sceneY)) return;

        Point2D canvasCoords = getCanvasCoordinates(sceneX, sceneY);
        double canvasX = canvasCoords.getX();
        double canvasY = canvasCoords.getY();

        if (canvasX < 0 || canvasX > renderer.getCanvas().getWidth() ||
                canvasY < 0 || canvasY > renderer.getCanvas().getHeight()) {
            lastPlacedCellX = -1;
            lastPlacedCellY = -1;
            return;
        }

        double worldX = camera.screenToWorldX(canvasX);
        double worldY = camera.screenToWorldY(canvasY);

        int baseCellSize = SpriteManager.getInstance().getBaseSize();
        int cellX = (int) Math.floor(worldX / baseCellSize);
        int cellY = (int) Math.floor(worldY / baseCellSize);

        if (cellX >= 0 && cellX < world.getSize() && cellY >= 0 && cellY < world.getSize()) {
            if (cellX == lastPlacedCellX && cellY == lastPlacedCellY) {
                return;
            }
            lastPlacedCellX = cellX;
            lastPlacedCellY = cellY;

            int layer = world.getCurrentLayer();
            if (world.blockTypes[layer][cellY][cellX] == World.TYPE_TEXT) return;

            BlockType type = palette.getSelectedBlock();
            Direction dir = palette.getCurrentDirection();

            String blockStr = type.getName();
            if (type.hasDirection()) {
                blockStr += "_f" + dir.getCode();
            }

            changeCellWithUndo(layer, cellX, cellY, blockStr, "place");
            simulationEngine.markWorldChanged();
            AudioManager.getInstance().playPlacement();
            System.out.println("Placed: " + blockStr + " at (" + cellX + ", " + cellY + ") on layer " + layer);
            requestRender();
        } else {
            lastPlacedCellX = -1;
            lastPlacedCellY = -1;
        }
    }

    private void removeBlockAtSceneCoords(double sceneX, double sceneY) {
        if (isMouseOverPalette(sceneX, sceneY)) return;

        Point2D canvasCoords = getCanvasCoordinates(sceneX, sceneY);
        double canvasX = canvasCoords.getX();
        double canvasY = canvasCoords.getY();

        if (canvasX < 0 || canvasX > renderer.getCanvas().getWidth() ||
                canvasY < 0 || canvasY > renderer.getCanvas().getHeight()) {
            return;
        }

        double worldX = camera.screenToWorldX(canvasX);
        double worldY = camera.screenToWorldY(canvasY);
        int baseCellSize = SpriteManager.getInstance().getBaseSize();
        int cellX = (int) Math.floor(worldX / baseCellSize);
        int cellY = (int) Math.floor(worldY / baseCellSize);

        if (cellX >= 0 && cellX < world.getSize() && cellY >= 0 && cellY < world.getSize()) {
            String block = world.getBlock(world.getCurrentLayer(), cellX, cellY);
            if (block != null && !block.equals("0")) {
                changeCellWithUndo(world.getCurrentLayer(), cellX, cellY, "0", "remove");
                simulationEngine.markWorldChanged();
                AudioManager.getInstance().playRemove();
                System.out.println("Removed at (" + cellX + ", " + cellY + ")");
                requestRender();
            }
        }
    }

    private void rebuildDebugLines() {
        int dirty   = simulationEngine.getDirtyCount();
        int active  = simulationEngine.getActiveCellsCount();
        int changes = simulationEngine.getPendingChangesCount();
        int props   = simulationEngine.getLastPropagateCalls();

        double lastNs = simulationEngine.getLastTickNs();
        double rbNs   = simulationEngine.getRebuildNs();
        double p1Ns   = simulationEngine.getPhase1Ns();
        double p2Ns   = simulationEngine.getPhase2Ns();
        double propNs = simulationEngine.getPropagateNs();

        double avgTick = simulationEngine.getSnapAvgTickNs();
        double minTick = simulationEngine.getSnapMinTickNs();
        double maxTick = simulationEngine.getSnapMaxTickNs();

        double avgRb   = simulationEngine.getSnapAvgRebuildNs();
        double avgP1   = simulationEngine.getSnapAvgPhase1Ns();
        double avgP2   = simulationEngine.getSnapAvgPhase2Ns();
        double avgProp = simulationEngine.getSnapAvgPropagateNs();

        int avgDirty   = simulationEngine.getSnapAvgDirty();
        int avgActive  = simulationEngine.getSnapAvgActive();
        int avgChanges = simulationEngine.getSnapAvgChanges();
        int avgProps   = simulationEngine.getSnapAvgProps();
        int maxCells   = simulationEngine.getSnapRebuildCellsMax();

        int done  = simulationEngine.getSnapProgressDone();
        int total = simulationEngine.getSnapProgressTotal();
        int every = simulationEngine.getTickSampleEvery();

        debugLines[0] = String.format(
                "profiling: sample every %d ticks | avg window %d samples (%d/%d done)",
                every, total, done, total);

        debugLines[1] = String.format(
                "dirty %6d   active %6d   changes %5d   props %5d   rebuild cells max %d",
                avgDirty, avgActive, avgChanges, avgProps, maxCells);

        debugLines[2] = String.format(
                "tick %8.0f ns   min %8.0f ns   max %8.0f ns",
                avgTick, minTick, maxTick);

        debugLines[3] = String.format(
                "rebuild %8.0f ns   phase1 %8.0f ns   phase2 %8.0f ns",
                avgRb, avgP1, avgP2);

        debugLines[4] = String.format(
                "propagate %8.0f ns", avgProp);

        int maxLen = 0;
        for (String s : debugLines) {
            if (s != null && s.length() > maxLen) maxLen = s.length();
        }
        debugBoxW = maxLen * 8.4 + 24;
        debugBoxH = 5 * 18 + 10 + 6 + 26 + 8 + 6 * 16 + 12;
    }

    private void drawDebugOverlay(GraphicsContext gc) {
        if (debugLines[0] == null) return;

        ColorConfig colors = SpriteManager.getInstance().getColors();

        Font font = Font.font("Monospaced", FontWeight.BOLD, 13);
        gc.setFont(font);

        double x0 = 12;
        double y0 = 12;

        gc.setFill(Color.web(colors.getBackground(), 0.92));
        gc.fillRect(x0, y0, debugBoxW, debugBoxH);

        gc.setStroke(Color.web(colors.getGrid(), 0.6));
        gc.setLineWidth(1);
        gc.strokeRect(x0 + 0.5, y0 + 0.5, debugBoxW - 1, debugBoxH - 1);

        gc.setFill(Color.web(colors.getGrid()));
        gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
        gc.setTextBaseline(javafx.geometry.VPos.TOP);

        double ty = y0 + 10;
        int textRows = 5; // Ровно 5 строк текста
        for (int i = 0; i < textRows; i++) {
            if (debugLines[i] != null) gc.fillText(debugLines[i], x0 + 12, ty);
            ty += 18;
        }

        double diagX = x0 + 12;
        double diagY = y0 + 10 + textRows * 18 + 6;
        double diagW = debugBoxW - 24;
        double diagH = 26;

        drawPhaseDiagram(gc, colors, diagX, diagY, diagW, diagH);

        gc.setTextBaseline(javafx.geometry.VPos.BASELINE);
    }

    private void drawPhaseDiagram(GraphicsContext gc, ColorConfig colors,
                                  double x, double y, double w, double h) {
        double avgTick = simulationEngine.getSnapAvgTickNs();
        if (avgTick <= 0) return;

        double rb   = simulationEngine.getSnapAvgRebuildNs();
        double p1   = simulationEngine.getSnapAvgPhase1Ns();
        double p2   = simulationEngine.getSnapAvgPhase2Ns();
        double prop = simulationEngine.getSnapAvgPropagateNs();

        double p2_prop = Math.min(prop, p2);
        double p2_rest = Math.max(0, p2 - p2_prop);

        double sumPhases = rb + p1 + p2;
        double other = Math.max(0, avgTick - sumPhases);
        double total = Math.max(1.0, sumPhases + other);

        // Порядок: rebuild | phase1 (compute) | propagate | phase2-rest | other
        double[] parts = { rb, p1, p2_prop, p2_rest, other };
        String[] labels = { "rebuild", "phase1 (compute)", "propagate", "phase2-rest", "other/overhead" };

        // Цвета
        Color[] colorsArr = {
                Color.web(colors.getArrow()),                                    // rebuild
                Color.web(colors.getBridge()),                                   // phase1
                Color.web(colors.getOr()),                                       // propagate
                Color.web(colors.getAnd()),                                      // phase2-rest
                Color.web(colors.getGrid()).interpolate(Color.web(colors.getBackground()), 0.5) // overhead
        };

        // Фон диаграммы
        gc.setFill(Color.web(colors.getBackground(), 0.6));
        gc.fillRect(x, y, w, h);

        // 1. Рисуем сегменты с отступами по 2px
        int visibleCount = 0;
        for (double p : parts) {
            if (p > 0) visibleCount++;
        }

        double totalGaps = Math.max(0, visibleCount - 1) * 2.0;
        double availW = Math.max(0, w - totalGaps);

        double px = x;
        int drawn = 0;

        for (int i = 0; i < parts.length; i++) {
            double frac = parts[i] / total;
            double segW = frac * availW;

            if (segW <= 0) continue;
            if (segW < 1.0) segW = 1.0;

            gc.setFill(colorsArr[i]);
            gc.fillRect(px, y, segW, h);

            px += segW;

            drawn++;
            if (drawn < visibleCount) {
                gc.setFill(Color.web(colors.getGrid()));
                gc.fillRect(px, y, 2.0, h);
                px += 2.0;
            }
        }

        // Внешняя рамка
        gc.setStroke(Color.web(colors.getGrid()));
        gc.setLineWidth(1);
        gc.strokeRect(x, y, w, h);

        // 2. Подписи процентов внутри сегментов
        gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        gc.setTextBaseline(javafx.geometry.VPos.CENTER);
        gc.setFont(Font.font("Monospaced", FontWeight.BOLD, 11));

        px = x;
        drawn = 0;
        for (int i = 0; i < parts.length; i++) {
            double frac = parts[i] / total;
            double segW = frac * availW;

            if (segW <= 0) continue;
            if (segW < 1.0) segW = 1.0;

            if (segW >= 44) {
                gc.setFill(Color.web(colors.getBackground()));
                gc.fillText(String.format("%.0f%%", frac * 100.0), px + segW / 2, y + h / 2);
            }

            px += segW;
            drawn++;
            if (drawn < visibleCount) {
                px += 2.0;
            }
        }

        // 3. Легенда под диаграммой
        gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
        gc.setTextBaseline(javafx.geometry.VPos.TOP);
        gc.setFont(Font.font("Monospaced", FontWeight.BOLD, 11));

        double lx = x;
        double ly = y + h + 8;

        for (int i = 0; i < parts.length; i++) {
            double pct = (parts[i] / total) * 100.0;
            gc.setFill(colorsArr[i]);
            gc.fillRect(lx, ly + 2, 10, 10);

            gc.setFill(Color.web(colors.getGrid()));
            gc.fillText(String.format("%s: %.1f%%", labels[i], pct), lx + 16, ly);

            ly += 16;
        }
    }

    private void renderPreview(GraphicsContext gc, int mouseCellX, int mouseCellY) {
        if (!previewMode || !Selection.hasClipboard()) return;

        List<List<String>> clip = Selection.getClipboardBlocks();
        int height = clip.size();
        if (height == 0) return;
        int width = clip.get(0).size();

        int cellScreenSize = camera.getCellScreenSize();
        int baseCell = camera.getBaseCellSize();

        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                String block = clip.get(dy).get(dx);
                if (block == null || block.equals("0")) continue;

                String spritePart = block;
                int pipe = block.indexOf('|');
                if (pipe >= 0) spritePart = block.substring(0, pipe);

                String[] parts = spritePart.split("_");
                String blockName = parts[0];
                Direction dir = Direction.UP;
                if (parts.length > 1 && parts[1].length() == 2) {
                    dir = Direction.fromChar(parts[1].charAt(1));
                }

                int[] sprite = SpriteManager.getInstance()
                        .getSpriteWithDirection(blockName, cellScreenSize, dir, false);
                if (sprite != null) {
                    int cellX = mouseCellX + dx;
                    int cellY = mouseCellY + dy;
                    double cellScreenX = camera.worldToScreenX(cellX * baseCell);
                    double cellScreenY = camera.worldToScreenY(cellY * baseCell);
                    int ix = (int) Math.round(cellScreenX);
                    int iy = (int) Math.round(cellScreenY);
                    renderer.drawSprite(gc, sprite, cellScreenSize, ix, iy);
                }
            }
        }

        double screenX1 = camera.worldToScreenX(mouseCellX * baseCell);
        double screenY1 = camera.worldToScreenY(mouseCellY * baseCell);
        double screenX2 = camera.worldToScreenX((mouseCellX + width) * baseCell);
        double screenY2 = camera.worldToScreenY((mouseCellY + height) * baseCell);

        gc.setStroke(Color.web(gridColor));
        gc.setLineWidth(3);
        gc.strokeRect(screenX1, screenY1, screenX2 - screenX1, screenY2 - screenY1);
    }

    private void saveMap() {
        if (currentMapName == null || currentMapName.isEmpty()) {
            saveAsMap();
        } else {
            performSave(currentMapName);
        }
    }

    private void saveAsMap() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Save Map");
        dialog.setHeaderText("Save current map");
        dialog.setContentText("Map name:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            if (!name.trim().isEmpty()) {
                currentMapName = name;
                performSave(currentMapName);
            }
        });

        javafx.application.Platform.runLater(() -> renderer.getCanvas().requestFocus());
    }

    private void performSave(String mapName) {
        if (MapManager.saveMap(mapName, world)) {
            System.out.println("Map saved: " + mapName);
            javafx.application.Platform.runLater(() -> {
                if (root.getScene() != null) {
                    Stage stage = (Stage) root.getScene().getWindow();
                    if (stage != null) stage.setTitle("Logica-Sandbox - " + currentMapName);
                }
            });
        } else {
            System.err.println("Failed to save map: " + mapName);
        }
    }

    @Override
    public void enter() {
        System.out.println("Entering MainState" + (currentMapName != null ? " - " + currentMapName : ""));
        nextState = this;

        previewMode = Selection.hasClipboard();
        if (previewMode) {
            System.out.println("Clipboard has data — preview mode enabled");
        }

        if (root.getScene() != null) {
            installKeyboardFilter(root.getScene());
        }

        palette.updateLayer(world.getCurrentLayer());
        requestRender();
        javafx.application.Platform.runLater(() -> renderer.getCanvas().requestFocus());
    }

    @Override
    public void exit() {
        System.out.println("Exiting MainState");
        simulationEngine.stop();
        if (deleteTimer != null) deleteTimer.stop();
        if (camera != null) camera.stop();
        if (selection != null) selection.clear();
        previewMode = false;
        keyboardCursorActive = false;
        panning = false;
        deleteMode = false;
        lastPlacedCellX = -1;
        lastPlacedCellY = -1;
    }

    @Override
    public void update() {
        long now = System.nanoTime();
        double deltaSeconds = 0;
        if (lastFrameNanos != 0) {
            deltaSeconds = (now - lastFrameNanos) / 1_000_000_000.0;
            if (deltaSeconds > 0.1) deltaSeconds = 0.1;
        }
        lastFrameNanos = now;

        if (keyboardCursorActive) {
            double cursorScreenX = camera.worldToScreenX(
                    (keyboardCursorX + 0.5) * camera.getBaseCellSize()
            );
            double cursorScreenY = camera.worldToScreenY(
                    (keyboardCursorY + 0.5) * camera.getBaseCellSize()
            );
            cameraFollower.update(
                    deltaSeconds,
                    cursorScreenX, cursorScreenY,
                    true,
                    CameraFollower.DEAD_ZONE_KEYBOARD,
                    CameraFollower.SMOOTHING_KEYBOARD
            );
            requestRender();
        } else {
            cameraFollower.reset();
        }

        boolean simRunning = simulationEngine.isRunning();
        if (needsRender || simRunning) {
            renderer.render();
            needsRender = false;
        }

        GraphicsContext gc = renderer.getCanvas().getGraphicsContext2D();

        if (previewMode && Selection.hasClipboard()) {
            int previewCellX, previewCellY;
            if (keyboardCursorActive) {
                previewCellX = keyboardCursorX;
                previewCellY = keyboardCursorY;
            } else {
                double worldX = camera.screenToWorldX(lastSceneMouseX);
                double worldY = camera.screenToWorldY(lastSceneMouseY);
                previewCellX = (int) Math.floor(worldX / camera.getBaseCellSize());
                previewCellY = (int) Math.floor(worldY / camera.getBaseCellSize());
            }
            renderPreview(gc, previewCellX, previewCellY);
        }

        if (selecting || selection.hasSelection()) {
            String borderColor = selecting ? gridColor : powerColor;
            gc.setStroke(Color.web(borderColor));
            gc.setLineWidth(3);

            if (selecting) {
                int endX, endY;
                if (keyboardCursorActive) {
                    endX = keyboardCursorX;
                    endY = keyboardCursorY;
                } else {
                    double worldX = camera.screenToWorldX(lastSceneMouseX);
                    double worldY = camera.screenToWorldY(lastSceneMouseY);
                    endX = (int) Math.floor(worldX / camera.getBaseCellSize());
                    endY = (int) Math.floor(worldY / camera.getBaseCellSize());
                }

                int minX = Math.min(selectionStartX, endX);
                int maxX = Math.max(selectionStartX, endX);
                int minY = Math.min(selectionStartY, endY);
                int maxY = Math.max(selectionStartY, endY);

                int selW = maxX - minX + 1;
                int selH = maxY - minY + 1;
                long nowNs = System.nanoTime();
                boolean sizeChanged = (selW != lastSelectionSoundWidth
                        || selH != lastSelectionSoundHeight);
                if (sizeChanged && nowNs - lastSelectionSoundNanos >= SELECTION_SOUND_MIN_INTERVAL) {
                    lastSelectionSoundWidth = selW;
                    lastSelectionSoundHeight = selH;
                    lastSelectionSoundNanos = nowNs;
                    AudioManager.getInstance().playSelectingTick(selW, selH);
                }

                double screenX1 = camera.worldToScreenX(minX * camera.getBaseCellSize());
                double screenY1 = camera.worldToScreenY(minY * camera.getBaseCellSize());
                double screenX2 = camera.worldToScreenX((maxX + 1) * camera.getBaseCellSize());
                double screenY2 = camera.worldToScreenY((maxY + 1) * camera.getBaseCellSize());

                gc.strokeRect(screenX1, screenY1, screenX2 - screenX1, screenY2 - screenY1);
            } else if (selection.hasSelection() && !previewMode) {
                int minX = Math.min(selection.getStartX(), selection.getEndX());
                int maxX = Math.max(selection.getStartX(), selection.getEndX());
                int minY = Math.min(selection.getStartY(), selection.getEndY());
                int maxY = Math.max(selection.getStartY(), selection.getEndY());

                double screenX1 = camera.worldToScreenX(minX * camera.getBaseCellSize());
                double screenY1 = camera.worldToScreenY(minY * camera.getBaseCellSize());
                double screenX2 = camera.worldToScreenX((maxX + 1) * camera.getBaseCellSize());
                double screenY2 = camera.worldToScreenY((maxY + 1) * camera.getBaseCellSize());

                gc.strokeRect(screenX1, screenY1, screenX2 - screenX1, screenY2 - screenY1);
            }
        }

        if (keyboardCursorActive) {
            double screenX = camera.worldToScreenX(keyboardCursorX * camera.getBaseCellSize());
            double screenY = camera.worldToScreenY(keyboardCursorY * camera.getBaseCellSize());
            double cellSize = camera.getCellScreenSize();

            gc.setStroke(Color.web(gridColor));
            gc.setLineWidth(3);
            gc.strokeRect(screenX + 1, screenY + 1, cellSize - 2, cellSize - 2);
        }

        palette.setActualTps(simulationEngine.getActualTps());

        if (now - lastDebugUpdateNanos >= DEBUG_UPDATE_INTERVAL_NANOS) {
            lastDebugUpdateNanos = now;
            if (debugOverlay) {
                rebuildDebugLines();
            }
        }

        if (debugOverlay) {
            drawDebugOverlay(gc);
        }
    }

    @Override
    public void handleKeyPressed(KeyEvent event) {
        if (event.isControlDown()) {
            KeyCode code = event.getCode();

            if (code == KeyCode.Z) {
                if (event.isShiftDown()) {
                    if (undoManager.redo()) {
                        simulationEngine.markWorldChanged();
                        AudioManager.getInstance().playInsert();
                        requestRender();
                        System.out.println("Redo");
                    }
                } else {
                    if (undoManager.undo()) {
                        simulationEngine.markWorldChanged();
                        AudioManager.getInstance().playRemove();
                        requestRender();
                        System.out.println("Undo");
                    }
                }
                event.consume();
                return;
            }

            if (event.isShiftDown()) {
                if (code == KeyCode.S) {
                    saveAsMap();
                    event.consume();
                    return;
                }
            } else {
                if (code == KeyCode.S) {
                    saveMap();
                    event.consume();
                    return;
                }
            }
        }

        switch (event.getCode()) {
            case ESCAPE:
                nextState = new MenuState();
                break;

            case H:
                HelpDialog.show();
                javafx.application.Platform.runLater(() -> renderer.getCanvas().requestFocus());
                break;

            case SPACE:
                if (simulationEngine.isRunning()) {
                    simulationEngine.pause();
                    palette.updateSimulationStatus(false);
                } else {
                    simulationEngine.start();
                    palette.updateSimulationStatus(true);
                }
                break;

            case E:
                if (!selecting) {
                    selecting = true;
                    if (keyboardCursorActive) {
                        selectionStartX = keyboardCursorX;
                        selectionStartY = keyboardCursorY;
                    } else {
                        double worldX = camera.screenToWorldX(lastSceneMouseX);
                        double worldY = camera.screenToWorldY(lastSceneMouseY);
                        selectionStartX = (int) Math.floor(worldX / camera.getBaseCellSize());
                        selectionStartY = (int) Math.floor(worldY / camera.getBaseCellSize());
                    }
                    selection.startSelection(selectionStartX, selectionStartY, world.getCurrentLayer());

                    lastSelectionSoundWidth = -1;
                    lastSelectionSoundHeight = -1;
                    lastSelectionSoundNanos = 0;
                }
                break;

            case T:
                world.nextLayer();
                palette.updateLayer(world.getCurrentLayer());
                requestRender();
                break;

            case G:
                world.previousLayer();
                palette.updateLayer(world.getCurrentLayer());
                requestRender();
                break;

            case R:
                handleRAction();
                break;

            case W: palette.setDirection(Direction.UP); break;
            case A: palette.setDirection(Direction.LEFT); break;
            case S: palette.setDirection(Direction.DOWN); break;
            case D:
                if (previewMode) {
                    previewMode = false;
                    selection.clear();
                    Selection.resetClipboard();
                    AudioManager.getInstance().playDeselect();
                    requestRender();
                    System.out.println("Preview mode off, clipboard cleared");
                } else if (selection.hasSelection()) {
                    selection.clear();
                    AudioManager.getInstance().playDeselect();
                    requestRender();
                } else {
                    palette.setDirection(Direction.RIGHT);
                }
                break;

            case X:
                if (selection.hasSelection() && !previewMode && !selecting) {
                    cutSelectionWithUndo();
                    simulationEngine.markWorldChanged();
                    previewMode = true;
                    AudioManager.getInstance().playCopy();
                    requestRender();
                    System.out.println("Cut: selection removed, buffer filled, preview mode activated");
                }
                break;

            case C:
                if (selection.hasSelection() && !previewMode && !selecting) {
                    selection.copyToClipboard();
                    previewMode = true;
                    AudioManager.getInstance().playCopy();
                    requestRender();
                    System.out.println("Copy: buffer filled, preview mode activated");
                }
                break;

            case V:
                if (Selection.hasClipboard()) {
                    int px, py;
                    if (keyboardCursorActive) {
                        px = keyboardCursorX;
                        py = keyboardCursorY;
                    } else {
                        double worldX = camera.screenToWorldX(lastSceneMouseX);
                        double worldY = camera.screenToWorldY(lastSceneMouseY);
                        px = (int) Math.floor(worldX / camera.getBaseCellSize());
                        py = (int) Math.floor(worldY / camera.getBaseCellSize());
                    }
                    pasteWithUndo(px, py);
                    simulationEngine.markWorldChanged();
                    AudioManager.getInstance().playInsert();
                    requestRender();
                    System.out.println("Paste done at (" + px + ", " + py + ")");
                }
                break;

            case DIGIT1: palette.setSelectedIndex(0); break;
            case DIGIT2: palette.setSelectedIndex(1); break;
            case DIGIT3: palette.setSelectedIndex(2); break;
            case DIGIT4: palette.setSelectedIndex(3); break;
            case DIGIT5: palette.setSelectedIndex(4); break;
            case DIGIT6: palette.setSelectedIndex(5); break;
            case DIGIT7: palette.setSelectedIndex(6); break;
            case DIGIT8: palette.setSelectedIndex(7); break;
            case DIGIT9: palette.setSelectedIndex(8); break;
            case DIGIT0: palette.setSelectedIndex(9); break;
            default: break;
        }
    }

    private void pasteWithUndo(int px, int py) {
        List<List<String>> clip = Selection.getClipboardBlocks();
        if (clip.isEmpty()) return;

        int h = clip.size();
        int w = clip.get(0).size();
        int layer = world.getCurrentLayer();

        undoManager.beginStep("paste");

        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                int x = px + dx;
                int y = py + dy;
                if (!isValidCell(x, y)) continue;

                String nb = clip.get(dy).get(dx);
                if (nb == null || nb.equals("0")) continue;

                String ob = world.getBlock(layer, x, y);
                boolean os = world.getSignal(layer, x, y);

                world.setBlock(layer, x, y, nb);
                boolean ns = world.getSignal(layer, x, y);

                undoManager.recordChange(layer, x, y, ob, nb, os, ns);
            }
        }

        undoManager.commitStep();
    }

    @Override
    public void handleKeyReleased(KeyEvent event) {
        if (event.getCode() == KeyCode.E && selecting) {
            selecting = false;

            int endX, endY;
            if (keyboardCursorActive) {
                endX = keyboardCursorX;
                endY = keyboardCursorY;
            } else {
                double worldX = camera.screenToWorldX(lastSceneMouseX);
                double worldY = camera.screenToWorldY(lastSceneMouseY);
                endX = (int) Math.floor(worldX / camera.getBaseCellSize());
                endY = (int) Math.floor(worldY / camera.getBaseCellSize());
            }
            selection.updateSelection(endX, endY);
            selection.finishSelection(world);

            AudioManager.getInstance().playSelect();

            requestRender();
            System.out.println("Selection finished: size "
                    + selection.getWidth() + "x" + selection.getHeight());
        }

        if (event.getCode() == KeyCode.R) {
            if (deleteMode) {
                deleteMode = false;
                deleteTimer.stop();
            }
        }
    }

    @Override
    public void handleMousePressed(MouseEvent event) {
        lastSceneMouseX = event.getSceneX();
        lastSceneMouseY = event.getSceneY();

        if (keyboardCursorActive) {
            keyboardCursorActive = false;
            cameraFollower.reset();
            requestRender();
        }

        lastPlacedCellX = -1;
        lastPlacedCellY = -1;

        boolean overPalette = isMouseOverPalette(event.getSceneX(), event.getSceneY());

        if (event.getButton() == MouseButton.SECONDARY) {
            if (!overPalette) {
                panning = true;
                lastMouseX = event.getSceneX();
                lastMouseY = event.getSceneY();
                renderer.getCanvas().setCursor(javafx.scene.Cursor.CLOSED_HAND);
            }
        } else if (event.getButton() == MouseButton.PRIMARY) {
            if (!overPalette && !selecting && !deleteMode) {
                int[] cell = getCellUnderSceneCoords(event.getSceneX(), event.getSceneY());
                if (isValidCell(cell[0], cell[1])
                        && world.blockTypes[world.getCurrentLayer()][cell[1]][cell[0]] == World.TYPE_TEXT) {
                    openTextEditor(cell[0], cell[1]);
                } else {
                    placeBlock(event.getSceneX(), event.getSceneY());
                }
            }
        }
    }

    @Override
    public void handleMouseReleased(MouseEvent event) {
        if (event.getButton() == MouseButton.SECONDARY) {
            panning = false;
            renderer.getCanvas().setCursor(javafx.scene.Cursor.DEFAULT);
        }
        if (event.getButton() == MouseButton.PRIMARY) {
            lastPlacedCellX = -1;
            lastPlacedCellY = -1;
        }
    }

    @Override
    public void handleMouseDragged(MouseEvent event) {
        lastSceneMouseX = event.getSceneX();
        lastSceneMouseY = event.getSceneY();

        boolean overPalette = isMouseOverPalette(event.getSceneX(), event.getSceneY());

        if (panning && !overPalette) {
            double dx = event.getSceneX() - lastMouseX;
            double dy = event.getSceneY() - lastMouseY;
            camera.moveScreen(dx, dy);
            lastMouseX = event.getSceneX();
            lastMouseY = event.getSceneY();
            requestRender();
        } else if (event.getButton() == MouseButton.PRIMARY && !overPalette
                && !selecting && !previewMode && !deleteMode) {
            placeBlock(event.getSceneX(), event.getSceneY());
        }

        if (selecting) {
            requestRender();
        }

        if (previewMode) {
            requestRender();
        }
    }

    @Override
    public void handleMouseMoved(MouseEvent event) {
        lastSceneMouseX = event.getSceneX();
        lastSceneMouseY = event.getSceneY();

        if (previewMode || selecting || keyboardCursorActive) {
            requestRender();
        }
    }

    @Override
    public void handleScroll(ScrollEvent event) {
        lastSceneMouseX = event.getSceneX();
        lastSceneMouseY = event.getSceneY();

        double deltaY = event.getDeltaY();
        double factor = deltaY > 0 ? 1.1 : 0.9;
        camera.zoom(factor, event.getSceneX(), event.getSceneY());
        SpriteManager.getInstance().trimRotatedCache();
        requestRender();
    }

    @Override
    public State getNextState() {
        return nextState;
    }

    @Override
    public BorderPane getRoot() {
        return root;
    }
}