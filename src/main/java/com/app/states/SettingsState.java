package com.app.states;

import com.app.Config;
import com.app.audio.AudioManager;
import com.app.graphics.ColorConfig;
import com.app.graphics.MenuBackgroundRenderer;
import com.app.graphics.SpriteManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

public class SettingsState implements State {
    private BorderPane root;
    private State nextState;

    private VBox centerPanel;
    private VBox groupsContainer;

    private ColorConfig colors;
    private Canvas backgroundCanvas;

    private final Config config;

    // ===== Эталон: BlockPalette.fieldStyle =====
    private static final String FIELD_BASE_STYLE =
            "-fx-font-family: 'Monospace';" +
                    "-fx-font-size: 18px;" +
                    "-fx-alignment: center;" +
                    "-fx-background-radius: 5;" +
                    "-fx-border-radius: 5;" +
                    "-fx-border-width: 2;" +
                    "-fx-padding: 4 6 4 6;";

    private static final int FIELD_WIDTH = 140;

    private static final String[] RESOLUTIONS = {
            "1280x720",
            "1366x768",
            "1600x900",
            "1920x1080",
            "2560x1440",
            "3840x2160"
    };

    private boolean suppressFieldListeners = false;

    public SettingsState() {
        this.config = Config.getInstance();
        this.colors = SpriteManager.getInstance().getColors();

        root = new BorderPane();
        root.setStyle("-fx-background-color: " + colors.getBackground() + ";");

        centerPanel = new VBox(22);
        centerPanel.setAlignment(Pos.TOP_CENTER);
        centerPanel.setPadding(new Insets(28));
        centerPanel.setMaxWidth(800);

        // ===== Заголовок — как menu-title =====
        Text title = new Text("SETTINGS");
        title.setFill(Color.web(colors.getGrid()));
        title.setStyle("-fx-fill: " + colors.getGrid() + ";");
        title.setFont(Font.font("Monospaced", FontWeight.BOLD, 32));

        groupsContainer = new VBox(22);
        groupsContainer.setAlignment(Pos.TOP_CENTER);

        ScrollPane scrollPane = new ScrollPane(groupsContainer);
        scrollPane.getStyleClass().add("map-scroll-pane");
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefWidth(720);
        scrollPane.setPrefHeight(820);

        // ===== BACK — только .button, без inline =====
        Button backBtn = new Button("← BACK");
        backBtn.getStyleClass().add("button");
        backBtn.setOnAction(e -> backToMenu());

        centerPanel.getChildren().addAll(title, scrollPane, backBtn);

        backgroundCanvas = new Canvas();
        StackPane stack = new StackPane(backgroundCanvas, centerPanel);
        backgroundCanvas.widthProperty().bind(stack.widthProperty());
        backgroundCanvas.heightProperty().bind(stack.heightProperty());
        backgroundCanvas.widthProperty().addListener(o -> drawBackground());
        backgroundCanvas.heightProperty().addListener(o -> drawBackground());
        root.setCenter(stack);

        buildGroups();
    }

    private void drawBackground() {
        if (backgroundCanvas != null) {
            MenuBackgroundRenderer.draw(backgroundCanvas, colors);
        }
    }

    // ============================================================
    // ПОСТРОЕНИЕ ГРУПП
    // ============================================================
    private void buildGroups() {
        groupsContainer.getChildren().clear();
        groupsContainer.getChildren().add(buildGameplayGroup());
        groupsContainer.getChildren().add(buildVideoGroup());
        groupsContainer.getChildren().add(buildAudioGroup());
    }

    private VBox buildGameplayGroup() {
        VBox rows = new VBox(0);
        rows.setFillWidth(true);

        rows.getChildren().add(intRow(
                "Simulation speed",
                String.valueOf(config.getSimulationSpeed()),
                true, false,
                config::setSimulationSpeed,
                this::formatSpeed
        ));
        rows.getChildren().add(separator());

        rows.getChildren().add(intRow(
                "Undo depth",
                String.valueOf(config.getUndoDepth()),
                false, false,
                config::setUndoDepth,
                String::valueOf
        ));

        return group("🎮 GAMEPLAY", rows);
    }

    private VBox buildVideoGroup() {
        VBox rows = new VBox(0);
        rows.setFillWidth(true);

        rows.getChildren().add(resolutionRow());
        rows.getChildren().add(separator());

        rows.getChildren().add(boolRow(
                "Fullscreen",
                config.isFullscreen(),
                true,
                config::setFullscreen
        ));
        rows.getChildren().add(separator());

        rows.getChildren().add(intRow(
                "Cell size min",
                String.valueOf(config.getCellSizeMin()),
                false, false,
                v -> {
                    if (v < 1) v = 1;
                    if (v >= config.getCellSizeMax()) v = config.getCellSizeMax() - 1;
                    config.setCellSizeMin(v);
                },
                String::valueOf
        ));
        rows.getChildren().add(separator());

        rows.getChildren().add(intRow(
                "Cell size max",
                String.valueOf(config.getCellSizeMax()),
                false, false,
                v -> {
                    if (v <= config.getCellSizeMin()) v = config.getCellSizeMin() + 1;
                    if (v > 512) v = 512;
                    config.setCellSizeMax(v);
                },
                String::valueOf
        ));

        return group("🖥️ VIDEO", rows);
    }

    private VBox buildAudioGroup() {
        VBox rows = new VBox(0);
        rows.setFillWidth(true);

        rows.getChildren().add(sliderRow(
                "Master volume",
                config.getMasterVolume(),
                config::setMasterVolume
        ));
        rows.getChildren().add(separator());

        rows.getChildren().add(sliderRow(
                "UI click volume",
                config.getUiClickVolume(),
                config::setUiClickVolume
        ));
        rows.getChildren().add(separator());

        rows.getChildren().add(sliderRow(
                "Placement volume",
                config.getPlacementVolume(),
                config::setPlacementVolume
        ));
        rows.getChildren().add(separator());

        rows.getChildren().add(sliderRow(
                "Remove volume",
                config.getRemoveVolume(),
                config::setRemoveVolume
        ));
        rows.getChildren().add(separator());

        rows.getChildren().add(sliderRow(
                "Block activation volume",
                config.getBlockActivationVolume(),
                config::setBlockActivationVolume
        ));

        return group("🔊 AUDIO", rows);
    }

    // ============================================================
    // ГРУППА / РАЗДЕЛИТЕЛЬ / СТРОКА
    // ============================================================
    private VBox group(String titleText, VBox rowsContainer) {
        VBox group = new VBox(8);
        group.setAlignment(Pos.TOP_LEFT);
        group.setFillWidth(true);

        // Заголовок группы — 20 bold, grid
        Text title = new Text(titleText);
        title.setFill(Color.web(colors.getGrid()));
        title.setStyle("-fx-fill: " + colors.getGrid() + ";");
        title.setFont(Font.font("Monospaced", FontWeight.BOLD, 20));

        // Рамка группы — border 2, radius 10, bg=background, border=grid
        rowsContainer.setStyle(
                "-fx-background-color: " + colors.getBackground() + ";" +
                        "-fx-border-color: " + colors.getGrid() + ";" +
                        "-fx-border-width: 2;" +
                        "-fx-border-radius: 10;" +
                        "-fx-background-radius: 10;"
        );

        group.getChildren().addAll(title, rowsContainer);
        group.setMaxWidth(700);
        group.setPrefWidth(700);
        return group;
    }

    /** Разделитель — как в BlockPalette: Rectangle высота 2, grid. */
    private Rectangle separator() {
        Rectangle sep = new Rectangle(700, 2);
        sep.setFill(Color.web(colors.getGrid()));
        return sep;
    }

    private HBox rowBase(String labelText, javafx.scene.Node control, boolean restartRequired) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 14, 10, 14));
        row.setStyle("-fx-background-color: " + colors.getBackground() + ";");

        // Лейбл — 14, без bold, grid (как speedLabel/simLabel в BlockPalette)
        Label label = new Label(labelText);
        label.setFont(Font.font("Monospace", 14));
        label.setStyle("-fx-text-fill: " + colors.getGrid() + ";");
        HBox.setHgrow(label, Priority.ALWAYS);
        label.setMaxWidth(Double.MAX_VALUE);

        row.getChildren().add(label);

        if (restartRequired) {
            Text marker = new Text("⟳");
            marker.setFill(Color.web(colors.getPower()));
            marker.setFont(Font.font("Monospaced", FontWeight.BOLD, 18));
            Tooltip.install(marker, new Tooltip("Requires restart"));
            row.getChildren().add(marker);
        }

        if (control != null) {
            row.getChildren().add(control);
        }
        return row;
    }

    // ---------- Числовое поле ----------
    private interface IntConsumer { void accept(int v); }
    private interface IntFormatter { String format(int v); }

    private HBox intRow(String labelText,
                        String initial,
                        boolean speedMode,
                        boolean restartRequired,
                        IntConsumer apply,
                        IntFormatter formatter) {

        TextField field = createField(initial);

        Runnable commit = () -> {
            String text = field.getText().trim().toUpperCase();

            if (speedMode && (text.equals("MAX") || text.equals("-1"))) {
                apply.accept(-1);
                setFieldTextSilently(field, "MAX");
                flashBorder(field, colors.getSignalOn(), colors.getGrid());
                return;
            }
            if (speedMode && (text.equals("PAUSE") || text.equals("PAUSED"))) {
                apply.accept(0);
                setFieldTextSilently(field, "0");
                flashBorder(field, colors.getSignalOn(), colors.getGrid());
                return;
            }

            try {
                int v = Integer.parseInt(text);
                apply.accept(v);
                setFieldTextSilently(field, formatter.format(v));
                flashBorder(field, colors.getSignalOn(), colors.getGrid());
            } catch (NumberFormatException e) {
                setFieldTextSilently(field, formatter.format(parseCurrent(field)));
                flashBorder(field, colors.getNot(), colors.getGrid());
            }
        };

        field.setOnAction(e -> commit.run());
        field.focusedProperty().addListener((obs, was, is) -> {
            if (was && !is) commit.run();
        });

        return rowBase(labelText, field, restartRequired);
    }

    private int parseCurrent(TextField field) {
        try {
            return Integer.parseInt(field.getText().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------- Resolution (ComboBox) ----------
    private HBox resolutionRow() {
        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().addAll(RESOLUTIONS);
        combo.setPrefWidth(180);
        combo.setMaxWidth(180);

        String current = config.getWindowWidth() + "x" + config.getWindowHeight();
        if (!combo.getItems().contains(current)) {
            combo.getItems().add(0, current);
        }
        combo.setValue(current);
        styleCombo(combo);

        combo.setOnAction(e -> {
            String sel = combo.getValue();
            if (sel == null) return;
            String[] parts = sel.split("x");
            if (parts.length == 2) {
                try {
                    int w = Integer.parseInt(parts[0].trim());
                    int h = Integer.parseInt(parts[1].trim());
                    config.setWindowWidth(w);
                    config.setWindowHeight(h);
                } catch (NumberFormatException ignored) {}
            }
        });

        return rowBase("Resolution", combo, true);
    }

    private void styleCombo(ComboBox<String> combo) {
        // Как поля: 18 без bold, bg=background, fg=arrow, border=grid, radius 5
        combo.setStyle(
                "-fx-font-family: 'Monospace';" +
                        "-fx-font-size: 18px;" +
                        "-fx-background-color: " + colors.getBackground() + ";" +
                        "-fx-text-fill: " + colors.getArrow() + ";" +
                        "-fx-background-radius: 5;" +
                        "-fx-border-radius: 5;" +
                        "-fx-border-width: 2;" +
                        "-fx-border-color: " + colors.getGrid() + ";" +
                        "-fx-padding: 0;"
        );

        combo.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    setStyle(
                            "-fx-font-family: 'Monospace';" +
                                    "-fx-font-size: 18px;" +
                                    "-fx-text-fill: " + colors.getArrow() + ";" +
                                    "-fx-background-color: " + colors.getBackground() + ";"
                    );
                }
            }
        });

        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                    setStyle(
                            "-fx-font-family: 'Monospace';" +
                                    "-fx-font-size: 18px;" +
                                    "-fx-text-fill: " + colors.getArrow() + ";" +
                                    "-fx-background-color: " + colors.getBackground() + ";"
                    );
                }
            }
        });

        javafx.application.Platform.runLater(() -> {
            javafx.scene.Node listView = combo.lookup(".list-view");
            if (listView != null) {
                listView.setStyle(
                        "-fx-background-color: " + colors.getBackground() + ";" +
                                "-fx-border-color: " + colors.getGrid() + ";" +
                                "-fx-border-width: 1;"
                );
            }
            javafx.scene.Node arrow = combo.lookup(".arrow");
            if (arrow != null) {
                arrow.setStyle("-fx-background-color: " + colors.getGrid() + ";");
            }
        });
    }

    // ---------- Toggle (как .button) ----------
    private interface BoolConsumer { void accept(boolean v); }

    private HBox boolRow(String labelText, boolean initial,
                         boolean restartRequired, BoolConsumer apply) {
        Button toggle = new Button(initial ? "ON" : "OFF");
        styleToggle(toggle, initial, false);

        toggle.setOnAction(e -> {
            boolean newVal = !"ON".equals(toggle.getText());
            apply.accept(newVal);
            toggle.setText(newVal ? "ON" : "OFF");
            styleToggle(toggle, newVal, false);
        });

        // hover — как .button:hover (signalOn/background)
        toggle.setOnMouseEntered(e -> {
            if (!toggle.isPressed()) styleToggle(toggle, isOn(toggle), false, true);
        });
        toggle.setOnMouseExited(e -> styleToggle(toggle, isOn(toggle), false));

        // pressed — как .button:pressed (power/background)
        toggle.setOnMousePressed(e -> styleToggle(toggle, isOn(toggle), true));
        toggle.setOnMouseReleased(e -> {
            if (toggle.isHover()) styleToggle(toggle, isOn(toggle), false, true);
            else styleToggle(toggle, isOn(toggle), false);
        });

        return rowBase(labelText, toggle, restartRequired);
    }

    private boolean isOn(Button toggle) {
        return "ON".equals(toggle.getText());
    }

    private void styleToggle(Button toggle, boolean on, boolean pressed) {
        styleToggle(toggle, on, pressed, false);
    }

    /**
     * Стиль toggle по логике .button из StyleManager:
     *  normal ON:  bg=signalOn, fg=background, border=grid
     *  normal OFF: bg=background, fg=grid, border=grid
     *  hover:      bg=signalOn, fg=background
     *  pressed:    bg=power, fg=background
     */
    private void styleToggle(Button toggle, boolean on, boolean pressed, boolean hover) {
        String bg;
        String fg;

        if (pressed) {
            bg = colors.getPower();
            fg = colors.getBackground();
        } else if (hover) {
            bg = colors.getSignalOn();
            fg = colors.getBackground();
        } else if (on) {
            bg = colors.getSignalOn();
            fg = colors.getBackground();
        } else {
            bg = colors.getBackground();
            fg = colors.getGrid();
        }

        toggle.setStyle(
                "-fx-font-family: 'Monospace';" +
                        "-fx-font-size: 24px;" +
                        "-fx-font-weight: bold;" +
                        "-fx-background-color: " + bg + ";" +
                        "-fx-text-fill: " + fg + ";" +
                        "-fx-background-radius: 5;" +
                        "-fx-border-radius: 5;" +
                        "-fx-border-width: 2;" +
                        "-fx-border-color: " + colors.getGrid() + ";" +
                        "-fx-padding: 3 15 3 15;" +
                        "-fx-min-width: 100;" +
                        "-fx-min-height: 42;"
        );
    }

    // ---------- Slider ----------
    private HBox sliderRow(String labelText, int initial, IntConsumer apply) {
        Slider slider = new Slider(0, 100, initial);
        slider.setPrefWidth(240);
        slider.setBlockIncrement(5);
        slider.setMajorTickUnit(25);
        slider.setMinorTickCount(4);
        slider.setShowTickMarks(true);
        slider.setShowTickLabels(false);
        styleSlider(slider);

        Region fill = new Region();
        fill.setStyle(
                "-fx-background-color: " + colors.getSignalOn() + ";" +
                        "-fx-background-radius: 2;"
        );
        fill.setMouseTransparent(true);
        fill.setPrefHeight(4);
        fill.setMaxHeight(4);
        fill.setMinHeight(4);

        StackPane sliderStack = new StackPane(fill, slider);
        sliderStack.setAlignment(Pos.CENTER_LEFT);
        sliderStack.setPrefWidth(240);
        sliderStack.setMaxWidth(240);

        Runnable updateFill = () -> {
            double w = slider.getWidth();
            if (w <= 0) return;
            double frac = (slider.getValue() - slider.getMin()) / (slider.getMax() - slider.getMin());
            double thumbW = 16.0;
            double usable = Math.max(0, w - thumbW);
            double fillW = thumbW / 2 + frac * usable;
            fill.setPrefWidth(fillW);
            fill.setMaxWidth(fillW);
            fill.setMinWidth(fillW);
        };
        slider.valueProperty().addListener((o, ov, nv) -> updateFill.run());
        slider.widthProperty().addListener((o, ov, nv) -> updateFill.run());
        javafx.application.Platform.runLater(updateFill);

        TextField field = createField(String.valueOf(initial));
        field.setPrefWidth(70);
        field.setMaxWidth(70);

        final boolean[] suppress = {false};

        slider.valueProperty().addListener((obs, oldV, newV) -> {
            if (suppress[0]) return;
            int v = (int) Math.round(newV.doubleValue());
            suppress[0] = true;
            setFieldTextSilently(field, String.valueOf(v));
            suppress[0] = false;
            apply.accept(v);
            AudioManager.getInstance().refreshVolumes();
        });

        Runnable commitField = () -> {
            try {
                int v = Integer.parseInt(field.getText().trim());
                if (v < 0) v = 0;
                if (v > 100) v = 100;
                apply.accept(v);
                suppress[0] = true;
                slider.setValue(v);
                setFieldTextSilently(field, String.valueOf(v));
                suppress[0] = false;
                flashBorder(field, colors.getSignalOn(), colors.getGrid());
                AudioManager.getInstance().refreshVolumes();
            } catch (NumberFormatException e) {
                suppress[0] = true;
                setFieldTextSilently(field, String.valueOf((int) slider.getValue()));
                suppress[0] = false;
                flashBorder(field, colors.getNot(), colors.getGrid());
            }
        };

        field.setOnAction(e -> commitField.run());
        field.focusedProperty().addListener((obs, was, is) -> {
            if (was && !is) commitField.run();
        });

        HBox control = new HBox(10, sliderStack, field);
        control.setAlignment(Pos.CENTER_RIGHT);

        return rowBase(labelText, control, false);
    }

    private void styleSlider(Slider slider) {
        slider.setStyle("-fx-control-inner-background: " + colors.getBackground() + ";");

        // Принудительно создаём skin ДО lookup — иначе на первом входе lookup() == null
        slider.applyCss();
        slider.layout();

        javafx.scene.Node track = slider.lookup(".track");
        if (track != null) {
            track.setStyle(
                    "-fx-background-color: " + colors.getGrid() + ";" +
                            "-fx-background-radius: 2;"
            );
        }
        javafx.scene.Node thumb = slider.lookup(".thumb");
        if (thumb != null) {
            thumb.setStyle("-fx-background-color: " + colors.getPower() + ";");
        }
    }

    // ============================================================
    // ПОЛЕ ВВОДА — эталон BlockPalette.fieldStyle
    // ============================================================
    private TextField createField(String initial) {
        TextField field = new TextField(initial);
        field.setPrefWidth(FIELD_WIDTH);
        field.setMaxWidth(FIELD_WIDTH);
        field.setStyle(fieldStyle(colors.getSignalOn())); // покой = signalOn (как BlockPalette)
        field.setFocusTraversable(true);

        field.focusedProperty().addListener((obs, was, is) -> {
            if (is) {
                field.setStyle(fieldStyle(colors.getPower()));   // фокус — power (акцент)
            } else {
                field.setStyle(fieldStyle(colors.getSignalOn())); // покой — signalOn
            }
        });

        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                field.getParent().requestFocus();
                e.consume();
            }
        });

        return field;
    }

    private String fieldStyle(String borderColor) {
        // Точно как BlockPalette.fieldStyle: fg=arrow, padding 4 6, без bold
        return FIELD_BASE_STYLE +
                "-fx-background-color: " + colors.getBackground() + ";" +
                "-fx-text-fill: " + colors.getArrow() + ";" +
                "-fx-control-inner-background: " + colors.getBackground() + ";" +
                "-fx-border-color: " + borderColor + ";";
    }

    private void setFieldTextSilently(TextField field, String text) {
        suppressFieldListeners = true;
        field.setText(text);
        suppressFieldListeners = false;
    }

    private void flashBorder(TextField field, String flashColor, String baseColor) {
        field.setStyle(fieldStyle(flashColor));
        javafx.animation.PauseTransition pt =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));
        pt.setOnFinished(e -> field.setStyle(fieldStyle(baseColor)));
        pt.play();
    }

    private String formatSpeed(int v) {
        if (v == -1) return "MAX";
        return String.valueOf(v);
    }

    // ============================================================
    // State
    // ============================================================
    private void backToMenu() {
        config.save();
        AudioManager.getInstance().refreshVolumes();
        nextState = new MenuState();
    }

    @Override
    public void enter() {
        System.out.println("Entering SettingsState");
        nextState = this;
        drawBackground();
    }

    @Override
    public void exit() {
        System.out.println("Exiting SettingsState");
        config.save();
        AudioManager.getInstance().refreshVolumes();
    }

    @Override
    public void update() {}

    @Override
    public void handleKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            backToMenu();
        }
    }

    @Override
    public void handleKeyReleased(KeyEvent event) {}

    @Override
    public void handleMousePressed(MouseEvent event) {}

    @Override
    public void handleMouseReleased(MouseEvent event) {}

    @Override
    public void handleMouseDragged(MouseEvent event) {}

    @Override
    public void handleMouseMoved(MouseEvent event) {}

    @Override
    public void handleScroll(ScrollEvent event) {}

    @Override
    public State getNextState() {
        return nextState;
    }

    @Override
    public BorderPane getRoot() {
        return root;
    }
}