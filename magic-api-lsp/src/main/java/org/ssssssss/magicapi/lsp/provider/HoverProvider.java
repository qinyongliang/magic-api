package org.ssssssss.magicapi.lsp.provider;

import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.ssssssss.script.ScriptClass.ScriptMethod;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class HoverProvider {

    public Hover generateHover(String text,
                               Position position,
                               List<String> magicKeywords,
                               List<String> linqKeywords,
                               List<String> operators,
                               Map<String, List<ScriptMethod>> functionDetails,
                               Map<String, List<ScriptMethod>> classMethodDetails,
                               java.util.function.Function<String, String> keywordDesc,
                               java.util.function.Function<String, String> operatorDesc,
                               Set<String> moduleNames) {
        String word = getWordAtPosition(text, position);
        if (word == null || word.isEmpty()) {
            return null;
        }

        if (magicKeywords.contains(word)) {
            MarkupContent content = new MarkupContent();
            content.setKind(MarkupKind.MARKDOWN);
            content.setValue("**Magic Script Keyword**: `" + word + "`\n\n" + keywordDesc.apply(word));
            return new Hover(content);
        }

        if (linqKeywords.contains(word)) {
            MarkupContent content = new MarkupContent();
            content.setKind(MarkupKind.MARKDOWN);
            content.setValue("**Magic Script LINQ Keyword**: `" + word + "`\n\nLINQ查询关键字，用于数据查询和操作");
            return new Hover(content);
        }

        if (operators.contains(word)) {
            MarkupContent content = new MarkupContent();
            content.setKind(MarkupKind.MARKDOWN);
            content.setValue("**Magic Script Operator**: `" + word + "`\n\n" + operatorDesc.apply(word));
            return new Hover(content);
        }

        // 模块提示（来自 MagicResourceLoader 的模块名集合）
        if (moduleNames != null && moduleNames.contains(word)) {
            MarkupContent content = new MarkupContent();
            content.setKind(MarkupKind.MARKDOWN);
            content.setValue("**Magic Script Module**: `" + word + "`\n\n" +
                    "可在import语句中使用：`import " + word + " as alias`\n" +
                    "模块提供一组函数或资源，可通过别名调用。");
            return new Hover(content);
        }

        List<ScriptMethod> overloads = functionDetails.get(word);
        if (overloads != null && !overloads.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("**Magic Script Function**: `").append(word).append("`\n\n");
            for (ScriptMethod m : overloads) {
                sb.append("- ").append(formatMethodSignature(m)).append("\n");
                if (m.getComment() != null && !m.getComment().isEmpty()) {
                    sb.append("  ").append(m.getComment()).append("\n");
                }
            }
            MarkupContent content = new MarkupContent();
            content.setKind(MarkupKind.MARKDOWN);
            content.setValue(sb.toString());
            return new Hover(content);
        }

        for (Map.Entry<String, List<ScriptMethod>> entry : classMethodDetails.entrySet()) {
            List<ScriptMethod> ms = entry.getValue();
            boolean match = false;
            StringBuilder sb = new StringBuilder();
            for (ScriptMethod m : ms) {
                if (word.equals(m.getName())) {
                    if (!match) {
                        sb.append("**").append(entry.getKey()).append(" Method**: `").append(word).append("`\n\n");
                        match = true;
                    }
                    sb.append("- ").append(formatMethodSignature(m)).append("\n");
                    if (m.getComment() != null && !m.getComment().isEmpty()) {
                        sb.append("  ").append(m.getComment()).append("\n");
                    }
                }
            }
            if (match) {
                MarkupContent content = new MarkupContent();
                content.setKind(MarkupKind.MARKDOWN);
                content.setValue(sb.toString());
                return new Hover(content);
            }
        }

        return null;
    }

    private String formatMethodSignature(ScriptMethod method) {
        String params = method.getParameters().stream()
                .map(p -> {
                    String type = p.getType();
                    if (p.isVarArgs()) {
                        type = type + "...";
                    }
                    return type + " " + p.getName();
                })
                .collect(java.util.stream.Collectors.joining(", "));
        String sig = method.getName() + "(" + params + "): " + method.getReturnType();
        if (method.isDeprecated()) {
            sig += " [deprecated]";
        }
        return sig;
    }

    private String getWordAtPosition(String text, Position position) {
        String[] lines = text.split("\n");
        if (position.getLine() >= lines.length) {
            return null;
        }
        String line = lines[position.getLine()];
        int character = position.getCharacter();
        if (character >= line.length()) {
            return null;
        }
        int start = character;
        int end = character;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) {
            start--;
        }
        while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) {
            end++;
        }
        if (start < end) {
            return line.substring(start, end);
        }
        return null;
    }
}

