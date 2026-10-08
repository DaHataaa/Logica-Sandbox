package com.app.ui;

import com.app.graphics.ColorConfig;
import com.app.graphics.SpriteManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.function.UnaryOperator;

public class TextEditorDialog {

    /** Результат редактирования. */
    public static class Result {
        public final String trueText;
        public final String falseText;

        public Result(String trueText, String falseText) {
            this.trueText = trueText;
            this.falseText = falseText;
        }
    }

    private static final int MAX_LEN = 64;

    /**
     * Открывает модальный диалог редактирования текстового блока.
     *
     * @param owner       родительское окно (может быть null)
     * @param currentTrue текущий текст для состояния "true" (может быть null)
     * @param currentFalse текущий текст для состояния "false" (может быть null)
     * @return Result или null, если пользователь отменил
     */
    public static Result show(Stage owner, String currentTrue, String currentFalse) {
        ColorConfig colors = SpriteManager.getInstance().getColors();

        if (currentTrue == null) currentTrue = "";
        if (currentFalse == null) currentFalse = "";

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("Edit text block");
        dialog.setResizable(false);

        // ===== Поля ввода =====
        TextField trueField = createField(colors, currentTrue);
        TextField falseField = createField(colors, currentFalse);

        // ===== Подписи =====
        Label trueLabel = new Label("TRUE:");
        trueLabel.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 16px; -fx-font-weight: bold;");
        trueLabel.setTextFill(Color.web(colors.getGrid()));

        Label falseLabel = new Label("FALS:");
        falseLabel.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 16px; -fx-font-weight: bold;");
        falseLabel.setTextFill(Color.web(colors.getGrid()));

        // ===== Строки =====
        HBox trueRow = new HBox(10, trueLabel, trueField);
        trueRow.setAlignment(Pos.CENTER_LEFT);

        HBox falseRow = new HBox(10, falseLabel, falseField);
        falseRow.setAlignment(Pos.CENTER_LEFT);

        // ===== Кнопки =====
        Button okBtn = new Button("OK");
        Button cancelBtn = new Button("Cancel");
        styleButton(okBtn, colors);
        styleButton(cancelBtn, colors);

        final Result[] result = {null};

        okBtn.setOnAction(e -> {
            String t = trueField.getText();
            String f = falseField.getText();
            if (t == null) t = "";
            if (f == null) f = "";
            if (t.length() > MAX_LEN) t = t.substring(0, MAX_LEN);
            if (f.length() > MAX_LEN) f = f.substring(0, MAX_LEN);
            result[0] = new Result(t, f);
            dialog.close();
        });

        cancelBtn.setOnAction(e -> {
            result[0] = null;
            dialog.close();
        });

        HBox buttonsRow = new HBox(10, okBtn, cancelBtn);
        buttonsRow.setAlignment(Pos.CENTER_RIGHT);

        // ===== Компоновка =====
        VBox root = new VBox(12, trueRow, falseRow, buttonsRow);
        root.setPadding(new Insets(16));
        root.setAlignment(Pos.CENTER_LEFT);
        root.setStyle("-fx-background-color: " + colors.getBackground() + ";");

        Scene scene = new Scene(root);
        dialog.setScene(scene);

        javafx.application.Platform.runLater(trueField::requestFocus);

        dialog.showAndWait();
        return result[0];
    }

    private static TextField createField(ColorConfig colors, String initial) {
        TextField field = new TextField(initial);
        field.setPrefWidth(320);
        field.setStyle(
                "-fx-font-family: 'Monospaced';" +
                        "-fx-font-size: 16px;" +
                        "-fx-font-weight: bold;" +
                        "-fx-background-color: " + colors.getBackground() + ";" +
                        "-fx-text-fill: " + colors.getText() + ";" +
                        "-fx-control-inner-background: " + colors.getBackground() + ";" +
                        "-fx-border-color: " + colors.getGrid() + ";" +
                        "-fx-border-width: 2;" +
                        "-fx-border-radius: 4;" +
                        "-fx-background-radius: 4;"
        );

        // Ограничение длины + запрет '|' (разделитель) и '_' (escape-символ для пробела)
        UnaryOperator<TextFormatter.Change> filter = change -> {
            String newText = change.getControlNewText();
            if (newText.length() > MAX_LEN) return null;
            if (newText.indexOf('|') >= 0) return null;
            if (newText.indexOf('_') >= 0) return null;
            return change;
        };
        field.setTextFormatter(new TextFormatter<>(filter));

        return field;
    }

    private static void styleButton(Button btn, ColorConfig colors) {
        btn.setStyle(
                "-fx-font-family: 'Monospaced';" +
                        "-fx-font-size: 14px;" +
                        "-fx-font-weight: bold;" +
                        "-fx-background-color: " + colors.getBridge() + ";" +
                        "-fx-text-fill: " + colors.getBackground() + ";" +
                        "-fx-background-radius: 4;" +
                        "-fx-padding: 6 16;"
        );
        btn.setMinWidth(90);
    }
}