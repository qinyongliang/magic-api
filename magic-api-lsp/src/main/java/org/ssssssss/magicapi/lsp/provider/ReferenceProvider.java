package org.ssssssss.magicapi.lsp.provider;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.service.MagicResourceService;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用查找提供者
 */
public class ReferenceProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(ReferenceProvider.class);
    
    /**
     * 查找符号的引用
     */
    public List<Location> findReferences(String symbol, String currentUri) {
        logger.debug("Finding references for symbol: {}", symbol);
        
        List<Location> references = new ArrayList<>();
        
        try {
            MagicResourceService resourceService = MagicConfiguration.getMagicResourceService();
            if (resourceService != null) {
                // 搜索API文件中的引用
                List<ApiInfo> apiInfos = resourceService.files("api");
                for (ApiInfo apiInfo : apiInfos) {
                    if (apiInfo.getScript() != null) {
                        references.addAll(findReferencesInScript(symbol, apiInfo));
                    }
                }
                
                // 搜索函数文件中的引用
                List<MagicEntity> functions = resourceService.files("function");
                for (MagicEntity function : functions) {
                    if (function.getScript() != null) {
                        references.addAll(findReferencesInScript(symbol, function));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error finding references", e);
        }
        
        return references;
    }
    
    /**
     * 在脚本中查找符号的引用
     */
    private List<Location> findReferencesInScript(String symbol, MagicEntity entity) {
        List<Location> references = new ArrayList<>();
        String script = entity.getScript();
        String[] lines = script.split("\n");
        
        // 创建多种引用模式
        List<Pattern> referencePatterns = createReferencePatterns(symbol);
        
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            
            for (Pattern pattern : referencePatterns) {
                Matcher matcher = pattern.matcher(line);
                while (matcher.find()) {
                    // 确保匹配的是完整的符号，而不是其他符号的一部分
                    if (isCompleteSymbolMatch(line, matcher.start(), matcher.end(), symbol)) {
                        Location location = new Location();
                        location.setUri("file:///" + entity.getId() + ".ms");
                        
                        Range range = new Range();
                        range.setStart(new Position(lineIndex, matcher.start()));
                        range.setEnd(new Position(lineIndex, matcher.end()));
                        location.setRange(range);
                        
                        references.add(location);
                    }
                }
            }
        }
        
        return references;
    }
    
    /**
     * 创建符号引用的正则表达式模式
     */
    private List<Pattern> createReferencePatterns(String symbol) {
        List<Pattern> patterns = new ArrayList<>();
        String escapedSymbol = Pattern.quote(symbol);
        
        // 1. 变量引用：symbol
        patterns.add(Pattern.compile("\\b" + escapedSymbol + "\\b"));
        
        // 2. 函数调用：symbol(
        patterns.add(Pattern.compile("\\b" + escapedSymbol + "\\s*\\("));
        
        // 3. 属性访问：obj.symbol
        patterns.add(Pattern.compile("\\." + escapedSymbol + "\\b"));
        
        // 4. 方法调用：obj.symbol(
        patterns.add(Pattern.compile("\\." + escapedSymbol + "\\s*\\("));
        
        // 5. 赋值：symbol = 
        patterns.add(Pattern.compile("\\b" + escapedSymbol + "\\s*="));
        
        // 6. 参数传递：function(symbol)
        patterns.add(Pattern.compile("\\(\\s*" + escapedSymbol + "\\s*[,)]"));
        
        // 7. 数组/对象访问：symbol[
        patterns.add(Pattern.compile("\\b" + escapedSymbol + "\\s*\\["));
        
        // 8. 字符串模板中的引用：${symbol}
        patterns.add(Pattern.compile("\\$\\{[^}]*\\b" + escapedSymbol + "\\b[^}]*\\}"));
        
        // 9. 返回语句：return symbol
        patterns.add(Pattern.compile("return\\s+" + escapedSymbol + "\\b"));
        
        // 10. 条件表达式中的引用
        patterns.add(Pattern.compile("\\b" + escapedSymbol + "\\s*[<>=!]+"));
        patterns.add(Pattern.compile("[<>=!]+\\s*" + escapedSymbol + "\\b"));
        
        return patterns;
    }
    
    /**
     * 检查是否为完整的符号匹配
     */
    private boolean isCompleteSymbolMatch(String line, int start, int end, String symbol) {
        // 检查匹配的文本是否确实包含我们要找的符号
        String matched = line.substring(start, end);
        
        // 移除可能的前缀和后缀（如括号、操作符等）
        String cleanMatched = matched.replaceAll("[^a-zA-Z0-9_$]", "");
        
        return cleanMatched.equals(symbol);
    }
    
    /**
     * 获取指定位置的符号
     */
    public String getSymbolAtPosition(String content, Position position) {
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
}
