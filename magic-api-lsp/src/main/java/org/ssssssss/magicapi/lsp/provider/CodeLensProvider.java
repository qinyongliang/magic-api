package org.ssssssss.magicapi.lsp.provider;

import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CodeLensProvider {

    private final DiagnosticsProvider diagnosticsProvider;

    public CodeLensProvider(DiagnosticsProvider diagnosticsProvider) {
        this.diagnosticsProvider = diagnosticsProvider;
    }

    public List<CodeLens> generateCodeLenses(String uri, String content) {
        List<CodeLens> lenses = new ArrayList<>();

        // 取消顶部诊断数量 CodeLens（改为由插件端提供独立按钮）

        // 在包含 function 的行添加“测试 API” CodeLens
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.matches(".*\\bfunction\\b.*")) {
                Command cmd = new Command("测试 API", "magicApi.testApi", Collections.singletonList(uri));
                lenses.add(new CodeLens(new Range(new Position(i, 0), new Position(i, Math.max(0, line.length()))), cmd, null));
            }
        }

        return lenses;
    }

    public CodeLens resolveCodeLens(CodeLens unresolved) {
        try {
            if (unresolved.getCommand() != null) {
                return unresolved;
            }
            Object data = unresolved.getData();
            if (data == null) {
                return unresolved;
            }
            String dataStr = data.toString();
            Command command = new Command();
            switch (dataStr) {
                case "function":
                    command.setTitle("📊 Function Info");
                    command.setCommand("magic.showFunctionInfo");
                    break;
                case "async":
                    command.setTitle("⚡ Async Function");
                    command.setCommand("magic.showAsyncInfo");
                    break;
                case "http":
                    command.setTitle("🌐 HTTP Request");
                    command.setCommand("magic.showHttpInfo");
                    break;
                case "sql":
                    command.setTitle("🗄️ SQL Query");
                    command.setCommand("magic.showSqlInfo");
                    break;
                case "performance":
                    command.setTitle("🔄 Performance Info");
                    command.setCommand("magic.showPerformanceInfo");
                    break;
                case "warning":
                    command.setTitle("⚠️ Performance Warning");
                    command.setCommand("magic.showPerformanceWarning");
                    break;
                default:
                    if (dataStr.matches("\\w+")) {
                        command.setTitle("📊 Show References");
                        command.setCommand("magic.showReferences");
                    } else {
                        command.setTitle("ℹ️ Info");
                        command.setCommand("magic.showInfo");
                    }
                    break;
            }
            unresolved.setCommand(command);
            return unresolved;
        } catch (Exception ignored) {
            return unresolved;
        }
    }

    /**
     * 从内容中生成“函数相关”的 CodeLens（不含 HTTP/SQL/性能）。
     * 逻辑源于 MagicWorkspaceService#createFunctionCodeLenses。
     */
    public List<CodeLens> createFunctionCodeLenses(String content, String uri) {
        List<CodeLens> codeLenses = new ArrayList<>();
        String[] lines = content.split("\n");

        Pattern functionPattern = Pattern.compile("function\\s+(\\w+)\\s*\\(");

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];

            // 检测函数定义
            Matcher functionMatcher = functionPattern.matcher(line);
            if (functionMatcher.find()) {
                String functionName = functionMatcher.group(1);

                // 计算函数引用次数（仅统计本文件，保持与原逻辑一致：count-1）
                int referenceCount = countFunctionReferences(content, functionName);

                Range range = new Range(
                        new Position(lineIndex, functionMatcher.start()),
                        new Position(lineIndex, functionMatcher.end())
                );

                Command command = new Command();
                command.setTitle(String.format("📊 %d references", referenceCount));
                command.setCommand("magic.showReferences");
                command.setArguments(Arrays.asList(uri, new Position(lineIndex, functionMatcher.start()), functionName));

                codeLenses.add(new CodeLens(range, command, functionName));
            }

            // 检测异步函数
            if (line.contains("async") && line.contains("function")) {
                Matcher asyncMatcher = Pattern.compile("async\\s+function\\s+(\\w+)").matcher(line);
                if (asyncMatcher.find()) {
                    Range range = new Range(
                            new Position(lineIndex, asyncMatcher.start()),
                            new Position(lineIndex, asyncMatcher.end())
                    );

                    Command command = new Command();
                    command.setTitle("⚡ Async Function");
                    command.setCommand("magic.showAsyncInfo");
                    command.setArguments(Arrays.asList(uri, asyncMatcher.group(1)));

                    codeLenses.add(new CodeLens(range, command, "async"));
                }
            }
        }

        return codeLenses;
    }

    private int countFunctionReferences(String content, String functionName) {
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(functionName) + "\\s*\\(");
        Matcher matcher = pattern.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return Math.max(0, count - 1);
    }

    /**
     * 从内容中生成“HTTP/SQL 相关”的 CodeLens。
     */
    public List<CodeLens> createApiCodeLenses(String content, String uri) {
        List<CodeLens> codeLenses = new ArrayList<>();
        String[] lines = content.split("\n");

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            if (line.contains("http.") || line.contains("request.")) {
                Pattern httpPattern = Pattern.compile("(http\\.(get|post|put|delete|patch)|request\\.(get|post|put|delete|patch))\\s*\\(");
                Matcher httpMatcher = httpPattern.matcher(line);
                if (httpMatcher.find()) {
                    String method = httpMatcher.group(2) != null ? httpMatcher.group(2) : httpMatcher.group(4);
                    Range range = new Range(
                            new Position(lineIndex, httpMatcher.start()),
                            new Position(lineIndex, httpMatcher.end() - 1)
                    );
                    Command command = new Command();
                    command.setTitle(String.format("🌐 %s Request", method.toUpperCase()));
                    command.setCommand("magic.showHttpInfo");
                    command.setArguments(Collections.unmodifiableList(Arrays.asList(uri, method, lineIndex)));
                    codeLenses.add(new CodeLens(range, command, "http"));
                }
            }

            if (line.contains("select ") || line.contains("SELECT ") ||
                    line.contains("insert ") || line.contains("INSERT ") ||
                    line.contains("update ") || line.contains("UPDATE ") ||
                    line.contains("delete ") || line.contains("DELETE ")) {
                Pattern sqlPattern = Pattern.compile("\\b(select|insert|update|delete)\\b", Pattern.CASE_INSENSITIVE);
                Matcher sqlMatcher = sqlPattern.matcher(line);
                if (sqlMatcher.find()) {
                    String sqlType = sqlMatcher.group(1).toUpperCase();
                    Range range = new Range(
                            new Position(lineIndex, sqlMatcher.start()),
                            new Position(lineIndex, sqlMatcher.end())
                    );
                    Command command = new Command();
                    command.setTitle(String.format("🗄️ %s Query", sqlType));
                    command.setCommand("magic.showSqlInfo");
                    command.setArguments(Collections.unmodifiableList(Arrays.asList(uri, sqlType, lineIndex)));
                    codeLenses.add(new CodeLens(range, command, "sql"));
                }
            }
        }
        return codeLenses;
    }

    /**
     * 从内容中生成“性能/警告相关”的 CodeLens。
     */
    public List<CodeLens> createPerformanceCodeLenses(String content, String uri) {
        List<CodeLens> codeLenses = new ArrayList<>();
        String[] lines = content.split("\n");
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            if (line.trim().startsWith("for") || line.trim().startsWith("while")) {
                Pattern loopPattern = Pattern.compile("\\b(for|while)\\b");
                Matcher loopMatcher = loopPattern.matcher(line);
                if (loopMatcher.find()) {
                    Range range = new Range(new Position(lineIndex, loopMatcher.start()), new Position(lineIndex, loopMatcher.end()));
                    String complexity = analyzeLoopComplexity(content, lineIndex);
                    Command command = new Command();
                    command.setTitle(String.format("🔄 %s", complexity));
                    command.setCommand("magic.showPerformanceInfo");
                    command.setArguments(Collections.unmodifiableList(Arrays.asList(uri, "loop", lineIndex, complexity)));
                    codeLenses.add(new CodeLens(range, command, "performance"));
                }
            }

            if (line.contains("sleep(") || line.contains("Thread.sleep")) {
                Pattern sleepPattern = Pattern.compile("(sleep|Thread\\.sleep)\\s*\\(");
                Matcher sleepMatcher = sleepPattern.matcher(line);
                if (sleepMatcher.find()) {
                    Range range = new Range(new Position(lineIndex, sleepMatcher.start()), new Position(lineIndex, sleepMatcher.end() - 1));
                    Command command = new Command();
                    command.setTitle("⚠️ Blocking Operation");
                    command.setCommand("magic.showPerformanceWarning");
                    command.setArguments(Collections.unmodifiableList(Arrays.asList(uri, "sleep", lineIndex)));
                    codeLenses.add(new CodeLens(range, command, "warning"));
                }
            }
        }
        return codeLenses;
    }

    private String analyzeLoopComplexity(String content, int loopLineIndex) {
        String[] lines = content.split("\n");
        int nestedLoops = 0;
        int braceLevel = 0;
        boolean inLoop = false;
        for (int i = loopLineIndex; i < lines.length; i++) {
            String line = lines[i].trim();
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    braceLevel++;
                    if (i == loopLineIndex) inLoop = true;
                } else if (c == '}') {
                    braceLevel--;
                    if (braceLevel == 0 && inLoop) {
                        break;
                    }
                }
            }
            if (inLoop && braceLevel > 1 && (line.startsWith("for") || line.startsWith("while"))) {
                nestedLoops++;
            }
            if (braceLevel == 0 && inLoop) {
                break;
            }
        }
        if (nestedLoops == 0) return "O(n) - Linear";
        if (nestedLoops == 1) return "O(n²) - Quadratic";
        return "O(n^" + (nestedLoops + 1) + ") - High Complexity";
    }
}