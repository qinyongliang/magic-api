package org.ssssssss.magicapi.lsp.provider;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provider for Magic Script formatting logic.
 */
public class FormattingProvider {

    private static final Logger logger = LoggerFactory.getLogger(FormattingProvider.class);

    public List<TextEdit> formatDocument(String content, FormattingOptions options) {
        try {
            if (content == null) {
                return Collections.emptyList();
            }

            String formattedContent = formatMagicScript(content, options);
            if (!content.equals(formattedContent)) {
                String[] lines = content.split("\n");
                Range range = new Range(
                    new Position(0, 0),
                    new Position(lines.length - 1, lines[lines.length - 1].length())
                );
                return Collections.singletonList(new TextEdit(range, formattedContent));
            }
            return Collections.emptyList();
        } catch (Exception e) {
            logger.error("Error during document formatting", e);
            return Collections.emptyList();
        }
    }

    public List<TextEdit> formatRange(String content, Range range, FormattingOptions options) {
        try {
            if (content == null || range == null) {
                return Collections.emptyList();
            }

            String[] lines = content.split("\n");
            StringBuilder rangeContent = new StringBuilder();

            int startLine = range.getStart().getLine();
            int endLine = range.getEnd().getLine();
            int startChar = range.getStart().getCharacter();
            int endChar = range.getEnd().getCharacter();

            for (int i = startLine; i <= endLine && i < lines.length; i++) {
                String line = lines[i];
                if (i == startLine && i == endLine) {
                    if (startChar < line.length() && endChar <= line.length()) {
                        rangeContent.append(line.substring(startChar, endChar));
                    }
                } else if (i == startLine) {
                    if (startChar < line.length()) {
                        rangeContent.append(line.substring(startChar));
                    }
                    rangeContent.append("\n");
                } else if (i == endLine) {
                    if (endChar <= line.length()) {
                        rangeContent.append(line.substring(0, endChar));
                    } else {
                        rangeContent.append(line);
                    }
                } else {
                    rangeContent.append(line);
                    if (i < endLine) {
                        rangeContent.append("\n");
                    }
                }
            }

            String originalRangeContent = rangeContent.toString();
            String formattedRangeContent = formatMagicScript(originalRangeContent, options);

            if (!originalRangeContent.equals(formattedRangeContent)) {
                return Collections.singletonList(new TextEdit(range, formattedRangeContent));
            }

            return Collections.emptyList();
        } catch (Exception e) {
            logger.error("Error during range formatting", e);
            return Collections.emptyList();
        }
    }

    public List<TextEdit> formatOnType(String content, Position position, String ch, FormattingOptions options) {
        try {
            if (content == null || position == null || ch == null) {
                return Collections.emptyList();
            }

            List<TextEdit> edits = new ArrayList<>();
            switch (ch) {
                case "}":
                    edits.addAll(formatOnCloseBrace(content, position, options));
                    break;
                case ";":
                    edits.addAll(formatOnSemicolon(content, position, options));
                    break;
                case "\n":
                    edits.addAll(formatOnNewline(content, position, options));
                    break;
                case ")":
                    edits.addAll(formatOnCloseParen(content, position, options));
                    break;
                default:
                    break;
            }
            return edits;
        } catch (Exception e) {
            logger.error("Error during on-type formatting", e);
            return Collections.emptyList();
        }
    }

    // ==================== 代码格式化相关方法 ====================

    private String formatMagicScript(String content, FormattingOptions options) {
        if (content == null || content.trim().isEmpty()) {
            return content;
        }

        try {
            String[] lines = content.split("\n");
            List<String> formattedLines = new ArrayList<>();

            int indentLevel = 0;
            boolean inBlockComment = false;
            boolean inStringLiteral = false;
            char stringDelimiter = 0;

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String trimmedLine = line.trim();

                if (trimmedLine.isEmpty()) {
                    formattedLines.add("");
                    continue;
                }

                updateParsingState(trimmedLine, inBlockComment, inStringLiteral, stringDelimiter);

                if (inBlockComment || inStringLiteral) {
                    formattedLines.add(line);
                    continue;
                }

                int currentIndentLevel = calculateIndentLevel(trimmedLine, indentLevel);

                String formattedLine = formatLine(trimmedLine, currentIndentLevel, options);
                formattedLines.add(formattedLine);

                indentLevel = updateIndentLevel(trimmedLine, currentIndentLevel);
            }

            String result = String.join("\n", formattedLines);
            result = applyFormattingOptions(result, options);
            return result;
        } catch (Exception e) {
            logger.error("Error formatting Magic Script", e);
            return content;
        }
    }

    private void updateParsingState(String line, boolean inBlockComment, boolean inStringLiteral, char stringDelimiter) {
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            char nextCh = (i + 1 < line.length()) ? line.charAt(i + 1) : 0;

            if (!inStringLiteral) {
                if (ch == '/' && nextCh == '*') {
                    inBlockComment = true;
                    i++;
                    continue;
                }

                if (inBlockComment && ch == '*' && nextCh == '/') {
                    inBlockComment = false;
                    i++;
                    continue;
                }

                if (ch == '/' && nextCh == '/') {
                    break;
                }

                if (!inBlockComment && (ch == '"' || ch == '\'' || ch == '`')) {
                    inStringLiteral = true;
                    stringDelimiter = ch;
                    continue;
                }
            } else {
                if (ch == stringDelimiter && (i == 0 || line.charAt(i - 1) != '\\')) {
                    inStringLiteral = false;
                    stringDelimiter = 0;
                }
            }
        }
    }

    private int calculateIndentLevel(String trimmedLine, int currentIndentLevel) {
        if (trimmedLine.startsWith("}") || trimmedLine.startsWith("]") ||
            trimmedLine.startsWith(")") || trimmedLine.startsWith("case ") ||
            trimmedLine.startsWith("default:") || trimmedLine.startsWith("else") ||
            trimmedLine.startsWith("catch") || trimmedLine.startsWith("finally")) {
            return Math.max(0, currentIndentLevel - 1);
        }
        return currentIndentLevel;
    }

    private int updateIndentLevel(String trimmedLine, int currentIndentLevel) {
        if (trimmedLine.endsWith("{") || trimmedLine.endsWith("[") ||
            trimmedLine.startsWith("if ") || trimmedLine.startsWith("for ") ||
            trimmedLine.startsWith("while ") || trimmedLine.startsWith("switch ") ||
            trimmedLine.startsWith("try") || trimmedLine.startsWith("catch") ||
            trimmedLine.startsWith("finally") || trimmedLine.startsWith("else") ||
            trimmedLine.startsWith("case ") || trimmedLine.startsWith("default:") ||
            trimmedLine.startsWith("function ")) {
            return currentIndentLevel + 1;
        }
        return currentIndentLevel;
    }

    private String formatLine(String line, int indentLevel, FormattingOptions options) {
        StringBuilder formatted = new StringBuilder();
        String indent = createIndent(indentLevel, options);
        formatted.append(indent);

        line = formatOperators(line);
        line = formatCommas(line);
        line = formatSemicolons(line);
        line = formatBraces(line);
        line = formatKeywords(line);

        formatted.append(line);
        return formatted.toString();
    }

    private String createIndent(int level, FormattingOptions options) {
        if (level <= 0) {
            return "";
        }
        if (options.isInsertSpaces()) {
            int tabSize = options.getTabSize();
            return StringUtils.repeat(" ", level * tabSize);
        } else {
            return StringUtils.repeat("\t", level);
        }
    }

    private String formatOperators(String line) {
        line = line.replaceAll("\\s*([+\\-*/%=!<>]+)\\s*", " $1 ");
        line = line.replaceAll("\\s+([+\\-*/%=!<>])\\s+([+\\-*/%=!<>])\\s+", " $1$2 ");
        line = line.replaceAll("\\s*\\+\\s*\\+", "++");
        line = line.replaceAll("\\s*-\\s*-", "--");
        line = line.replaceAll("\\s*=\\s*=", "==");
        line = line.replaceAll("\\s*!\\s*=", "!=");
        line = line.replaceAll("\\s*<\\s*=", "<=");
        line = line.replaceAll("\\s*>\\s*=", ">=");
        line = line.replaceAll("\\s*&\\s*&", "&&");
        line = line.replaceAll("\\s*\\|\\s*\\|", "||");
        return line;
    }

    private String formatCommas(String line) {
        return line.replaceAll(",\\s*", ", ");
    }

    private String formatSemicolons(String line) {
        return line.replaceAll(";\\s+", "; ");
    }

    private String formatBraces(String line) {
        line = line.replaceAll("\\s*\\{", " {");
        if (!line.trim().startsWith("}")) {
            line = line.replaceAll("\\s*\\}", "}");
        }
        return line;
    }

    private String formatKeywords(String line) {
        String[] keywords = {"if", "for", "while", "switch", "catch", "function", "return", "var", "let", "const"};
        for (String keyword : keywords) {
            line = line.replaceAll("\\b" + keyword + "\\s*\\(", keyword + " (");
            line = line.replaceAll("\\b" + keyword + "\\s+", keyword + " ");
        }
        return line;
    }

    private String applyFormattingOptions(String content, FormattingOptions options) {
        if (options.isTrimTrailingWhitespace()) {
            content = content.replaceAll("[ \t]+$", "");
        }
        if (options.isInsertFinalNewline() && !content.endsWith("\n")) {
            content += "\n";
        }
        return content;
    }

    private List<TextEdit> formatOnCloseBrace(String content, Position position, FormattingOptions options) {
        List<TextEdit> edits = new ArrayList<>();
        try {
            String[] lines = content.split("\n");
            int lineIndex = position.getLine();
            if (lineIndex >= 0 && lineIndex < lines.length) {
                String line = lines[lineIndex];
                int braceStart = findMatchingOpenBrace(lines, lineIndex, position.getCharacter());
                if (braceStart >= 0) {
                    StringBuilder blockContent = new StringBuilder();
                    for (int i = braceStart; i <= lineIndex; i++) {
                        blockContent.append(lines[i]);
                        if (i < lineIndex) {
                            blockContent.append("\n");
                        }
                    }
                    String formattedBlock = formatMagicScript(blockContent.toString(), options);
                    Range range = new Range(new Position(braceStart, 0), new Position(lineIndex, lines[lineIndex].length()));
                    edits.add(new TextEdit(range, formattedBlock));
                }
            }
        } catch (Exception e) {
            logger.error("Error formatting on close brace", e);
        }
        return edits;
    }

    private List<TextEdit> formatOnSemicolon(String content, Position position, FormattingOptions options) {
        List<TextEdit> edits = new ArrayList<>();
        try {
            String[] lines = content.split("\n");
            int lineIndex = position.getLine();
            if (lineIndex >= 0 && lineIndex < lines.length) {
                String line = lines[lineIndex];
                String formattedLine = formatLine(line.trim(), 0, options);
                int indentLevel = calculateLineIndent(lines, lineIndex);
                String indent = createIndent(indentLevel, options);
                formattedLine = indent + formattedLine.trim();
                if (!line.equals(formattedLine)) {
                    Range range = new Range(new Position(lineIndex, 0), new Position(lineIndex, line.length()));
                    edits.add(new TextEdit(range, formattedLine));
                }
            }
        } catch (Exception e) {
            logger.error("Error formatting on semicolon", e);
        }
        return edits;
    }

    private List<TextEdit> formatOnNewline(String content, Position position, FormattingOptions options) {
        List<TextEdit> edits = new ArrayList<>();
        try {
            String[] lines = content.split("\n");
            int lineIndex = position.getLine();
            if (lineIndex > 0 && lineIndex < lines.length) {
                int indentLevel = calculateLineIndent(lines, lineIndex);
                String indent = createIndent(indentLevel, options);
                String currentLine = lines[lineIndex];
                if (!currentLine.startsWith(indent)) {
                    String newLine = indent + currentLine.trim();
                    Range range = new Range(new Position(lineIndex, 0), new Position(lineIndex, currentLine.length()));
                    edits.add(new TextEdit(range, newLine));
                }
            }
        } catch (Exception e) {
            logger.error("Error formatting on newline", e);
        }
        return edits;
    }

    private List<TextEdit> formatOnCloseParen(String content, Position position, FormattingOptions options) {
        List<TextEdit> edits = new ArrayList<>();
        try {
            String[] lines = content.split("\n");
            int lineIndex = position.getLine();
            if (lineIndex >= 0 && lineIndex < lines.length) {
                String line = lines[lineIndex];
                String formattedLine = formatFunctionCall(line);
                if (!line.equals(formattedLine)) {
                    Range range = new Range(new Position(lineIndex, 0), new Position(lineIndex, line.length()));
                    edits.add(new TextEdit(range, formattedLine));
                }
            }
        } catch (Exception e) {
            logger.error("Error formatting on close paren", e);
        }
        return edits;
    }

    private int findMatchingOpenBrace(String[] lines, int endLine, int endChar) {
        int braceCount = 1;
        for (int i = endLine; i >= 0; i--) {
            String line = lines[i];
            int startPos = (i == endLine) ? endChar - 1 : line.length() - 1;
            for (int j = startPos; j >= 0; j--) {
                char ch = line.charAt(j);
                if (ch == '}') {
                    braceCount++;
                } else if (ch == '{') {
                    braceCount--;
                    if (braceCount == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private int calculateLineIndent(String[] lines, int lineIndex) {
        int indentLevel = 0;
        for (int i = 0; i < lineIndex; i++) {
            String line = lines[i].trim();
            if (line.endsWith("{") || line.endsWith("[") ||
                line.startsWith("if ") || line.startsWith("for ") ||
                line.startsWith("while ") || line.startsWith("switch ") ||
                line.startsWith("try") || line.startsWith("catch") ||
                line.startsWith("finally") || line.startsWith("else") ||
                line.startsWith("case ") || line.startsWith("default:") ||
                line.startsWith("function ")) {
                indentLevel++;
            }
            if (line.startsWith("}") || line.startsWith("]") || line.startsWith(")")) {
                indentLevel = Math.max(0, indentLevel - 1);
            }
        }
        return indentLevel;
    }

    private String formatFunctionCall(String line) {
        return line.replaceAll("\\(\\s*", "(")
                .replaceAll("\\s*\\)", ")")
                .replaceAll(",\\s*", ", ");
    }
}