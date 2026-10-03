package com.app.ui;

import com.app.Config;
import com.app.core.BlockType;
import com.app.core.Direction;
import com.app.graphics.SpriteManager;
import com.app.graphics.ColorConfig;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelWriter;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

public class BlockPalette extends VBox {
    private final SpriteManager spriteManager;
    private final ColorConfig colors;
    private int iconSize = 64;
    private final int paletteWidth = 145;
    private BlockType[] blockTypes;
    private int selectedIndex = 0;
    private Direction currentDirection = Direction.UP;
    private HBox[] itemContainers;
    private Canvas[] icons;
    private Runnable onSelectionChange;
    private Runnable onSpeedChange;

    private Text layerText;
    private Text simStatusText;
    private Rectangle simStatusDot;

    // ===== TPS ввод =====
    private TextField speedField;
    private Text actualTpsText;
    private PauseTransition speedDebounce;

    private int currentLayer = 0;
    private int maxLayers = 5;
    private boolean isSimulationRunning = true;
    private int currentSpeed = 60;

    // Флаг, чтобы listener не срабатывал при программном setText
    private boolean suppressSpeedListener = false;

    private static final String FIELD_BASE_STYLE =
            "-fx-font-family: 'Monospace';" +
                    "-fx-font-size: 18px;" +
                    "-fx-alignment: center;" +
                    "-fx-background-radius: 5;" +
                    "-fx-border-radius: 5;" +
                    "-fx-border-width: 2;" +
                    "-fx-padding: 4 6 4 6;";

    public BlockPalette() {
        super(10);
        spriteManager = SpriteManager.getInstance();
        colors = spriteManager.getColors();
        blockTypes = BlockType.values();
        icons = new Canvas[blockTypes.length];
        itemContainers = new HBox[blockTypes.length];
        currentSpeed = Config.getInstance().getSimulationSpeed();

        maxLayers = Config.getInstance().getLayers();

        String bgColor = colors.getBackground();
        setStyle("-fx-background-color: " + bgColor + "; -fx-padding: 18; -fx-border-color: " + colors.getGrid() + "; -fx-border-width: 0 0 0 3;");

        setPrefWidth(paletteWidth);
        setMinWidth(paletteWidth);
        setMaxWidth(paletteWidth);

        setAlignment(Pos.TOP_CENTER);

        createItems();
        createSpeedControl();
        createSimulationStatus();
        createLayerDisplay();

        sceneProperty().addListener((obs, old, newScene) -> {
            if (newScene != null) {
                newScene.heightProperty().addListener((h, oldH, newH) -> resizePalette());
                resizePalette();
            }
        });
        parentProperty().addListener((obs, old, newParent) -> {
            if (newParent != null) resizePalette();
        });
    }

    private void createItems() {
        for (int i = 0; i < blockTypes.length; i++) {
            BlockType type = blockTypes[i];
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);

            Text numberText = new Text(getNumberForIndex(i));
            numberText.setFill(Color.web(colors.getGrid()));
            numberText.setFont(Font.font("Monospace", 20));

            Canvas canvas = new Canvas(iconSize, iconSize);
            canvas.setWidth(iconSize);
            canvas.setHeight(iconSize);

            final int index = i;
            row.setOnMouseClicked(e -> {
                selectedIndex = index;
                updateSelection();
                if (onSelectionChange != null) onSelectionChange.run();
            });

            drawIcon(canvas, getSpriteName(type), type.hasDirection(), currentDirection);
            icons[i] = canvas;
            itemContainers[i] = row;
            row.getChildren().addAll(numberText, canvas);
            getChildren().add(row);
        }
        updateSelection();
    }

    private void createSpeedControl() {
        VBox speedBox = new VBox(5);
        speedBox.setAlignment(Pos.CENTER);
        speedBox.setStyle("-fx-padding: 10 0 10 0;");

        Rectangle topSeparator = new Rectangle(paletteWidth - 40, 2);
        topSeparator.setFill(Color.web(colors.getGrid()));

        Text speedLabel = new Text("SIM. SPEED");
        speedLabel.setFill(Color.web(colors.getGrid()));
        speedLabel.setFont(Font.font("Monospace", 14));

        speedField = new TextField(getSpeedString(currentSpeed));
        speedField.setPrefWidth(paletteWidth - 30);
        speedField.setMaxWidth(paletteWidth - 30);
        // Обычное состояние — ОРАНЖЕВАЯ рамка
        speedField.setStyle(fieldStyle(colors.getSignalOn()));
        speedField.setFocusTraversable(true);

        // Любой ввод — рамка становится ОБЫЧНОЙ (grid)
        speedField.textProperty().addListener((obs, oldText, newText) -> {
            if (suppressSpeedListener) return;
            speedField.setStyle(fieldStyle(colors.getGrid()));
        });

        // Enter — применяем и возвращаем ОРАНЖЕВУЮ рамку
        speedField.setOnAction(e -> {
            applySpeedFromField();
        });

        // Клик по полю — рамка обычная (показываем, что идёт ввод)
        speedField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                speedField.setStyle(fieldStyle(colors.getGrid()));
            }
            // Blur — НЕ применяем, но возвращаем оранжевую рамку
            if (wasFocused && !isFocused) {
                speedField.setStyle(fieldStyle(colors.getSignalOn()));
            }
        });

        // ESC — откат к текущему и оранжевая рамка
        speedField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                setFieldTextSilently(getSpeedString(currentSpeed));
                speedField.setStyle(fieldStyle(colors.getSignalOn()));
                speedField.getParent().requestFocus();
                e.consume();
            }
        });

        // ===== Реальный TPS =====
        actualTpsText = new Text("actual: 0 TPS");
        actualTpsText.setFill(Color.web(colors.getSignalOn()));
        actualTpsText.setFont(Font.font("Monospace", 14));
        actualTpsText.setStyle("-fx-font-weight: bold;");

        speedBox.getChildren().addAll(topSeparator, speedLabel, speedField, actualTpsText);
        getChildren().add(speedBox);
    }


    private String fieldStyle(String borderColor) {
        String bg = colors.getBackground();
        String fg = colors.getArrow();
        return FIELD_BASE_STYLE +
                "-fx-background-color: " + bg + ";" +
                "-fx-text-fill: " + fg + ";" +
                "-fx-control-inner-background: " + bg + ";" +
                "-fx-border-color: " + borderColor + ";";
    }

    /** Устанавливает текст в поле, не триггеря listener. */
    private void setFieldTextSilently(String text) {
        suppressSpeedListener = true;
        speedField.setText(text);
        suppressSpeedListener = false;
    }

    /** Применяет ввод из поля. Вызывается ТОЛЬКО по Enter. */
    private void applySpeedFromField() {
        if (speedField == null) return;

        String text = speedField.getText().trim().toUpperCase();

        if (text.isEmpty()) {
            setFieldTextSilently(getSpeedString(currentSpeed));
            speedField.getParent().requestFocus();
            return;
        }

        int newSpeed;
        if (text.equals("MAX") || text.equals("-1")) {
            newSpeed = -1;
        } else if (text.equals("PAUSE") || text.equals("PAUSED") || text.equals("0")) {
            newSpeed = 0;
        } else {
            try {
                newSpeed = Integer.parseInt(text);
                if (newSpeed < 0) newSpeed = -1;
            } catch (NumberFormatException e) {
                setFieldTextSilently(getSpeedString(currentSpeed));
                speedField.getParent().requestFocus();
                return;
            }
        }

        boolean changed = (newSpeed != currentSpeed);
        currentSpeed = newSpeed;

        setFieldTextSilently(getSpeedString(currentSpeed));

        speedField.setStyle(fieldStyle(colors.getSignalOn()));

        speedField.getParent().requestFocus();

        if (changed && onSpeedChange != null) {
            onSpeedChange.run();
        }
    }



    private void createSimulationStatus() {
        VBox statusBox = new VBox(5);
        statusBox.setAlignment(Pos.CENTER);
        statusBox.setStyle("-fx-padding: 10 0 10 0;");

        Rectangle topSeparator = new Rectangle(paletteWidth - 40, 2);
        topSeparator.setFill(Color.web(colors.getGrid()));

        Text simLabel = new Text("SIM. STATUS");
        simLabel.setFill(Color.web(colors.getGrid()));
        simLabel.setFont(Font.font("Monospace", 14));

        HBox statusRow = new HBox(8);
        statusRow.setAlignment(Pos.CENTER);

        simStatusDot = new Rectangle(12, 12);
        simStatusDot.setArcWidth(6);
        simStatusDot.setArcHeight(6);

        simStatusText = new Text("RUNNING");
        simStatusText.setFill(Color.web(colors.getSignalOn()));
        simStatusText.setFont(Font.font("Monospace", 22));
        simStatusText.setStyle("-fx-font-weight: bold;");

        updateSimulationStatus(true);

        statusRow.getChildren().addAll(simStatusDot, simStatusText);
        statusBox.getChildren().addAll(topSeparator, simLabel, statusRow);
        getChildren().add(statusBox);
    }

    private void createLayerDisplay() {
        VBox layerBox = new VBox(5);
        layerBox.setAlignment(Pos.CENTER);
        layerBox.setStyle("-fx-padding: 10 0 10 0;");

        Rectangle topSeparator = new Rectangle(paletteWidth - 40, 2);
        topSeparator.setFill(Color.web(colors.getGrid()));

        Text layerLabel = new Text("LAYER");
        layerLabel.setFill(Color.web(colors.getGrid()));
        layerLabel.setFont(Font.font("Monospace", 14));

        layerText = new Text((currentLayer + 1) + " / " + maxLayers);
        layerText.setFill(Color.web(colors.getSignalOn()));
        layerText.setFont(Font.font("Monospace", 22));
        layerText.setStyle("-fx-font-weight: bold");

        layerBox.getChildren().addAll(topSeparator, layerLabel, layerText);
        getChildren().add(layerBox);
    }

    private String getSpeedString(int speed) {
        if (speed == -1) return "MAX";
        if (speed == 0) return "0";
        return String.valueOf(speed);
    }

    public int getCurrentSpeed() { return currentSpeed; }
    public boolean isMaxSpeed() { return currentSpeed == -1; }

    public void setCurrentSpeed(int speed) {
        this.currentSpeed = speed;
        if (speedField != null) {
            setFieldTextSilently(getSpeedString(speed));
            speedField.setStyle(fieldStyle(colors.getSignalOn()));
        }
    }

    /** Реальный TPS. Вызывается из FX-потока. */
    public void setActualTps(int tps) {
        if (actualTpsText == null) return;
        String text;
        if (currentSpeed == 0) {
            text = "actual: paused";
        } else {
            text = "actual: " + tps + " TPS";
        }
        actualTpsText.setText(text);
    }

    public void setOnSpeedChange(Runnable callback) { this.onSpeedChange = callback; }

    public void updateSimulationStatus(boolean isRunning) {
        this.isSimulationRunning = isRunning;
        if (simStatusText != null && simStatusDot != null) {
            if (isRunning) {
                simStatusText.setText("RUNNING");
                simStatusText.setFill(Color.web(colors.getSignalOn()));
                simStatusDot.setFill(Color.web(colors.getSignalOn()));
            } else {
                simStatusText.setText("PAUSED");
                simStatusText.setFill(Color.web(colors.getGrid()));
                simStatusDot.setFill(Color.web(colors.getGrid()));
            }
        }
    }

    public void updateLayer(int layer) {
        this.currentLayer = layer;
        if (layerText != null) {
            layerText.setText((layer + 1) + " / " + maxLayers);
            layerText.setFill(Color.web(colors.getSignalOn()));
        }
    }

    private void resizePalette() {
        if (getScene() == null) return;
        double sceneHeight = getScene().getHeight();
        double availableHeight = sceneHeight - 320;
        int totalIcons = blockTypes.length;
        int spacing = 10;
        int minIconSize = (int) ((availableHeight - (totalIcons - 1) * spacing) / totalIcons);
        if (minIconSize > paletteWidth - 40) minIconSize = paletteWidth - 40;
        if (minIconSize < 20) minIconSize = 20;
        if (minIconSize != iconSize) {
            iconSize = minIconSize;
            resizeIcons();
        }
    }

    private String getNumberForIndex(int index) {
        if (index >= 0 && index <= 8) return String.valueOf(index + 1);
        if (index == 9) return "0";
        return String.valueOf(index + 1);
    }

    private String getSpriteName(BlockType type) {
        return type.getName();
    }

    private void resizeIcons() {
        for (int i = 0; i < icons.length; i++) {
            Canvas canvas = icons[i];
            canvas.setWidth(iconSize);
            canvas.setHeight(iconSize);
            drawIcon(canvas, getSpriteName(blockTypes[i]), blockTypes[i].hasDirection(), currentDirection);
        }
        updateSelection();
    }

    private void drawIcon(Canvas canvas, String spriteName, boolean hasDirection, Direction dir) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.web(colors.getBackground()));
        gc.fillRect(0, 0, iconSize, iconSize);

        int[] sprite;
        if (hasDirection) {
            sprite = spriteManager.getRotatedSprite(spriteName, iconSize, dir);
        } else {
            sprite = spriteManager.getSprite(spriteName, iconSize);
        }

        if (sprite != null) {
            drawSprite(gc, sprite, iconSize, 0, 0);
        }

        gc.setStroke(Color.web(colors.getGrid()));
        gc.setLineWidth(1);
        gc.strokeRect(2, 2, iconSize - 4, iconSize - 4);
    }

    private void drawSprite(GraphicsContext gc, int[] sprite, int size, int x, int y) {
        PixelWriter pw = gc.getPixelWriter();
        for (int py = 0; py < size; py++) {
            int srcRowStart = py * size;
            for (int px = 0; px < size; px++) {
                int color = sprite[srcRowStart + px];
                if ((color & 0xFF000000) != 0) {
                    pw.setArgb(x + px, y + py, color);
                }
            }
        }
    }

    private void updateSelection() {
        String signalOnColor = colors.getSignalOn();
        String gridColor = colors.getGrid();

        for (int i = 0; i < icons.length; i++) {
            Canvas canvas = icons[i];
            GraphicsContext gc = canvas.getGraphicsContext2D();
            if (i == selectedIndex) {
                gc.setStroke(Color.web(signalOnColor));
                gc.setLineWidth(3);
                gc.strokeRect(1, 1, iconSize - 2, iconSize - 2);
                Text numberText = (Text) itemContainers[i].getChildren().get(0);
                numberText.setFill(Color.web(signalOnColor));
            } else {
                drawIcon(canvas, getSpriteName(blockTypes[i]), blockTypes[i].hasDirection(), currentDirection);
                Text numberText = (Text) itemContainers[i].getChildren().get(0);
                numberText.setFill(Color.web(gridColor));
            }
        }
    }

    public void setDirection(Direction dir) {
        this.currentDirection = dir;
        for (int i = 0; i < icons.length; i++) {
            drawIcon(icons[i], getSpriteName(blockTypes[i]), blockTypes[i].hasDirection(), dir);
        }
        updateSelection();
    }

    public BlockType getSelectedBlock() { return blockTypes[selectedIndex]; }
    public Direction getCurrentDirection() { return currentDirection; }

    public void setSelectedIndex(int index) {
        if (index >= 0 && index < blockTypes.length) {
            selectedIndex = index;
            updateSelection();
            if (onSelectionChange != null) onSelectionChange.run();
        }
    }

    public void setOnSelectionChange(Runnable callback) { this.onSelectionChange = callback; }
    public void forceResize() { resizePalette(); }
}