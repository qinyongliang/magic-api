package org.ssssssss.magicapi.lsp.provider;

import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 代码折叠提供者
 */
public class FoldingProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(FoldingProvider.class);
    
    /**
     * 创建折叠范围
     */
    public List<FoldingRange> createFoldingRanges(String content) {
        try {
            if (content == null || content.trim().isEmpty()) {
                return Collections.emptyList();
            }
            
            List<FoldingRange> foldingRanges = new ArrayList<>();
            String[] lines = content.split("\n");
            
            // 查找注释块
            findCommentFoldingRanges(lines, foldingRanges);
            
            // 查找函数定义
            findFunctionFoldingRanges(lines, foldingRanges);
            
            // 查找对象字面量
            findObjectFoldingRanges(lines, foldingRanges);
            
            // 查找数组字面量
            findArrayFoldingRanges(lines, foldingRanges);
            
            // 查找控制结构（if, for, while等）
            findControlStructureFoldingRanges(lines, foldingRanges);
            
            // 查找try-catch块
            findTryCatchFoldingRanges(lines, foldingRanges);
            
            return foldingRanges;
        } catch (Exception e) {
            logger.error("Error creating folding ranges", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 查找注释块的折叠范围
     */
    private void findCommentFoldingRanges(String[] lines, List<FoldingRange> foldingRanges) {
        int commentStart = -1;
        boolean inBlockComment = false;
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            
            // 处理块注释 /* */
            if (line.contains("/*") && !inBlockComment) {
                commentStart = i;
                inBlockComment = true;
                // 如果在同一行结束
                if (line.contains("*/")) {
                    inBlockComment = false;
                    if (commentStart != i) {
                        foldingRanges.add(createFoldingRange(commentStart, i, FoldingRangeKind.Comment));
                    }
                    commentStart = -1;
                }
            } else if (line.contains("*/") && inBlockComment) {
                inBlockComment = false;
                if (commentStart != -1 && commentStart != i) {
                    foldingRanges.add(createFoldingRange(commentStart, i, FoldingRangeKind.Comment));
                }
                commentStart = -1;
            }
            
            // 处理连续的单行注释
            if (line.startsWith("//") && !inBlockComment) {
                if (commentStart == -1) {
                    commentStart = i;
                }
            } else if (commentStart != -1 && !inBlockComment) {
                // 连续注释结束
                if (i - commentStart > 1) {
                    foldingRanges.add(createFoldingRange(commentStart, i - 1, FoldingRangeKind.Comment));
                }
                commentStart = -1;
            }
        }
        
        // 处理文件末尾的注释
        if (commentStart != -1 && !inBlockComment && lines.length - commentStart > 1) {
            foldingRanges.add(createFoldingRange(commentStart, lines.length - 1, FoldingRangeKind.Comment));
        }
    }
    
    /**
     * 查找函数定义的折叠范围
     */
    private void findFunctionFoldingRanges(String[] lines, List<FoldingRange> foldingRanges) {
        Pattern functionPattern = Pattern.compile("^\\s*(var\\s+\\w+\\s*=\\s*)?(\\w+\\s*=>|function\\s*\\(|\\([^)]*\\)\\s*=>|\\w+\\s*\\([^)]*\\)\\s*\\{)");
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (functionPattern.matcher(line).find()) {
                int braceEnd = findMatchingBrace(lines, i, '{', '}');
                if (braceEnd > i + 1) {
                    foldingRanges.add(createFoldingRange(i, braceEnd, null));
                }
            }
        }
    }
    
    /**
     * 查找对象字面量的折叠范围
     */
    private void findObjectFoldingRanges(String[] lines, List<FoldingRange> foldingRanges) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("{") && !line.trim().startsWith("//")) {
                int openBraces = countChar(line, '{');
                int closeBraces = countChar(line, '}');
                
                if (openBraces > closeBraces) {
                    int braceEnd = findMatchingBrace(lines, i, '{', '}');
                    if (braceEnd > i + 1) {
                        foldingRanges.add(createFoldingRange(i, braceEnd, null));
                    }
                }
            }
        }
    }
    
    /**
     * 查找数组字面量的折叠范围
     */
    private void findArrayFoldingRanges(String[] lines, List<FoldingRange> foldingRanges) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("[") && !line.trim().startsWith("//")) {
                int openBrackets = countChar(line, '[');
                int closeBrackets = countChar(line, ']');
                
                if (openBrackets > closeBrackets) {
                    int bracketEnd = findMatchingBrace(lines, i, '[', ']');
                    if (bracketEnd > i + 1) {
                        foldingRanges.add(createFoldingRange(i, bracketEnd, null));
                    }
                }
            }
        }
    }
    
    /**
     * 查找控制结构的折叠范围
     */
    private void findControlStructureFoldingRanges(String[] lines, List<FoldingRange> foldingRanges) {
        Pattern controlPattern = Pattern.compile("^\\s*(if|for|while|switch)\\s*\\(");
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (controlPattern.matcher(line).find()) {
                // 查找对应的代码块
                int braceStart = findBraceStart(lines, i);
                if (braceStart != -1) {
                    int braceEnd = findMatchingBrace(lines, braceStart, '{', '}');
                    if (braceEnd > braceStart + 1) {
                        foldingRanges.add(createFoldingRange(braceStart, braceEnd, null));
                    }
                }
            }
        }
    }
    
    /**
     * 查找try-catch块的折叠范围
     */
    private void findTryCatchFoldingRanges(String[] lines, List<FoldingRange> foldingRanges) {
        Pattern tryPattern = Pattern.compile("^\\s*try\\s*\\{");
        Pattern catchPattern = Pattern.compile("^\\s*catch\\s*\\(");
        Pattern finallyPattern = Pattern.compile("^\\s*finally\\s*\\{");
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            
            if (tryPattern.matcher(line).find()) {
                int braceEnd = findMatchingBrace(lines, i, '{', '}');
                if (braceEnd > i + 1) {
                    foldingRanges.add(createFoldingRange(i, braceEnd, null));
                }
            } else if (catchPattern.matcher(line).find()) {
                int braceStart = findBraceStart(lines, i);
                if (braceStart != -1) {
                    int braceEnd = findMatchingBrace(lines, braceStart, '{', '}');
                    if (braceEnd > braceStart + 1) {
                        foldingRanges.add(createFoldingRange(braceStart, braceEnd, null));
                    }
                }
            } else if (finallyPattern.matcher(line).find()) {
                int braceEnd = findMatchingBrace(lines, i, '{', '}');
                if (braceEnd > i + 1) {
                    foldingRanges.add(createFoldingRange(i, braceEnd, null));
                }
            }
        }
    }
    
    /**
     * 创建折叠范围对象
     */
    private FoldingRange createFoldingRange(int startLine, int endLine, String kind) {
        FoldingRange range = new FoldingRange();
        range.setStartLine(startLine);
        range.setEndLine(endLine);
        if (kind != null) {
            range.setKind(kind);
        }
        return range;
    }
    
    /**
     * 查找匹配的括号位置
     */
    private int findMatchingBrace(String[] lines, int startLine, char openChar, char closeChar) {
        int depth = 0;
        boolean inString = false;
        boolean inComment = false;
        char stringChar = 0;
        
        for (int i = startLine; i < lines.length; i++) {
            String line = lines[i];
            
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                
                // 处理字符串
                if (!inComment && (c == '"' || c == '\'' || c == '`')) {
                    if (!inString) {
                        inString = true;
                        stringChar = c;
                    } else if (c == stringChar && (j == 0 || line.charAt(j - 1) != '\\')) {
                        inString = false;
                    }
                    continue;
                }
                
                // 处理注释
                if (!inString && j < line.length() - 1) {
                    if (line.charAt(j) == '/' && line.charAt(j + 1) == '/') {
                        break; // 行注释，跳过本行剩余部分
                    }
                    if (line.charAt(j) == '/' && line.charAt(j + 1) == '*') {
                        inComment = true;
                        j++; // 跳过下一个字符
                        continue;
                    }
                    if (inComment && line.charAt(j) == '*' && line.charAt(j + 1) == '/') {
                        inComment = false;
                        j++; // 跳过下一个字符
                        continue;
                    }
                }
                
                if (!inString && !inComment) {
                    if (c == openChar) {
                        depth++;
                    } else if (c == closeChar) {
                        depth--;
                        if (depth == 0) {
                            return i;
                        }
                    }
                }
            }
        }
        
        return -1;
    }
    
    /**
     * 查找代码块开始的大括号位置
     */
    private int findBraceStart(String[] lines, int startLine) {
        for (int i = startLine; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("{")) {
                return i;
            }
            // 如果遇到分号或其他语句结束符，停止查找
            if (line.trim().endsWith(";")) {
                break;
            }
        }
        return -1;
    }
    
    /**
     * 计算字符在字符串中的出现次数
     */
    private int countChar(String str, char ch) {
        int count = 0;
        for (char c : str.toCharArray()) {
            if (c == ch) {
                count++;
            }
        }
        return count;
    }
}
