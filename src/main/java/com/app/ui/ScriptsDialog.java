package com.app.ui;

import com.app.Config;
import com.app.audio.AudioManager;
import com.app.core.Script;
import com.app.core.ScriptManager;
import com.app.graphics.ColorConfig;
import com.app.graphics.SpriteManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.List;

public class ScriptsDialog {

    public interface OnRun {
        void run(String code, int delaySeconds);
    }

    private static final int MAX_NAME_LEN = 48;

    public static void show(Stage owner, OnRun onRun) {
        ColorConfig colors = SpriteManager.getInstance().getColors();
        final boolean[] contextMenuOpen = {false};

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Scripts");

        VBox listBox = new VBox(6);
        listBox.setPadding(new Insets(10));
        listBox.setStyle("-fx-background-color: " + colors.getBackground() + ";");

        ScrollPane listScroll = new ScrollPane(listBox);
        listScroll.setFitToWidth(true);
        listScroll.setPrefWidth(300);
        listScroll.setStyle("-fx-background: " + colors.getBackground() + ";");

        TextArea codeArea = new TextArea();
        codeArea.setWrapText(false);
        codeArea.setStyle(
                "-fx-font-family: 'Monospaced';" +
                        "-fx-font-size: 14px;" +
                        "-fx-control-inner-background: " + colors.getBackground() + ";" +
                        "-fx-text-fill: " + colors.getText() + ";" +
                        "-fx-highlight-fill: " + colors.getSignalOn() + ";"
        );
        VBox.setVgrow(codeArea, Priority.ALWAYS);

        Button saveBtn = gameButton("SAVE", colors);
        Button runBtn = gameButton("RUN", colors);
        Button newBtn = gameButton("NEW", colors);

        HBox btnRow = new HBox(10, newBtn, saveBtn, runBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(10, 0, 0, 0));

        VBox rightBox = new VBox(10, codeArea, btnRow);
        rightBox.setPadding(new Insets(10));
        VBox.setVgrow(rightBox, Priority.ALWAYS);

        HBox root = new HBox(listScroll, rightBox);
        HBox.setHgrow(rightBox, Priority.ALWAYS);
        root.setStyle("-fx-background-color: " + colors.getBackground() + ";");

        final Script[] locked = {null};
        final Script[] hovered = {null};
        final Runnable[] refresh = new Runnable[1];
        final boolean[] closing = {false};

        // Определяем refresh ДО использования
        refresh[0] = () -> {
            listBox.getChildren().clear();

            // Загружаем список заново с диска!
            List<Script> currentScripts = ScriptManager.loadAll();

            for (Script s : currentScripts) {
                listBox.getChildren().add(
                        makeItem(s, colors, locked, hovered, codeArea, listBox,
                                onRun, dialog, closing, refresh[0], contextMenuOpen));
            }

            if (currentScripts.isEmpty()) {
                codeArea.clear();
                codeArea.setDisable(true);
                locked[0] = null;
                hovered[0] = null;
                applyStyles(listBox, null, null, colors);
                return;
            }

            codeArea.setDisable(false);

            // Логика восстановления выделенного скрипта
            Script target = null;
            if (locked[0] != null) {
                target = currentScripts.stream()
                        .filter(sc -> sc.getName().equals(locked[0].getName()))
                        .findFirst().orElse(null);
            }

            if (target == null && hovered[0] != null) {
                target = currentScripts.stream()
                        .filter(sc -> sc.getName().equals(hovered[0].getName()))
                        .findFirst().orElse(null);
            }

            if (target != null) {
                locked[0] = target;
                hovered[0] = target;
                codeArea.setText(target.getCode());
            } else {
                // Если ничего не выбрано, очищаем поле или берем первый?
                // По запросу "превью сначала не было ничьего" лучше очистить,
                // но для удобства часто выбирают первый. Оставим очистку, если нет активного.
                locked[0] = null;
                hovered[0] = null;
                codeArea.clear();
            }

            applyStyles(listBox, locked[0], hovered[0], colors);
        };

        // Deselect по клику в пустое место
        listBox.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getTarget() == listBox) {
                if (locked[0] != null) {
                    locked[0].setCode(codeArea.getText());
                    ScriptManager.save(locked[0]);
                }
                locked[0] = null;
                hovered[0] = null;
                codeArea.clear(); // Очищаем при снятии выделения
                refresh[0].run();
            }
        });

        // Контекстное меню по ПКМ на пустом месте списка
        listBox.setOnContextMenuRequested(e -> {
            if (e.getTarget() != listBox) return;

            java.util.List<ContextMenuPopup.Item> items = java.util.List.of(
                    new ContextMenuPopup.Item("NEW SCRIPT", () -> {
                        Script newScript = ScriptManager.createNew("new_script");
                        locked[0] = newScript;
                        hovered[0] = newScript;
                        codeArea.setText(newScript.getCode());
                        refresh[0].run();
                    })
            );
            ContextMenuPopup.show(dialog, e.getScreenX(), e.getScreenY(), items,
                    blocked -> contextMenuOpen[0] = blocked);
            e.consume();
        });

        saveBtn.setOnAction(e -> {
            if (locked[0] == null) return;
            locked[0].setCode(codeArea.getText());
            ScriptManager.save(locked[0]);
            hovered[0] = locked[0];
            locked[0] = null;
            refresh[0].run();
        });

        runBtn.setOnAction(e -> {
            if (locked[0] == null) return;
            locked[0].setCode(codeArea.getText());
            ScriptManager.save(locked[0]);
            int delay = Config.getInstance().getScriptRunDelay();
            String code = locked[0].getCode();
            locked[0] = null;
            hovered[0] = null;
            closing[0] = true;
            dialog.hide();
            if (onRun != null) onRun.run(code, delay);
        });

        newBtn.setOnAction(e -> {
            Script newScript = ScriptManager.createNew("new_script");
            locked[0] = newScript;
            hovered[0] = newScript;
            codeArea.setText(newScript.getCode());
            refresh[0].run();
        });

        refresh[0].run();

        Scene scene = new Scene(root, 1000, 640);
        dialog.setScene(scene);
        AudioManager.installButtonSound(scene);

        dialog.setOnHidden(ev -> {
            if (closing[0]) return;
            if (locked[0] != null) {
                locked[0].setCode(codeArea.getText());
                ScriptManager.save(locked[0]);
            }
        });

        dialog.showAndWait();
    }

    // ============ СПИСОК ============
    private static HBox makeItem(Script script, ColorConfig colors,
                                 Script[] locked, Script[] hovered,
                                 TextArea codeArea, VBox listBox,
                                 OnRun onRun, Stage dialog,
                                 boolean[] closing, Runnable refresh,
                                 boolean[] contextMenuOpen) {
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 10, 6, 10));
        row.setUserData(script);

        StackPane nameBox = new StackPane();
        nameBox.setPadding(new Insets(3, 8, 3, 8));
        nameBox.setStyle(nameBoxStyle(false, false, colors));
        nameBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(nameBox, Priority.ALWAYS);
        nameBox.setMaxWidth(Double.MAX_VALUE);

        Label name = new Label(script.getName());
        name.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 14px; -fx-text-fill: " + colors.getGrid() + ";");
        nameBox.getChildren().add(name);
        row.getChildren().add(nameBox);

        row.setOnMouseEntered(e -> hoverEnter(script, locked, hovered, codeArea, listBox, colors));
        row.setOnMouseExited(e -> hoverExit(locked, hovered, codeArea, listBox, colors));

        // Клик ЛКМ = lock
        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY
                    && (e.getTarget() == name || e.getTarget() == nameBox || e.getTarget() == row)) {
                if (locked[0] != null && locked[0] != script) {
                    locked[0].setCode(codeArea.getText());
                    ScriptManager.save(locked[0]);
                }
                locked[0] = script;
                hovered[0] = script;
                codeArea.setText(script.getCode());
                applyStyles(listBox, locked[0], hovered[0], colors);
            }
        });

        row.setOnContextMenuRequested(e -> {
            if (dialog.getScene() == null || dialog.getScene().getWindow() == null) return;

            java.util.List<ContextMenuPopup.Item> items = java.util.List.of(
                    new ContextMenuPopup.Item("RUN", () -> {
                        if (locked[0] != null && locked[0] != script) {
                            locked[0].setCode(codeArea.getText());
                            ScriptManager.save(locked[0]);
                        } else if (locked[0] == script) {
                            script.setCode(codeArea.getText());
                        }
                        ScriptManager.save(script);
                        int delay = Config.getInstance().getScriptRunDelay();
                        String code = script.getCode();
                        locked[0] = null;
                        hovered[0] = null;
                        closing[0] = true;
                        dialog.hide();
                        if (onRun != null) onRun.run(code, delay);
                    }),
                    new ContextMenuPopup.Item("RENAME",
                            () -> {
                                nameBox.setStyle(nameBoxStyle(false, true, colors));
                                beginInlineRename(name, script, nameBox, colors);
                            },
                            true),
                    new ContextMenuPopup.Item("DELETE",
                            () -> {
                                if (confirmDelete(dialog, script.getName(), colors)) {
                                    boolean success = ScriptManager.delete(script.getName());
                                    if (success) {
                                        locked[0] = null;
                                        hovered[0] = null;
                                        codeArea.clear();
                                        javafx.application.Platform.runLater(refresh);
                                    }
                                }
                            },
                            true)
            );
            ContextMenuPopup.show(dialog, e.getScreenX(), e.getScreenY(), items,
                    blocked -> contextMenuOpen[0] = blocked);
            e.consume();
        });

        return row;
    }

    private static void beginInlineRename(Label name, Script script, StackPane nameBox, ColorConfig colors) {
        TextField field = new TextField(script.getName());
        field.setStyle(
                "-fx-font-family: 'Monospaced';" +
                        "-fx-font-size: 14px;" +
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
            String nn = field.getText().trim();
            if (!nn.isEmpty() && nn.length() <= MAX_NAME_LEN
                    && !nn.equals(script.getName())
                    && !ScriptManager.exists(nn)) {
                String old = script.getName();
                if (ScriptManager.rename(old, nn)) {
                    script.setName(nn);
                    ScriptManager.save(script);
                    name.setText(nn);
                }
            }
            nameBox.getChildren().setAll(name);
            nameBox.setStyle(nameBoxStyle(false, false, colors));
        };

        field.setOnAction(ev -> commit.run());
        field.focusedProperty().addListener((obs, was, is) -> {
            if (was && !is) commit.run();
        });
        field.setOnKeyPressed(ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) {
                nameBox.getChildren().setAll(name);
                nameBox.setStyle(nameBoxStyle(false, false, colors));
            }
        });
    }

    private static void hoverEnter(Script script, Script[] locked, Script[] hovered,
                                   TextArea codeArea, VBox listBox, ColorConfig colors) {
        hovered[0] = script;
        if (locked[0] == null) {
            codeArea.setText(script.getCode());
        }
        applyStyles(listBox, locked[0], hovered[0], colors);
    }

    private static void hoverExit(Script[] locked, Script[] hovered,
                                  TextArea codeArea, VBox listBox, ColorConfig colors) {
        hovered[0] = null;
        if (locked[0] != null) {
            codeArea.setText(locked[0].getCode());
        } else {
            codeArea.clear();
        }
        applyStyles(listBox, locked[0], null, colors);
    }

    private static String nameBoxStyle(boolean hover, boolean active, ColorConfig colors) {
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

    private static void applyStyles(VBox listBox, Script locked, Script hovered, ColorConfig colors) {
        for (javafx.scene.Node n : listBox.getChildren()) {
            Script s = (Script) n.getUserData();
            if (s == null) continue;
            String border;
            if (s == locked) {
                border = "-fx-border-color: " + colors.getSignalOn() + ";" +
                        "-fx-border-width: 3;" +
                        "-fx-border-radius: 6;";
            } else if (s == hovered) {
                border = "-fx-border-color: " + colors.getGrid() + ";" +
                        "-fx-border-width: 2;" +
                        "-fx-border-radius: 6;";
            } else {
                border = "-fx-border-color: transparent;" +
                        "-fx-border-width: 2;" +
                        "-fx-border-radius: 6;";
            }
            n.setStyle(
                    "-fx-background-color: " + colors.getBackground() + ";" +
                            "-fx-background-radius: 6;" +
                            border
            );
        }
    }

    private static Button gameButton(String text, ColorConfig colors) {
        Button b = new Button(text);
        b.setStyle(buttonStyle(colors, ButtonState.NORMAL));
        b.setMinWidth(80);
        b.setOnMouseEntered(e -> b.setStyle(buttonStyle(colors, ButtonState.HOVER)));
        b.setOnMouseExited(e -> b.setStyle(buttonStyle(colors, ButtonState.NORMAL)));
        b.setOnMousePressed(e -> b.setStyle(buttonStyle(colors, ButtonState.PRESSED)));
        b.setOnMouseReleased(e ->
                b.setStyle(buttonStyle(colors, b.isHover()
                        ? ButtonState.HOVER : ButtonState.NORMAL)));
        return b;
    }

    private enum ButtonState {NORMAL, HOVER, PRESSED}

    private static String buttonStyle(ColorConfig colors, ButtonState state) {
        String bg;
        switch (state) {
            case HOVER:
                bg = colors.getSignalOn();
                break;
            case PRESSED:
                bg = colors.getPower();
                break;
            default:
                bg = colors.getBridge();
                break;
        }
        return "-fx-font-family: 'Monospaced';" +
                "-fx-font-size: 14px;" +
                "-fx-font-weight: bold;" +
                "-fx-background-color: " + bg + ";" +
                "-fx-text-fill: " + colors.getBackground() + ";" +
                "-fx-background-radius: 5;" +
                "-fx-padding: 6 16;";
    }

    private static boolean confirmDelete(Stage owner, String name, ColorConfig colors) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initModality(Modality.APPLICATION_MODAL);
        alert.initOwner(owner);
        alert.setTitle("Delete script");
        alert.setHeaderText("Delete \"" + name + "\"?");
        alert.setContentText("This action cannot be undone.");
        alert.getDialogPane().setStyle(
                "-fx-background-color: " + colors.getBackground() + ";"
        );
        if (alert.getDialogPane().lookup(".header-panel") != null) {
            alert.getDialogPane().lookup(".header-panel").setStyle(
                    "-fx-background-color: " + colors.getArrow() + ";"
            );
        }
        AudioManager.installButtonSound(alert.getDialogPane().getScene());
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }
}