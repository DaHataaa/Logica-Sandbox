package com.app.core;

import com.app.Config;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class ScriptManager {
    private static final String EXT = ".logicscript";
    private static final Path DIR = Config.getDataDir().resolve("scripts");

    static {
        try { Files.createDirectories(DIR); }
        catch (IOException e) { System.err.println("scripts dir: " + e.getMessage()); }
    }

    public static List<Script> loadAll() {
        List<Script> out = new ArrayList<>();
        if (!Files.exists(DIR)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(DIR, "*" + EXT)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                n = n.substring(0, n.length() - EXT.length());
                out.add(new Script(n, new String(Files.readAllBytes(p))));
            }
        } catch (IOException e) { System.err.println("loadAll: " + e.getMessage()); }
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    public static boolean save(Script s) {
        try {
            Files.write(DIR.resolve(sanitize(s.getName()) + EXT), s.getCode().getBytes());
            return true;
        } catch (IOException e) { System.err.println("save: " + e.getMessage()); return false; }
    }

    // В ScriptManager.java
    public static boolean delete(String name) {
        Path path = DIR.resolve(sanitize(name) + EXT);
        try {
            boolean deleted = Files.deleteIfExists(path);
            if (!deleted) {
                System.err.println("Script file not found or could not be deleted: " + path.toAbsolutePath());
            } else {
                System.out.println("Script deleted: " + path.getFileName());
            }
            return deleted;
        } catch (IOException e) {
            System.err.println("Error deleting script: " + e.getMessage());
            return false;
        }
    }

    public static boolean rename(String oldName, String newName) {
        try {
            Files.move(DIR.resolve(sanitize(oldName) + EXT),
                    DIR.resolve(sanitize(newName) + EXT),
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) { return false; }
    }

    public static boolean exists(String name) {
        return Files.exists(DIR.resolve(sanitize(name) + EXT));
    }

    public static Script createNew(String base) {
        String n = base; int i = 1;
        while (exists(n)) n = base + "_" + i++;
        Script s = new Script(n, "// " + n + "\n");
        save(s);
        return s;
    }

    private static String sanitize(String n) {
        return n.replaceAll("[<>:\"/\\\\|?*]", "_");
    }
}