package com.app.states;

import com.app.Config;
import com.app.core.MapManager;
import com.app.graphics.SpriteManager;
import com.app.graphics.ColorConfig;
import com.app.ui.HelpDialog;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class MenuState implements State {
    private BorderPane root;
    private State nextState;
    private VBox centerPanel;
    private VBox mapsContainer;
    private ScrollPane scrollPane;

    private ColorConfig colors;
    private String backgroundColor;

    public MenuState() {
        // Получаем цвета из SpriteManager
        this.colors = SpriteManager.getInstance().getColors();
        this.backgroundColor = colors.getBackground();

        root = new BorderPane();
        root.setStyle("-fx-background-color: " + backgroundColor + ";");

        centerPanel = new VBox(22);
        centerPanel.setAlignment(Pos.TOP_CENTER);
        centerPanel.setStyle("-fx-padding: 20;");
        centerPanel.setMaxWidth(800);

        // Заголовок
        Text title = new Text("Logica-Sandbox");
        title.getStyleClass().add("menu-title");
        title.setStyle("-fx-font-size: 64px;");
        title.setFill(Color.web(colors.getGrid()));

        Text mapsText = new Text("Local maps:");
        mapsText.setStyle("-fx-font-size: 28px;");
        mapsText.setFill(Color.web(colors.getGrid()));

        // Кнопка новой карты
        Button newMapBtn = new Button("+ NEW MAP");
        newMapBtn.getStyleClass().add("button");
        newMapBtn.setOnAction(e -> createNewMap());

        // Кнопка Texturepacks
        Button texpacksBtn = new Button("🎨 TEXTURE PACKS");
        texpacksBtn.getStyleClass().add("button");
        texpacksBtn.setOnAction(e -> openTexpacks());

        // Кнопка HELP
        Button helpBtn = new Button("❓ HELP");
        helpBtn.getStyleClass().add("button");
        helpBtn.setOnAction(e -> HelpDialog.show());

        // Контейнер для списка карт
        mapsContainer = new VBox(8);
        mapsContainer.setAlignment(Pos.TOP_CENTER);

        scrollPane = new ScrollPane(mapsContainer);
        scrollPane.getStyleClass().add("map-scroll-pane");
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefWidth(500);
        scrollPane.setPrefHeight(850);

        // Добавляем все элементы
        centerPanel.getChildren().addAll(
                title, mapsText, scrollPane,
                newMapBtn, texpacksBtn, helpBtn
        );
        root.setCenter(centerPanel);

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
            HBox mapRow = createMapRow(name);
            mapsContainer.getChildren().add(mapRow);
        }
    }

    private HBox createMapRow(String mapName) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("map-row");
        row.setMaxWidth(500);
        row.setPrefWidth(500);
        row.setStyle("-fx-background-color: " + colors.getBridge() + ";");

        Text nameLabel = new Text(mapName);
        nameLabel.getStyleClass().add("map-name");
        nameLabel.setFill(Color.web(colors.getBackground()));

        HBox buttonsBox = new HBox(8);
        buttonsBox.setAlignment(Pos.CENTER_RIGHT);
        buttonsBox.setVisible(false);
        buttonsBox.setManaged(false);

        Button playBtn = new Button("▶");
        playBtn.getStyleClass().add("icon-button");
        playBtn.setOnAction(e -> loadMap(mapName));

        Button renameBtn = new Button("✎");
        renameBtn.getStyleClass().add("icon-button");
        renameBtn.setOnAction(e -> renameMap(mapName));

        Button deleteBtn = new Button("🗑");
        deleteBtn.getStyleClass().add("icon-button");
        deleteBtn.setOnAction(e -> deleteMap(mapName));

        buttonsBox.getChildren().addAll(playBtn, renameBtn, deleteBtn);

        row.setOnMouseEntered(e -> {
            row.setStyle("-fx-background-color: " + colors.getSignalOn() + ";");
            buttonsBox.setVisible(true);
            buttonsBox.setManaged(true);
        });

        row.setOnMouseExited(e -> {
            row.setStyle("-fx-background-color: " + colors.getBridge() + ";");
            buttonsBox.setVisible(false);
            buttonsBox.setManaged(false);
        });

        row.getChildren().addAll(nameLabel, buttonsBox);
        HBox.setHgrow(buttonsBox, javafx.scene.layout.Priority.ALWAYS);

        return row;
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