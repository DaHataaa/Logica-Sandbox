package com.app.states;

import com.app.Config;
import com.app.core.BlockType;
import com.app.core.Direction;
import com.app.core.World;
import com.app.core.SimulationEngine;
import com.app.core.Selection;
import com.app.core.MapManager;
import com.app.graphics.Camera;
import com.app.graphics.CameraFollower;
import com.app.graphics.GridRenderer;
import com.app.graphics.SpriteManager;
import com.app.graphics.ColorConfig;
import com.app.ui.BlockPalette;
import com.app.ui.HelpDialog;
import javafx.animation.AnimationTimer;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.canvas.GraphicsContext;

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
    private long lastDeleteTime = 0;
    private static final long DELETE_INTERVAL = 50_000_000;

    // ===== Клавиатурный курсор =====
    private boolean keyboardCursorActive = false;
    private int keyboardCursorX = 0;
    private int keyboardCursorY = 0;

    // Защита от двойного срабатывания F
    private boolean fHandledOnPress = false;

    // Для расчёта deltaSeconds в update()
    private long lastFrameNanos = 0;

    // Защита от двойной установки фильтра клавиатуры
    private boolean filterInstalled = false;

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

        ColorConfig colors = SpriteManager.getInstance().getColors();
        this.powerColor = colors.getPower();
        this.gridColor = colors.getGrid();

        simulationEngine.setOnTickComplete(() -> renderer.render());

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

        // Фокус: канвас получает клавиатуру
        renderer.getCanvas().setFocusTraversable(true);
        renderer.getCanvas().setOnMousePressed(e -> renderer.getCanvas().requestFocus());

        // Фильтр клавиатуры на сцене
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                installKeyboardFilter(newScene);
            }
        });

        javafx.application.Platform.runLater(() -> {
            if (root.getScene() != null) {
                javafx.stage.Stage stage = (javafx.stage.Stage) root.getScene().getWindow();
                if (stage != null && currentMapName != null) {
                    stage.setTitle("Logic 2 - " + currentMapName);
                }
                renderer.getCanvas().requestFocus();
            }
        });

        simulationEngine.start();
        initDeleteTimer();
    }

    // ===== Фильтр клавиатуры =====
    private void installKeyboardFilter(Scene scene) {
        if (filterInstalled) return;
        filterInstalled = true;

        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getTarget() instanceof TextInputControl) return;

            KeyCode code = event.getCode();

            // ===== Стрелки =====
            boolean isArrow = code == KeyCode.LEFT || code == KeyCode.RIGHT
                    || code == KeyCode.UP || code == KeyCode.DOWN;

            if (isArrow) {
                handleKeyboardCursorMove(code);
                event.consume();
                return;
            }

            // ===== + / - : зум с клавиатуры =====
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
                event.consume();
                return;
            }

            // ===== F — установка блока =====
            if (code == KeyCode.F && !event.isControlDown()) {
                handlePlaceAction();
                fHandledOnPress = true;
                event.consume();
            }
        });

        scene.addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            if (event.getTarget() instanceof TextInputControl) return;
            if (event.getCode() == KeyCode.F) {
                if (!fHandledOnPress) {
                    handlePlaceAction();
                }
                fHandledOnPress = false;
                event.consume();
            }
        });
    }

    // ===== Логика клавиатурного курсора =====
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

        renderer.render();
    }

    private void handlePlaceAction() {
        ensureKeyboardCursor();

        if (previewMode && selection.hasSelection()) {
            selection.paste(world, world.getCurrentLayer(), keyboardCursorX, keyboardCursorY);
            renderer.render();
            System.out.println("Paste (F) at (" + keyboardCursorX + ", " + keyboardCursorY + ")");
        } else if (!selecting) {
            placeBlockAtCell(keyboardCursorX, keyboardCursorY);
        }
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

    private void placeBlockAtCell(int cellX, int cellY) {
        if (!isValidCell(cellX, cellY)) return;

        BlockType type = palette.getSelectedBlock();
        Direction dir = palette.getCurrentDirection();

        String blockStr = type.getName();
        if (type.hasDirection()) {
            blockStr += "_f" + dir.getCode();
        }

        world.setBlock(world.getCurrentLayer(), cellX, cellY, blockStr);
        System.out.println("Placed (kb): " + blockStr + " at (" + cellX + ", " + cellY
                + ") on layer " + world.getCurrentLayer());
        renderer.render();
    }

    private void removeBlockAtCell(int cellX, int cellY) {
        if (!isValidCell(cellX, cellY)) return;
        String block = world.getBlock(world.getCurrentLayer(), cellX, cellY);
        if (block != null && !block.equals("0")) {
            world.removeBlock(world.getCurrentLayer(), cellX, cellY);
            System.out.println("Removed (kb) at (" + cellX + ", " + cellY + ")");
            renderer.render();
        }
    }

    private void initDeleteTimer() {
        deleteTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (deleteMode && (now - lastDeleteTime) >= DELETE_INTERVAL) {
                    lastDeleteTime = now;
                    removeBlockAtSceneCoords(lastSceneMouseX, lastSceneMouseY);
                }
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
                canvasY < 0 || canvasY > renderer.getCanvas().getHeight()) return;

        double worldX = camera.screenToWorldX(canvasX);
        double worldY = camera.screenToWorldY(canvasY);

        int baseCellSize = SpriteManager.getInstance().getBaseSize();
        int cellX = (int) Math.floor(worldX / baseCellSize);
        int cellY = (int) Math.floor(worldY / baseCellSize);

        if (cellX >= 0 && cellX < world.getSize() && cellY >= 0 && cellY < world.getSize()) {
            BlockType type = palette.getSelectedBlock();
            Direction dir = palette.getCurrentDirection();

            String blockStr = type.getName();

            if (type.hasDirection()) {
                blockStr += "_f" + dir.getCode();
            }

            world.setBlock(world.getCurrentLayer(), cellX, cellY, blockStr);
            System.out.println("Placed: " + blockStr + " at (" + cellX + ", " + cellY + ") on layer " + world.getCurrentLayer());
            renderer.render();
        }
    }

    private void removeBlockAtSceneCoords(double sceneX, double sceneY) {
        if (isMouseOverPalette(sceneX, sceneY)) return;

        Point2D canvasCoords = getCanvasCoordinates(sceneX, sceneY);
        double canvasX = canvasCoords.getX();
        double canvasY = canvasCoords.getY();

        if (canvasX < 0 || canvasX > renderer.getCanvas().getWidth() ||
                canvasY < 0 || canvasY > renderer.getCanvas().getHeight()) return;

        double worldX = camera.screenToWorldX(canvasX);
        double worldY = camera.screenToWorldY(canvasY);
        int baseCellSize = SpriteManager.getInstance().getBaseSize();
        int cellX = (int) Math.floor(worldX / baseCellSize);
        int cellY = (int) Math.floor(worldY / baseCellSize);

        if (cellX >= 0 && cellX < world.getSize() && cellY >= 0 && cellY < world.getSize()) {
            String block = world.getBlock(world.getCurrentLayer(), cellX, cellY);
            if (block != null && !block.equals("0")) {
                world.removeBlock(world.getCurrentLayer(), cellX, cellY);
                System.out.println("Removed at (" + cellX + ", " + cellY + ")");
                renderer.render();
            }
        }
    }

    private void renderPreview(GraphicsContext gc, int mouseCellX, int mouseCellY) {
        if (!previewMode || !selection.hasSelection()) return;

        int width = selection.getWidth();
        int height = selection.getHeight();
        int cellScreenSize = camera.getCellScreenSize();

        double screenX = camera.worldToScreenX(mouseCellX * camera.getBaseCellSize());
        double screenY = camera.worldToScreenY(mouseCellY * camera.getBaseCellSize());

        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                String block = selection.getBlocks().get(dy).get(dx);
                if (block == null || block.equals("0")) continue;

                String[] parts = block.split("_");
                String blockName = parts[0];
                Direction dir = Direction.UP;
                if (parts.length > 1 && parts[1].length() == 2) {
                    dir = Direction.fromChar(parts[1].charAt(1));
                }

                int[] sprite = SpriteManager.getInstance().getSpriteWithDirection(blockName, cellScreenSize, dir, false);
                if (sprite != null) {
                    int ix = (int) (screenX + dx * cellScreenSize);
                    int iy = (int) (screenY + dy * cellScreenSize);
                    renderer.drawSprite(gc, sprite, cellScreenSize, ix, iy);
                }
            }
        }

        gc.setStroke(Color.web(gridColor));
        gc.setLineWidth(3);
        gc.strokeRect(screenX, screenY, width * cellScreenSize, height * cellScreenSize);
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
                    javafx.stage.Stage stage = (javafx.stage.Stage) root.getScene().getWindow();
                    if (stage != null) stage.setTitle("Logic 2 - " + currentMapName);
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

        if (root.getScene() != null) {
            installKeyboardFilter(root.getScene());
        }

        palette.updateLayer(world.getCurrentLayer());
        renderer.render();
        javafx.application.Platform.runLater(() -> renderer.getCanvas().requestFocus());
    }

    @Override
    public void exit() {
        System.out.println("Exiting MainState");
        simulationEngine.stop();
        if (deleteTimer != null) {
            deleteTimer.stop();
        }
        if (camera != null) {
            camera.stop();
        }
    }

    @Override
    public void update() {
        // ===== Расчёт deltaSeconds =====
        long now = System.nanoTime();
        double deltaSeconds = 0;
        if (lastFrameNanos != 0) {
            deltaSeconds = (now - lastFrameNanos) / 1_000_000_000.0;
            if (deltaSeconds > 0.1) deltaSeconds = 0.1;
        }
        lastFrameNanos = now;

        // ===== Слежение камеры =====
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
        } else {
            boolean mouseInsideCanvas = false;
            double mouseCanvasX = 0;
            double mouseCanvasY = 0;

            if (!panning && !isMouseOverPalette(lastSceneMouseX, lastSceneMouseY)) {
                Point2D canvasCoords = getCanvasCoordinates(lastSceneMouseX, lastSceneMouseY);
                mouseCanvasX = canvasCoords.getX();
                mouseCanvasY = canvasCoords.getY();

                if (mouseCanvasX >= 0 && mouseCanvasX <= renderer.getCanvas().getWidth() &&
                        mouseCanvasY >= 0 && mouseCanvasY <= renderer.getCanvas().getHeight()) {
                    mouseInsideCanvas = true;
                }
            }

            cameraFollower.update(
                    deltaSeconds,
                    mouseCanvasX, mouseCanvasY,
                    mouseInsideCanvas,
                    CameraFollower.DEAD_ZONE_MOUSE,
                    CameraFollower.SMOOTHING_MOUSE
            );
        }

        renderer.render();

        GraphicsContext gc = renderer.getCanvas().getGraphicsContext2D();

        // 1) Превью копирования/вставки
        if (previewMode && selection.hasSelection()) {
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

        // 2) Рамка активного выделения
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

        // 3) Клавиатурный курсор
        if (keyboardCursorActive) {
            double screenX = camera.worldToScreenX(keyboardCursorX * camera.getBaseCellSize());
            double screenY = camera.worldToScreenY(keyboardCursorY * camera.getBaseCellSize());
            double cellSize = camera.getCellScreenSize();

            gc.setStroke(Color.web(gridColor));
            gc.setLineWidth(3);
            gc.strokeRect(screenX + 1, screenY + 1, cellSize - 2, cellSize - 2);
        }
    }

    @Override
    public void handleKeyPressed(KeyEvent event) {
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
                if (!selecting && !previewMode) {
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
                }
                break;

            case T:
                world.nextLayer();
                palette.updateLayer(world.getCurrentLayer());
                renderer.render();
                break;

            case G:
                world.previousLayer();
                palette.updateLayer(world.getCurrentLayer());
                renderer.render();
                break;

            case R:
                if (!selecting && !previewMode && !panning) {
                    if (keyboardCursorActive) {
                        removeBlockAtCell(keyboardCursorX, keyboardCursorY);
                    } else if (selection.hasSelection()) {
                        selection.deleteSelected(world, world.getCurrentLayer());
                        selection.clear();
                        renderer.render();
                        System.out.println("Deleted selected area");
                    } else {
                        deleteMode = true;
                        lastDeleteTime = System.nanoTime();
                        deleteTimer.start();
                        removeBlockAtSceneCoords(lastSceneMouseX, lastSceneMouseY);
                    }
                }
                break;

            case W: palette.setDirection(Direction.UP); break;
            case A: palette.setDirection(Direction.LEFT); break;
            case S: palette.setDirection(Direction.DOWN); break;
            case D:
                if (selection.hasSelection()) {
                    selection.clear();
                    previewMode = false;
                    renderer.render();
                } else {
                    palette.setDirection(Direction.RIGHT);
                }
                break;

            case X:
                if (selection.hasSelection() && !previewMode && !selecting) {
                    selection.cut(world, world.getCurrentLayer());
                    previewMode = true;
                    renderer.render();
                    System.out.println("Cut: selection removed, preview mode activated");
                }
                break;

            case C:
                if (selection.hasSelection() && !previewMode && !selecting) {
                    previewMode = true;
                    renderer.render();
                    System.out.println("Copy: preview mode activated");
                }
                break;

            case V:
                if (previewMode && selection.hasSelection()) {
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
                    selection.paste(world, world.getCurrentLayer(), px, py);
                    renderer.render();
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

        if (event.isControlDown()) {
            if (event.isShiftDown()) {
                if (event.getCode() == KeyCode.S) {
                    saveAsMap();
                }
            } else {
                if (event.getCode() == KeyCode.S) {
                    saveMap();
                }
            }
        }
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
            renderer.render();
            System.out.println("Selection finished: size " + selection.getWidth() + "x" + selection.getHeight());
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
            renderer.render();
        }

        boolean overPalette = isMouseOverPalette(event.getSceneX(), event.getSceneY());

        if (event.getButton() == MouseButton.SECONDARY) {
            if (!overPalette) {
                panning = true;
                lastMouseX = event.getSceneX();
                lastMouseY = event.getSceneY();
                renderer.getCanvas().setCursor(javafx.scene.Cursor.CLOSED_HAND);
            }
        } else if (event.getButton() == MouseButton.PRIMARY) {
            if (!overPalette && !selecting && !previewMode && !deleteMode) {
                placeBlock(event.getSceneX(), event.getSceneY());
                renderer.render();
            }
        }
    }

    @Override
    public void handleMouseReleased(MouseEvent event) {
        if (event.getButton() == MouseButton.SECONDARY) {
            panning = false;
            renderer.getCanvas().setCursor(javafx.scene.Cursor.DEFAULT);
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
            renderer.render();
        } else if (event.getButton() == MouseButton.PRIMARY && !overPalette && !selecting && !previewMode && !deleteMode) {
            placeBlock(event.getSceneX(), event.getSceneY());
            renderer.render();
        }

        if (selecting) {
            renderer.render();
        }

        if (previewMode) {
            renderer.render();
        }
    }

    @Override
    public void handleMouseMoved(MouseEvent event) {
        lastSceneMouseX = event.getSceneX();
        lastSceneMouseY = event.getSceneY();

        if (previewMode) {
            renderer.render();
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
        renderer.render();
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