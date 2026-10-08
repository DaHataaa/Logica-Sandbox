package com.app.states;

import com.app.graphics.SpriteManager;
import com.app.graphics.ColorConfig;
import com.app.graphics.SpriteLoader;
import com.app.graphics.Sprite;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelWriter;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;

public class TexturepackState implements State {
    private BorderPane root;
    private State nextState;
    private VBox centerPanel;
    private VBox texpacksContainer;
    private ScrollPane scrollPane;

    private SpriteManager spriteManager;
    private String currentTexpack;
    private List<String> texpacks;
    private ColorConfig currentColors;
    private String currentBackground;

    private List<VBox> cards = new ArrayList<>();
    private List<Button> selectButtons = new ArrayList<>();
    private List<String> cardNames = new ArrayList<>();

    public TexturepackState() {
        spriteManager = SpriteManager.getInstance();
        currentTexpack = spriteManager.getCurrentTexturepack();
        texpacks = spriteManager.getAvailableTexturepacks();
        currentColors = spriteManager.getColors();
        currentBackground = currentColors.getBackground();

        root = new BorderPane();
        root.setStyle("-fx-background-color: " + currentBackground + ";");

        centerPanel = new VBox(20);
        centerPanel.setAlignment(Pos.TOP_CENTER);
        centerPanel.setStyle("-fx-padding: 30;");
        centerPanel.setMaxWidth(750);

        Text title = new Text("TEXTURE PACKS");
        title.setStyle("-fx-font-size: 32px; -fx-font-weight: bold;");
        title.setFill(Color.web(currentColors.getGrid()));

        Button backBtn = new Button("← BACK");
        backBtn.getStyleClass().add("button");
        backBtn.setOnAction(e -> backToMenu());

        texpacksContainer = new VBox(15);
        texpacksContainer.setAlignment(Pos.TOP_CENTER);

        scrollPane = new ScrollPane(texpacksContainer);
        scrollPane.getStyleClass().add("map-scroll-pane");
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefWidth(650);
        scrollPane.setPrefHeight(850);

        centerPanel.getChildren().addAll(title, scrollPane, backBtn);
        root.setCenter(centerPanel);

        createTexpacksList();
    }

    private void createTexpacksList() {
        texpacks = spriteManager.getAvailableTexturepacks();
        texpacksContainer.getChildren().clear();
        cards.clear();
        selectButtons.clear();
        cardNames.clear();

        if (texpacks.isEmpty()) {
            Text emptyText = new Text("No texture packs found");
            emptyText.getStyleClass().add("empty-text");
            emptyText.setFill(Color.web(currentColors.getGrid()));
            texpacksContainer.getChildren().add(emptyText);
            return;
        }

        List<TexpackInfo> texpackInfos = new ArrayList<>();
        for (String name : texpacks) {
            ColorConfig texpackColors = ColorConfig.load(name);
            String author = texpackColors.getAuthor();
            if (author == null || author.isEmpty() || author.equals("Unknown")) {
                author = "Unknown";
            }
            texpackInfos.add(new TexpackInfo(name, author, texpackColors));
        }

        texpackInfos.sort(Comparator
                .comparing(TexpackInfo::getAuthor, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TexpackInfo::getName, String.CASE_INSENSITIVE_ORDER));

        for (TexpackInfo info : texpackInfos) {
            VBox texpackCard = createTexpackCard(info);
            texpacksContainer.getChildren().add(texpackCard);
        }
    }

    private void updateActiveButton(String newActiveTexpack) {
        for (int i = 0; i < selectButtons.size(); i++) {
            Button btn = selectButtons.get(i);
            String cardName = cardNames.get(i);
            ColorConfig texpackColors = ColorConfig.load(cardName);

            if (cardName.equals(newActiveTexpack)) {
                btn.setText("✓ ACTIVE");
                btn.setStyle("-fx-background-color: " + texpackColors.getPower() + "; -fx-text-fill: white; -fx-font-weight: bold;");
                btn.getStyleClass().add("button");
            } else {
                btn.setText("SELECT");
                btn.setStyle("");
                btn.getStyleClass().add("button");
            }
        }
    }

    private class TexpackInfo {
        private String name;
        private String author;
        private ColorConfig colors;

        TexpackInfo(String name, String author, ColorConfig colors) {
            this.name = name;
            this.author = author;
            this.colors = colors;
        }

        public String getName() { return name; }
        public String getAuthor() { return author; }
        public ColorConfig getColors() { return colors; }
    }

    private VBox createTexpackCard(TexpackInfo info) {
        String texpackName = info.getName();
        ColorConfig texpackColors = info.getColors();
        String cardBgColor = texpackColors.getBackground();
        String cardSignalOn = texpackColors.getSignalOn();
        String cardPower = texpackColors.getPower();
        String cardGrid = texpackColors.getGrid();

        VBox card = new VBox(10);
        card.setAlignment(Pos.CENTER);
        card.setStyle(
                "-fx-background-color: " + cardBgColor + ";" +
                        "-fx-background-radius: 15;" +
                        "-fx-border-color: " + cardGrid + ";" +
                        "-fx-border-width: 2;" +
                        "-fx-border-radius: 15;" +
                        "-fx-padding: 10;" +
                        "-fx-min-width: 580;" +
                        "-fx-max-width: 580;"
        );

        Text nameLabel = new Text(texpackName.toUpperCase());
        nameLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        nameLabel.setFill(Color.web(cardSignalOn));

        String author = info.getAuthor();
        if (author == null || author.isEmpty() || author.equals("Unknown")) {
            author = "Unknown author";
        }
        Text authorLabel = new Text("by " + author);
        authorLabel.setStyle("-fx-font-size: 14px; -fx-font-style: italic;");
        authorLabel.setFill(Color.web(cardSignalOn));

        VBox titleBox = new VBox(4);
        titleBox.setAlignment(Pos.CENTER);
        titleBox.getChildren().addAll(nameLabel, authorLabel);

        HBox previewRow = createPreviewRow(texpackName, texpackColors);

        Button selectBtn = new Button(currentTexpack.equals(texpackName) ? "✓ ACTIVE" : "SELECT");
        selectBtn.getStyleClass().add("button");
        if (currentTexpack.equals(texpackName)) {
            selectBtn.setStyle("-fx-background-color: " + cardPower + "; -fx-text-fill: white; -fx-font-weight: bold;");
        }

        final String finalTexpackName = texpackName;
        selectBtn.setOnAction(e -> selectTexpack(finalTexpackName));

        card.getChildren().addAll(titleBox, previewRow, selectBtn);

        cards.add(card);
        selectButtons.add(selectBtn);
        cardNames.add(texpackName);

        return card;
    }

    private HBox createPreviewRow(String texpackName, ColorConfig texpackColors) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER);
        row.setStyle("-fx-padding: 1;");

        // ← добавлен "text"
        String[] blocks = {"arrow", "getter", "bridge", "peg", "power", "not", "or", "and", "xor", "text"};

        Map<String, Sprite> sprites = SpriteLoader.loadAllSprites(texpackName);

        for (String block : blocks) {
            Canvas canvas = createBlockPreview(block, 48, texpackColors, sprites);
            row.getChildren().add(canvas);
        }

        return row;
    }

    private Canvas createBlockPreview(String blockName, int size, ColorConfig texpackColors, Map<String, Sprite> sprites) {
        Canvas canvas = new Canvas(size, size + 20);
        GraphicsContext gc = canvas.getGraphicsContext2D();

        Sprite sprite = sprites.get(blockName);

        // Заливаем фон цветом background
        gc.setFill(Color.web(texpackColors.getBackground()));
        gc.fillRect(0, 0, size, size + 20);

        if (sprite != null) {
            int targetColor = parseColor(texpackColors.getColorForBlock(blockName));
            int[] pixels = sprite.getPixels();
            int spriteSize = sprite.getWidth();
            PixelWriter pw = gc.getPixelWriter();

            for (int y = 0; y < size; y++) {
                int srcY = y * spriteSize / size;
                if (srcY >= spriteSize) srcY = spriteSize - 1;
                for (int x = 0; x < size; x++) {
                    int srcX = x * spriteSize / size;
                    if (srcX >= spriteSize) srcX = spriteSize - 1;
                    int color = pixels[srcY * spriteSize + srcX];

                    int r = (color >> 16) & 0xFF;
                    int g = (color >> 8) & 0xFF;
                    int b = color & 0xFF;

                    boolean isWhite = (r > 250 && g > 250 && b > 250);
                    boolean isBlack = (r < 30 && g < 30 && b < 30);

                    if (isWhite) {
                        // Белый фон — оставляем background (уже залит)
                    } else if (isBlack) {
                        // Чёрные пиксели — заменяем на цвет блока
                        pw.setArgb(x, y, targetColor);
                    } else {
                        // Цветные пиксели — рисуем как есть
                        pw.setArgb(x, y, color);
                    }
                }
            }
        } else {
            String colorHex = texpackColors.getColorForBlock(blockName);
            gc.setFill(Color.web(colorHex));
            gc.fillRect(0, 0, size, size);
            gc.setStroke(Color.web(texpackColors.getGrid()));
            gc.setLineWidth(2);
            gc.strokeRect(2, 2, size - 4, size - 4);
            gc.setFill(Color.BLACK);
            gc.setFont(javafx.scene.text.Font.font(10));
            String text = blockName.substring(0, Math.min(3, blockName.length())).toUpperCase();
            gc.fillText(text, size/2 - 8, size/2 + 5);
        }

        gc.setStroke(Color.web(texpackColors.getGrid()));
        gc.setLineWidth(1.5);
        gc.strokeRect(1, 1, size - 2, size - 2);

        String label = getBlockShortName(blockName);
        gc.setFill(Color.web(texpackColors.getGrid()));
        gc.setFont(javafx.scene.text.Font.font(11));
        gc.fillText(label, size/2 - 12, size + 15);

        return canvas;
    }

    private int parseColor(String hex) {
        if (hex == null) return 0xFFFFFFFF;
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() == 6) {
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            return (255 << 24) | (r << 16) | (g << 8) | b;
        }
        return 0xFFFFFFFF;
    }

    private String getBlockShortName(String blockName) {
        switch (blockName) {
            case "arrow": return "ARR";
            case "getter": return "GET";
            case "bridge": return "BRG";
            case "peg": return "PEG";
            case "power": return "PWR";
            case "not": return "NOT";
            case "or": return "OR";
            case "and": return "AND";
            case "xor": return "XOR";
            case "text": return "TXT";   // ← добавлено
            default: return blockName.substring(0, 3).toUpperCase();
        }
    }

    private void selectTexpack(String texpackName) {
        if (currentTexpack.equals(texpackName)) return;

        spriteManager.changeTexturepack(texpackName);
        currentTexpack = texpackName;
        currentColors = spriteManager.getColors();
        currentBackground = currentColors.getBackground();

        root.setStyle("-fx-background-color: " + currentBackground + ";");

        updateActiveButton(texpackName);

        com.app.StyleManager.generateCSS();

        System.out.println("Switched to texpack: " + texpackName);
    }

    private void backToMenu() {
        nextState = new MenuState();
    }

    @Override
    public void enter() {
        System.out.println("Entering TexturepackState");
        nextState = this;
    }

    @Override
    public void exit() {
        System.out.println("Exiting TexturepackState");
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