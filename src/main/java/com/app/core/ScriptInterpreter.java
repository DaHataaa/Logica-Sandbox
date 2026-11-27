package com.app.core;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

public class ScriptInterpreter {

    /** Обработчик "мировых" команд (PLACE, REMOVE, и т.д.). Вызывается в JavaFX-потоке. */
    public interface CommandHandler {
        void handle(String command, String[] args);
    }

    private static class ParsedCommand {
        final String raw;
        final String name;
        final String[] args;
        ParsedCommand(String raw, String name, String[] args) {
            this.raw = raw;
            this.name = name;
            this.args = args;
        }
    }

    private final List<ParsedCommand> commands;
    private int currentIndex;
    private final CommandHandler handler;
    private final SimulationEngine engine;
    private Runnable onComplete;
    private boolean running;

    // Для SLEEP_TICKS
    private Runnable tickListener;
    private int ticksRemaining;

    public ScriptInterpreter(String code, CommandHandler handler, SimulationEngine engine) {
        this.handler = handler;
        this.engine = engine;
        this.commands = parse(code);
        this.currentIndex = 0;
        this.running = false;
    }

    public void setOnComplete(Runnable onComplete) {
        this.onComplete = onComplete;
    }

    /** Запуск выполнения. Повторный вызов игнорируется. */
    public void start() {
        if (running) return;
        running = true;
        if (commands.isEmpty()) {
            finish();
            return;
        }
        executeNext();
    }

    /** Прервать выполнение скрипта. */
    public void stop() {
        running = false;
        detachTickListener();
    }

    private void finish() {
        running = false;
        detachTickListener();
        if (onComplete != null) {
            Platform.runLater(onComplete);
        }
    }

    private void executeNext() {
        if (!running) return;
        if (currentIndex >= commands.size()) {
            finish();
            return;
        }

        ParsedCommand cmd = commands.get(currentIndex);
        currentIndex++;

        switch (cmd.name) {
            case "SLEEP_MS": {
                long ms = parseLong(cmd, 0);
                if (ms <= 0) { executeNext(); return; }
                PauseTransition pt = new PauseTransition(Duration.millis(ms));
                pt.setOnFinished(e -> executeNext());
                pt.play();
                break;
            }
            case "SLEEP_US": {
                long us = parseLong(cmd, 0);
                if (us <= 0) { executeNext(); return; }
                // PauseTransition работает в мс, но поддерживает дробные значения через Duration
                PauseTransition pt = new PauseTransition(Duration.millis(us / 1000.0));
                pt.setOnFinished(e -> executeNext());
                pt.play();
                break;
            }
            case "SLEEP_TICKS": {
                int ticks = parseInt(cmd, 0);
                if (ticks <= 0) { executeNext(); return; }
                // Если симуляция не запущена — запускаем, иначе тики не пойдут
                if (engine != null && !engine.isRunning()) {
                    engine.start();
                }
                ticksRemaining = ticks;
                attachTickListener();
                break;
            }
            default: {
                // Мировая команда — передаём обработчику
                if (handler != null) {
                    try {
                        handler.handle(cmd.name, cmd.args);
                    } catch (Exception e) {
                        System.err.println("[SCRIPT] Handler error for '" + cmd.raw + "': " + e.getMessage());
                    }
                }
                // Следующая команда выполняется сразу (без задержки)
                executeNext();
                break;
            }
        }
    }

    // ===== SLEEP_TICKS: подписка на тики симуляции =====

    private void attachTickListener() {
        if (engine == null) {
            System.err.println("[SCRIPT] SLEEP_TICKS: no SimulationEngine attached, skipping");
            executeNext();
            return;
        }
        detachTickListener();
        tickListener = () -> {
            if (!running) return;
            ticksRemaining--;
            if (ticksRemaining <= 0) {
                detachTickListener();
                executeNext();
            }
        };
        engine.addTickListener(tickListener);
    }

    private void detachTickListener() {
        if (tickListener != null && engine != null) {
            engine.removeTickListener(tickListener);
            tickListener = null;
        }
    }

    // ===== Парсинг =====

    private int parseInt(ParsedCommand cmd, int index) {
        if (index >= cmd.args.length) {
            System.err.println("[SCRIPT] Missing argument in: " + cmd.raw);
            return 0;
        }
        try {
            return Integer.parseInt(cmd.args[index]);
        } catch (NumberFormatException e) {
            System.err.println("[SCRIPT] Invalid integer '" + cmd.args[index] + "' in: " + cmd.raw);
            return 0;
        }
    }

    private long parseLong(ParsedCommand cmd, int index) {
        if (index >= cmd.args.length) {
            System.err.println("[SCRIPT] Missing argument in: " + cmd.raw);
            return 0;
        }
        try {
            return Long.parseLong(cmd.args[index]);
        } catch (NumberFormatException e) {
            System.err.println("[SCRIPT] Invalid integer '" + cmd.args[index] + "' in: " + cmd.raw);
            return 0;
        }
    }

    private List<ParsedCommand> parse(String code) {
        List<ParsedCommand> out = new ArrayList<>();
        if (code == null) return out;

        String[] lines = code.split("\r?\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // Комментарий
            if (trimmed.startsWith("//")) continue;
            // Убираем inline-комментарий
            int commentIdx = trimmed.indexOf("//");
            if (commentIdx >= 0) {
                trimmed = trimmed.substring(0, commentIdx).trim();
                if (trimmed.isEmpty()) continue;
            }

            String[] parts = trimmed.split("\\s+");
            String name = parts[0].toUpperCase();
            String[] args = new String[parts.length - 1];
            System.arraycopy(parts, 1, args, 0, args.length);
            out.add(new ParsedCommand(trimmed, name, args));
        }
        return out;
    }
}