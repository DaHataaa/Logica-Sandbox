package com.app.core;

import com.app.Config;

import java.io.*;
import java.nio.file.*;
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
                        String block = world.getBlock(l, x, y);
                        if (block != null && !block.equals("0")) {
                            String[] parts = block.split("_");
                            if (parts.length > 1 && parts[1].length() == 2) {
                                char state = parts[1].charAt(0);
                                world.setSignal(l, x, y, state == 't');
                            }
                        }
                    }
                }
            }

            System.out.println("Map loaded successfully: " + file);
            System.out.println("Total blocks loaded: " + blocksLoaded);

            String testBlock = world.getBlock(0, 115, 120);
            System.out.println("Test block at (0,115,120): " + testBlock);

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