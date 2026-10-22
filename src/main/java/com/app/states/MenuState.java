package com.app.states;

import com.app.Config;
import com.app.core.MapManager;
import com.app.core.World;
import com.app.graphics.SpriteManager;
import com.app.graphics.ColorConfig;
import com.app.ui.HelpDialog;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
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

        // <<< УМЕНЬШЕНО: 12 → 8 (расстояние между карточками)
        mapsContainer = new VBox(8);
        mapsContainer.setAlignment(Pos.TOP_CENTER);

        scrollPane = new ScrollPane(mapsContainer);
        scrollPane.getStyleClass().add("map-scroll-pane");
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefWidth(700);
        scrollPane.setPrefHeight(850);
        // <<< НОВОЕ: список карт растягивается по вертикали, забирая всё
        //     свободное место у centerPanel, а кнопки прижимаются вниз
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

        Text nameLabel = new Text(mapName);
        nameLabel.setFill(Color.web(colors.getGrid()));
        nameLabel.setFont(Font.font("Monospaced", FontWeight.BOLD, 22));
        HBox.setHgrow(nameLabel, Priority.ALWAYS);
        nameLabel.setWrappingWidth(480);

        HBox buttonsBox = new HBox(6);
        buttonsBox.setAlignment(Pos.CENTER_RIGHT);

        Button playBtn = makeIconButton("▶", () -> loadMap(mapName));
        Button renameBtn = makeIconButton("✎", () -> renameMap(mapName));
        Button deleteBtn = makeIconButton("🗑", () -> deleteMap(mapName));

        buttonsBox.getChildren().addAll(playBtn, renameBtn, deleteBtn);
        header.getChildren().addAll(nameLabel, buttonsBox);

        String dateStr = (stats != null) ? stats.lastModifiedPretty : "—";
        Text dateLabel = smallItalicText("🕒 " + dateStr);

        Region sep = new Region();
        sep.setPrefHeight(1);
        sep.setMaxHeight(1);
        sep.setStyle("-fx-background-color: " + colors.getGrid() + ";");

        VBox statsBox = new VBox(3);

        if (stats != null) {
            String gatesLine = "🔣 Logic gates: " + stats.logicGates
                    + "   ≈   " + stats.approxTransistors + " transistors";
            statsBox.getChildren().add(smallItalicText(gatesLine));

            String arrowsLine = "➡ Arrows: " + stats.arrows
                    + "    📌 Pegs: " + stats.pegs;
            statsBox.getChildren().add(smallItalicText(arrowsLine));

            String textLine = "📝 Text blocks: " + stats.texts
                    + "    🧩 Total blocks: " + stats.totalBlocks;
            statsBox.getChildren().add(smallItalicText(textLine));
        } else {
            statsBox.getChildren().add(smallItalicText("📊 Stats unavailable"));
        }

        card.getChildren().addAll(header, dateLabel, sep, statsBox);

        card.setOnMouseEntered(e -> card.setStyle(cardStyle(true)));
        card.setOnMouseExited(e -> card.setStyle(cardStyle(false)));

        return card;
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

    private void renameMap(String oldName) {
        TextInputDialog dialog = new TextInputDialog(oldName);
        dialog.setTitle("Rename Map");
        dialog.setHeaderText("Enter new name");
        dialog.setContentText("Map name:");
        dialog.getEditor().setStyle("-fx-font-size: 14px;");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(newName -> {
            if (!newName.trim().isEmpty() && !newName.equals(oldName)) {
                if (MapManager.renameMap(oldName, newName)) {
                    refreshMapsList();
                } else {
                    showError("Failed to rename map");
                }
            }
        });
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