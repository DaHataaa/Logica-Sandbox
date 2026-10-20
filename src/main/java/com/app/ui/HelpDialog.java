package com.app.ui;

import com.app.Config;
import com.app.audio.AudioManager;
import com.app.graphics.ColorConfig;
import com.app.graphics.SpriteManager;
import javafx.scene.control.Alert;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class HelpDialog {

    public static void show() {
        ColorConfig colors = SpriteManager.getInstance().getColors();

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initModality(Modality.APPLICATION_MODAL);
        alert.setTitle("Help");
        alert.setHeaderText("Game Manual");

        TextArea textArea = new TextArea();
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setStyle(
                "-fx-font-family: 'Monospaced', 'Consolas', 'Courier New', monospace;" +
                        "-fx-font-size: 18px;" +
                        "-fx-control-inner-background: " + colors.getBackground() + ";" +
                        "-fx-text-fill: " + colors.getGrid() + ";"
        );

        try {
            Path helpPath = Config.getDataDir().resolve("help_text.txt");
            if (Files.exists(helpPath)) {
                String content = new String(Files.readAllBytes(helpPath));
                textArea.setText(content);
            } else {
                textArea.setText(
                        "File: " + helpPath + "\n\n" +
                                "Please create this file with:\n" +
                                "  - Keyboard controls\n" +
                                "  - Game rules\n" +
                                "  - Tips and tricks"
                );
            }
        } catch (IOException e) {
            textArea.setText("Error: " + e.getMessage());
            System.err.println("Failed to read help file: " + e.getMessage());
        }

        textArea.setPrefHeight(700);
        textArea.setPrefWidth(800);

        ScrollPane scrollPane = new ScrollPane(textArea);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setPrefHeight(700);
        scrollPane.setPrefWidth(800);

        alert.getDialogPane().setContent(scrollPane);
        alert.getDialogPane().setPrefSize(820, 740);

        alert.getDialogPane().setStyle(
                "-fx-background-color: " + colors.getBackground() + ";"
        );
        if (alert.getDialogPane().lookup(".header-panel") != null) {
            alert.getDialogPane().lookup(".header-panel").setStyle(
                    "-fx-background-color: " + colors.getSignalOn() + ";"
            );
        }

        alert.setOnShown(evt -> {
            Stage stage = (Stage) alert.getDialogPane().getScene().getWindow();
            AudioManager.installButtonSound(alert.getDialogPane().getScene());

            javafx.stage.Window owner = null;
            for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
                if (w instanceof Stage && w != stage && w.isShowing()) {
                    owner = w;
                    break;
                }
            }

            if (owner != null) {
                stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
                stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
            } else {
                javafx.geometry.Rectangle2D screen =
                        javafx.stage.Screen.getPrimary().getVisualBounds();
                stage.setX((screen.getWidth() - stage.getWidth()) / 2);
                stage.setY((screen.getHeight() - stage.getHeight()) / 2);
            }
        });

        alert.showAndWait();
    }
}