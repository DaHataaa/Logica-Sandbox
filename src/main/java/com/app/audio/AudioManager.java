package com.app.audio;

import com.app.Config;
import com.app.core.BlockType;
import com.app.core.World;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.media.AudioClip;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AudioManager {
    private static AudioManager instance;

    private static final String[] SFX_FILES = {
            "button", "placement", "remove",
            "arrow", "getter", "bridge", "peg", "power",
            "not", "or", "and", "xor", "text",
            "copy", "insert", "deselect",
            "select", "selecting"
    };

    // Питч на клетку диагонали для selecting.wav
    private static final double PITCH_PER_CELL = 0.01;
    private static final double MAX_RATE = 8.0;
    private static final double MIN_PITCH_RATE = 1.0;

    // Квантование rate: 0.01 ≈ 0.17 полутона — на слух непрерывно.
    private static final double PITCH_QUANTUM = 0.01;
    private static final int    PITCH_KEY_MIN = (int) Math.round(MIN_PITCH_RATE / PITCH_QUANTUM); // 100
    private static final int    PITCH_KEY_MAX = (int) Math.round(MAX_RATE      / PITCH_QUANTUM); // 400

    // Диапазон фонового прогрева файлов (чтобы первый драг не лагал)
    private static final double PREWARM_MAX_RATE = 2.8;

    private final Map<String, AudioClip> clips = new HashMap<>();
    private final Map<String, Double> normalizationGains = new HashMap<>();
    private boolean loaded = false;

    // ===== Питч-кэш selecting.wav =====
    // Ключ — rate * 100 (т.е. rate, квантованный до 0.01)
    private final Map<Integer, AudioClip> selectingPitchClips = new ConcurrentHashMap<>();
    private WavNormalizer.Pcm16Data selectingPcm;
    private long selectingSrcMod;
    private Path selectingCacheDir;
    private volatile double selectingVolume = 1.0;

    private volatile boolean masterMuted = false;
    private volatile boolean uiClickMuted = false;
    private volatile boolean editorMuted = false;
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
        normalizationGains.clear();
        selectingPitchClips.clear();
        selectingPcm = null;
        selectingSrcMod = 0L;
        selectingCacheDir = null;

        Path sfxDir = Config.getDataDir().resolve("sfx");
        if (!Files.exists(sfxDir)) {
            try { Files.createDirectories(sfxDir); } catch (Exception e) {
                System.err.println("Failed to create sfx dir: " + e.getMessage());
            }
            System.out.println("SFX directory created (empty): " + sfxDir.toAbsolutePath());
            loaded = true;
            return;
        }

        // ===== Проход 1: загрузка клипов + RMS/peak =====
        Map<String, Double> rmsMap  = new HashMap<>();
        Map<String, Double> peakMap = new HashMap<>();

        for (String name : SFX_FILES) {
            Path file = sfxDir.resolve(name + ".wav");
            if (!Files.exists(file)) continue;
            try {
                AudioClip clip = new AudioClip(file.toUri().toString());
                clips.put(name, clip);

                double[] stats = WavNormalizer.computeRmsAndPeak(file);
                if (stats != null) {
                    rmsMap.put(name, stats[0]);
                    peakMap.put(name, stats[1]);
                }
            } catch (Exception e) {
                System.err.println("Failed to load sfx " + file + ": " + e.getMessage());
            }
        }

        // ===== Проход 2: targetRms =====
        double targetRms = 0.0;
        String loudest = null;
        for (Map.Entry<String, Double> e : rmsMap.entrySet()) {
            if (e.getValue() > targetRms) {
                targetRms = e.getValue();
                loudest = e.getKey();
            }
        }
        if (targetRms < WavNormalizer.MIN_RMS) {
            targetRms = 0.125;
            loudest = null;
        }
        System.out.printf("Normalization: targetRms=%.4f (%.1f dBFS) from '%s'%n",
                targetRms, 20 * Math.log10(targetRms),
                loudest != null ? loudest : "<fallback>");

        // ===== Проход 3: gain'ы =====
        for (Map.Entry<String, Double> e : rmsMap.entrySet()) {
            String name = e.getKey();
            double rms  = e.getValue();
            double peak = peakMap.getOrDefault(name, 0.0);
            double gain = WavNormalizer.computeGain(rms, peak, targetRms);
            normalizationGains.put(name, gain);
            if (Math.abs(gain - 1.0) > 0.001) {
                System.out.printf("  %-10s gain=%.3f (%+.1f dB)%n",
                        name, gain, 20 * Math.log10(gain));
            }
        }

        // ===== Проход 4: источник для питч-кэша selecting =====
        initSelectingPitchSource(sfxDir);

        loaded = true;
        refreshVolumes();
        System.out.println("AudioManager loaded " + clips.size() + " sfx clips");

        // ===== Фоновый прогрев WAV-файлов (не блокирует) =====
        prewarmPitchFilesAsync();
    }

    private void initSelectingPitchSource(Path sfxDir) {
        Path src = sfxDir.resolve("selecting.wav");
        if (!Files.exists(src)) return;

        WavNormalizer.Pcm16Data pcm = WavNormalizer.readPcm16(src);
        if (pcm == null || pcm.samples.length == 0) {
            System.err.println("selecting.wav is not PCM16 — continuous pitch disabled");
            return;
        }

        Path cacheDir = sfxDir.resolve("generated");
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            System.err.println("Cannot create pitch cache dir: " + e.getMessage());
            return;
        }

        long mod;
        try { mod = Files.getLastModifiedTime(src).toMillis(); }
        catch (IOException e) { mod = 0L; }

        this.selectingPcm      = pcm;
        this.selectingSrcMod   = mod;
        this.selectingCacheDir = cacheDir;
    }

    /**
     * Возвращает AudioClip для заданного квантованного rate.
     * Файл WAV генерируется на лету и кэшируется, если его ещё нет
     * (или если исходный selecting.wav новее).
     */
    private AudioClip getOrCreateSelectingClip(int quantKey) {
        AudioClip cached = selectingPitchClips.get(quantKey);
        if (cached != null) return cached;

        if (selectingPcm == null || selectingCacheDir == null) return null;
        if (quantKey < PITCH_KEY_MIN) quantKey = PITCH_KEY_MIN;
        if (quantKey > PITCH_KEY_MAX) quantKey = PITCH_KEY_MAX;

        double rate = quantKey * PITCH_QUANTUM;
        Path out = selectingCacheDir.resolve("selecting_r" + quantKey + ".wav");

        try {
            boolean needGen = !Files.exists(out);
            if (!needGen) {
                long outMod = Files.getLastModifiedTime(out).toMillis();
                needGen = outMod < selectingSrcMod;
            }
            if (needGen) {
                writeResampled(out, rate);
            }
            AudioClip clip = new AudioClip(out.toUri().toString());
            clip.setVolume(selectingVolume);

            // double-check: пока генерили, могли из другого потока положить
            AudioClip prev = selectingPitchClips.putIfAbsent(quantKey, clip);
            return prev != null ? prev : clip;
        } catch (Exception e) {
            System.err.println("Pitched selecting[" + quantKey + "] failed: " + e.getMessage());
            return null;
        }
    }

    /** Resampling исходных PCM-сэмплов с коэффициентом rate и запись в WAV. */
    private void writeResampled(Path out, double rate) throws IOException {
        WavNormalizer.Pcm16Data pcm = selectingPcm;
        if (pcm == null) return;

        int srcLen = pcm.samples.length;
        int outLen = (int) (srcLen / rate);
        if (outLen <= 0) outLen = 1;

        short[] dst = new short[outLen];
        for (int j = 0; j < outLen; j++) {
            int s = (int) (j * rate);
            if (s >= srcLen) s = srcLen - 1;
            dst[j] = pcm.samples[s];
        }
        WavNormalizer.writePcm16Wav(out, dst, pcm.sampleRate, pcm.channels);
    }

    /** Фоновая генерация WAV-файлов для типичного диапазона (1.00..2.80). */
    private void prewarmPitchFilesAsync() {
        if (selectingPcm == null || selectingCacheDir == null) return;

        Thread t = new Thread(() -> {
            int kMin = PITCH_KEY_MIN;
            int kMax = (int) Math.round(Math.min(MAX_RATE, PREWARM_MAX_RATE) / PITCH_QUANTUM);
            int generated = 0;
            for (int k = kMin; k <= kMax; k++) {
                if (Thread.currentThread().isInterrupted()) return;
                try {
                    Path out = selectingCacheDir.resolve("selecting_r" + k + ".wav");
                    boolean needGen = !Files.exists(out);
                    if (!needGen) {
                        long outMod = Files.getLastModifiedTime(out).toMillis();
                        needGen = outMod < selectingSrcMod;
                    }
                    if (!needGen) continue;
                    writeResampled(out, k * PITCH_QUANTUM);
                    generated++;
                } catch (Exception ignored) {}
            }
            if (generated > 0) {
                System.out.println("Pitch prewarm: generated " + generated
                        + " selecting_r*.wav files ("
                        + kMin * PITCH_QUANTUM + ".." + kMax * PITCH_QUANTUM + ")");
            }
        }, "pitch-prewarm");
        t.setDaemon(true);
        t.start();
    }

    // ============================================================
    // Громкость
    // ============================================================

    public synchronized void refreshVolumes() {
        Config cfg = Config.getInstance();
        double master = clamp01(cfg.getMasterVolume() / 100.0);
        double uiClick = clamp01(cfg.getUiClickVolume() / 100.0);
        double editor = clamp01(cfg.getEditorVolume() / 100.0);
        double blockActivation = clamp01(cfg.getBlockActivationVolume() / 100.0);

        masterMuted = (master <= 0.0);
        uiClickMuted = masterMuted || (uiClick <= 0.0);
        editorMuted = masterMuted || (editor <= 0.0);
        blockActivationMuted = masterMuted || (blockActivation <= 0.0);

        for (Map.Entry<String, AudioClip> e : clips.entrySet()) {
            String name = e.getKey();
            double category = categoryVolume(name, uiClick, editor, blockActivation);
            double gain = normalizationGains.getOrDefault(name, 1.0);
            e.getValue().setVolume(clamp01(master * category * gain));
        }

        // Тот же гейн, что у базового selecting.wav
        double selGain = normalizationGains.getOrDefault("selecting", 1.0);
        this.selectingVolume = clamp01(master * editor * selGain);
        for (AudioClip c : selectingPitchClips.values()) {
            if (c != null) c.setVolume(selectingVolume);
        }
    }

    private static double categoryVolume(String name,
                                         double uiClick,
                                         double editor,
                                         double blockActivation) {
        switch (name) {
            case "button":
                return uiClick;
            case "placement":
            case "remove":
            case "copy":
            case "insert":
            case "deselect":
            case "select":
            case "selecting":
                return editor;
            default:
                return blockActivation;
        }
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    // ============================================================
    // Воспроизведение
    // ============================================================

    public void playButton()       { if (!uiClickMuted)         play("button"); }
    public void playPlacement()    { if (!editorMuted)          play("placement"); }
    public void playRemove()       { if (!editorMuted)          play("remove"); }
    public void playCopy()         { if (!editorMuted)          play("copy"); }
    public void playInsert()       { if (!editorMuted)          play("insert"); }
    public void playDeselect()     { if (!editorMuted)          play("deselect"); }
    public void playSelect()       { if (!editorMuted)          play("select"); }

    /**
     * selecting.wav с непрерывным питчем от диагонали.
     * rate = 1.0 + diagonal * 0.06, квантуется до 0.01 (≈0.17 полутона — неотличимо от непрерывного).
     * На каждый новый rate генерируется WAV + AudioClip, дальше кэш.
     */
    public void playSelectingTick(int width, int height) {
        if (editorMuted) return;

        double diagonal = Math.sqrt((double) width * width + (double) height * height);
        double rate = MIN_PITCH_RATE + diagonal * PITCH_PER_CELL;
        if (rate < MIN_PITCH_RATE) rate = MIN_PITCH_RATE;
        if (rate > MAX_RATE) rate = MAX_RATE;

        int quantKey = (int) Math.round(rate / PITCH_QUANTUM);
        if (quantKey < PITCH_KEY_MIN) quantKey = PITCH_KEY_MIN;
        if (quantKey > PITCH_KEY_MAX) quantKey = PITCH_KEY_MAX;

        AudioClip clip = getOrCreateSelectingClip(quantKey);
        if (clip == null) {
            // fallback: без питча, но хоть что-то
            play("selecting");
            return;
        }
        try { clip.play(); }
        catch (Exception e) {
            System.err.println("Failed to play pitched selecting: " + e.getMessage());
        }
    }

    public void playBlockSelected(BlockType type) {
        if (blockActivationMuted || type == null) return;
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
        try { clip.play(); }
        catch (Exception e) {
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