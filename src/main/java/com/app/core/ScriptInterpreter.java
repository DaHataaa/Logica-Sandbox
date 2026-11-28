package com.app.core;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScriptInterpreter {

    public interface CommandHandler {
        void handle(String command, String[] args);
    }

    private static class ParsedCommand {
        final String raw;
        final String name;
        final String[] args;
        String varName;
        String expression;
        String arrayIndexExpr;
        String condition;
        String targetLabel;

        ParsedCommand(String raw, String name, String[] args) {
            this.raw = raw;
            this.name = name;
            this.args = args;
        }
    }

    // === КОНТЕКСТ ВЫПОЛНЕНИЯ (для стека вызовов процедур) ===
    private static class ExecutionContext {
        List<ParsedCommand> cmds;
        int index;
        Map<String, Integer> labels;

        ExecutionContext(List<ParsedCommand> cmds, int index, Map<String, Integer> labels) {
            this.cmds = cmds;
            this.index = index;
            this.labels = labels;
        }
    }

    // === РЕЗУЛЬТАТ ПАРСИНГА БЛОКА ===
    private static class ParseResult {
        List<ParsedCommand> commands = new ArrayList<>();
        Map<String, Integer> labels = new HashMap<>();
    }

    private ExecutionContext currentContext;
    private final Stack<ExecutionContext> callStack = new Stack<>();

    // Хранилища процедур (final Map, поэтому мы делаем .put(), а не переназначаем переменную)
    private final Map<String, List<ParsedCommand>> procedureCommands = new HashMap<>();
    private final Map<String, Map<String, Integer>> procedureLabels = new HashMap<>();

    private final CommandHandler handler;
    private final SimulationEngine engine;
    private Runnable onComplete;
    private volatile boolean running;

    // === ДИНАМИЧЕСКИЙ GET ===
    private IntSupplier getProvider;
    public void setGetProvider(IntSupplier provider) { this.getProvider = provider; }

    // === ПАМЯТЬ: ПЕРЕМЕННЫЕ ===
    private final int[] variables = new int[64];
    private final boolean[] varUsed = new boolean[64];
    private final Map<String, Integer> varIndexMap = new HashMap<>();

    // === ПАМЯТЬ: МАССИВЫ ===
    private final int[][] arrays = new int[8][64];
    private final boolean[] arrayUsed = new boolean[8];
    private final Map<String, Integer> arrayIndexMap = new HashMap<>();
    private final Map<String, Integer> arraySizeMap = new HashMap<>();

    // === ФАЙЛЫ ===
    private final Map<String, List<String>> fileData = new HashMap<>();
    private String currentFile = null;

    private Runnable tickListener;
    private volatile int ticksRemaining;
    private int ifCounter = 0;

    public boolean isRunning() { return running; }

    public ScriptInterpreter(String code, CommandHandler handler, SimulationEngine engine) {
        this.handler = handler;
        this.engine = engine;

        // Парсим основной код. Процедуры автоматически извлекутся в procedureCommands
        ParseResult mainRes = parseBlock(code, 0);
        this.currentContext = new ExecutionContext(mainRes.commands, 0, mainRes.labels);
        this.running = false;
    }

    public void setOnComplete(Runnable onComplete) {
        this.onComplete = onComplete;
    }

    public void start() {
        if (running) return;
        running = true;
        if (currentContext == null || currentContext.cmds.isEmpty()) {
            finish();
            return;
        }
        executeNext();
    }

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
        while (currentContext != null && currentContext.index < currentContext.cmds.size()) {
            if (!running) return;

            ParsedCommand cmd = currentContext.cmds.get(currentContext.index);
            currentContext.index++;

            switch (cmd.name) {
                case "LABEL":
                    break;
                case "GOTO":
                    jumpToLabel(cmd.targetLabel);
                    break;
                case "IF":
                    if (evaluateCondition(cmd.condition)) jumpToLabel(cmd.targetLabel);
                    break;
                case "IF_FALSE":
                    if (!evaluateCondition(cmd.condition)) jumpToLabel(cmd.targetLabel);
                    break;
                case "CALL": {
                    String procName = cmd.args.length > 0 ? cmd.args[0].toLowerCase() : "";
                    if (procedureCommands.containsKey(procName)) {
                        callStack.push(currentContext);
                        currentContext = new ExecutionContext(
                                procedureCommands.get(procName),
                                0,
                                procedureLabels.get(procName)
                        );
                    } else {
                        System.err.println("[SCRIPT] Procedure not found: " + procName);
                    }
                    break;
                }
                case "RETURN": {
                    if (!callStack.isEmpty()) {
                        currentContext = callStack.pop();
                    } else {
                        System.err.println("[SCRIPT] RETURN without CALL");
                    }
                    break;
                }
                case "ASSIGN":
                    try {
                        int val = evaluateExpression(cmd.expression);
                        assignVariable(cmd.varName, val);
                    } catch (Exception e) {
                        System.err.println("[SCRIPT] Assign error in '" + cmd.raw + "': " + e.getMessage());
                    }
                    break;
                case "ASSIGN_ARRAY":
                    try {
                        int idx = evaluateExpression(cmd.arrayIndexExpr);
                        int val = evaluateExpression(cmd.expression);
                        assignArrayValue(cmd.varName, idx, val);
                    } catch (Exception e) {
                        System.err.println("[SCRIPT] Array assign error in '" + cmd.raw + "': " + e.getMessage());
                    }
                    break;
                case "OPENFILE":
                    if (cmd.args.length > 0) loadFile(cmd.args[0]);
                    break;
                case "SLEEP_MS": {
                    long ms = parseLong(cmd, 0);
                    if (ms <= 0) break;
                    PauseTransition pt = new PauseTransition(Duration.millis(ms));
                    pt.setOnFinished(e -> Platform.runLater(this::executeNext));
                    pt.play();
                    return;
                }
                case "SLEEP_US": {
                    long us = parseLong(cmd, 0);
                    if (us <= 0) break;
                    PauseTransition pt = new PauseTransition(Duration.millis(us / 1000.0));
                    pt.setOnFinished(e -> Platform.runLater(this::executeNext));
                    pt.play();
                    return;
                }
                case "SLEEP_TICKS": {
                    int ticks = parseInt(cmd, 0);
                    if (ticks <= 0) break;
                    if (engine != null && !engine.isRunning()) engine.start();
                    ticksRemaining = ticks;
                    attachTickListener();
                    return;
                }
                default: {
                    // Проверяем, не является ли команда неявным вызовом процедуры
                    if (procedureCommands.containsKey(cmd.name.toLowerCase())) {
                        callStack.push(currentContext);
                        currentContext = new ExecutionContext(
                                procedureCommands.get(cmd.name.toLowerCase()),
                                0,
                                procedureLabels.get(cmd.name.toLowerCase())
                        );
                    } else if (handler != null) {
                        try {
                            String[] evaluatedArgs = new String[cmd.args.length];
                            for (int i = 0; i < cmd.args.length; i++) {
                                evaluatedArgs[i] = String.valueOf(evaluateExpression(cmd.args[i]));
                            }
                            handler.handle(cmd.name, evaluatedArgs);
                        } catch (Exception e) {
                            System.err.println("[SCRIPT] Handler error for '" + cmd.raw + "': " + e.getMessage());
                        }
                    }
                    break;
                }
            }
        }
        finish();
    }

    private void jumpToLabel(String label) {
        Integer targetIndex = currentContext.labels.get(label);
        if (targetIndex != null) {
            currentContext.index = targetIndex + 1;
        } else {
            System.err.println("[SCRIPT] Label not found: " + label);
        }
    }

    // === ВЫЧИСЛЕНИЕ ВЫРАЖЕНИЙ ===
    private int findTopLevelOperator(String expr, String op) {
        int depth = 0;
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            else if (depth == 0) {
                if (expr.startsWith(op, i)) {
                    if (i > 0) return i;
                }
            }
            i++;
        }
        return -1;
    }

    private int evaluateExpression(String expr) {
        if (expr == null) return 0;
        expr = expr.trim();
        if (expr.isEmpty()) return 0;

        String[] ops = {"<<", ">>", "+", "-", "*", "/", "%", "&", "|", "^"};
        for (String op : ops) {
            int idx = findTopLevelOperator(expr, op);
            if (idx > 0) {
                String leftStr = expr.substring(0, idx).trim();
                String rightStr = expr.substring(idx + op.length()).trim();
                int left = evaluateExpression(leftStr);
                int right = evaluateExpression(rightStr);
                switch (op) {
                    case "+": return left + right;
                    case "-": return left - right;
                    case "*": return left * right;
                    case "/": return right != 0 ? left / right : 0;
                    case "%": return right != 0 ? left % right : 0;
                    case "&": return left & right;
                    case "|": return left | right;
                    case "^": return left ^ right;
                    case "<<": return left << right;
                    case ">>": return left >> right;
                }
            }
        }
        return evaluateTerm(expr);
    }

    private int evaluateTerm(String term) {
        if (term == null) return 0;
        term = term.trim();
        if (term.isEmpty()) return 0;

        if (term.equalsIgnoreCase("GET")) {
            if (getProvider != null) return getProvider.getAsInt();
            return -1;
        }

        Pattern filePattern = Pattern.compile("^FILE\\[\\s*(.+)\\s*\\]\\[\\s*(.+)\\s*\\]$", Pattern.CASE_INSENSITIVE);
        Matcher m = filePattern.matcher(term);
        if (m.matches()) {
            int y = evaluateExpression(m.group(1));
            int x = evaluateExpression(m.group(2));
            return getFileValue(y, x);
        }

        Pattern arrPattern = Pattern.compile("^([a-zA-Z_]\\w*)\\[\\s*(.+)\\s*\\]$", Pattern.CASE_INSENSITIVE);
        Matcher arrMatcher = arrPattern.matcher(term);
        if (arrMatcher.matches()) {
            String arrName = arrMatcher.group(1);
            String idxExpr = arrMatcher.group(2);
            if (!arrayIndexMap.containsKey(arrName)) throw new RuntimeException("Undeclared array: " + arrName);
            int slot = arrayIndexMap.get(arrName);
            int maxLen = arraySizeMap.get(arrName);
            int idx = evaluateExpression(idxExpr);
            if (idx < 0 || idx >= maxLen) throw new RuntimeException("Array index out of bounds: " + arrName + "[" + idx + "]");
            return arrays[slot][idx];
        }

        if (varIndexMap.containsKey(term)) {
            return variables[varIndexMap.get(term)];
        }

        try {
            return Integer.parseInt(term);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean evaluateCondition(String cond) {
        cond = cond.trim();
        if (cond.startsWith("(") && cond.endsWith(")")) {
            cond = cond.substring(1, cond.length() - 1).trim();
        }
        String[] ops = {"==", "!=", "<=", ">=", "<", ">"};
        for (String op : ops) {
            int idx = findTopLevelOperator(cond, op);
            if (idx > 0) {
                int left = evaluateExpression(cond.substring(0, idx).trim());
                int right = evaluateExpression(cond.substring(idx + op.length()).trim());
                switch (op) {
                    case "==": return left == right;
                    case "!=": return left != right;
                    case "<=": return left <= right;
                    case ">=": return left >= right;
                    case "<":  return left < right;
                    case ">":  return left > right;
                }
            }
        }
        return evaluateExpression(cond) != 0;
    }

    private void assignVariable(String name, int value) {
        if (!varIndexMap.containsKey(name)) {
            for (int i = 0; i < 64; i++) {
                if (!varUsed[i]) {
                    varUsed[i] = true;
                    varIndexMap.put(name, i);
                    variables[i] = value;
                    return;
                }
            }
            throw new RuntimeException("Variables limit reached (64)");
        } else {
            variables[varIndexMap.get(name)] = value;
        }
    }

    private void assignArrayValue(String name, int index, int value) {
        if (!arrayIndexMap.containsKey(name)) throw new RuntimeException("Undeclared array: " + name);
        int slot = arrayIndexMap.get(name);
        int maxLen = arraySizeMap.get(name);
        if (index < 0 || index >= maxLen) throw new RuntimeException("Array index out of bounds: " + name + "[" + index + "]");
        arrays[slot][index] = value;
    }

    private int getFileValue(int y, int x) {
        if (currentFile == null || !fileData.containsKey(currentFile)) return -1;
        List<String> lines = fileData.get(currentFile);
        if (y < 0 || y >= lines.size()) return -1;
        String line = lines.get(y);
        if (x < 0 || x >= line.length()) return -1;
        return line.charAt(x) == '1' ? 1 : 0;
    }

    private void loadFile(String path) {
        if (path.startsWith("\"") && path.endsWith("\"")) path = path.substring(1, path.length() - 1);
        currentFile = path;
        if (fileData.containsKey(path)) return;
        try {
            fileData.put(path, Files.readAllLines(Path.of(path)));
        } catch (Exception e) {
            System.err.println("[SCRIPT] Failed to load file: " + path);
            fileData.put(path, new ArrayList<>());
        }
    }

    // === ПАРСИНГ ===
    private ParseResult parseBlock(String code, int offset) {
        ParseResult res = new ParseResult();
        if (code == null) return res;

        String[] lines = code.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            int commentIdx = trimmed.indexOf("//");
            if (commentIdx >= 0) {
                trimmed = trimmed.substring(0, commentIdx).trim();
                if (trimmed.isEmpty()) continue;
            }
            if (trimmed.endsWith(";")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
                if (trimmed.isEmpty()) continue;
            }

            // 1. Метка
            if (trimmed.endsWith(":")) {
                String labelName = trimmed.substring(0, trimmed.length() - 1).trim();
                res.labels.put(labelName, res.commands.size() + offset);
                res.commands.add(new ParsedCommand(trimmed, "LABEL", new String[]{labelName}));
                continue;
            }

            // 2. Объявление переменных/массивов (обновляем глобальные карты памяти)
            if (trimmed.toLowerCase().startsWith("int ")) {
                String rest = trimmed.substring(4).trim();
                if (rest.contains("[")) {
                    int bracketStart = rest.indexOf('[');
                    int bracketEnd = rest.indexOf(']');
                    if (bracketStart > 0 && bracketEnd > bracketStart) {
                        String arrName = rest.substring(0, bracketStart).trim();
                        String sizeStr = rest.substring(bracketStart + 1, bracketEnd).trim();
                        int size = Integer.parseInt(sizeStr);
                        if (size <= 0 || size > 64) throw new RuntimeException("Array size must be 1-64: " + arrName);
                        if (arrayIndexMap.containsKey(arrName)) throw new RuntimeException("Array already declared: " + arrName);
                        int slot = -1;
                        for (int j = 0; j < 8; j++) { if (!arrayUsed[j]) { slot = j; break; } }
                        if (slot == -1) throw new RuntimeException("Array limit reached (8 max)");
                        arrayUsed[slot] = true;
                        arrayIndexMap.put(arrName, slot);
                        arraySizeMap.put(arrName, size);
                    }
                } else {
                    String varName = rest;
                    if (varIndexMap.containsKey(varName)) throw new RuntimeException("Variable already declared: " + varName);
                    int slot = -1;
                    for (int j = 0; j < 64; j++) { if (!varUsed[j]) { slot = j; break; } }
                    if (slot == -1) throw new RuntimeException("Variable limit reached (64 max)");
                    varUsed[slot] = true;
                    varIndexMap.put(varName, slot);
                }
                continue;
            }

            // 3. Процедура
            if (trimmed.toLowerCase().startsWith("procedure ")) {
                String rest = trimmed.substring(10).trim();
                String procName = "";
                int braceIdx = rest.indexOf('{');
                if (braceIdx > 0) procName = rest.substring(0, braceIdx).trim();
                else procName = rest;

                if (!procName.matches("[a-zA-Z_]\\w*")) throw new RuntimeException("Invalid procedure name: " + procName);

                int braceCount = 0;
                StringBuilder blockCode = new StringBuilder();
                if (braceIdx > 0) {
                    braceCount = 1;
                    String afterBrace = rest.substring(braceIdx + 1).trim();
                    if (!afterBrace.isEmpty()) {
                        int closeIdx = afterBrace.indexOf('}');
                        if (closeIdx >= 0) { blockCode.append(afterBrace.substring(0, closeIdx).trim()).append("\n"); braceCount = 0; }
                        else { blockCode.append(afterBrace).append("\n"); }
                    }
                }
                while (i + 1 < lines.length && braceCount > 0) {
                    i++;
                    String blockLine = lines[i];
                    for (char c : blockLine.toCharArray()) {
                        if (c == '{') braceCount++;
                        else if (c == '}') braceCount--;
                    }
                    if (braceCount > 0) blockCode.append(blockLine).append("\n");
                    else {
                        int lastBrace = blockLine.lastIndexOf('}');
                        if (lastBrace >= 0) {
                            String beforeBrace = blockLine.substring(0, lastBrace).trim();
                            if (!beforeBrace.isEmpty()) blockCode.append(beforeBrace).append("\n");
                        }
                    }
                }
                if (braceCount > 0) throw new RuntimeException("Syntax error: Unclosed '{' in procedure " + procName);

                // Рекурсивно парсим тело процедуры (изолированные метки!)
                ParseResult procRes = parseBlock(blockCode.toString(), 0);
                procedureCommands.put(procName.toLowerCase(), procRes.commands);
                procedureLabels.put(procName.toLowerCase(), procRes.labels);

                continue; // Не добавляем определение процедуры в список команд
            }

            // 4. Goto
            Pattern gotoPattern = Pattern.compile("^goto\\s+([a-zA-Z_]\\w*)$", Pattern.CASE_INSENSITIVE);
            Matcher gotoMatcher = gotoPattern.matcher(trimmed);
            if (gotoMatcher.matches()) {
                ParsedCommand cmd = new ParsedCommand(line.trim(), "GOTO", new String[]{gotoMatcher.group(1)});
                cmd.targetLabel = gotoMatcher.group(1);
                res.commands.add(cmd);
                continue;
            }

            // 5. IF старый
            Pattern oldIfPattern = Pattern.compile("^if\\s*\\((.+)\\)\\s+goto\\s+([a-zA-Z_]\\w*)$", Pattern.CASE_INSENSITIVE);
            Matcher oldIfMatcher = oldIfPattern.matcher(trimmed);
            if (oldIfMatcher.matches()) {
                ParsedCommand cmd = new ParsedCommand(line.trim(), "IF", new String[]{oldIfMatcher.group(1), oldIfMatcher.group(2)});
                cmd.condition = oldIfMatcher.group(1);
                cmd.targetLabel = oldIfMatcher.group(2);
                res.commands.add(cmd);
                continue;
            }

            // 6. IF новый
            Pattern newIfPattern = Pattern.compile("^if\\s*\\((.+)\\)\\s*(.*)$", Pattern.CASE_INSENSITIVE);
            Matcher newIfMatcher = newIfPattern.matcher(trimmed);
            if (newIfMatcher.matches()) {
                String condition = newIfMatcher.group(1).trim();
                String remainder = newIfMatcher.group(2).trim();
                String skipLabel = "__if_skip_" + (ifCounter++);

                if (remainder.startsWith("{")) {
                    int braceCount = 1;
                    StringBuilder blockCode = new StringBuilder();
                    String afterBrace = remainder.substring(1).trim();
                    if (!afterBrace.isEmpty()) {
                        int firstClose = afterBrace.indexOf('}');
                        if (firstClose >= 0) { blockCode.append(afterBrace.substring(0, firstClose).trim()).append("\n"); braceCount = 0; }
                        else { blockCode.append(afterBrace).append("\n"); }
                    }
                    while (i + 1 < lines.length && braceCount > 0) {
                        i++;
                        String blockLine = lines[i];
                        for (char c : blockLine.toCharArray()) {
                            if (c == '{') braceCount++;
                            else if (c == '}') braceCount--;
                        }
                        if (braceCount > 0) blockCode.append(blockLine).append("\n");
                        else {
                            int lastBrace = blockLine.lastIndexOf('}');
                            if (lastBrace >= 0) {
                                String beforeBrace = blockLine.substring(0, lastBrace).trim();
                                if (!beforeBrace.isEmpty()) blockCode.append(beforeBrace).append("\n");
                            }
                        }
                    }
                    if (braceCount > 0) throw new RuntimeException("Syntax error: Unclosed '{' in if statement: " + line);

                    ParsedCommand ifCmd = new ParsedCommand(line.trim(), "IF_FALSE", new String[]{condition, skipLabel});
                    ifCmd.condition = condition;
                    ifCmd.targetLabel = skipLabel;
                    res.commands.add(ifCmd);

                    int blockOffset = offset + res.commands.size();
                    ParseResult blockRes = parseBlock(blockCode.toString(), blockOffset);
                    res.commands.addAll(blockRes.commands);

                    res.labels.put(skipLabel, res.commands.size() + offset);
                    res.commands.add(new ParsedCommand(skipLabel + ":", "LABEL", new String[]{skipLabel}));
                } else if (!remainder.isEmpty()) {
                    ParsedCommand ifCmd = new ParsedCommand(line.trim(), "IF_FALSE", new String[]{condition, skipLabel});
                    ifCmd.condition = condition;
                    ifCmd.targetLabel = skipLabel;
                    res.commands.add(ifCmd);

                    int inlineOffset = offset + res.commands.size();
                    ParseResult inlineRes = parseBlock(remainder, inlineOffset);
                    res.commands.addAll(inlineRes.commands);

                    res.labels.put(skipLabel, res.commands.size() + offset);
                    res.commands.add(new ParsedCommand(skipLabel + ":", "LABEL", new String[]{skipLabel}));
                } else {
                    throw new RuntimeException("Syntax error: Expected command or '{' after if condition: " + line);
                }
                continue;
            }

            // 7. Присваивание массиву
            Pattern arrAssignPattern = Pattern.compile("^([a-zA-Z_]\\w*)\\s*\\[\\s*(.+)\\s*\\]\\s*=\\s*(.+)$", Pattern.CASE_INSENSITIVE);
            Matcher arrAssignMatcher = arrAssignPattern.matcher(trimmed);
            if (arrAssignMatcher.matches()) {
                ParsedCommand cmd = new ParsedCommand(line.trim(), "ASSIGN_ARRAY", new String[]{arrAssignMatcher.group(1), arrAssignMatcher.group(2), arrAssignMatcher.group(3)});
                cmd.varName = arrAssignMatcher.group(1);
                cmd.arrayIndexExpr = arrAssignMatcher.group(2);
                cmd.expression = arrAssignMatcher.group(3);
                res.commands.add(cmd);
                continue;
            }

            // 8. Присваивание переменной
            Pattern varAssignPattern = Pattern.compile("^([a-zA-Z_]\\w*)\\s*=\\s*(.+)$", Pattern.CASE_INSENSITIVE);
            Matcher varAssignMatcher = varAssignPattern.matcher(trimmed);
            if (varAssignMatcher.matches()) {
                String varName = varAssignMatcher.group(1);
                ParsedCommand cmd = new ParsedCommand(line.trim(), "ASSIGN", new String[]{varName, varAssignMatcher.group(2)});
                cmd.varName = varName;
                cmd.expression = varAssignMatcher.group(2);
                res.commands.add(cmd);
                continue;
            }

            // 9. OPENFILE
            Pattern openFilePattern = Pattern.compile("^OPENFILE\\s+(.+)$", Pattern.CASE_INSENSITIVE);
            Matcher openFileMatcher = openFilePattern.matcher(trimmed);
            if (openFileMatcher.matches()) {
                res.commands.add(new ParsedCommand(line.trim(), "OPENFILE", new String[]{openFileMatcher.group(1).trim()}));
                continue;
            }

            // 10. Задержки
            if (trimmed.toUpperCase().startsWith("SLEEP_MS")) {
                res.commands.add(new ParsedCommand(line.trim(), "SLEEP_MS", new String[]{trimmed.substring(8).trim()}));
                continue;
            }
            if (trimmed.toUpperCase().startsWith("SLEEP_US")) {
                res.commands.add(new ParsedCommand(line.trim(), "SLEEP_US", new String[]{trimmed.substring(8).trim()}));
                continue;
            }
            if (trimmed.toUpperCase().startsWith("SLEEP_TICKS")) {
                res.commands.add(new ParsedCommand(line.trim(), "SLEEP_TICKS", new String[]{trimmed.substring(11).trim()}));
                continue;
            }

            // 11. CALL
            if (trimmed.toUpperCase().startsWith("CALL ")) {
                String target = trimmed.substring(5).trim();
                res.commands.add(new ParsedCommand(line.trim(), "CALL", new String[]{target}));
                continue;
            }

            // 12. RETURN
            if (trimmed.toUpperCase().equals("RETURN")) {
                res.commands.add(new ParsedCommand(line.trim(), "RETURN", new String[0]));
                continue;
            }

            // 13. Мировая команда (по умолчанию)
            String[] parts = trimmed.split("\\s+");
            String name = parts[0].toUpperCase();
            String[] args = new String[parts.length - 1];
            System.arraycopy(parts, 1, args, 0, args.length);
            res.commands.add(new ParsedCommand(line.trim(), name, args));
        }
        return res;
    }

    private int parseInt(ParsedCommand cmd, int index) {
        if (index >= cmd.args.length) return 0;
        try { return Integer.parseInt(cmd.args[index]); } catch (NumberFormatException e) { return 0; }
    }

    private long parseLong(ParsedCommand cmd, int index) {
        if (index >= cmd.args.length) return 0;
        try { return Long.parseLong(cmd.args[index]); } catch (NumberFormatException e) { return 0; }
    }

    private void attachTickListener() {
        if (engine == null) { executeNext(); return; }
        detachTickListener();
        tickListener = () -> {
            if (!running) return;
            Platform.runLater(() -> {
                if (!running) return;
                ticksRemaining--;
                if (ticksRemaining <= 0) {
                    detachTickListener();
                    executeNext();
                }
            });
        };
        engine.addTickListener(tickListener);
    }

    private void detachTickListener() {
        if (tickListener != null && engine != null) {
            engine.removeTickListener(tickListener);
            tickListener = null;
        }
    }
}