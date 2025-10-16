package org.ssssssss.magicapi.lsp.provider;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.service.MagicResourceService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 重命名操作提供者
 */
public class RenameProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(RenameProvider.class);
    
    /**
     * 执行重命名操作
     */
    public WorkspaceEdit rename(String content, Position position, String newName, String currentUri) {
        logger.debug("Rename requested at {}:{} to '{}'", 
                    position.getLine(), 
                    position.getCharacter(),
                    newName);
        
        try {
            // 验证新名称的有效性
            if (!isValidIdentifier(newName)) {
                logger.warn("Invalid identifier name: {}", newName);
                return new WorkspaceEdit();
            }
            
            // 获取光标位置的符号
            String symbolToRename = getSymbolAtPosition(content, position);
            if (symbolToRename == null || symbolToRename.isEmpty()) {
                logger.debug("No symbol found at position {}:{}", position.getLine(), position.getCharacter());
                return new WorkspaceEdit();
            }
            
            // 创建工作区编辑
            WorkspaceEdit workspaceEdit = new WorkspaceEdit();
            Map<String, List<TextEdit>> changes = new HashMap<>();
            
            // 在当前文档中查找所有引用并重命名
            List<TextEdit> currentDocumentEdits = findAndRenameInDocument(content, symbolToRename, newName);
            if (!currentDocumentEdits.isEmpty()) {
                changes.put(currentUri, currentDocumentEdits);
            }
            
            // 在工作区的其他文档中查找引用并重命名
            findAndRenameInWorkspace(symbolToRename, newName, changes, currentUri);
            
            workspaceEdit.setChanges(changes);
            
            logger.debug("Rename operation completed. {} files affected", changes.size());
            return workspaceEdit;
            
        } catch (Exception e) {
            logger.error("Error during rename operation", e);
            return new WorkspaceEdit();
        }
    }
    
    /**
     * 验证标识符是否有效
     */
    private boolean isValidIdentifier(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        
        // 检查是否符合JavaScript/Magic Script标识符规则
        return name.matches("^[a-zA-Z_$][a-zA-Z0-9_$]*$") && !isReservedKeyword(name);
    }
    
    /**
     * 检查是否为保留关键字
     */
    private boolean isReservedKeyword(String name) {
        Set<String> keywords = new HashSet<>(Arrays.asList(
            "var", "let", "const", "function", "return", "if", "else", "for", "while", "do",
            "switch", "case", "default", "break", "continue", "try", "catch", "finally",
            "throw", "new", "this", "super", "class", "extends", "import", "export",
            "true", "false", "null", "undefined", "typeof", "instanceof", "in", "of",
            "async", "await", "yield", "delete", "void", "with", "debugger"
        ));
        return keywords.contains(name);
    }
    
    /**
     * 获取指定位置的符号
     */
    private String getSymbolAtPosition(String content, Position position) {
        try {
            if (content == null || position == null) {
                return null;
            }
            
            // 解析内容，获取指定位置的符号
            String[] lines = content.split("\n");
            if (position.getLine() >= lines.length) {
                return null;
            }
            
            String line = lines[position.getLine()];
            int character = position.getCharacter();
            
            if (character >= line.length()) {
                return null;
            }
            
            // 找到符号的边界
            int start = character;
            int end = character;
            
            // 向前找到符号开始
            while (start > 0 && isSymbolCharacter(line.charAt(start - 1))) {
                start--;
            }
            
            // 向后找到符号结束
            while (end < line.length() && isSymbolCharacter(line.charAt(end))) {
                end++;
            }
            
            if (start < end) {
                return line.substring(start, end);
            }
            
        } catch (Exception e) {
            logger.warn("Error getting symbol at position", e);
        }
        
        return null;
    }
    
    /**
     * 判断字符是否为符号字符
     */
    private boolean isSymbolCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
    
    /**
     * 在文档中查找并重命名符号
     */
    private List<TextEdit> findAndRenameInDocument(String content, String oldName, String newName) {
        List<TextEdit> edits = new ArrayList<>();
        String[] lines = content.split("\n");
        
        // 创建多种匹配模式
        List<Pattern> patterns = createRenamePatterns(oldName);
        
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(line);
                while (matcher.find()) {
                    // 确保匹配的是完整的标识符
                    if (isCompleteIdentifierMatch(line, matcher.start(), matcher.end(), oldName)) {
                        Range range = new Range(
                            new Position(lineIndex, matcher.start()),
                            new Position(lineIndex, matcher.end())
                        );
                        edits.add(new TextEdit(range, newName));
                    }
                }
            }
        }
        
        return edits;
    }
    
    /**
     * 创建重命名的正则表达式模式
     */
    private List<Pattern> createRenamePatterns(String symbolName) {
        List<Pattern> patterns = new ArrayList<>();
        String escapedName = Pattern.quote(symbolName);
        
        // 基本标识符匹配
        patterns.add(Pattern.compile("\\b" + escapedName + "\\b"));
        
        // 函数定义
        patterns.add(Pattern.compile("function\\s+" + escapedName + "\\s*\\("));
        
        // 变量声明
        patterns.add(Pattern.compile("(var|let|const)\\s+" + escapedName + "\\b"));
        
        // 对象属性
        patterns.add(Pattern.compile("\\." + escapedName + "\\b"));
        patterns.add(Pattern.compile("\\[\\s*['\"]" + escapedName + "['\"]\\s*\\]"));
        
        // 函数调用
        patterns.add(Pattern.compile(escapedName + "\\s*\\("));
        
        return patterns;
    }
    
    /**
     * 检查是否为完整的标识符匹配
     */
    private boolean isCompleteIdentifierMatch(String line, int start, int end, String symbolName) {
        // 检查匹配的文本是否确实是目标符号
        String matched = line.substring(start, end);
        if (!matched.equals(symbolName)) {
            return false;
        }
        
        // 检查前后字符，确保是完整的标识符边界
        char prevChar = start > 0 ? line.charAt(start - 1) : ' ';
        char nextChar = end < line.length() ? line.charAt(end) : ' ';
        
        boolean prevIsIdentifierChar = Character.isLetterOrDigit(prevChar) || prevChar == '_' || prevChar == '$';
        boolean nextIsIdentifierChar = Character.isLetterOrDigit(nextChar) || nextChar == '_' || nextChar == '$';
        
        return !prevIsIdentifierChar && !nextIsIdentifierChar;
    }
    
    /**
     * 在工作区的其他文档中查找引用并重命名
     */
    private void findAndRenameInWorkspace(String symbolName, String newName, 
                                        Map<String, List<TextEdit>> changes, String currentUri) {
        try {
            // 获取所有Magic API文件
            List<MagicEntity> allEntities = getAllMagicEntities();
            
            for (MagicEntity entity : allEntities) {
                String entityUri = "file:///" + entity.getId() + ".ms";
                
                // 跳过当前文档
                if (entityUri.equals(currentUri)) {
                    continue;
                }
                
                // 获取文档内容
                String content = entity.getScript();
                if (content == null || content.trim().isEmpty()) {
                    continue;
                }
                
                // 查找引用
                List<TextEdit> edits = findAndRenameInDocument(content, symbolName, newName);
                if (!edits.isEmpty()) {
                    changes.put(entityUri, edits);
                }
            }
            
        } catch (Exception e) {
            logger.error("Error searching workspace for symbol references", e);
        }
    }
    
    /**
     * 获取所有Magic API实体
     */
    private List<MagicEntity> getAllMagicEntities() {
        List<MagicEntity> entities = new ArrayList<>();
        
        try {
            // 从配置中获取Magic Resource服务
            MagicResourceService resourceService = MagicConfiguration.getMagicResourceService();
            if (resourceService != null) {
                // 获取所有API
                List<MagicEntity> apis = resourceService.files("api");
                if (apis != null) {
                    entities.addAll(apis);
                }
                
                // 获取所有函数
                List<MagicEntity> functions = resourceService.files("function");
                if (functions != null) {
                    entities.addAll(functions);
                }
            }
        } catch (Exception e) {
            logger.error("Error getting Magic API entities", e);
        }
        
        return entities;
    }
}
