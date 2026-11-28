package com.app.states;

import com.app.Config;
import com.app.audio.AudioManager;
import com.app.core.*;
import com.app.graphics.Camera;
import com.app.graphics.CameraFollower;
import com.app.graphics.GridRenderer;
import com.app.graphics.SpriteManager;
import com.app.graphics.ColorConfig;
import com.app.ui.*;
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

    private boolean fHeld = false;
    private boolean rHeld = false;
    private long lastSoundNanos = 0;
    private static final long MIN_SOUND_INTERVAL_NANOS = 45_000_000L;
    private static final long SELECTION_SOUND_MIN_INTERVAL = 60_000_000L;

    private boolean rmbCandidate = false;   // ПКМ нажат, ещё не поняли — пан или меню
    private double rmbStartX, rmbStartY;

    private ScriptInterpreter currentInterpreter;

    // === Countdown перед запуском скрипта ===
    private boolean scriptCountdownActive = false;
    private long scriptCountdownEndNanos = 0L;
    private String scriptCountdownCode = null;
    private javafx.animation.AnimationTimer countdownTimer;

    public static boolean isScriptsDialogOpen = false;

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

        // УБРАЛИ слушатель sceneProperty, чтобы не вешать фильтры на общий Scene

        javafx.application.Platform.runLater(() -> {
            if (root.getScene() != null) {
                Stage stage = (Stage) root.getScene().getWindow();
                if (stage != null && currentMapName != null) {
                    stage.setTitle("Logica-Sandbox - " + currentMapName);
                }
                renderer.getCanvas().requestFocus();
            }
        });

        this.debugOverlay = Config.getInstance().isDebugOverlay();
        simulationEngine.setTickProfiling(debugOverlay);
        simulationEngine.setTickSampleEvery(Config.getInstance().getDebugSampleEvery());
        simulationEngine.setAvgWindow(Config.getInstance().getDebugAvgWindow());

        simulationEngine.start();
        initDeleteTimer();

        // Вешаем фильтр на root (он уникален для каждого MainState), а не на Scene
        installKeyboardFilter();
    }

    private void requestRender() { needsRender = true; }

    // ИЗМЕНЕНО: теперь фильтр вешается на root, а не на Scene
    private void installKeyboardFilter() {
        if (filterInstalled) return;
        filterInstalled = true;

        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getTarget() instanceof TextInputControl) return;

            KeyCode code = event.getCode();

            if (code == KeyCode.LEFT || code == KeyCode.RIGHT
                    || code == KeyCode.UP || code == KeyCode.DOWN) {
                handleKeyboardCursorMove(code);
                if (fHeld) {
                    handleFAction();
                }
                if (rHeld && keyboardCursorActive) {
                    removeBlockAtCell(keyboardCursorX, keyboardCursorY);
                }
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

                camera.zoomAt(zoomIn ? 1.1 : 0.9, zx, zy);
                SpriteManager.getInstance().trimRotatedCache();
                requestRender();
                event.consume();
                return;
            }

            if (code == KeyCode.F2) {
                openScriptsDialog();
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
                if (!fHeld) {
                    fHeld = true;
                    handleFAction();
                }
                event.consume();
                return;
            }

            if (code == KeyCode.R && !event.isControlDown()) {
                if (!rHeld) {
                    rHeld = true;
                    handleRAction();
                }
                event.consume();
                return;
            }
        });

        root.addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            if (event.getTarget() instanceof TextInputControl) return;

            KeyCode code = event.getCode();
            if (code == KeyCode.F) {
                fHeld = false;
                event.consume();
            } else if (code == KeyCode.R) {
                rHeld = false;
                if (deleteMode) {
                    deleteMode = false;
                    deleteTimer.stop();
                }
                event.consume();
            }
        });
    }

    private void openScriptsDialog() {
        if (isScriptsDialogOpen) {
            System.out.println("[SCRIPT] Dialog already open, ignoring F2");
            return;
        }
        isScriptsDialogOpen = true;

        Stage owner = null;
        if (root.getScene() != null && root.getScene().getWindow() instanceof Stage) {
            owner = (Stage) root.getScene().getWindow();
        }

        ScriptsDialog.show(owner, (code, delaySeconds) -> {
            isScriptsDialogOpen = false;
            startScriptWithCountdown(code, delaySeconds);
        });
    }

    /** Устанавливает клавиатурный курсор в клетку под текущей позицией мыши. */
    private void beginKeyboardCursorAtMouse() {
        int[] cell = getCellUnderSceneCoords(lastSceneMouseX, lastSceneMouseY);
        keyboardCursorX = Math.max(0, Math.min(world.getSize() - 1, cell[0]));
        keyboardCursorY = Math.max(0, Math.min(world.getSize() - 1, cell[1]));
        keyboardCursorActive = true;
        requestRender();
    }

    private void executeScript(String code) {
        if (currentInterpreter != null) {
            currentInterpreter.stop();
            System.out.println("[SCRIPT] Previous script interrupted.");
        }

        currentInterpreter = new ScriptInterpreter(code, this::handleScriptCommand, simulationEngine);

        // Динамический GET — всегда актуальное значение под курсором
        currentInterpreter.setGetProvider(() -> {
            if (keyboardCursorActive && isValidCell(keyboardCursorX, keyboardCursorY)) {
                int layer = world.getCurrentLayer();
                int idx = world.getIdx(layer, keyboardCursorX, keyboardCursorY);
                return worldTypeToScriptId(world.blockTypes[idx]);
            }
            return -1;
        });

        currentInterpreter.setOnComplete(() -> {
            System.out.println("[SCRIPT] Execution finished.");
            currentInterpreter = null;
            javafx.application.Platform.runLater(() -> renderer.getCanvas().requestFocus());
        });

        System.out.println("[SCRIPT] Starting execution...");
        currentInterpreter.start();
    }

    private int worldTypeToScriptId(int worldType) {
        switch (worldType) {
            case World.TYPE_EMPTY:  return 0;
            case World.TYPE_ARROW:  return 1;
            case World.TYPE_GETTER: return 2;
            case World.TYPE_BRIDGE: return 3;
            case World.TYPE_PEG:    return 4;
            case World.TYPE_POWER:  return 5;
            case World.TYPE_NOT:    return 6;
            case World.TYPE_OR:     return 7;
            case World.TYPE_AND:    return 8;
            case World.TYPE_XOR:    return 9;
            case World.TYPE_TEXT:   return 10;
            default:                return -1;
        }
    }

    /** Обработчик "мировых" команд скрипта. */
    private void handleScriptCommand(String command, String[] args) {
        int layer = world.getCurrentLayer();

        // Обновляем lastGetValue перед выполнением любой команды,
        // чтобы GET всегда актуален
        if (currentInterpreter != null) {
            if (isValidCell(keyboardCursorX, keyboardCursorY)) {
                int idx = world.getIdx(layer, keyboardCursorX, keyboardCursorY);
            }
        }

        switch (command) {
            case "RIGHT":
            case "LEFT":
            case "UP":
            case "DOWN": {
                int steps = 1;
                if (args.length > 0) {
                    try { steps = Integer.parseInt(args[0]); } catch (Exception ignored) {}
                }
                ensureKeyboardCursor();
                switch (command) {
                    case "RIGHT": keyboardCursorX += steps; break;
                    case "LEFT":  keyboardCursorX -= steps; break;
                    case "UP":    keyboardCursorY -= steps; break;
                    case "DOWN":  keyboardCursorY += steps; break;
                }
                // Clamp coordinates
                keyboardCursorX = Math.max(0, Math.min(world.getSize() - 1, keyboardCursorX));
                keyboardCursorY = Math.max(0, Math.min(world.getSize() - 1, keyboardCursorY));
                requestRender();
                break;
            }
            case "SET": {
                if (args.length < 1) return;
                int blockId;
                try { blockId = Integer.parseInt(args[0]); } catch (Exception e) { return; }

                String blockName = "";
                // Твоя новая нумерация
                switch (blockId) {
                    case 0: blockName = "0"; break;
                    case 1: blockName = "arrow"; break;
                    case 2: blockName = "getter"; break;
                    case 3: blockName = "bridge"; break;
                    case 4: blockName = "peg"; break;
                    case 5: blockName = "power"; break;
                    case 6: blockName = "not"; break;
                    case 7: blockName = "or"; break;
                    case 8: blockName = "and"; break;
                    case 9: blockName = "xor"; break;
                    case 10: blockName = "text"; break;
                    default: return;
                }

                if (blockName.equals("0")) {
                    removeBlockAtCell(keyboardCursorX, keyboardCursorY);
                } else {
                    BlockType type = findBlockType(blockName);
                    String blockStr = blockName;
                    if (type != null && type.hasDirection()) {
                        Direction dir = palette.getCurrentDirection();
                        blockStr += "_f" + dir.getCode();
                    }
                    if (changeCellWithUndo(layer, keyboardCursorX, keyboardCursorY, blockStr, "script-set")) {
                        simulationEngine.markWorldChanged();
                        requestRender();
                    }
                }
                break;
            }
            case "REMOVE": {
                int savedX = keyboardCursorX;
                int savedY = keyboardCursorY;
                if (args.length == 0) {
                    removeBlockAtCell(keyboardCursorX, keyboardCursorY);
                } else if (args.length >= 2) {
                    int w, h;
                    try {
                        w = Integer.parseInt(args[0]);
                        h = Integer.parseInt(args[1]);
                    } catch (Exception e) { return; }

                    undoManager.beginStep("script-remove-area");
                    // ИЗМЕНЕНО: идем ВНИЗ и ВПРАВО
                    for (int dy = 0; dy < h; dy++) {
                        for (int dx = 0; dx < w; dx++) {
                            int cx = savedX + dx;
                            int cy = savedY + dy;
                            if (isValidCell(cx, cy)) {
                                String old = world.getBlock(layer, cx, cy);
                                if (old != null && !old.equals("0")) {
                                    boolean os = world.getSignal(layer, cx, cy);
                                    world.removeBlock(layer, cx, cy);
                                    undoManager.recordChange(layer, cx, cy, old, "0", os, false);
                                }
                            }
                        }
                    }
                    undoManager.commitStep();
                    simulationEngine.markWorldChanged();
                    requestRender();
                }
                // Возврат курсора
                keyboardCursorX = savedX;
                keyboardCursorY = savedY;
                requestRender();
                break;
            }
            case "COPY": {
                if (args.length >= 2) {
                    int savedX = keyboardCursorX;
                    int savedY = keyboardCursorY;
                    int w, h;
                    try {
                        w = Integer.parseInt(args[0]);
                        h = Integer.parseInt(args[1]);
                    } catch (Exception e) { return; }

                    // ИЗМЕНЕНО: выделение идет ВНИЗ и ВПРАВО
                    selection.startSelection(savedX, savedY, layer);
                    selection.updateSelection(savedX + w + 1, savedY + h - 1);
                    selection.finishSelection(world);
                    selection.copyToClipboard();

                    previewMode = true;
                    keyboardCursorX = savedX;
                    keyboardCursorY = savedY;
                    requestRender();
                }
                break;
            }
            case "CUT": {
                if (args.length >= 2) {
                    int savedX = keyboardCursorX;
                    int savedY = keyboardCursorY;
                    int w, h;
                    try {
                        w = Integer.parseInt(args[0]);
                        h = Integer.parseInt(args[1]);
                    } catch (Exception e) { return; }

                    // ИЗМЕНЕНО: выделение идет ВНИЗ и ВПРАВО
                    selection.startSelection(savedX, savedY, layer);
                    selection.updateSelection(savedX + w - 1, savedY + h - 1);
                    selection.finishSelection(world);
                    cutSelectionWithUndo();

                    previewMode = true;
                    simulationEngine.markWorldChanged();
                    keyboardCursorX = savedX;
                    keyboardCursorY = savedY;
                    requestRender();
                }
                break;
            }
            case "PASTE": {
                if (Selection.hasClipboard()) {
                    pasteWithUndo(keyboardCursorX, keyboardCursorY);
                    simulationEngine.markWorldChanged();
                    requestRender();
                }
                break;
            }
            case "DESELECT": {
                previewMode = false;
                selection.clear();
                Selection.resetClipboard();
                requestRender();
                break;
            }
            case "GET": {
                // Значение уже обновлено в начале метода
                break;
            }
            case "OPENFILE": {
                // Обработка файла происходит внутри ScriptInterpreter
                break;
            }
            default:
                System.err.println("[SCRIPT] Unknown command: " + command);
                break;
        }
    }

    /** Поиск BlockType по имени (без учёта регистра). */
    private BlockType findBlockType(String name) {
        for (BlockType t : BlockType.values()) {
            if (t.getName().equalsIgnoreCase(name)) return t;
        }
        return null;
    }

    private boolean changeCellWithUndo(int layer, int x, int y,
                                       String newBlock,
                                       String stepLabel) {
        if (!isValidCell(x, y)) return false;

        String oldBlock = world.getBlock(layer, x, y);
        boolean oldSignal = world.getSignal(layer, x, y);

        if (oldBlock == null) oldBlock = "0";
        if (newBlock == null || newBlock.isEmpty()) newBlock = "0";
        if (oldBlock.equals(newBlock)) return false;

        world.setBlock(layer, x, y, newBlock);
        boolean newSignal = world.getSignal(layer, x, y);

        String actualNew = world.getBlock(layer, x, y);
        if (actualNew == null) actualNew = "0";

        undoManager.beginStep(stepLabel);
        undoManager.recordChange(layer, x, y, oldBlock, actualNew, oldSignal, newSignal);
        undoManager.commitStep();
        return true;
    }

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
        int idx = world.getIdx(layer, cx, cy);

        if (world.blockTypes[idx] == World.TYPE_TEXT) {
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

        int layer = world.getCurrentLayer();
        if (world.blockTypes[world.getIdx(layer, cellX, cellY)] == World.TYPE_TEXT) return;

        BlockType type = palette.getSelectedBlock();
        Direction dir = palette.getCurrentDirection();
        String blockStr = type.getName();
        if (type.hasDirection()) {
            blockStr += "_f" + dir.getCode();
        }

        if (changeCellWithUndo(layer, cellX, cellY, blockStr, "place")) {
            simulationEngine.markWorldChanged();
            AudioManager.getInstance().playPlacement();
            requestRender();
        }
    }

    private void removeBlockAtCell(int cellX, int cellY) {
        if (!isValidCell(cellX, cellY)) return;
        if (changeCellWithUndo(world.getCurrentLayer(), cellX, cellY, "0", "remove")) {
            simulationEngine.markWorldChanged();
            AudioManager.getInstance().playRemove();
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
            if (cellX == lastPlacedCellX && cellY == lastPlacedCellY) return;
            lastPlacedCellX = cellX;
            lastPlacedCellY = cellY;

            int layer = world.getCurrentLayer();
            int idx = world.getIdx(layer, cellX, cellY);
            if (world.blockTypes[idx] == World.TYPE_TEXT) return;

            BlockType type = palette.getSelectedBlock();
            Direction dir = palette.getCurrentDirection();
            String blockStr = type.getName();
            if (type.hasDirection()) blockStr += "_f" + dir.getCode();

            if (changeCellWithUndo(layer, cellX, cellY, blockStr, "place")) {
                simulationEngine.markWorldChanged();
                if (canPlaySound()) {
                    AudioManager.getInstance().playPlacement();
                }
                requestRender();
            }
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
            if (changeCellWithUndo(world.getCurrentLayer(), cellX, cellY, "0", "remove")) {
                simulationEngine.markWorldChanged();
                if (canPlaySound()) {
                    AudioManager.getInstance().playRemove();
                }
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
        int textRows = 5;
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

        double[] parts = { rb, p1, p2_prop, p2_rest, other };
        String[] labels = { "rebuild", "phase1 (compute)", "propagate", "phase2-rest", "other/overhead" };
        Color[] colorsArr = {
                Color.web(colors.getArrow()),
                Color.web(colors.getBridge()),
                Color.web(colors.getOr()),
                Color.web(colors.getAnd()),
                Color.web(colors.getGrid()).interpolate(Color.web(colors.getBackground()), 0.5)
        };

        gc.setFill(Color.web(colors.getBackground(), 0.6));
        gc.fillRect(x, y, w, h);

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

        gc.setStroke(Color.web(colors.getGrid()));
        gc.setLineWidth(1);
        gc.strokeRect(x, y, w, h);

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

        // УБРАЛИ вызов installKeyboardFilter отсюда, он теперь в конструкторе

        palette.updateLayer(world.getCurrentLayer());
        requestRender();
        javafx.application.Platform.runLater(() -> renderer.getCanvas().requestFocus());
    }

    @Override
    public void exit() {
        System.out.println("Exiting MainState");
        cancelScriptCountdown(true);
        simulationEngine.stop();

        if (deleteTimer != null) deleteTimer.stop();
        if (camera != null) camera.stop();
        if (selection != null) selection.clear();

        previewMode = false;
        keyboardCursorActive = false;
        panning = false;
        deleteMode = false;
        fHeld = false;
        rHeld = false;
        lastPlacedCellX = -1;
        lastPlacedCellY = -1;

        // Сбрасываем флаг, чтобы новый MainState мог установить фильтр
        filterInstalled = false;
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

        if (scriptCountdownActive) {
            drawScriptCountdown(gc, now);
        }
    }

    /** Обратный отсчёт до старта скрипта: компактный, полупрозрачный, внизу по центру. */
    private void drawScriptCountdown(GraphicsContext gc, long nowNanos) {
        ColorConfig colors = SpriteManager.getInstance().getColors();
        double cw = renderer.getCanvas().getWidth();
        double ch = renderer.getCanvas().getHeight();
        if (cw <= 0 || ch <= 0) return;

        long remaining = scriptCountdownEndNanos - nowNanos;
        if (remaining < 0) remaining = 0;

        // Формат: секунды.десятые (например, 2.5)
        double secs = remaining / 1_000_000_000.0;
        String text = String.format("%.1f", secs);

        // Размеры плашки (меньше предыдущего варианта)
        double boxW = Math.max(100, cw * 0.12);
        double boxH = boxW * 0.45;

        // Позиция: центр по горизонтали, но смещено вниз (80% высоты экрана)
        double bx = (cw - boxW) / 2.0;
        double by = ch * 0.80;

        // Фон-плашка (более прозрачный)
        gc.setFill(Color.web(colors.getBackground(), 0.6));
        gc.fillRoundRect(bx, by, boxW, boxH, 12, 12);

        // Рамка (тоньше и менее яркая)
        gc.setStroke(Color.web(colors.getSignalOn(), 0.7));
        gc.setLineWidth(2);
        gc.strokeRoundRect(bx, by, boxW, boxH, 12, 12);

        // Цифры
        Font font = Font.font("Monospaced", FontWeight.BOLD, boxH * 0.6);
        gc.setFont(font);
        gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        gc.setTextBaseline(javafx.geometry.VPos.CENTER);
        gc.setFill(Color.web(colors.getSignalOn()));
        gc.fillText(text, cw / 2.0, by + boxH / 2.0);
    }

    /**
     * Запуск скрипта с обратным отсчётом.
     */
    private void startScriptWithCountdown(String code, int delaySeconds) {
        // Отменяем предыдущий отсчёт, если он ещё идёт
        cancelScriptCountdown(false);
        renderer.getCanvas().requestFocus();

        if (delaySeconds <= 0) {
            beginKeyboardCursorAtMouse();
            executeScript(code);
            return;
        }

        scriptCountdownActive = true;
        scriptCountdownCode = code;
        scriptCountdownEndNanos = System.nanoTime() + delaySeconds * 1_000_000_000L;

        if (countdownTimer == null) {
            countdownTimer = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    if (!scriptCountdownActive) { stop(); return; }

                    long remaining = scriptCountdownEndNanos - now;
                    if (remaining <= 0) {
                        scriptCountdownActive = false;
                        stop();

                        String c = scriptCountdownCode;
                        scriptCountdownCode = null;

                        // Курсор клавиатуры — точно под мышь, прямо перед запуском
                        beginKeyboardCursorAtMouse();
                        executeScript(c);
                        return;
                    }
                    requestRender(); // перерисовываем кадр с цифрами отсчёта
                }
            };
        }
        countdownTimer.start();
        requestRender();
    }

    /** Прерывает активный обратный отсчёт (и, опционально, выполняющийся скрипт). */
    private void cancelScriptCountdown(boolean alsoStopScript) {
        if (countdownTimer != null) countdownTimer.stop();
        scriptCountdownActive = false;
        scriptCountdownCode = null;

        if (alsoStopScript && currentInterpreter != null) {
            currentInterpreter.stop();
            currentInterpreter = null;
        }
        requestRender();
    }

    @Override
    public void handleKeyPressed(KeyEvent event) {
        // Обработка Ctrl-комбинаций
        if (event.isControlDown()) {
            KeyCode code = event.getCode();

            // Undo/Redo
            if (code == KeyCode.Z) {
                if (event.isShiftDown()) {
                    if (undoManager.redo()) {
                        simulationEngine.markWorldChanged();
                        if (canPlaySound()) {
                            AudioManager.getInstance().playInsert();
                        }
                        requestRender();
                        System.out.println("Redo");
                    }
                } else {
                    if (undoManager.undo()) {
                        simulationEngine.markWorldChanged();
                        if (canPlaySound()) {
                            AudioManager.getInstance().playRemove();
                        }
                        requestRender();
                        System.out.println("Undo");
                    }
                }
                event.consume();
                return;
            }

            // Rotate CW (Ctrl+D)
            if (code == KeyCode.D) {
                boolean hasSel = selection.hasSelection();
                boolean hasClip = Selection.hasClipboard() || previewMode;
                if (hasSel || hasClip) {
                    applyTransform(hasSel, hasClip, TransformKind.ROTATE_CW, MainState::rotateCw);
                    if (canPlaySound()) AudioManager.getInstance().playSelect(); // или другой звук
                    event.consume();
                    return;
                }
            }

            // Rotate CCW (Ctrl+A)
            if (code == KeyCode.A) {
                boolean hasSel = selection.hasSelection();
                boolean hasClip = Selection.hasClipboard() || previewMode;
                if (hasSel || hasClip) {
                    applyTransform(hasSel, hasClip, TransformKind.ROTATE_CCW, MainState::rotateCcw);
                    if (canPlaySound()) AudioManager.getInstance().playSelect();
                    event.consume();
                    return;
                }
            }

            // Save As (Ctrl+Shift+S)
            if (event.isShiftDown()) {
                if (code == KeyCode.S) {
                    saveAsMap();
                    event.consume();
                    return;
                }
            } else {
                // Save (Ctrl+S)
                if (code == KeyCode.S) {
                    saveMap();
                    event.consume();
                    return;
                }
            }
        }

        // Обработка Shift-комбинаций (без Ctrl)
        if (event.isShiftDown() && !event.isControlDown()) {
            KeyCode code = event.getCode();
            boolean hasSel = selection.hasSelection();
            boolean hasClip = Selection.hasClipboard() || previewMode;

            // Flip V (Shift+W or Shift+S) - вертикальное отражение
            if (code == KeyCode.W || code == KeyCode.S) {
                if (hasSel || hasClip) {
                    applyTransform(hasSel, hasClip, TransformKind.FLIP_V, MainState::flipV);
                    if (canPlaySound()) AudioManager.getInstance().playSelect();
                    event.consume();
                    return;
                }
            }

            // Flip H (Shift+A or Shift+D) - горизонтальное отражение
            if (code == KeyCode.A || code == KeyCode.D) {
                if (hasSel || hasClip) {
                    applyTransform(hasSel, hasClip, TransformKind.FLIP_H, MainState::flipH);
                    if (canPlaySound()) AudioManager.getInstance().playSelect();
                    event.consume();
                    return;
                }
            }
        }

        // Остальные клавиши (без модификаторов Ctrl/Shift)
        switch (event.getCode()) {
            case ESCAPE:
                // ESC отменяет незапущенный обратный отсчёт перед скриптом
                if (scriptCountdownActive) {
                    cancelScriptCountdown(false);
                    return;
                }
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

            case W:
                palette.setDirection(Direction.UP);
                break;

            case A:
                // Если не Shift+A (который уже обработан выше как Flip H), то просто направление
                if (!event.isShiftDown()) {
                    palette.setDirection(Direction.LEFT);
                }
                break;

            case S:
                // Если не Shift+S (который уже обработан выше как Flip V), то просто направление
                if (!event.isShiftDown()) {
                    palette.setDirection(Direction.DOWN);
                }
                break;

            case D:
                // Если не Ctrl+D и не Shift+D (уже обработаны выше)
                if (!event.isControlDown() && !event.isShiftDown()) {
                    if (previewMode) {
                        previewMode = false;
                        selection.clear();
                        Selection.resetClipboard();
                        if (canPlaySound()) {
                            AudioManager.getInstance().playDeselect();
                        }
                        requestRender();
                        System.out.println("Preview mode off, clipboard cleared");
                    } else if (selection.hasSelection()) {
                        selection.clear();
                        if (canPlaySound()) {
                            AudioManager.getInstance().playDeselect();
                        }
                        requestRender();
                    } else {
                        palette.setDirection(Direction.RIGHT);
                    }
                }
                break;

            case X:
                if (selection.hasSelection() && !previewMode && !selecting) {
                    cutSelectionWithUndo();
                    simulationEngine.markWorldChanged();
                    previewMode = true;
                    if (canPlaySound()) {
                        AudioManager.getInstance().playCopy();
                    }
                    requestRender();
                    System.out.println("Cut: selection removed, buffer filled, preview mode activated");
                }
                break;

            case C:
                if (selection.hasSelection() && !previewMode && !selecting) {
                    selection.copyToClipboard();
                    previewMode = true;
                    if (canPlaySound()) {
                        AudioManager.getInstance().playCopy();
                    }
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
                    if (canPlaySound()) {
                        AudioManager.getInstance().playInsert();
                    }
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
                int y = py + dy; // Уже ВНИЗ и ВПРАВО
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

    private static Direction rotateCw(Direction d) {
        switch (d) {
            case UP: return Direction.RIGHT;
            case RIGHT: return Direction.DOWN;
            case DOWN: return Direction.LEFT;
            case LEFT: return Direction.UP;
        }
        return d;
    }

    private static Direction rotateCcw(Direction d) {
        switch (d) {
            case UP: return Direction.LEFT;
            case LEFT: return Direction.DOWN;
            case DOWN: return Direction.RIGHT;
            case RIGHT: return Direction.UP;
        }
        return d;
    }

    private static Direction flipH(Direction d) {
        switch (d) {
            case LEFT: return Direction.RIGHT;
            case RIGHT: return Direction.LEFT;
            default: return d;
        }
    }

    private static Direction flipV(Direction d) {
        switch (d) {
            case UP: return Direction.DOWN;
            case DOWN: return Direction.UP;
            default: return d;
        }
    }

    private boolean transformSelection(java.util.function.UnaryOperator<Direction> dirOp,
                                       TransformKind kind) {
        if (!selection.hasSelection()) return false;

        int layer = world.getCurrentLayer();
        int minX = Math.min(selection.getStartX(), selection.getEndX());
        int maxX = Math.max(selection.getStartX(), selection.getEndX());
        int minY = Math.min(selection.getStartY(), selection.getEndY());
        int maxY = Math.max(selection.getStartY(), selection.getEndY());

        int w = maxX - minX + 1;
        int h = maxY - minY + 1;

        String[][] src = new String[h][w];
        boolean[][] srcSignal = new boolean[h][w];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                String b = world.getBlock(layer, minX + x, minY + y);
                src[y][x] = (b == null) ? "0" : b;
                srcSignal[y][x] = world.getSignal(layer, minX + x, minY + y);
            }
        }

        String[][] dst;
        boolean[][] dstSignal;
        int newW, newH;

        if (kind == TransformKind.ROTATE_CW || kind == TransformKind.ROTATE_CCW) {
            newW = h; newH = w;
        } else {
            newW = w; newH = h;
        }

        dst = new String[newH][newW];
        dstSignal = new boolean[newH][newW];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int nx, ny;
                switch (kind) {
                    case ROTATE_CW:  nx = h - 1 - y; ny = x;         break;
                    case ROTATE_CCW: nx = y;         ny = w - 1 - x; break;
                    case FLIP_H:     nx = w - 1 - x; ny = y;         break;
                    case FLIP_V:     nx = x;         ny = h - 1 - y; break;
                    default:         nx = x;         ny = y;         break;
                }
                dst[ny][nx] = transformBlockString(src[y][x], dirOp);
                dstSignal[ny][nx] = srcSignal[y][x];
            }
        }

        // Очищаем старую область
        undoManager.beginStep("transform");
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int wx = minX + x, wy = minY + y;
                String ob = world.getBlock(layer, wx, wy);
                boolean os = world.getSignal(layer, wx, wy);
                if (ob != null && !ob.equals("0")) {
                    world.removeBlock(layer, wx, wy);
                    undoManager.recordChange(layer, wx, wy, ob, "0", os, false);
                }
            }
        }

        // Пишем новую (в той же левой-верхней точке, размер мог измениться)
        for (int y = 0; y < newH; y++) {
            for (int x = 0; x < newW; x++) {
                int wx = minX + x, wy = minY + y;
                if (!isValidCell(wx, wy)) continue;

                String nb = dst[y][x];
                if (nb == null || nb.equals("0")) continue;

                String ob = world.getBlock(layer, wx, wy);
                boolean os = world.getSignal(layer, wx, wy);

                world.setBlock(layer, wx, wy, nb);
                boolean ns = world.getSignal(layer, wx, wy);

                undoManager.recordChange(layer, wx, wy, ob, nb, os, ns);
            }
        }
        undoManager.commitStep();

        // Обновляем рамку выделения под новые размеры
        selection.startSelection(minX, minY, layer);
        selection.updateSelection(minX + newW - 1, minY + newH - 1);
        selection.finishSelection(world);

        simulationEngine.markWorldChanged();
        requestRender();
        return true;
    }

    private boolean transformClipboard(java.util.function.UnaryOperator<Direction> dirOp,
                                       TransformKind kind) {
        List<List<String>> clip = Selection.getClipboardBlocks();
        if (clip == null || clip.isEmpty()) return false;

        int h = clip.size();
        int w = clip.get(0).size();

        // 1) Новая геометрия
        String[][] src = new String[h][w];
        for (int y = 0; y < h; y++) {
            List<String> row = clip.get(y);
            for (int x = 0; x < w; x++) {
                src[y][x] = (x < row.size()) ? row.get(x) : "0";
            }
        }

        String[][] dst;
        if (kind == TransformKind.ROTATE_CW || kind == TransformKind.ROTATE_CCW) {
            dst = new String[w][h];
        } else {
            dst = new String[h][w];
        }

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                String b = src[y][x];
                if (b == null) b = "0";

                int nx, ny;
                switch (kind) {
                    case ROTATE_CW:  nx = h - 1 - y; ny = x;          break;
                    case ROTATE_CCW: nx = y;         ny = w - 1 - x;  break;
                    case FLIP_H:     nx = w - 1 - x; ny = y;          break;
                    case FLIP_V:     nx = x;         ny = h - 1 - y;  break;
                    default:         nx = x;         ny = y;          break;
                }
                dst[ny][nx] = transformBlockString(b, dirOp);
            }
        }

        // 2) Записываем обратно в буфер (мутация in-place)
        int newH = dst.length;
        int newW = dst[0].length;

        clip.clear();
        for (int y = 0; y < newH; y++) {
            List<String> row = new java.util.ArrayList<>(newW);
            for (int x = 0; x < newW; x++) {
                row.add(dst[y][x] != null ? dst[y][x] : "0");
            }
            clip.add(row);
        }

        requestRender();
        return true;
    }

    /** Поворот/флип одной строки блока "<name>_<state><dir>" → новое направление. */
    private static String transformBlockString(String b,
                                               java.util.function.UnaryOperator<Direction> dirOp) {
        if (b == null || b.equals("0")) return b;
        if (b.startsWith("text_") || b.equals("power") || b.equals("peg")) return b;

        int us = b.indexOf('_');
        if (us < 0 || us + 2 >= b.length()) return b;

        String name = b.substring(0, us);
        char state = b.charAt(us + 1);
        char dirC = b.charAt(us + 2);

        Direction d = Direction.fromChar(dirC);
        Direction nd = dirOp.apply(d);

        if (nd == d) return b;
        return name + "_" + state + nd.getCode();
    }

    private enum TransformKind { ROTATE_CW, ROTATE_CCW, FLIP_H, FLIP_V }

    private void showEditorContextMenu(double screenX, double screenY) {
        Stage owner = null;
        if (root.getScene() != null && root.getScene().getWindow() instanceof Stage) {
            owner = (Stage) root.getScene().getWindow();
        }
        if (owner == null) return;

        boolean hasSel = selection.hasSelection();
        boolean hasClip = Selection.hasClipboard() || previewMode;

        if (!hasSel && !hasClip) return;

        java.util.List<ContextMenuPopup.Item> items = new java.util.ArrayList<>();

        // ===== РЕЖИМ "В РУКАХ" (буфер) =====
        if (hasClip) {
            items.add(new ContextMenuPopup.Item("PASTE", () -> {
                int[] c = getActiveCursorCell();
                if (isValidCell(c[0], c[1])) {
                    pasteWithUndo(c[0], c[1]);
                    simulationEngine.markWorldChanged();
                    requestRender();
                }
            }));
            items.add(new ContextMenuPopup.Item("DESELECT", () -> {
                previewMode = false;
                selection.clear();
                Selection.resetClipboard();
                requestRender();
            }));
        }

        // ===== РЕЖИМ "ВЫДЕЛЕНО" (буфера нет) =====
        else if (hasSel) {
            items.add(new ContextMenuPopup.Item("COPY", () -> {
                selection.copyToClipboard();
                previewMode = true;
                requestRender();
            }));
            items.add(new ContextMenuPopup.Item("CUT", () -> {
                cutSelectionWithUndo();
                previewMode = true;
                requestRender();
            }));
            items.add(new ContextMenuPopup.Item("REMOVE", () -> {
                deleteSelectionWithUndo();
                selection.clear();
                simulationEngine.markWorldChanged();
                requestRender();
            }));
        }

        items.add(new ContextMenuPopup.Item("ROTATE CW",
                () -> applyTransform(hasSel, hasClip, TransformKind.ROTATE_CW, MainState::rotateCw)));
        items.add(new ContextMenuPopup.Item("ROTATE CCW",
                () -> applyTransform(hasSel, hasClip, TransformKind.ROTATE_CCW, MainState::rotateCcw)));
        items.add(new ContextMenuPopup.Item("FLIP H",
                () -> applyTransform(hasSel, hasClip, TransformKind.FLIP_H, MainState::flipH)));
        items.add(new ContextMenuPopup.Item("FLIP V",
                () -> applyTransform(hasSel, hasClip, TransformKind.FLIP_V, MainState::flipV)));

        ContextMenuPopup.show(owner, screenX, screenY, items);
    }

    private void applyTransform(boolean hasSel, boolean hasClip,
                                TransformKind kind,
                                java.util.function.UnaryOperator<Direction> dirOp) {
        if (hasClip) transformClipboard(dirOp, kind);
        else if (hasSel) transformSelection(dirOp, kind);
        requestRender();
    }

    private boolean canPlaySound() {
        long now = System.nanoTime();
        if (now - lastSoundNanos < MIN_SOUND_INTERVAL_NANOS) {
            return false;
        }
        lastSoundNanos = now;
        return true;
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

            if (canPlaySound()) {
                AudioManager.getInstance().playSelect();
            }
            requestRender();

            System.out.println("Selection finished: size "
                    + selection.getWidth() + "x" + selection.getHeight());
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
                rmbCandidate = true;
                rmbStartX = event.getSceneX();
                rmbStartY = event.getSceneY();
                lastMouseX = event.getSceneX();
                lastMouseY = event.getSceneY();
            }
        } else if (event.getButton() == MouseButton.PRIMARY) {
            if (!overPalette && !selecting && !deleteMode) {
                int[] cell = getCellUnderSceneCoords(event.getSceneX(), event.getSceneY());
                int layer = world.getCurrentLayer();

                if (isValidCell(cell[0], cell[1]) &&
                        world.blockTypes[world.getIdx(layer, cell[0], cell[1])] == World.TYPE_TEXT) {
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
            if (rmbCandidate) {
                rmbCandidate = false;
                showEditorContextMenu(event.getScreenX(), event.getScreenY());
            }
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

        if (event.getButton() == MouseButton.SECONDARY && rmbCandidate && !overPalette) {
            double dx = event.getSceneX() - rmbStartX;
            double dy = event.getSceneY() - rmbStartY;
            if (Math.hypot(dx, dy) > 4.0) {
                panning = true;
                rmbCandidate = false;
                renderer.getCanvas().setCursor(javafx.scene.Cursor.CLOSED_HAND);
            }
        }

        if (panning && !overPalette) { /* как раньше */ }

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
        if (deltaY == 0) return;

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