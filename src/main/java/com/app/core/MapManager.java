package com.app.core;

import com.app.Config;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class MapManager {
    private static final Path MAPS_DIR = Config.getDataDir().resolve("maps");

    static {
        try {
            Files.createDirectories(MAPS_DIR);
            System.out.println("Maps directory: " + MAPS_DIR.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to create maps directory: " + e.getMessage());
        }
    }

    // ============================================================
    // СТАТИСТИКА КАРТЫ (для карточек в меню)
    // ============================================================

    // ============================================================
// СТАТИСТИКА КАРТЫ (для карточек в меню)
// ============================================================

    public static class MapStats {
        public final String name;
        public final long lastModifiedMillis;
        public final String lastModifiedPretty;

        public final int logicGates;        // not + or + and + xor
        public final int approxTransistors; // точная сумма по типам вентилей
        public final int arrows;            // arrow + bridge + getter
        public final int pegs;
        public final int powers;
        public final int texts;
        public final int totalBlocks;

        public MapStats(String name,
                        long lastModifiedMillis,
                        String lastModifiedPretty,
                        int logicGates,
                        int approxTransistors,
                        int arrows,
                        int pegs,
                        int powers,
                        int texts,
                        int totalBlocks) {
            this.name = name;
            this.lastModifiedMillis = lastModifiedMillis;
            this.lastModifiedPretty = lastModifiedPretty;
            this.logicGates = logicGates;
            this.approxTransistors = approxTransistors;
            this.arrows = arrows;
            this.pegs = pegs;
            this.powers = powers;
            this.texts = texts;
            this.totalBlocks = totalBlocks;
        }
    }

    // Точные коэффициенты транзисторов на один вентиль (static CMOS, NAND-эквивалент):
//   NOT  = инвертор            = 1 pMOS + 1 nMOS                  = 2
//   AND  = NAND2 + NOT         = (2 pMOS + 2 nMOS) + 2            = 6
//   OR   = NOR2 + NOT          = (2 pMOS + 2 nMOS) + 2            = 6
//   XOR  = оптимизированный    = 12 (static CMOS XOR2)
    public static final int TR_NOT = 2;
    public static final int TR_AND = 6;
    public static final int TR_OR  = 6;
    public static final int TR_XOR = 12;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());

    /**
     * Возвращает статистику карты по её файлу (без загрузки в World).
     * Если файл не читается — возвращает null.
     */
    public static MapStats getMapStats(String mapName) {
        Path file = MAPS_DIR.resolve(sanitizeFilename(mapName) + ".logicmap");
        if (!Files.exists(file)) return null;

        long lastModifiedMillis = 0L;
        try {
            FileTime ft = Files.getLastModifiedTime(file);
            lastModifiedMillis = ft.toMillis();
        } catch (IOException ignored) {}

        String pretty = lastModifiedMillis > 0
                ? DATE_FMT.format(Instant.ofEpochMilli(lastModifiedMillis))
                : "—";

        int notCount = 0;
        int andCount = 0;
        int orCount  = 0;
        int xorCount = 0;

        int arrows = 0;
        int pegs = 0;
        int powers = 0;   // оставлено для совместимости с MapStats; в карточке не показываем
        int texts = 0;
        int total = 0;

        boolean readingData = false;

        try (BufferedReader reader = new BufferedReader(new FileReader(file.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("#LOGICMAP")) continue;
                if (line.startsWith("size=")) continue;
                if (line.startsWith("layers=")) continue;
                if (line.startsWith("#layer")) { readingData = true; continue; }
                if (line.equals("---")) { readingData = !readingData; continue; }

                if (!readingData) continue;

                String[] parts = line.split(" ");
                for (String block : parts) {
                    if (block == null || block.isEmpty() || block.equals("0")) continue;
                    total++;

                    // Текстовый блок: "text_<sf>f|TRUE|FALSE"
                    if (block.startsWith("text_")) {
                        texts++;
                        continue;
                    }
                    // Без суффиксов: power
                    if (block.equals("power")) {
                        powers++;
                        continue;
                    }

                    // С суффиксами: "<name>_<s><d>"
                    int us = block.indexOf('_');
                    if (us <= 0) continue;
                    String name = block.substring(0, us);

                    switch (name) {
                        case "not" -> notCount++;
                        case "and" -> andCount++;
                        case "or"  -> orCount++;
                        case "xor" -> xorCount++;
                        case "arrow", "bridge", "getter" -> arrows++;
                        case "peg" -> pegs++;
                        default -> { /* неизвестное — игнорируем */ }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read map stats: " + e.getMessage());
            return null;
        }

        int logicGates = notCount + andCount + orCount + xorCount;

        // Точный подсчёт: каждый тип вентиля по своему коэффициенту
        int approxTransistors =
                notCount * TR_NOT
                        + andCount * TR_AND
                        + orCount  * TR_OR
                        + xorCount * TR_XOR;

        return new MapStats(
                mapName,
                lastModifiedMillis,
                pretty,
                logicGates,
                approxTransistors,
                arrows,
                pegs,
                powers,
                texts,
                total
        );
    }

    public static boolean saveMap(String name, World world) {
        Path file = MAPS_DIR.resolve(sanitizeFilename(name) + ".logicmap");

        try (PrintWriter writer = new PrintWriter(new FileWriter(file.toFile()))) {
            int size = world.getSize();
            int layers = world.getLayers();

            writer.println("#LOGICMAP v1.0");
            writer.println("size=" + size);
            writer.println("layers=" + layers);
            writer.println("---");

            for (int layer = 0; layer < layers; layer++) {
                writer.println("#layer " + layer);
                for (int y = 0; y < size; y++) {
                    StringBuilder line = new StringBuilder();
                    for (int x = 0; x < size; x++) {
                        int t = world.blockTypes[layer][y][x];
                        if (t == World.TYPE_EMPTY) {
                            line.append("0");
                        } else if (t == World.TYPE_POWER) {
                            line.append("power");
                        } else if (t == World.TYPE_TEXT) {
                            char s = world.blockStates[layer][y][x] ? 't' : 'f';
                            String texts = world.blockTexts[layer][y][x];
                            if (texts == null || texts.isEmpty()) {
                                texts = World.DEFAULT_TEXT_TRUE + "|" + World.DEFAULT_TEXT_FALSE;
                            }
                            texts = texts.replace(' ', '_');
                            line.append("text_").append(s).append("f|").append(texts);
                        } else {
                            char s = world.blockStates[layer][y][x] ? 't' : 'f';
                            char d = "urdl".charAt(world.blockDirs[layer][y][x] & 3);
                            line.append(World.TYPE_NAMES[t]).append('_').append(s).append(d);
                        }
                        if (x < size - 1) line.append(" ");
                    }
                    writer.println(line);
                }
                writer.println("---");
            }

            System.out.println("Map saved: " + file + " (" + (size * size * layers) + " cells)");
            return true;

        } catch (IOException e) {
            System.err.println("Failed to save map: " + e.getMessage());
            return false;
        }
    }

    public static boolean loadMap(String name, World world) {
        Path file = MAPS_DIR.resolve(sanitizeFilename(name) + ".logicmap");

        if (!Files.exists(file)) {
            System.err.println("Map not found: " + file);
            return false;
        }

        System.out.println("Loading map from: " + file);

        try (BufferedReader reader = new BufferedReader(new FileReader(file.toFile()))) {
            String line;
            int size = -1;
            int layers = -1;
            int currentLayer = -1;
            int currentY = 0;
            boolean readingData = false;
            int blocksLoaded = 0;

            world.clear();

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("#LOGICMAP")) continue;
                if (line.startsWith("size=")) {
                    size = Integer.parseInt(line.substring(5));
                    System.out.println("Map size: " + size);
                    continue;
                }
                if (line.startsWith("layers=")) {
                    layers = Integer.parseInt(line.substring(7));
                    System.out.println("Map layers: " + layers);
                    continue;
                }
                if (line.equals("---")) {
                    if (readingData) {
                        readingData = false;
                        currentLayer++;
                        currentY = 0;
                        System.out.println("Finished loading layer " + currentLayer);
                    } else {
                        readingData = true;
                        currentLayer = 0;
                        currentY = 0;
                    }
                    continue;
                }
                if (line.startsWith("#layer")) {
                    readingData = true;
                    String[] parts = line.split(" ");
                    if (parts.length > 1) {
                        currentLayer = Integer.parseInt(parts[1]);
                    }
                    currentY = 0;
                    System.out.println("Loading layer " + currentLayer);
                    continue;
                }

                if (readingData && size > 0 && currentLayer >= 0 && currentLayer < layers) {
                    String[] parts = line.split(" ");
                    for (int x = 0; x < parts.length && x < size; x++) {
                        String block = parts[x];
                        if (!block.equals("0")) {
                            world.setBlock(currentLayer, x, currentY, block);
                            blocksLoaded++;
                            if (blocksLoaded < 20) {
                                System.out.println("Loaded: layer " + currentLayer + " (" + x + "," + currentY + ") = " + block);
                            }
                        }
                    }
                    currentY++;
                }
            }

            for (int l = 0; l < layers; l++) {
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int t = world.blockTypes[l][y][x];
                        if (t == World.TYPE_EMPTY) continue;
                        boolean state = world.blockStates[l][y][x];
                        world.setSignal(l, x, y, state);
                    }
                }
            }

            System.out.println("Map loaded successfully: " + file);
            System.out.println("Total blocks loaded: " + blocksLoaded);

            return true;

        } catch (IOException e) {
            System.err.println("Failed to load map: " + e.getMessage());
            return false;
        }
    }

    public static List<String> getMapList() {
        List<String> maps = new ArrayList<>();

        try {
            if (!Files.exists(MAPS_DIR)) {
                Files.createDirectories(MAPS_DIR);
            }
            Files.list(MAPS_DIR)
                    .filter(p -> p.toString().endsWith(".logicmap"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        name = name.substring(0, name.length() - 9);
                        maps.add(name);
                    });
        } catch (IOException e) {
            System.err.println("Failed to list maps: " + e.getMessage());
        }

        return maps;
    }

    public static boolean deleteMap(String name) {
        Path file = MAPS_DIR.resolve(sanitizeFilename(name) + ".logicmap");
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("Failed to delete map: " + e.getMessage());
            return false;
        }
    }

    public static boolean renameMap(String oldName, String newName) {
        Path oldFile = MAPS_DIR.resolve(sanitizeFilename(oldName) + ".logicmap");
        Path newFile = MAPS_DIR.resolve(sanitizeFilename(newName) + ".logicmap");

        try {
            Files.move(oldFile, newFile, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Map renamed: " + oldName + " -> " + newName);
            return true;
        } catch (IOException e) {
            System.err.println("Failed to rename map: " + e.getMessage());
            return false;
        }
    }

    public static boolean mapExists(String name) {
        Path file = MAPS_DIR.resolve(sanitizeFilename(name) + ".logicmap");
        return Files.exists(file);
    }

    private static String sanitizeFilename(String name) {
        return name.replaceAll("[<>:\"/\\\\|?*]", "_");
    }
}