package org.ssssssss.magicapi.lsp.provider;

import org.eclipse.lsp4j.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.script.MagicScriptEngine;
import org.ssssssss.script.MagicResourceLoader;
import org.ssssssss.script.ScriptClass.ScriptMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 符号搜索和定义查找提供者
 */
public class SymbolProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(SymbolProvider.class);
    
    // 符号搜索的正则表达式模式
    private static final Pattern FUNCTION_PATTERN = Pattern.compile("function\\s+(\\w+)\\s*\\(");
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("(?:var|let|const)\\s+(\\w+)");
    private static final Pattern API_PATTERN = Pattern.compile("@RequestMapping\\s*\\(.*?value\\s*=\\s*[\"']([^\"']+)[\"']");
    
    /**
     * 搜索工作区符号
     */
    public List<SymbolInformation> searchWorkspaceSymbols(String query) {
        logger.debug("Workspace symbol search requested: {}", query);
        
        List<SymbolInformation> symbols = new ArrayList<>();
        String lowerQuery = query.toLowerCase();
        
        try {
            MagicResourceService resourceService = MagicConfiguration.getMagicResourceService();
            if (resourceService != null) {
                // 搜索所有API文件
                List<ApiInfo> apiInfos = resourceService.files("api");
                for (ApiInfo apiInfo : apiInfos) {
                    if (apiInfo.getScript() != null) {
                        symbols.addAll(searchSymbolsInScript(apiInfo, lowerQuery));
                    }
                }
                
                // 搜索函数文件
                List<MagicEntity> functions = resourceService.files("function");
                for (MagicEntity function : functions) {
                    if (function.getScript() != null) {
                        symbols.addAll(searchSymbolsInScript(function, lowerQuery));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error searching workspace symbols", e);
        }
        
        return symbols;
    }
    
    /**
     * 在脚本中搜索符号
     */
    private List<SymbolInformation> searchSymbolsInScript(MagicEntity entity, String query) {
        List<SymbolInformation> symbols = new ArrayList<>();
        String script = entity.getScript();
        String[] lines = script.split("\n");
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            
            // 搜索函数定义
            Matcher functionMatcher = FUNCTION_PATTERN.matcher(line);
            while (functionMatcher.find()) {
                String functionName = functionMatcher.group(1);
                if (functionName.toLowerCase().contains(query)) {
                    SymbolInformation symbol = createSymbolInformation(
                        functionName, 
                        SymbolKind.Function, 
                        entity, 
                        i, 
                        functionMatcher.start(1), 
                        functionMatcher.end(1)
                    );
                    symbols.add(symbol);
                }
            }
            
            // 搜索变量定义
            Matcher variableMatcher = VARIABLE_PATTERN.matcher(line);
            while (variableMatcher.find()) {
                String variableName = variableMatcher.group(1);
                if (variableName.toLowerCase().contains(query)) {
                    SymbolInformation symbol = createSymbolInformation(
                        variableName, 
                        SymbolKind.Variable, 
                        entity, 
                        i, 
                        variableMatcher.start(1), 
                        variableMatcher.end(1)
                    );
                    symbols.add(symbol);
                }
            }
            
            // 搜索API路径（如果是ApiInfo）
            if (entity instanceof ApiInfo) {
                ApiInfo apiInfo = (ApiInfo) entity;
                String path = apiInfo.getPath();
                if (path != null && path.toLowerCase().contains(query)) {
                    SymbolInformation symbol = createSymbolInformation(
                        apiInfo.getMethod() + " " + path, 
                        SymbolKind.Interface, 
                        entity, 
                        0, 
                        0, 
                        path.length()
                    );
                    symbols.add(symbol);
                }
            }
        }
        
        return symbols;
    }
    
    /**
     * 创建符号信息
     */
    private SymbolInformation createSymbolInformation(String name, SymbolKind kind, MagicEntity entity, 
                                                     int line, int startChar, int endChar) {
        SymbolInformation symbol = new SymbolInformation();
        symbol.setName(name);
        symbol.setKind(kind);
        
        // 创建位置信息
        Location location = new Location();
        location.setUri("file:///" + entity.getId() + ".ms"); // 使用实体ID作为文件标识
        
        Range range = new Range();
        range.setStart(new Position(line, startChar));
        range.setEnd(new Position(line, endChar));
        location.setRange(range);
        
        symbol.setLocation(location);
        
        // 设置容器名称（文件路径或分组）
        if (entity.getName() != null) {
            symbol.setContainerName(entity.getName());
        }
        
        return symbol;
    }
    
    /**
     * 查找符号定义
     */
    public List<Location> findDefinitions(String symbol) {
        logger.debug("Finding definition for symbol: {}", symbol);
        
        List<Location> definitions = new ArrayList<>();
        
        try {
            MagicResourceService resourceService = MagicConfiguration.getMagicResourceService();
            if (resourceService != null) {
                // 搜索API文件中的定义
                List<ApiInfo> apiInfos = resourceService.files("api");
                for (ApiInfo apiInfo : apiInfos) {
                    if (apiInfo.getScript() != null) {
                        definitions.addAll(findDefinitionsInScript(symbol, apiInfo));
                    }
                }
                
                // 搜索函数文件中的定义
                List<MagicEntity> functions = resourceService.files("function");
                for (MagicEntity function : functions) {
                    if (function.getScript() != null) {
                        definitions.addAll(findDefinitionsInScript(symbol, function));
                    }
                }
                
                // 如果没有找到定义，尝试查找内置函数或Magic API特定的符号
                if (definitions.isEmpty()) {
                    definitions.addAll(findBuiltinDefinitions(symbol));
                }
            }
        } catch (Exception e) {
            logger.error("Error finding definition", e);
        }
        
        return definitions;
    }
    
    /**
     * 在脚本中查找符号的定义
     */
    private List<Location> findDefinitionsInScript(String symbol, MagicEntity entity) {
        List<Location> definitions = new ArrayList<>();
        String script = entity.getScript();
        String[] lines = script.split("\n");
        
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex].trim();
            
            // 查找函数定义
            if (isFunctionDefinition(line, symbol)) {
                Location location = createDefinitionLocation(entity, lineIndex, line, symbol);
                if (location != null) {
                    definitions.add(location);
                }
            }
            
            // 查找变量定义
            if (isVariableDefinition(line, symbol)) {
                Location location = createDefinitionLocation(entity, lineIndex, line, symbol);
                if (location != null) {
                    definitions.add(location);
                }
            }
            
            // 查找参数定义（在函数参数列表中）
            if (isParameterDefinition(line, symbol)) {
                Location location = createDefinitionLocation(entity, lineIndex, line, symbol);
                if (location != null) {
                    definitions.add(location);
                }
            }
            
            // 查找import定义
            if (isImportDefinition(line, symbol)) {
                Location location = createDefinitionLocation(entity, lineIndex, line, symbol);
                if (location != null) {
                    definitions.add(location);
                }
            }
        }
        
        return definitions;
    }
    
    /**
     * 检查是否是函数定义
     */
    private boolean isFunctionDefinition(String line, String symbol) {
        // 匹配 function functionName( 或 var functionName = function(
        Pattern functionPattern = Pattern.compile("(?:function\\s+(" + Pattern.quote(symbol) + ")\\s*\\()|(?:(?:var|let|const)\\s+(" + Pattern.quote(symbol) + ")\\s*=\\s*function\\s*\\()");
        Matcher matcher = functionPattern.matcher(line);
        return matcher.find() && (symbol.equals(matcher.group(1)) || symbol.equals(matcher.group(2)));
    }
    
    /**
     * 检查是否是变量定义
     */
    private boolean isVariableDefinition(String line, String symbol) {
        // 匹配 var/let/const symbol = 或简单的赋值 symbol =
        Pattern varPattern = Pattern.compile("(?:(?:var|let|const)\\s+(" + Pattern.quote(symbol) + ")\\s*=)|(?:^\\s*(" + Pattern.quote(symbol) + ")\\s*=(?!=))");
        Matcher matcher = varPattern.matcher(line);
        return matcher.find() && (symbol.equals(matcher.group(1)) || symbol.equals(matcher.group(2)));
    }
    
    /**
     * 检查是否是参数定义
     */
    private boolean isParameterDefinition(String line, String symbol) {
        // 匹配函数参数列表中的参数
        Pattern paramPattern = Pattern.compile("function\\s+\\w+\\s*\\([^)]*\\b" + Pattern.quote(symbol) + "\\b[^)]*\\)");
        return paramPattern.matcher(line).find();
    }
    
    /**
     * 检查是否是import定义
     */
    private boolean isImportDefinition(String line, String symbol) {
        // 匹配 import symbol 或 import { symbol } 或 var symbol = import(...)
        Pattern importPattern = Pattern.compile("(?:import\\s+(" + Pattern.quote(symbol) + "))|(?:import\\s*\\{[^}]*\\b(" + Pattern.quote(symbol) + ")\\b[^}]*\\})|(?:(?:var|let|const)\\s+(" + Pattern.quote(symbol) + ")\\s*=\\s*import\\s*\\()");
        Matcher matcher = importPattern.matcher(line);
        return matcher.find() && (symbol.equals(matcher.group(1)) || symbol.equals(matcher.group(2)) || symbol.equals(matcher.group(3)));
    }
    
    /**
     * 创建定义位置
     */
    private Location createDefinitionLocation(MagicEntity entity, int lineIndex, String line, String symbol) {
        // 找到符号在行中的位置
        int symbolIndex = findSymbolIndexInLine(line, symbol);
        if (symbolIndex == -1) {
            return null;
        }
        
        Location location = new Location();
        location.setUri("file:///" + entity.getId() + ".ms");
        
        Range range = new Range();
        range.setStart(new Position(lineIndex, symbolIndex));
        range.setEnd(new Position(lineIndex, symbolIndex + symbol.length()));
        location.setRange(range);
        
        return location;
    }
    
    /**
     * 在行中查找符号的索引位置
     */
    private int findSymbolIndexInLine(String line, String symbol) {
        // 使用正则表达式确保匹配完整的符号
        Pattern symbolPattern = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");
        Matcher matcher = symbolPattern.matcher(line);
        if (matcher.find()) {
            return matcher.start();
        }
        return -1;
    }
    
    /**
     * 查找内置函数或Magic API特定符号的定义（动态）
     */
    private List<Location> findBuiltinDefinitions(String symbol) {
        List<Location> definitions = new ArrayList<>();

        // 动态检查函数（来自 MagicScriptEngine）
        try {
            List<ScriptMethod> functions = MagicScriptEngine.getFunctions();
            if (functions != null) {
                for (ScriptMethod m : functions) {
                    if (symbol.equals(m.getName())) {
                        Location location = new Location();
                        location.setUri("magic-api://builtin/function/" + symbol);
                        Range range = new Range();
                        range.setStart(new Position(0, 0));
                        range.setEnd(new Position(0, symbol.length()));
                        location.setRange(range);
                        definitions.add(location);
                        break;
                    }
                }
            }
        } catch (Throwable ignore) {
            // 保持静默，避免影响符号查找流程
        }

        // 动态检查模块（来自 MagicResourceLoader）
        try {
            java.util.Set<String> modules = MagicResourceLoader.getModuleNames();
            if (modules != null && modules.contains(symbol)) {
                Location location = new Location();
                location.setUri("magic-api://builtin/module/" + symbol);
                Range range = new Range();
                range.setStart(new Position(0, 0));
                range.setEnd(new Position(0, symbol.length()));
                location.setRange(range);
                definitions.add(location);
            }
        } catch (Throwable ignore) {
            // 保持静默
        }

        return definitions;
    }
    
}
