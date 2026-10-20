package com.app.audio;

import com.app.Config;
import com.app.core.BlockType;
import com.app.core.World;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.media.AudioClip;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class AudioManager {
    private static AudioManager instance;

    private static final String[] SFX_FILES = {
            "button", "placement", "remove",
            "arrow", "getter", "bridge", "peg", "power",
            "not", "or", "and", "xor", "text"
    };

    private final Map<String, AudioClip> clips = new HashMap<>();
    private boolean loaded = false;

    private volatile boolean masterMuted = false;
    private volatile boolean uiClickMuted = false;
    private volatile boolean placementMuted = false;
    private volatile boolean removeMuted = false;
    private volatile boolean blockActivationMuted = false;

    private AudioManager() {
        reload();
    }

    public static synchronized AudioManager getInstance() {
        if (instance == null) {
            instance = new AudioManager();
        }
        return instance;
    }

    public synchronized void reload() {
        clips.clear();

        Path sfxDir = Config.getDataDir().resolve("sfx");
        if (!Files.exists(sfxDir)) {
            try {
                Files.createDirectories(sfxDir);
            } catch (Exception e) {
                System.err.println("Failed to create sfx dir: " + e.getMessage());
            }
            System.out.println("SFX directory created (empty): " + sfxDir.toAbsolutePath());
            loaded = true;
            return;
        }

        for (String name : SFX_FILES) {
            Path file = sfxDir.resolve(name + ".wav");
            if (!Files.exists(file)) continue;
            try {
                AudioClip clip = new AudioClip(file.toUri().toString());
                clips.put(name, clip);
            } catch (Exception e) {
                System.err.println("Failed to load sfx " + file + ": " + e.getMessage());
            }
        }

        loaded = true;
        refreshVolumes();
        System.out.println("AudioManager loaded " + clips.size() + " sfx clips");
    }

    public synchronized void refreshVolumes() {
        Config cfg = Config.getInstance();
        double master = clamp01(cfg.getMasterVolume() / 100.0);
        double uiClick = clamp01(cfg.getUiClickVolume() / 100.0);
        double placement = clamp01(cfg.getPlacementVolume() / 100.0);
        double remove = clamp01(cfg.getRemoveVolume() / 100.0);
        double blockActivation = clamp01(cfg.getBlockActivationVolume() / 100.0);

        masterMuted = (master <= 0.0);
        uiClickMuted = masterMuted || (uiClick <= 0.0);
        placementMuted = masterMuted || (placement <= 0.0);
        removeMuted = masterMuted || (remove <= 0.0);
        blockActivationMuted = masterMuted || (blockActivation <= 0.0);

        for (Map.Entry<String, AudioClip> e : clips.entrySet()) {
            String name = e.getKey();
            double category;
            switch (name) {
                case "button":
                    category = uiClick;
                    break;
                case "placement":
                    category = placement;
                    break;
                case "remove":
                    category = remove;
                    break;
                default:
                    category = blockActivation;
                    break;
            }
            e.getValue().setVolume(master * category);
        }
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    public void playButton() {
        if (uiClickMuted) return;
        play("button");
    }

    public void playPlacement() {
        if (placementMuted) return;
        play("placement");
    }

    public void playRemove() {
        if (removeMuted) return;
        play("remove");
    }

    public void playBlockSelected(BlockType type) {
        if (blockActivationMuted) return;
        if (type == null) return;
        play(type.getName());
    }

    public void playSignal(int worldTypeId) {
        if (blockActivationMuted) return;
        String name = fileNameForWorldType(worldTypeId);
        if (name == null) return;
        play(name);
    }

    private static String fileNameForWorldType(int type) {
        switch (type) {
            case World.TYPE_ARROW:  return "arrow";
            case World.TYPE_GETTER: return "getter";
            case World.TYPE_BRIDGE: return "bridge";
            case World.TYPE_PEG:    return "peg";
            case World.TYPE_POWER:  return "power";
            case World.TYPE_NOT:    return "not";
            case World.TYPE_OR:     return "or";
            case World.TYPE_AND:    return "and";
            case World.TYPE_XOR:    return "xor";
            case World.TYPE_TEXT:   return "text";
            default: return null;
        }
    }

    private void play(String name) {
        if (!loaded) return;
        AudioClip clip = clips.get(name);
        if (clip == null) return;
        try {
            clip.play();
        } catch (Exception e) {
            System.err.println("Failed to play sfx " + name + ": " + e.getMessage());
        }
    }

    public static void installButtonSound(Scene scene) {
        if (scene == null) return;
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            javafx.scene.Node target = (event.getTarget() instanceof javafx.scene.Node)
                    ? (javafx.scene.Node) event.getTarget() : null;
            while (target != null) {
                if (target instanceof ButtonBase) {
                    if (!target.isDisabled()) {
                        AudioManager.getInstance().playButton();
                    }
                    break;
                }
                target = target.getParent();
            }
        });
    }
}