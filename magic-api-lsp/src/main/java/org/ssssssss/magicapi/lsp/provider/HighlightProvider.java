package org.ssssssss.magicapi.lsp.provider;

import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档高亮提供者
 */
public class HighlightProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(HighlightProvider.class);
    
    /**
     * 查找文档中符号的所有高亮位置
     */
    public List<DocumentHighlight> findSymbolHighlights(String content, String symbol) {
        List<DocumentHighlight> highlights = new ArrayList<>();
        String[] lines = content.split("\n");
        
        // 创建不同类型的符号匹配模式
        List<Pattern> patterns = createHighlightPatterns(symbol);
        
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(line);
                while (matcher.find()) {
                    // 确保是完整的符号匹配
                    if (isCompleteSymbolMatch(line, matcher.start(), matcher.end(), symbol)) {
                        Range range = new Range(
                            new Position(lineIndex, matcher.start()),
                            new Position(lineIndex, matcher.end())
                        );
                        
                        // 根据上下文确定高亮类型
                        DocumentHighlightKind kind = determineHighlightKind(line, matcher.start(), symbol);
                        highlights.add(new DocumentHighlight(range, kind));
                    }
                }
            }
        }
        
        return highlights;
    }
    
    /**
     * 创建符号高亮的匹配模式
     */
    private List<Pattern> createHighlightPatterns(String symbol) {
        List<Pattern> patterns = new ArrayList<>();
        String escapedSymbol = Pattern.quote(symbol);
        
        // 基本符号匹配
        patterns.add(Pattern.compile("\\b" + escapedSymbol + "\\b"));
        
        // 函数调用匹配
        patterns.add(Pattern.compile("\\b" + escapedSymbol + "\\s*\\("));
        
        // 属性访问匹配
        patterns.add(Pattern.compile("\\." + escapedSymbol + "\\b"));
        
        // 赋值匹配
        patterns.add(Pattern.compile("\\b" + escapedSymbol + "\\s*="));
        
        // 参数匹配
        patterns.add(Pattern.compile("\\(\\s*" + escapedSymbol + "\\s*[,)]"));
        patterns.add(Pattern.compile("[,]\\s*" + escapedSymbol + "\\s*[,)]"));
        
        // 数组/对象访问匹配
        patterns.add(Pattern.compile("\\[\\s*['\"]?" + escapedSymbol + "['\"]?\\s*\\]"));
        
        // 字符串模板匹配
        patterns.add(Pattern.compile("\\$\\{[^}]*\\b" + escapedSymbol + "\\b[^}]*\\}"));
        
        return patterns;
    }
    
    /**
     * 根据上下文确定高亮类型
     */
    private DocumentHighlightKind determineHighlightKind(String line, int startPos, String symbol) {
        String beforeSymbol = line.substring(0, startPos).trim();
        String afterSymbol = line.substring(startPos + symbol.length()).trim();
        
        // 检查是否为写操作（赋值）
        if (afterSymbol.startsWith("=") && !afterSymbol.startsWith("==") && !afterSymbol.startsWith("!=")) {
            return DocumentHighlightKind.Write;
        }
        
        // 检查是否为变量声明
        if (beforeSymbol.endsWith("var") || beforeSymbol.endsWith("let") || beforeSymbol.endsWith("const")) {
            return DocumentHighlightKind.Write;
        }
        
        // 检查是否为函数参数声明
        if (beforeSymbol.contains("function") && (beforeSymbol.endsWith("(") || beforeSymbol.endsWith(","))) {
            return DocumentHighlightKind.Write;
        }
        
        // 检查是否为读操作
        if (afterSymbol.startsWith("(") || afterSymbol.startsWith(".") || 
            beforeSymbol.endsWith(".") || beforeSymbol.endsWith("return")) {
            return DocumentHighlightKind.Read;
        }
        
        // 默认为文本高亮
        return DocumentHighlightKind.Text;
    }
    
    /**
     * 检查是否为完整的符号匹配
     */
    private boolean isCompleteSymbolMatch(String line, int start, int end, String symbol) {
        // 检查匹配的文本是否确实是目标符号
        String matched = line.substring(start, end);
        if (!matched.equals(symbol)) {
            return false;
        }
        
        // 检查前后字符，确保是完整的标识符边界
        char prevChar = start > 0 ? line.charAt(start - 1) : ' ';
        char nextChar = end < line.length() ? line.charAt(end) : ' ';
        
        boolean prevIsIdentifierChar = Character.isLetterOrDigit(prevChar) || prevChar == '_' || prevChar == '$';
        boolean nextIsIdentifierChar = Character.isLetterOrDigit(nextChar) || nextChar == '_' || nextChar == '$';
        
        return !prevIsIdentifierChar && !nextIsIdentifierChar;
    }
}
