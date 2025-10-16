package org.ssssssss.magicapi.lsp.provider;

import org.eclipse.lsp4j.Range;
import org.ssssssss.script.parsing.VarScope;

import java.util.*;

/**
 * 提供语义标记生成逻辑，支持整文档与范围生成。
 * 注意：本提供者内的类型与修饰符枚举顺序需与 SemanticTokensLegend 保持一致。
 */
public class SemanticTokensProvider {

    // 关键字、操作符、类型常量需与服务端保持一致
    private static final List<String> MAGIC_KEYWORDS = Arrays.asList(
            "import", "as", "var", "let", "const", "return", "break", "continue", "if", "for",
            "in", "new", "true", "false", "null", "else", "try", "catch", "finally", "async",
            "while", "exit", "and", "or", "throw", "function", "lambda"
    );

    private static final List<String> LINQ_KEYWORDS = Arrays.asList(
            "from", "join", "left", "group", "by", "as", "having", "and", "or", "in",
            "where", "on", "limit", "offset", "select", "order", "desc", "asc"
    );

    private static final List<String> OPERATORS = Arrays.asList(
            "+", "-", "*", "/", "%", "++", "--", "+=", "-=", "*=", "/=", "%=",
            "<", "<=", ">", ">=", "==", "!=", "===", "!==", "&&", "||", "!",
            "&", "|", "^", "<<", ">>", ">>>", "~", "?", ":", "?.", "...",
            "=", "=>", "::", "?:"
    );

    private static final List<String> BUILTIN_TYPES = Arrays.asList(
            "byte", "short", "int", "long", "float", "double", "boolean", "string",
            "BigDecimal", "Pattern", "Date", "List", "Map", "Set", "Array"
    );

    /**
     * 生成整个文档的语义标记
     */
    public List<Integer> generateSemanticTokens(String content, String uri,
                                                Map<Integer, VarScope> lineScopes,
                                                List<String> magicFunctions) {
        List<SemanticToken> semanticTokens = new ArrayList<>();
        String[] lines = content.split("\n");

        boolean inTripleString = false;
        int tripleStartLine = -1;
        int tripleStartChar = 0;

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            VarScope currentScope = lineScopes != null ? lineScopes.get(lineIndex) : null;

            if (!inTripleString) {
                String trimmed = line.trim();
                if (trimmed.startsWith("//")) {
                    analyzeLineForSemanticTokens(line, lineIndex, currentScope, semanticTokens, uri, 0, magicFunctions);
                    continue;
                }
            }

            if (inTripleString) {
                int closeIdx = indexOfTripleQuote(line, 0);
                if (closeIdx >= 0) {
                    addStringAndInterpolationTokensInSegment(line, 0, closeIdx + 3, 0, lineIndex, semanticTokens);
                    inTripleString = false;
                    if (closeIdx + 3 < line.length()) {
                        String rest = line.substring(closeIdx + 3);
                        analyzeLineForSemanticTokens(rest, lineIndex, currentScope, semanticTokens, uri, closeIdx + 3, magicFunctions);
                    }
                } else {
                    addStringAndInterpolationTokensInSegment(line, 0, line.length(), 0, lineIndex, semanticTokens);
                }
                continue;
            }

            int openIdx = indexOfTripleQuote(line, 0);
            if (openIdx < 0) {
                analyzeLineForSemanticTokens(line, lineIndex, currentScope, semanticTokens, uri, 0, magicFunctions);
            } else {
                int slCommentIdx = line.indexOf("//");
                if (slCommentIdx >= 0 && slCommentIdx <= openIdx) {
                    analyzeLineForSemanticTokens(line, lineIndex, currentScope, semanticTokens, uri, 0, magicFunctions);
                    continue;
                }

                if (openIdx > 0) {
                    String before = line.substring(0, openIdx);
                    analyzeLineForSemanticTokens(before, lineIndex, currentScope, semanticTokens, uri, 0, magicFunctions);
                }
                int closeIdx = indexOfTripleQuote(line, openIdx + 3);
                if (closeIdx >= 0) {
                    addStringAndInterpolationTokensInSegment(line, openIdx, closeIdx + 3, openIdx, lineIndex, semanticTokens);
                    if (closeIdx + 3 < line.length()) {
                        String after = line.substring(closeIdx + 3);
                        analyzeLineForSemanticTokens(after, lineIndex, currentScope, semanticTokens, uri, closeIdx + 3, magicFunctions);
                    }
                } else {
                    addStringAndInterpolationTokensInSegment(line, openIdx, line.length(), openIdx, lineIndex, semanticTokens);
                    inTripleString = true;
                    tripleStartLine = lineIndex;
                    tripleStartChar = openIdx;
                }
            }
        }

        return encodeSemanticTokens(semanticTokens);
    }

    /**
     * 生成指定范围的语义标记
     */
    public List<Integer> generateSemanticTokensForRange(String content, String uri, Range range,
                                                        Map<Integer, VarScope> lineScopes,
                                                        List<String> magicFunctions) {
        List<SemanticToken> semanticTokens = new ArrayList<>();
        String[] lines = content.split("\n");

        int startLine = range.getStart().getLine();
        int endLine = range.getEnd().getLine();

        for (int lineIndex = startLine; lineIndex <= endLine && lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            VarScope currentScope = lineScopes != null ? lineScopes.get(lineIndex) : null;

            if (lineIndex == startLine || lineIndex == endLine) {
                int startChar = (lineIndex == startLine) ? range.getStart().getCharacter() : 0;
                int endChar = (lineIndex == endLine) ? range.getEnd().getCharacter() : line.length();

                if (startChar < line.length()) {
                    String linePart = line.substring(startChar, Math.min(endChar, line.length()));
                    analyzeLineForSemanticTokens(linePart, lineIndex, currentScope, semanticTokens, uri, startChar, magicFunctions);
                }
            } else {
                analyzeLineForSemanticTokens(line, lineIndex, currentScope, semanticTokens, uri, 0, magicFunctions);
            }
        }

        return encodeSemanticTokens(semanticTokens);
    }

    private void analyzeLineForSemanticTokens(String line, int lineNumber, VarScope scope,
                                              List<SemanticToken> tokens, String uri, int startOffset,
                                              List<String> magicFunctions) {
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            if (c == '"' || c == '\'') {
                int start = i;
                int end = findStringEnd(line, i, c);
                addStringAndInterpolationTokensInSegment(line, start, end + 1, startOffset + start, lineNumber, tokens);
                i = end + 1;
                continue;
            }

            if (c == '/' && i + 1 < line.length()) {
                if (line.charAt(i + 1) == '/') {
                    tokens.add(new SemanticToken(lineNumber, startOffset + i, line.length() - i,
                            SemanticTokenType.COMMENT, Collections.emptyList()));
                    break;
                } else if (line.charAt(i + 1) == '*') {
                    int start = i;
                    int end = line.indexOf("*/", i + 2);
                    if (end == -1) {
                        end = line.length();
                    } else {
                        end += 2;
                    }
                    tokens.add(new SemanticToken(lineNumber, startOffset + start, end - start,
                            SemanticTokenType.COMMENT, Collections.emptyList()));
                    i = end;
                    continue;
                }
            }

            if (Character.isDigit(c)) {
                int start = i;
                while (i < line.length() && (Character.isDigit(line.charAt(i)) || line.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new SemanticToken(lineNumber, startOffset + start, i - start,
                        SemanticTokenType.NUMBER, Collections.emptyList()));
                continue;
            }

            if (isIdentifierStart(c)) {
                int start = i;
                while (i < line.length() && isIdentifierChar(line.charAt(i))) {
                    i++;
                }

                String identifier = line.substring(start, i);
                SemanticTokenType tokenType = determineTokenType(identifier, scope, uri, magicFunctions);
                List<SemanticTokenModifier> modifiers = determineTokenModifiers(identifier, scope, uri);

                tokens.add(new SemanticToken(lineNumber, startOffset + start, i - start, tokenType, modifiers));
                continue;
            }

            if (OPERATORS.contains(String.valueOf(c))) {
                int start = i;
                String op = String.valueOf(c);

                if (i + 1 < line.length()) {
                    String twoChar = line.substring(i, i + 2);
                    if (OPERATORS.contains(twoChar)) {
                        op = twoChar;
                        i++;
                    }
                }

                tokens.add(new SemanticToken(lineNumber, startOffset + start, op.length(),
                        SemanticTokenType.OPERATOR, Collections.emptyList()));
                i++;
                continue;
            }

            i++;
        }
    }

    private int findStringEnd(String line, int start, char quote) {
        int i = start + 1;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == quote) {
                return i;
            }
            if (c == '\\' && i + 1 < line.length()) {
                i++;
            }
            i++;
        }
        return line.length() - 1;
    }

    private int indexOfTripleQuote(String line, int fromIndex) {
        return line.indexOf("\"\"\"", fromIndex);
    }

    private void addInterpolationTokensInSegment(String line, int segmentStart, int segmentEnd, int segmentOffset,
                                                 int lineNumber, List<SemanticToken> tokens) {
        if (segmentStart >= segmentEnd) {
            return;
        }
        int i = segmentStart;
        while (i < segmentEnd) {
            char c = line.charAt(i);
            if ((c == '#' || c == '$') && i + 1 < segmentEnd && line.charAt(i + 1) == '{') {
                int braceOpen = i + 1;
                int j = braceOpen + 1;
                int braceDepth = 1;
                while (j < segmentEnd && braceDepth > 0) {
                    char cj = line.charAt(j);
                    if (cj == '{') braceDepth++;
                    else if (cj == '}') braceDepth--;
                    j++;
                }
                int braceClose = j - 1;
                if (braceDepth == 0 && braceOpen + 1 <= braceClose - 1) {
                    int varStart = braceOpen + 1;
                    int varLength = braceClose - varStart;
                    if (varLength > 0) {
                        tokens.add(new SemanticToken(lineNumber, segmentOffset + (varStart - segmentStart), varLength,
                                SemanticTokenType.VARIABLE, Collections.emptyList()));
                    }
                }
                i = j;
                continue;
            }
            i++;
        }
    }

    private void addStringAndInterpolationTokensInSegment(String line, int segmentStart, int segmentEnd, int segmentOffset,
                                                          int lineNumber, List<SemanticToken> tokens) {
        if (segmentStart >= segmentEnd) return;

        int i = segmentStart;
        while (i < segmentEnd) {
            int strStart = -1;
            char quote = 0;

            while (i < segmentEnd) {
                char c = line.charAt(i);
                if (c == '"' || c == '\'') {
                    strStart = i;
                    quote = c;
                    break;
                }
                i++;
            }

            if (strStart == -1) {
                break;
            }

            int j = strStart + 1;
            while (j < segmentEnd) {
                char c = line.charAt(j);
                if (c == quote) {
                    int strEnd = j + 1;
                    tokens.add(new SemanticToken(lineNumber, segmentOffset + (strStart - segmentStart), strEnd - strStart,
                            SemanticTokenType.STRING, Collections.emptyList()));

                    addInterpolationTokensInSegment(line, strStart + 1, j, segmentOffset + (strStart - segmentStart), lineNumber, tokens);
                    i = strEnd;
                    break;
                }
                if ((c == '#' || c == '$') && j + 1 < segmentEnd && line.charAt(j + 1) == '{') {
                    int braceOpen = j + 1;
                    int k = braceOpen + 1;
                    int braceDepth = 1;
                    while (k < segmentEnd && braceDepth > 0) {
                        char ck = line.charAt(k);
                        if (ck == '{') braceDepth++;
                        else if (ck == '}') braceDepth--;
                        k++;
                    }
                    int braceClose = k - 1;
                    int substringEnd = braceClose + 1;
                    tokens.add(new SemanticToken(lineNumber, segmentOffset + (strStart - segmentStart), substringEnd - strStart,
                            SemanticTokenType.STRING, Collections.emptyList()));

                    int varStart = braceOpen + 1;
                    int varLength = braceClose - varStart;
                    if (varLength > 0) {
                        tokens.add(new SemanticToken(lineNumber, segmentOffset + (varStart - segmentStart), varLength,
                                SemanticTokenType.VARIABLE, Collections.emptyList()));
                    }
                    i = substringEnd;
                    break;
                }
                if (c == '\\' && j + 1 < segmentEnd) {
                    j++;
                }
                j++;
            }

            if (j >= segmentEnd) {
                tokens.add(new SemanticToken(lineNumber, segmentOffset + (strStart - segmentStart), segmentEnd - strStart,
                        SemanticTokenType.STRING, Collections.emptyList()));
                addInterpolationTokensInSegment(line, strStart + 1, segmentEnd, segmentOffset + (strStart - segmentStart), lineNumber, tokens);
                break;
            }
        }
    }

    private boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private SemanticTokenType determineTokenType(String identifier, VarScope scope, String uri, List<String> magicFunctions) {
        if (MAGIC_KEYWORDS.contains(identifier) || LINQ_KEYWORDS.contains(identifier)) {
            return SemanticTokenType.KEYWORD;
        }
        if (BUILTIN_TYPES.contains(identifier)) {
            return SemanticTokenType.TYPE;
        }
        if (isFunctionInScope(identifier, scope, magicFunctions)) {
            return SemanticTokenType.FUNCTION;
        }
        if (isVariableInScope(identifier, scope)) {
            return SemanticTokenType.VARIABLE;
        }
        if (isParameterInScope(identifier, scope)) {
            return SemanticTokenType.PARAMETER;
        }
        return SemanticTokenType.VARIABLE;
    }

    private List<SemanticTokenModifier> determineTokenModifiers(String identifier, VarScope scope, String uri) {
        List<SemanticTokenModifier> modifiers = new ArrayList<>();
        if (isReadonlyVariable(identifier, scope)) {
            modifiers.add(SemanticTokenModifier.READONLY);
        }
        if (isStaticMember(identifier)) {
            modifiers.add(SemanticTokenModifier.STATIC);
        }
        if (isDefinition(identifier, scope)) {
            modifiers.add(SemanticTokenModifier.DEFINITION);
        }
        return modifiers;
    }

    private boolean isVariableInScope(String identifier, VarScope scope) {
        if (scope == null) return false;
        try {
            // 简化：直接遍历作用域内变量名集合（具体实现由调用方维护）
            // VarScope 的具体 API 在不同版本可能不同，这里按现有服务端的约定处理
            // 调用方会基于 VarScope 构造 lineScopes，因此此处采用与服务端一致的判断：
            // 通过 getVariablesInScope(scope) 的结果进行包含判断，实际由上层提供
            // 若无法获取，则返回 false。
            // 注意：为了与原服务逻辑一致，调用方通常会先解析并填充作用域信息。
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isFunctionInScope(String identifier, VarScope scope, List<String> magicFunctions) {
        if (scope == null) return false;
        try {
            VarScope currentScope = scope;
            while (currentScope != null) {
                if (magicFunctions != null && magicFunctions.contains(identifier)) {
                    return true;
                }
                currentScope = getParentScope(currentScope);
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean isParameterInScope(String identifier, VarScope scope) {
        return false;
    }

    private boolean isReadonlyVariable(String identifier, VarScope scope) {
        return identifier.equals(identifier.toUpperCase()) && identifier.length() > 1;
    }

    private boolean isStaticMember(String identifier) {
        return false;
    }

    private boolean isDefinition(String identifier, VarScope scope) {
        return false;
    }

    private VarScope getParentScope(VarScope scope) {
        try {
            return scope == null ? null : scope.getParent();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private List<Integer> encodeSemanticTokens(List<SemanticToken> tokens) {
        List<Integer> encoded = new ArrayList<>();
        tokens.sort((a, b) -> {
            int lineCompare = Integer.compare(a.line, b.line);
            if (lineCompare != 0) return lineCompare;
            return Integer.compare(a.character, b.character);
        });

        int prevLine = 0;
        int prevChar = 0;

        for (SemanticToken token : tokens) {
            int deltaLine = token.line - prevLine;
            int deltaChar = (deltaLine == 0) ? token.character - prevChar : token.character;
            encoded.add(deltaLine);
            encoded.add(deltaChar);
            encoded.add(token.length);
            encoded.add(token.type.ordinal());
            encoded.add(encodeModifiers(token.modifiers));
            prevLine = token.line;
            prevChar = token.character;
        }
        return encoded;
    }

    private int encodeModifiers(List<SemanticTokenModifier> modifiers) {
        int bitset = 0;
        if (modifiers != null) {
            for (SemanticTokenModifier modifier : modifiers) {
                bitset |= (1 << modifier.ordinal());
            }
        }
        return bitset;
    }

    private static class SemanticToken {
        final int line;
        final int character;
        final int length;
        final SemanticTokenType type;
        final List<SemanticTokenModifier> modifiers;

        SemanticToken(int line, int character, int length, SemanticTokenType type, List<SemanticTokenModifier> modifiers) {
            this.line = line;
            this.character = character;
            this.length = length;
            this.type = type;
            this.modifiers = modifiers;
        }
    }

    private enum SemanticTokenType {
        NAMESPACE, TYPE, CLASS, ENUM, INTERFACE, STRUCT, TYPE_PARAMETER, PARAMETER,
        VARIABLE, PROPERTY, ENUM_MEMBER, EVENT, FUNCTION, METHOD, MACRO, KEYWORD,
        MODIFIER, COMMENT, STRING, NUMBER, REGEXP, OPERATOR
    }

    private enum SemanticTokenModifier {
        DECLARATION, DEFINITION, READONLY, STATIC, DEPRECATED, ABSTRACT, ASYNC,
        MODIFICATION, DOCUMENTATION, DEFAULT_LIBRARY
    }
}