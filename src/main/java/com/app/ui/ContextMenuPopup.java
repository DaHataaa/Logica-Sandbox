package com.app.ui;

import com.app.graphics.ColorConfig;
import com.app.graphics.SpriteManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.util.List;

public class ContextMenuPopup {

    public static class Item {
        public final String text;
        public final Runnable action;
        public final boolean enabled;

        public Item(String text, Runnable action) {
            this(text, action, true);
        }

        public Item(String text, Runnable action, boolean enabled) {
            this.text = text;
            this.action = action;
            this.enabled = enabled;
        }
    }

    /** Колбэк: попап открыт — блокируем hover на фоне; закрыт — снимаем. */
    public interface HoverBlocker {
        void setBlocked(boolean blocked);
    }

    public static void show(Window owner, double screenX, double screenY,
                            List<Item> items, HoverBlocker blocker) {
        if (owner == null || items == null || items.isEmpty()) return;

        ColorConfig colors = SpriteManager.getInstance().getColors();

        VBox box = new VBox(0);
        box.setPadding(new Insets(6));
        box.setStyle(
                "-fx-background-color: " + colors.getBackground() + ";" +
                        "-fx-border-color: " + colors.getGrid() + ";" +
                        "-fx-border-width: 2;" +
                        "-fx-border-radius: 8;" +
                        "-fx-background-radius: 8;"
        );

        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);

        if (blocker != null) blocker.setBlocked(true);

        for (Item item : items) {
            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(8, 16, 8, 16));
            row.setMinWidth(200);
            row.setStyle(rowStyle(colors, item.enabled ? RowState.NORMAL : RowState.DISABLED));

            Label lbl = new Label(item.text);
            lbl.setStyle(labelStyle(colors, item.enabled ? RowState.NORMAL : RowState.DISABLED));
            row.getChildren().add(lbl);

            if (item.enabled) {
                row.setOnMouseEntered(e -> {
                    row.setStyle(rowStyle(colors, RowState.HOVER));
                    lbl.setStyle(labelStyle(colors, RowState.HOVER));
                });
                row.setOnMouseExited(e -> {
                    row.setStyle(rowStyle(colors, RowState.NORMAL));
                    lbl.setStyle(labelStyle(colors, RowState.NORMAL));
                });
                row.setOnMousePressed(e -> {
                    row.setStyle(rowStyle(colors, RowState.PRESSED));
                    lbl.setStyle(labelStyle(colors, RowState.PRESSED));
                });
                row.setOnMouseReleased(e -> {
                    popup.hide();
                    if (item.action != null) item.action.run();
                });
            } else {
                row.setDisable(true);
                row.setOpacity(0.55);
            }

            box.getChildren().add(row);
        }

        popup.getContent().add(box);

        Scene scene = owner.getScene();
        if (scene != null) {
            javafx.event.EventHandler<MouseEvent> outside = ev -> {
                if (!popup.isShowing()) return;
                Node pick = ev.getPickResult() != null ? ev.getPickResult().getIntersectedNode() : null;
                boolean inside = false;
                Node n = pick;
                while (n != null) {
                    if (n == box) { inside = true; break; }
                    n = n.getParent();
                }
                if (!inside) popup.hide();
            };
            scene.addEventFilter(MouseEvent.MOUSE_PRESSED, outside);
            popup.setOnHidden(ev -> {
                scene.removeEventFilter(MouseEvent.MOUSE_PRESSED, outside);
                if (blocker != null) blocker.setBlocked(false);
            });
        } else {
            popup.setOnHidden(ev -> { if (blocker != null) blocker.setBlocked(false); });
        }

        popup.show(owner, screenX, screenY);
    }

    /** Упрощённый вариант без блокировки фона. */
    public static void show(Window owner, double screenX, double screenY, List<Item> items) {
        show(owner, screenX, screenY, items, null);
    }

    private enum RowState { NORMAL, HOVER, PRESSED, DISABLED }

    private static String rowStyle(ColorConfig colors, RowState st) {
        switch (st) {
            case HOVER:   return "-fx-background-color: " + colors.getSignalOn() + "; -fx-background-radius: 5;";
            case PRESSED: return "-fx-background-color: " + colors.getPower() + "; -fx-background-radius: 5;";
            default:      return "-fx-background-color: transparent; -fx-background-radius: 5;";
        }
    }

    private static String labelStyle(ColorConfig colors, RowState st) {
        String fg = (st == RowState.HOVER || st == RowState.PRESSED)
                ? colors.getBackground() : colors.getGrid();
        return "-fx-font-family: 'Monospaced';" +
                "-fx-font-size: 14px;" +
                "-fx-font-weight: bold;" +
                "-fx-text-fill: " + fg + ";";
    }
}