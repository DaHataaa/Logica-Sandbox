package com.app;

import com.app.graphics.SpriteManager;
import com.app.states.MenuState;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.*;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.io.File;

public class Main extends Application {
    private MasterState masterState;
    private Scene scene;
    private Parent currentRoot;

    @Override
    public void start(Stage primaryStage) {
        Config config = Config.getInstance();

        SpriteManager.getInstance(); // инициализируем SpriteManager
        StyleManager.generateCSS();

        masterState = new MasterState(new MenuState());
        currentRoot = masterState.getRoot();

        scene = new Scene(currentRoot, config.getWindowWidth(), config.getWindowHeight());

        // Загружаем сгенерированный CSS
        File cssFile = Config.getDataDir().resolve("styles.css").toFile();
        if (cssFile.exists()) {
            scene.getStylesheets().add(cssFile.toURI().toString());
            System.out.println("CSS loaded from: " + cssFile.getAbsolutePath());
        } else {
            System.out.println("CSS not found at: " + cssFile.getAbsolutePath());
        }

        scene.setOnKeyPressed(masterState::handleKeyPressed);
        scene.setOnKeyReleased(masterState::handleKeyReleased);
        scene.setOnMousePressed(masterState::handleMousePressed);
        scene.setOnMouseReleased(masterState::handleMouseReleased);
        scene.setOnMouseDragged(masterState::handleMouseDragged);
        scene.setOnMouseMoved(masterState::handleMouseMoved);
        scene.setOnScroll(masterState::handleScroll);

        new AnimationTimer() {
            @Override
            public void handle(long now) {
                masterState.gameTick();

                Parent newRoot = masterState.getRoot();
                if (newRoot != currentRoot) {
                    currentRoot = newRoot;
                    scene.setRoot(currentRoot);
                }
            }
        }.start();

        primaryStage.setScene(scene);
        primaryStage.setTitle("Logica-Sandbox");
        primaryStage.setOnCloseRequest(e -> {
            Config.getInstance().save();
            System.exit(0);
        });
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}