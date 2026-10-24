package com.app.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Читает/пишет PCM 16-bit WAV (RIFF/WAVE).
 * computeRmsAndPeak — для нормализации громкости SFX.
 * readPcm16 / writePcm16Wav — для генерации питч-вариантов (resampling).
 */
public final class WavNormalizer {

    public static final double PEAK_CEILING = 0.98;
    public static final double MIN_RMS = 0.001;
    public static final double MIN_GAIN = 0.063;
    public static final double MAX_GAIN = 16.0;

    private WavNormalizer() {}

    // ============================================================
    // RMS / PEAK
    // ============================================================

    public static double[] computeRmsAndPeak(Path wavPath) {
        try {
            return computeRmsAndPeak(Files.readAllBytes(wavPath));
        } catch (IOException e) {
            System.err.println("WavNormalizer: cannot read " + wavPath + ": " + e.getMessage());
            return null;
        }
    }

    public static double[] computeRmsAndPeak(byte[] wav) {
        try {
            short[] samples = parsePcm16(wav);
            if (samples == null || samples.length == 0) return null;
            double sumSq = 0.0, peak = 0.0;
            int n = samples.length;
            for (int i = 0; i < n; i++) {
                double s = samples[i] / 32768.0;
                double a = Math.abs(s);
                if (a > peak) peak = a;
                sumSq += s * s;
            }
            return new double[]{Math.sqrt(sumSq / n), peak};
        } catch (Exception e) {
            System.err.println("WavNormalizer: parse failed: " + e.getMessage());
            return null;
        }
    }

    public static double computeGain(double rms, double peak, double targetRms) {
        if (rms < MIN_RMS) return 1.0;
        double gain = targetRms / rms;
        if (peak > 0.0 && peak * gain > PEAK_CEILING) gain = PEAK_CEILING / peak;
        if (gain < MIN_GAIN) gain = MIN_GAIN;
        if (gain > MAX_GAIN) gain = MAX_GAIN;
        return gain;
    }

    // ============================================================
    // PCM16: чтение с метаданными + запись (для питч-кэша)
    // ============================================================

    public static final class Pcm16Data {
        public final short[] samples;
        public final int sampleRate;
        public final int channels;

        public Pcm16Data(short[] samples, int sampleRate, int channels) {
            this.samples = samples;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }

    public static Pcm16Data readPcm16(Path wavPath) {
        try {
            return parsePcm16Full(Files.readAllBytes(wavPath));
        } catch (IOException e) {
            System.err.println("WavNormalizer: cannot read " + wavPath + ": " + e.getMessage());
            return null;
        }
    }

    public static void writePcm16Wav(Path path, short[] samples, int sampleRate, int channels)
            throws IOException {
        int dataSize   = samples.length * 2;
        int blockAlign = channels * 2;
        int byteRate   = sampleRate * blockAlign;

        ByteBuffer buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        buf.putInt(36 + dataSize);
        buf.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        buf.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(byteRate);
        buf.putShort((short) blockAlign);
        buf.putShort((short) 16);
        buf.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        buf.putInt(dataSize);
        for (short s : samples) buf.putShort(s);

        Files.write(path, buf.array());
    }

    // ============================================================
    // RIFF/WAVE PCM 16-bit parser
    // ============================================================

    private static short[] parsePcm16(byte[] data) {
        Pcm16Data full = parsePcm16Full(data);
        return full != null ? full.samples : null;
    }

    private static Pcm16Data parsePcm16Full(byte[] data) {
        if (data.length < 44) return null;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        if (buf.get() != 'R' || buf.get() != 'I' || buf.get() != 'F' || buf.get() != 'F') return null;
        buf.getInt();
        if (buf.get() != 'W' || buf.get() != 'A' || buf.get() != 'V' || buf.get() != 'E') return null;

        int audioFormat = 0, channels = 0, bitsPerSample = 0, sampleRate = 0;
        short[] samples = null;

        while (buf.remaining() >= 8) {
            int id0 = buf.get() & 0xFF, id1 = buf.get() & 0xFF;
            int id2 = buf.get() & 0xFF, id3 = buf.get() & 0xFF;
            int chunkSize = buf.getInt();
            int chunkStart = buf.position();
            if (chunkSize < 0 || chunkStart + chunkSize > data.length) break;

            String id = "" + (char) id0 + (char) id1 + (char) id2 + (char) id3;

            if ("fmt ".equals(id)) {
                audioFormat   = buf.getShort() & 0xFFFF;
                channels      = buf.getShort() & 0xFFFF;
                sampleRate    = buf.getInt();
                buf.getInt();
                buf.getShort();
                bitsPerSample = buf.getShort() & 0xFFFF;
            } else if ("data".equals(id)) {
                if (bitsPerSample == 16 && audioFormat == 1 && channels > 0) {
                    int sampleCount = chunkSize / 2;
                    samples = new short[sampleCount];
                    for (int i = 0; i < sampleCount; i++) samples[i] = buf.getShort();
                }
            }

            int newPos = chunkStart + chunkSize;
            if ((chunkSize & 1) != 0) newPos++;
            if (newPos <= chunkStart) break;
            buf.position(Math.min(newPos, data.length));
        }

        if (samples == null) return null;
        return new Pcm16Data(samples, sampleRate, channels);
    }
}