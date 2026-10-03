package com.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Config {
    private static Config instance;

    // ===== Базовая директория и data =====
    private static Path baseDir;
    private static Path dataDir;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    // Настройки окна
    @SerializedName("window_width")
    private int windowWidth = 1600;

    @SerializedName("window_height")
    private int windowHeight = 900;

    @SerializedName("fullscreen")
    private boolean fullscreen = false;

    // Настройки симуляции
    @SerializedName("simulation_speed")
    private int simulationSpeed = 60;

    @SerializedName("available_speeds")
    private int[] availableSpeeds = {0, 1, 2, 4, 8, 15, 30, 60, 120, 240, 480};

    // Настройки редактора
    @SerializedName("undo_depth")
    private int undoDepth = 50;

    @SerializedName("cell_size_min")
    private int cellSizeMin = 4;

    @SerializedName("cell_size_max")
    private int cellSizeMax = 128;

    @SerializedName("default_cell_size")
    private int defaultCellSize = 32;

    // Настройки мира
    @SerializedName("world_size")
    private int worldSize = 256;

    @SerializedName("layers")
    private int layers = 5;

    // Текущий текстурпак
    @SerializedName("current_texturepack")
    private String currentTexturepack = "Classic";

    private Config() {}

    public static synchronized Config getInstance() {
        if (instance == null) {
            instance = new Config();
            instance.load();
        }
        return instance;
    }

    // ===== Определение базовой папки =====
    /**
     * Возвращает папку, относительно которой лежит data/.
     * - Запуск из IDE: корень проекта (там где pom.xml)
     * - Запуск из jar: папка, где лежит jar
     */
    public static Path getBaseDir() {
        if (baseDir == null) {
            baseDir = resolveBaseDir();
        }
        return baseDir;
    }

    /**
     * Возвращает путь к папке data (baseDir/data).
     */
    public static Path getDataDir() {
        if (dataDir == null) {
            dataDir = getBaseDir().resolve("data");
        }
        return dataDir;
    }

    private static Path resolveBaseDir() {
        try {
            Path loc = Paths.get(Config.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());

            if (Files.isRegularFile(loc)) {
                // Запуск из jar → папка рядом с jar
                return loc.getParent();
            } else {
                // Запуск из IDE → target/classes, поднимаемся до корня проекта
                Path p = loc;
                while (p != null) {
                    if (Files.exists(p.resolve("pom.xml"))
                            || Files.exists(p.resolve("build.gradle"))
                            || Files.exists(p.resolve("data"))) {
                        return p;
                    }
                    p = p.getParent();
                }
                // Fallback — рабочая директория
                return Paths.get(".").toAbsolutePath().normalize();
            }
        } catch (Exception e) {
            System.err.println("Failed to resolve base dir: " + e.getMessage());
            return Paths.get(".").toAbsolutePath().normalize();
        }
    }

    // ===== Загрузка / сохранение =====
    public void load() {
        File file = getDataDir().resolve("config.json").toFile();
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                Config loaded = GSON.fromJson(reader, Config.class);
                if (loaded != null) {
                    this.windowWidth = loaded.windowWidth;
                    this.windowHeight = loaded.windowHeight;
                    this.fullscreen = loaded.fullscreen;
                    this.simulationSpeed = loaded.simulationSpeed;
                    this.availableSpeeds = loaded.availableSpeeds;
                    this.undoDepth = loaded.undoDepth;
                    this.cellSizeMin = loaded.cellSizeMin;
                    this.cellSizeMax = loaded.cellSizeMax;
                    this.defaultCellSize = loaded.defaultCellSize;
                    this.worldSize = loaded.worldSize;
                    this.layers = loaded.layers;
                    this.currentTexturepack = loaded.currentTexturepack;
                }
            } catch (IOException e) {
                System.err.println("Failed to load config, using defaults: " + e.getMessage());
                save();
            }
        } else {
            save();
        }
    }

    public void save() {
        try {
            File file = getDataDir().resolve("config.json").toFile();
            file.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }

    // Getters
    public int getWindowWidth() { return windowWidth; }
    public int getWindowHeight() { return windowHeight; }
    public boolean isFullscreen() { return fullscreen; }
    public int getSimulationSpeed() { return simulationSpeed; }
    public int[] getAvailableSpeeds() { return availableSpeeds; }
    public int getUndoDepth() { return undoDepth; }
    public int getCellSizeMin() { return cellSizeMin; }
    public int getCellSizeMax() { return cellSizeMax; }
    public int getDefaultCellSize() { return defaultCellSize; }
    public int getWorldSize() { return worldSize; }
    public int getLayers() { return layers; }
    public String getCurrentTexturepack() { return currentTexturepack; }

    // Setters
    public void setCurrentTexturepack(String currentTexturepack) { this.currentTexturepack = currentTexturepack; }
    public void setSimulationSpeed(int simulationSpeed) { this.simulationSpeed = simulationSpeed; }
    public void setWindowWidth(int windowWidth) { this.windowWidth = windowWidth; }
    public void setWindowHeight(int windowHeight) { this.windowHeight = windowHeight; }
    public void setFullscreen(boolean fullscreen) { this.fullscreen = fullscreen; }
}