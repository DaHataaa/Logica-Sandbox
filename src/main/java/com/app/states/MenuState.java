package com.app.states;

import com.app.audio.AudioManager;
import com.app.core.MapManager;
import com.app.core.World;
import com.app.graphics.SpriteManager;
import com.app.graphics.ColorConfig;
import com.app.ui.ContextMenuPopup;
import com.app.ui.HelpDialog;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.app.graphics.MenuBackgroundRenderer;
import javafx.scene.canvas.Canvas;

public class MenuState implements State {
    private BorderPane root;
    private State nextState;
    private VBox centerPanel;
    private VBox mapsContainer;
    private ScrollPane scrollPane;

    private ColorConfig colors;
    private String backgroundColor;

    private boolean contextMenuOpen = false;

    public MenuState() {
        this.colors = SpriteManager.getInstance().getColors();
        this.backgroundColor = colors.getBackground();

        root = new BorderPane();
        root.setStyle("-fx-background-color: " + backgroundColor + ";");

        centerPanel = new VBox(10);
        centerPanel.setAlignment(Pos.TOP_CENTER);
        centerPanel.setStyle("-fx-padding: 20;");
        centerPanel.setMaxWidth(820);

        Text title = new Text("Logica-Sandbox");
        title.getStyleClass().add("menu-title");
        title.setStyle("-fx-font-size: 64px;");
        title.setFill(Color.web(colors.getGrid()));

        Text mapsText = new Text("Local maps:");
        mapsText.setStyle("-fx-font-size: 28px;");
        mapsText.setFill(Color.web(colors.getGrid()));

        Button newMapBtn = new Button("+ NEW MAP");
        newMapBtn.getStyleClass().add("button");
        newMapBtn.setOnAction(e -> createNewMap());

        Button texpacksBtn = new Button("🎨 TEXTURE PACKS");
        texpacksBtn.getStyleClass().add("button");
        texpacksBtn.setOnAction(e -> openTexpacks());

        Button helpBtn = new Button("❓ HELP");
        helpBtn.getStyleClass().add("button");
        helpBtn.setOnAction(e -> HelpDialog.show());

        Button settingsBtn = new Button("⚙ SETTINGS");
        settingsBtn.getStyleClass().add("button");
        settingsBtn.setOnAction(e -> nextState = new SettingsState());

        VBox bottomButtons = new VBox(8);
        bottomButtons.setAlignment(Pos.CENTER);
        bottomButtons.getChildren().addAll(newMapBtn, texpacksBtn, helpBtn, settingsBtn);

        mapsContainer = new VBox(8);
        mapsContainer.setAlignment(Pos.TOP_CENTER);

        scrollPane = new ScrollPane(mapsContainer);
        scrollPane.getStyleClass().add("map-scroll-pane");
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefWidth(700);
        scrollPane.setPrefHeight(850);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        centerPanel.getChildren().addAll(
                title, mapsText, scrollPane, bottomButtons
        );

        Canvas bgCanvas = new Canvas();
        StackPane stack = new StackPane(bgCanvas, centerPanel);
        bgCanvas.widthProperty().bind(stack.widthProperty());
        bgCanvas.heightProperty().bind(stack.heightProperty());
        bgCanvas.widthProperty().addListener(o -> MenuBackgroundRenderer.draw(bgCanvas, colors));
        bgCanvas.heightProperty().addListener(o -> MenuBackgroundRenderer.draw(bgCanvas, colors));
        root.setCenter(stack);

        refreshMapsList();
    }

    private void refreshMapsList() {
        List<String> mapNames = MapManager.getMapList();
        Collections.sort(mapNames);
        mapsContainer.getChildren().clear();

        if (mapNames.isEmpty()) {
            Text emptyText = new Text("No maps yet...");
            emptyText.getStyleClass().add("empty-text");
            emptyText.setFill(Color.web(colors.getGrid()));
            mapsContainer.getChildren().add(emptyText);
            return;
        }

        for (String name : mapNames) {
            VBox card = createMapCard(name);
            mapsContainer.getChildren().add(card);
        }
    }

    private VBox createMapCard(String mapName) {
        MapManager.MapStats stats = MapManager.getMapStats(mapName);

        VBox card = new VBox(6);
        card.setPadding(new Insets(10, 14, 10, 14));
        card.setMaxWidth(660);
        card.setPrefWidth(660);
        card.setStyle(cardStyle(false));

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);

        // === Плашка имени на всю ширину ===
        StackPane nameBox = new StackPane();
        nameBox.setPadding(new Insets(3, 10, 3, 10));
        nameBox.setStyle(mapNameBoxStyle(false, false));
        nameBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(nameBox, Priority.ALWAYS);
        nameBox.setMaxWidth(Double.MAX_VALUE);

        Label nameLabel = new Label(mapName);
        nameLabel.setStyle(
                "-fx-font-family: 'Monospaced';" +
                        "-fx-font-size: 22px;" +
                        "-fx-font-weight: bold;" +
                        "-fx-text-fill: " + colors.getGrid() + ";"
        );
        nameBox.getChildren().add(nameLabel);

        header.getChildren().add(nameBox);

        String dateStr = (stats != null) ? stats.lastModifiedPretty : "—";
        Text dateLabel = smallItalicText("🕒 " + dateStr);

        Region sep = new Region();
        sep.setPrefHeight(1);
        sep.setMaxHeight(1);
        sep.setStyle("-fx-background-color: " + colors.getGrid() + ";");

        VBox statsBox = new VBox(3);
        if (stats != null) {
            statsBox.getChildren().add(smallItalicText(
                    "🔣 Logic gates: " + stats.logicGates + "   ≈   " + stats.approxTransistors + " transistors"));
            statsBox.getChildren().add(smallItalicText(
                    "➡ Arrows: " + stats.arrows + "    📌 Pegs: " + stats.pegs));
            statsBox.getChildren().add(smallItalicText(
                    "📝 Text blocks: " + stats.texts + "    🧩 Total blocks: " + stats.totalBlocks));
        } else {
            statsBox.getChildren().add(smallItalicText("📊 Stats unavailable"));
        }

        card.getChildren().addAll(header, dateLabel, sep, statsBox);

        card.setOnMouseEntered(e -> {
            if (contextMenuOpen) return;
            card.setStyle(cardStyle(true));
        });
        card.setOnMouseExited(e -> {
            if (contextMenuOpen) { card.setStyle(cardStyle(false)); return; }
            card.setStyle(cardStyle(false));
        });

        // === Двойной клик — открыть карту ===
        card.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                loadMap(mapName);
                e.consume();
            }
        });

        // === ПКМ — контекстное меню ===
        card.setOnContextMenuRequested(e -> {
            Stage owner = null;
            if (root.getScene() != null && root.getScene().getWindow() instanceof Stage) {
                owner = (Stage) root.getScene().getWindow();
            }
            if (owner == null) return;

            final Stage finalOwner = owner;
            java.util.List<ContextMenuPopup.Item> items = java.util.List.of(
                    new ContextMenuPopup.Item("PLAY", () -> loadMap(mapName)),
                    new ContextMenuPopup.Item("RENAME", () -> {
                        nameBox.setStyle(mapNameBoxStyle(false, true));
                        beginInlineRename(nameLabel, mapName, nameBox);
                    }),
                    new ContextMenuPopup.Item("DELETE", () -> {
                        if (confirmDeleteMap(mapName)) deleteMap(mapName);
                    })
            );

            ContextMenuPopup.show(owner, e.getScreenX(), e.getScreenY(), items,
                    blocked -> {
                        contextMenuOpen = blocked;
                        if (blocked) {
                            // снять hover у всех карточек
                            for (javafx.scene.Node n : mapsContainer.getChildren()) {
                                if (n instanceof VBox) n.setStyle(cardStyle(false));
                            }
                        }
                    });
            e.consume();
        });

        return card;
    }

    private boolean isInside(javafx.scene.Node parent, Object target) {
        if (!(target instanceof javafx.scene.Node)) return false;
        javafx.scene.Node n = (javafx.scene.Node) target;
        while (n != null) {
            if (n == parent) return true;
            n = n.getParent();
        }
        return false;
    }

    private void beginInlineRename(Label nameLabel, String oldName, StackPane nameBox) {
        TextField field = new TextField(oldName);
        field.setStyle(
                "-fx-font-family: 'Monospaced';" +
                        "-fx-font-size: 22px;" +
                        "-fx-font-weight: bold;" +
                        "-fx-background-color: " + colors.getBackground() + ";" +
                        "-fx-text-fill: " + colors.getText() + ";" +
                        "-fx-control-inner-background: " + colors.getBackground() + ";" +
                        "-fx-border-width: 0;" +
                        "-fx-padding: 0;"
        );

        nameBox.getChildren().setAll(field);
        field.requestFocus();
        field.selectAll();

        Runnable commit = () -> {
            String newName = field.getText().trim();
            if (!newName.isEmpty() && !newName.equals(oldName)
                    && !MapManager.mapExists(newName)) {
                if (MapManager.renameMap(oldName, newName)) {
                    nameLabel.setText(newName);
                }
            }
            nameBox.getChildren().setAll(nameLabel);
            nameBox.setStyle(mapNameBoxStyle(false, false));
        };

        field.setOnAction(ev -> commit.run());
        field.focusedProperty().addListener((obs, was, is) -> { if (was && !is) commit.run(); });
        field.setOnKeyPressed(ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) {
                nameBox.getChildren().setAll(nameLabel);
                nameBox.setStyle(mapNameBoxStyle(false, false));
            }
        });
    }

    private String mapNameBoxStyle(boolean hover, boolean active) {
        String border;
        if (active) border = colors.getSignalOn();
        else if (hover) border = colors.getGrid();
        else border = "transparent";

        return "-fx-background-color: " + colors.getBackground() + ";" +
                "-fx-border-color: " + border + ";" +
                "-fx-border-width: 2;" +
                "-fx-border-radius: 6;" +
                "-fx-background-radius: 6;";
    }

    private boolean confirmDeleteMap(String name) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initModality(Modality.APPLICATION_MODAL);
        if (root.getScene() != null && root.getScene().getWindow() instanceof Stage) {
            alert.initOwner((Stage) root.getScene().getWindow());
        }
        alert.setTitle("Delete map");
        alert.setHeaderText("Delete \"" + name + "\"?");
        alert.setContentText("This action cannot be undone.");

        alert.getDialogPane().setStyle("-fx-background-color: " + colors.getBackground() + ";");
        if (alert.getDialogPane().lookup(".header-panel") != null) {
            alert.getDialogPane().lookup(".header-panel")
                    .setStyle("-fx-background-color: " + colors.getArrow() + ";");
        }
        AudioManager.installButtonSound(alert.getDialogPane().getScene());

        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private String cardStyle(boolean hover) {
        String border = hover ? colors.getSignalOn() : colors.getGrid();
        return "-fx-background-color: " + colors.getBackground() + ";" +
                "-fx-border-color: " + border + ";" +
                "-fx-border-width: 3;" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;";
    }

    private Button makeIconButton(String glyph, Runnable action) {
        Button btn = new Button(glyph);
        btn.getStyleClass().add("icon-button");
        btn.setOnAction(e -> action.run());

        String normalStyle = iconBtnStyle(colors.getBridge());
        String hoverStyle = iconBtnStyle(colors.getPower());

        btn.setStyle(normalStyle);
        btn.setOnMouseEntered(e -> btn.setStyle(hoverStyle));
        btn.setOnMouseExited(e -> btn.setStyle(normalStyle));

        return btn;
    }

    private String iconBtnStyle(String bg) {
        return "-fx-text-fill: " + colors.getBackground() + ";" +
                "-fx-font-size: 24px;" +
                "-fx-font-weight: bold;" +
                "-fx-padding: 5 10 5 10;" +
                "-fx-background-radius: 3;" +
                "-fx-cursor: hand;" +
                "-fx-min-width: 50;" +
                "-fx-min-height: 42;" +
                "-fx-pref-height: 42;" +
                "-fx-background-color: " + bg + ";";
    }

    private Text smallItalicText(String s) {
        Text t = new Text(s);
        t.setFill(Color.web(colors.getGrid()));
        t.setFont(Font.font("Monospaced", FontPosture.ITALIC, 13));
        return t;
    }

    private void openTexpacks() {
        nextState = new TexturepackState();
    }

    private void createNewMap() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("New Map");
        dialog.setHeaderText("Create new map");
        dialog.setContentText("Map name:");
        dialog.getEditor().setStyle("-fx-font-size: 14px;");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            if (!name.trim().isEmpty()) {
                if (MapManager.mapExists(name)) {
                    showError("Map already exists!");
                } else {
                    MapManager.saveMap(name, new World());
                    nextState = new MainState(name);
                }
            }
        });
    }

    private void loadMap(String name) {
        nextState = new MainState(name);
    }

    private void deleteMap(String name) {
        if (MapManager.deleteMap(name)) {
            refreshMapsList();
        } else {
            showError("Failed to delete map");
        }
    }

    private void showError(String message) {
        System.err.println(message);
    }

    @Override
    public void enter() {
        System.out.println("Entering MenuState");
        nextState = this;
        refreshMapsList();
    }

    @Override
    public void exit() {
        System.out.println("Exiting MenuState");
    }

    @Override
    public void update() {}

    @Override
    public void handleKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            System.exit(0);
        } else if (event.getCode() == KeyCode.H) {
            HelpDialog.show();
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