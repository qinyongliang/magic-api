package org.ssssssss.magicapi.lsp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.event.EventAction;
import org.ssssssss.magicapi.core.event.FileEvent;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.service.MagicResourceService;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Magic API Workspace Service Implementation
 * 
 * @author magic-api-team
 */
public class MagicWorkspaceService implements WorkspaceService {
    
    private static final Logger logger = LoggerFactory.getLogger(MagicWorkspaceService.class);
    
    // 符号搜索的正则表达式模式
    private static final Pattern FUNCTION_PATTERN = Pattern.compile("function\\s+(\\w+)\\s*\\(");
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("(?:var|let|const)\\s+(\\w+)");
    private static final Pattern API_PATTERN = Pattern.compile("@RequestMapping\\s*\\(.*?value\\s*=\\s*[\"']([^\"']+)[\"']");
    
    // 文件缓存，用于跟踪文件变更
    private final Map<String, Long> fileTimestamps = new ConcurrentHashMap<>();
    private final Map<String, String> fileContents = new ConcurrentHashMap<>();
    
    // 事件发布器，用于发布文件变更事件
    private ApplicationEventPublisher eventPublisher;
    
    // Magic API配置实例
    private MagicConfiguration magicConfiguration;
    
    // Magic Text Document Service，用于基于作用域的分析
    private MagicTextDocumentService textDocumentService;
    
    // Language Client，用于与客户端通信
    private LanguageClient client;
    
    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }
    
    public void setTextDocumentService(MagicTextDocumentService textDocumentService) {
        this.textDocumentService = textDocumentService;
    }
    
    public void setClient(LanguageClient client) {
        this.client = client;
    }
    
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) {
        logger.debug("Workspace symbol search requested: {}", params.getQuery());
        
        return CompletableFuture.supplyAsync(() -> {
            List<SymbolInformation> symbols = new ArrayList<>();
            String query = params.getQuery().toLowerCase();
            
            try {
                MagicResourceService resourceService = MagicConfiguration.getMagicResourceService();
                if (resourceService != null) {
                    // 搜索所有API文件
                    List<ApiInfo> apiInfos = resourceService.files("api");
                    for (ApiInfo apiInfo : apiInfos) {
                        if (apiInfo.getScript() != null) {
                            symbols.addAll(searchSymbolsInScript(apiInfo, query));
                        }
                    }
                    
                    // 搜索函数文件
                    List<MagicEntity> functions = resourceService.files("function");
                    for (MagicEntity function : functions) {
                        if (function.getScript() != null) {
                            symbols.addAll(searchSymbolsInScript(function, query));
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error searching workspace symbols", e);
            }
            
            return Either.forLeft(symbols);
        });
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
    
    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        logger.debug("Configuration changed: {}", params.getSettings());
        
        try {
            // 处理配置变更
            Object settings = params.getSettings();
            if (settings instanceof JsonObject) {
                JsonObject configObject = (JsonObject) settings;
                handleMagicApiConfiguration(configObject);
            } else if (settings instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> configMap = (Map<String, Object>) settings;
                handleMagicApiConfiguration(configMap);
            }
            
            // 清除缓存，强制重新解析
            clearCaches();
            
            logger.info("Configuration updated successfully");
        } catch (Exception e) {
            logger.error("Failed to handle configuration change", e);
        }
    }
    
    /**
     * 处理Magic API配置变更
     */
    private void handleMagicApiConfiguration(JsonObject config) {
        // 处理Magic API相关配置
        if (config.has("magic-api")) {
            JsonElement magicApiConfig = config.get("magic-api");
            if (magicApiConfig.isJsonObject()) {
                JsonObject magicApiObj = magicApiConfig.getAsJsonObject();
                
                // 处理调试配置
                if (magicApiObj.has("debug")) {
                    handleDebugConfiguration(magicApiObj.getAsJsonObject("debug"));
                }
                
                // 处理资源配置
                if (magicApiObj.has("resource")) {
                    handleResourceConfiguration(magicApiObj.getAsJsonObject("resource"));
                }
                
                // 处理安全配置
                if (magicApiObj.has("security")) {
                    handleSecurityConfiguration(magicApiObj.getAsJsonObject("security"));
                }
                
                // 处理缓存配置
                if (magicApiObj.has("cache")) {
                    handleCacheConfiguration(magicApiObj.getAsJsonObject("cache"));
                }
                
                // 处理脚本执行配置
                if (magicApiObj.has("threadPoolExecutorSize")) {
                    int threadPoolSize = magicApiObj.get("threadPoolExecutorSize").getAsInt();
                    logger.info("Thread pool executor size changed to: {}", threadPoolSize);
                }
                
                // 处理编译缓存配置
                if (magicApiObj.has("compileCacheSize")) {
                    int compileCacheSize = magicApiObj.get("compileCacheSize").getAsInt();
                    logger.info("Compile cache size changed to: {}", compileCacheSize);
                }
            }
        }
        
        // 处理LSP特定配置
        if (config.has("magicScript")) {
            JsonElement lspConfig = config.get("magicScript");
            if (lspConfig.isJsonObject()) {
                handleLspConfiguration(lspConfig.getAsJsonObject());
            }
        }
    }
    
    /**
     * 处理Map格式的配置
     */
    private void handleMagicApiConfiguration(Map<String, Object> config) {
        // 处理Magic API相关配置
        if (config.containsKey("magic-api")) {
            Object magicApiConfig = config.get("magic-api");
            if (magicApiConfig instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> magicApiMap = (Map<String, Object>) magicApiConfig;
                
                // 处理调试配置
                if (magicApiMap.containsKey("debug")) {
                    handleDebugConfiguration(magicApiMap.get("debug"));
                }
                
                // 处理资源配置
                if (magicApiMap.containsKey("resource")) {
                    handleResourceConfiguration(magicApiMap.get("resource"));
                }
                
                // 处理其他配置项...
            }
        }
    }
    
    /**
     * 处理调试配置变更
     */
    private void handleDebugConfiguration(JsonObject debugConfig) {
        if (debugConfig.has("timeout")) {
            int timeout = debugConfig.get("timeout").getAsInt();
            logger.info("Debug timeout changed to: {} seconds", timeout);
            // 更新调试超时设置
            if (magicConfiguration != null) {
                magicConfiguration.setDebugTimeout(timeout);
            }
        }
    }
    
    private void handleDebugConfiguration(Object debugConfig) {
        if (debugConfig instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> debugMap = (Map<String, Object>) debugConfig;
            if (debugMap.containsKey("timeout")) {
                Object timeoutObj = debugMap.get("timeout");
                if (timeoutObj instanceof Number) {
                    int timeout = ((Number) timeoutObj).intValue();
                    logger.info("Debug timeout changed to: {} seconds", timeout);
                    if (magicConfiguration != null) {
                        magicConfiguration.setDebugTimeout(timeout);
                    }
                }
            }
        }
    }
    
    /**
     * 处理资源配置变更
     */
    private void handleResourceConfiguration(JsonObject resourceConfig) {
        if (resourceConfig.has("type")) {
            String type = resourceConfig.get("type").getAsString();
            logger.info("Resource type changed to: {}", type);
        }
        
        if (resourceConfig.has("location")) {
            String location = resourceConfig.get("location").getAsString();
            logger.info("Resource location changed to: {}", location);
        }
        
        if (resourceConfig.has("datasource")) {
            String datasource = resourceConfig.get("datasource").getAsString();
            logger.info("Resource datasource changed to: {}", datasource);
        }
    }
    
    private void handleResourceConfiguration(Object resourceConfig) {
        if (resourceConfig instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resourceMap = (Map<String, Object>) resourceConfig;
            
            if (resourceMap.containsKey("type")) {
                logger.info("Resource type changed to: {}", resourceMap.get("type"));
            }
            
            if (resourceMap.containsKey("location")) {
                logger.info("Resource location changed to: {}", resourceMap.get("location"));
            }
        }
    }
    
    /**
     * 处理安全配置变更
     */
    private void handleSecurityConfiguration(JsonObject securityConfig) {
        if (securityConfig.has("username")) {
            logger.info("Security username configuration changed");
        }
        
        if (securityConfig.has("password")) {
            logger.info("Security password configuration changed");
        }
    }
    
    /**
     * 处理缓存配置变更
     */
    private void handleCacheConfiguration(JsonObject cacheConfig) {
        if (cacheConfig.has("enable")) {
            boolean enable = cacheConfig.get("enable").getAsBoolean();
            logger.info("Cache enable changed to: {}", enable);
        }
        
        if (cacheConfig.has("capacity")) {
            int capacity = cacheConfig.get("capacity").getAsInt();
            logger.info("Cache capacity changed to: {}", capacity);
        }
        
        if (cacheConfig.has("ttl")) {
            long ttl = cacheConfig.get("ttl").getAsLong();
            logger.info("Cache TTL changed to: {} ms", ttl);
        }
    }
    
    /**
     * 处理LSP特定配置
     */
    private void handleLspConfiguration(JsonObject lspConfig) {
        if (lspConfig.has("completion")) {
            JsonObject completionConfig = lspConfig.getAsJsonObject("completion");
            if (completionConfig.has("enabled")) {
                boolean enabled = completionConfig.get("enabled").getAsBoolean();
                logger.info("Completion enabled changed to: {}", enabled);
            }
        }
        
        if (lspConfig.has("hover")) {
            JsonObject hoverConfig = lspConfig.getAsJsonObject("hover");
            if (hoverConfig.has("enabled")) {
                boolean enabled = hoverConfig.get("enabled").getAsBoolean();
                logger.info("Hover enabled changed to: {}", enabled);
            }
        }
        
        if (lspConfig.has("validation")) {
            JsonObject validationConfig = lspConfig.getAsJsonObject("validation");
            if (validationConfig.has("enabled")) {
                boolean enabled = validationConfig.get("enabled").getAsBoolean();
                logger.info("Validation enabled changed to: {}", enabled);
            }
        }
        
        if (lspConfig.has("definition")) {
            JsonObject definitionConfig = lspConfig.getAsJsonObject("definition");
            if (definitionConfig.has("enabled")) {
                boolean enabled = definitionConfig.get("enabled").getAsBoolean();
                logger.info("Definition enabled changed to: {}", enabled);
            }
        }
    }
    
    /**
     * 清除所有缓存
     */
    private void clearCaches() {
        if (fileTimestamps != null) {
            fileTimestamps.clear();
        }
        if (fileContents != null) {
            fileContents.clear();
        }
        logger.debug("All caches cleared due to configuration change");
    }
    
    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        logger.debug("Watched files changed: {} files", params.getChanges().size());
        
        for (org.eclipse.lsp4j.FileEvent change : params.getChanges()) {
            logger.debug("File {} changed with type: {}", change.getUri(), change.getType());
            
            try {
                handleFileChange(change);
            } catch (Exception e) {
                logger.error("Error handling file change for: " + change.getUri(), e);
            }
        }
    }
    
    /**
     * 处理文件变更事件
     */
    private void handleFileChange(org.eclipse.lsp4j.FileEvent change) {
        String uri = change.getUri();
        FileChangeType changeType = change.getType();
        
        // 解析文件URI，提取文件ID
        String fileId = extractFileIdFromUri(uri);
        if (fileId == null) {
            logger.warn("Cannot extract file ID from URI: {}", uri);
            return;
        }
        
        MagicResourceService resourceService = MagicConfiguration.getMagicResourceService();
        if (resourceService == null) {
            logger.warn("MagicResourceService is not available");
            return;
        }
        
        switch (changeType) {
            case Created:
                handleFileCreated(fileId, resourceService);
                break;
            case Changed:
                handleFileChanged(fileId, resourceService);
                break;
            case Deleted:
                handleFileDeleted(fileId, resourceService);
                break;
        }
    }
    
    /**
     * 处理文件创建事件
     */
    private void handleFileCreated(String fileId, MagicResourceService resourceService) {
        logger.debug("Handling file created: {}", fileId);
        
        // 刷新资源服务缓存
        resourceService.refresh();
        
        // 获取新创建的文件
        MagicEntity entity = resourceService.file(fileId);
        if (entity != null) {
            // 更新本地缓存
            updateFileCache(fileId, entity);
            
            // 发布文件创建事件
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new FileEvent("lsp", EventAction.CREATE, entity));
            }
        }
    }
    
    /**
     * 处理文件变更事件
     */
    private void handleFileChanged(String fileId, MagicResourceService resourceService) {
        logger.debug("Handling file changed: {}", fileId);
        
        // 获取变更后的文件
        MagicEntity entity = resourceService.file(fileId);
        if (entity != null) {
            // 检查文件内容是否真的发生了变化
            String oldContent = fileContents.get(fileId);
            String newContent = entity.getScript();
            
            if (!java.util.Objects.equals(oldContent, newContent)) {
                // 更新本地缓存
                updateFileCache(fileId, entity);
                
                // 发布文件变更事件
                if (eventPublisher != null) {
                    eventPublisher.publishEvent(new FileEvent("lsp", EventAction.SAVE, entity));
                }
                
                logger.debug("File content changed for: {}", fileId);
            }
        }
    }
    
    /**
     * 处理文件删除事件
     */
    private void handleFileDeleted(String fileId, MagicResourceService resourceService) {
        logger.debug("Handling file deleted: {}", fileId);
        
        // 从本地缓存中移除
        fileTimestamps.remove(fileId);
        String oldContent = fileContents.remove(fileId);
        
        if (oldContent != null) {
            // 创建一个临时实体用于事件发布
            MagicEntity tempEntity = new MagicEntity() {
                @Override
                public String getId() { return fileId; }
                @Override
                public String getScript() { return oldContent; }
            };
            
            // 发布文件删除事件
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new FileEvent("lsp", EventAction.DELETE, tempEntity));
            }
        }
    }
    
    /**
     * 更新文件缓存
     */
    private void updateFileCache(String fileId, MagicEntity entity) {
        fileTimestamps.put(fileId, System.currentTimeMillis());
        if (entity.getScript() != null) {
            fileContents.put(fileId, entity.getScript());
        }
    }
    
    /**
     * 从URI中提取文件ID
     */
    private String extractFileIdFromUri(String uri) {
        try {
            URI parsedUri = URI.create(uri);
            String path = parsedUri.getPath();
            
            // 假设URI格式为 file:///path/to/file/{fileId}.ms
            if (path != null && path.endsWith(".ms")) {
                String fileName = path.substring(path.lastIndexOf('/') + 1);
                return fileName.substring(0, fileName.length() - 3); // 移除 .ms 扩展名
            }
        } catch (Exception e) {
            logger.warn("Error parsing URI: {}", uri, e);
        }
        return null;
    }
    
    /**
     * 监听Magic API文件事件
     */
    @EventListener
    public void onMagicFileEvent(FileEvent event) {
        logger.debug("Received Magic API file event: {} - {}", event.getAction(), event.getEntity().getId());
        
        // 当Magic API内部发生文件变更时，更新LSP缓存
        MagicEntity entity = event.getEntity();
        if (entity != null) {
            updateFileCache(entity.getId(), entity);
        }
    }
    
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        logger.debug("Find references requested at {}:{}", 
                    params.getPosition().getLine(), 
                    params.getPosition().getCharacter());
        
        return CompletableFuture.supplyAsync(() -> {
            List<Location> references = new ArrayList<>();
            
            try {
                String uri = params.getTextDocument().getUri();
                Position position = params.getPosition();
                
                // 首先尝试使用基于作用域的引用查找
                if (textDocumentService != null) {
                    List<Location> scopeBasedReferences = textDocumentService.findReferencesWithScope(uri, position);
                    if (!scopeBasedReferences.isEmpty()) {
                        logger.debug("Found {} scope-based references", scopeBasedReferences.size());
                        return scopeBasedReferences;
                    }
                }
                
                // 如果基于作用域的查找没有结果，回退到传统的正则表达式查找
                String symbol = getSymbolAtPosition(uri, position);
                if (symbol == null || symbol.trim().isEmpty()) {
                    return references;
                }
                
                logger.debug("Finding references for symbol using regex fallback: {}", symbol);
                
                // 在整个工作区中搜索引用
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
        });
    }
    
    /**
     * 获取指定位置的符号
     */
    private String getSymbolAtPosition(String uri, Position position) {
        try {
            // 从URI中提取文件ID
            String fileId = extractFileIdFromUri(uri);
            if (fileId == null) {
                return null;
            }
            
            // 获取文件内容
            String content = fileContents.get(fileId);
            if (content == null) {
                // 尝试从资源服务获取
                MagicResourceService resourceService = MagicConfiguration.getMagicResourceService();
                if (resourceService != null) {
                    MagicEntity entity = resourceService.file(fileId);
                    if (entity != null) {
                        content = entity.getScript();
                    }
                }
            }
            
            if (content == null) {
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
    
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        logger.debug("Go to definition requested at {}:{}", 
                    params.getPosition().getLine(), 
                    params.getPosition().getCharacter());
        
        return CompletableFuture.supplyAsync(() -> {
            List<Location> definitions = new ArrayList<>();
            
            try {
                // 获取当前位置的符号
                String symbol = getSymbolAtPosition(params.getTextDocument().getUri(), params.getPosition());
                if (symbol == null || symbol.trim().isEmpty()) {
                    return Either.forLeft(definitions);
                }
                
                logger.debug("Finding definition for symbol: {}", symbol);
                
                // 在整个工作区中搜索定义
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
            
            return Either.forLeft(definitions);
        });
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
     * 查找内置函数或Magic API特定符号的定义
     */
    private List<Location> findBuiltinDefinitions(String symbol) {
        List<Location> definitions = new ArrayList<>();
        
        // Magic API内置函数列表
        List<String> builtinFunctions = getBuiltinFunctions();
        if (builtinFunctions.contains(symbol)) {
            // 创建一个虚拟的位置指向内置函数文档
            Location location = new Location();
            location.setUri("magic-api://builtin/" + symbol);
            
            Range range = new Range();
            range.setStart(new Position(0, 0));
            range.setEnd(new Position(0, symbol.length()));
            location.setRange(range);
            
            definitions.add(location);
        }
        
        return definitions;
    }
    
    /**
     * 获取Magic API内置函数列表
     */
    private List<String> getBuiltinFunctions() {
        return List.of(
            // 数据库操作
            "db", "select", "selectInt", "selectOne", "selectValue", "insert", "update", "delete",
            // HTTP操作
            "http", "get", "post", "put", "delete", "request",
            // 工具函数
            "json", "xml", "date", "uuid", "md5", "sha1", "sha256", "base64",
            // 字符串操作
            "string", "format", "substring", "indexOf", "replace", "split", "join",
            // 数组操作
            "array", "list", "map", "filter", "reduce", "forEach", "sort",
            // 数学操作
            "math", "random", "abs", "max", "min", "round", "floor", "ceil",
            // 日期操作
            "now", "today", "yesterday", "tomorrow", "formatDate", "parseDate",
            // 验证操作
            "validate", "required", "email", "phone", "idCard", "regex",
            // 缓存操作
            "cache", "get", "set", "remove", "clear", "exists",
            // 日志操作
            "log", "info", "warn", "error", "debug",
            // 文件操作
            "file", "read", "write", "exists", "delete", "mkdir",
            // 加密操作
            "encrypt", "decrypt", "aes", "des", "rsa"
        );
    }

    public CompletableFuture<List<? extends Location>> implementation(ImplementationParams params) {
        logger.debug("Find implementation requested at {}:{}", 
                    params.getPosition().getLine(), 
                    params.getPosition().getCharacter());
        
        // TODO: Implement find implementation functionality
        
        return CompletableFuture.completedFuture(List.of());
    }
    
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(DocumentHighlightParams params) {
        logger.debug("Document highlight requested at {}:{}", 
                    params.getPosition().getLine(), 
                    params.getPosition().getCharacter());
        
        try {
            String uri = params.getTextDocument().getUri();
            Position position = params.getPosition();
            
            // 获取文档内容
            String content = getDocumentContent(uri);
            if (content == null) {
                return CompletableFuture.completedFuture(List.of());
            }
            
            // 获取光标位置的符号
            String symbol = getSymbolAtPosition(content, position);
            if (symbol == null || symbol.trim().isEmpty()) {
                return CompletableFuture.completedFuture(List.of());
            }
            
            // 查找文档中所有符号出现位置
            List<DocumentHighlight> highlights = findSymbolHighlights(content, symbol);
            
            logger.debug("Found {} highlights for symbol '{}' in document {}", 
                        highlights.size(), symbol, uri);
            
            return CompletableFuture.completedFuture(highlights);
            
        } catch (Exception e) {
            logger.error("Error during document highlight", e);
            return CompletableFuture.completedFuture(List.of());
        }
    }
    
    /**
     * 查找文档中符号的所有高亮位置
     */
    private List<DocumentHighlight> findSymbolHighlights(String content, String symbol) {
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
    
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        logger.debug("Code lens requested for: {}", params.getTextDocument().getUri());
        
        try {
            String uri = params.getTextDocument().getUri();
            String content = getDocumentContent(uri);
            if (content == null) {
                return CompletableFuture.completedFuture(List.of());
            }
            
            List<CodeLens> codeLenses = new ArrayList<>();
            
            // 分析代码并生成代码透镜
            codeLenses.addAll(createFunctionCodeLenses(content, uri));
            codeLenses.addAll(createApiCodeLenses(content, uri));
            codeLenses.addAll(createPerformanceCodeLenses(content, uri));
            
            logger.debug("Generated {} code lenses for document {}", codeLenses.size(), uri);
            
            return CompletableFuture.completedFuture(codeLenses);
            
        } catch (Exception e) {
            logger.error("Error during code lens generation", e);
            return CompletableFuture.completedFuture(List.of());
        }
    }
    
    /**
     * 创建函数相关的代码透镜
     */
    private List<CodeLens> createFunctionCodeLenses(String content, String uri) {
        List<CodeLens> codeLenses = new ArrayList<>();
        String[] lines = content.split("\n");
        
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            
            // 检测函数定义
            Matcher functionMatcher = FUNCTION_PATTERN.matcher(line);
            if (functionMatcher.find()) {
                String functionName = functionMatcher.group(1);
                
                // 计算函数引用次数
                int referenceCount = countFunctionReferences(content, functionName);
                
                Range range = new Range(
                    new Position(lineIndex, functionMatcher.start()),
                    new Position(lineIndex, functionMatcher.end())
                );
                
                Command command = new Command();
                command.setTitle(String.format("📊 %d references", referenceCount));
                command.setCommand("magic.showReferences");
                command.setArguments(List.of(uri, new Position(lineIndex, functionMatcher.start()), functionName));
                
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
                    command.setArguments(List.of(uri, asyncMatcher.group(1)));
                    
                    codeLenses.add(new CodeLens(range, command, "async"));
                }
            }
        }
        
        return codeLenses;
    }
    
    /**
     * 创建API相关的代码透镜
     */
    private List<CodeLens> createApiCodeLenses(String content, String uri) {
        List<CodeLens> codeLenses = new ArrayList<>();
        String[] lines = content.split("\n");
        
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            
            // 检测HTTP请求
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
                    command.setArguments(List.of(uri, method, lineIndex));
                    
                    codeLenses.add(new CodeLens(range, command, "http"));
                }
            }
            
            // 检测数据库查询
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
                    command.setArguments(List.of(uri, sqlType, lineIndex));
                    
                    codeLenses.add(new CodeLens(range, command, "sql"));
                }
            }
        }
        
        return codeLenses;
    }
    
    /**
     * 创建性能相关的代码透镜
     */
    private List<CodeLens> createPerformanceCodeLenses(String content, String uri) {
        List<CodeLens> codeLenses = new ArrayList<>();
        String[] lines = content.split("\n");
        
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            
            // 检测循环语句
            if (line.trim().startsWith("for") || line.trim().startsWith("while")) {
                Pattern loopPattern = Pattern.compile("\\b(for|while)\\b");
                Matcher loopMatcher = loopPattern.matcher(line);
                if (loopMatcher.find()) {
                    Range range = new Range(
                        new Position(lineIndex, loopMatcher.start()),
                        new Position(lineIndex, loopMatcher.end())
                    );
                    
                    // 分析循环复杂度
                    String complexity = analyzeLoopComplexity(content, lineIndex);
                    
                    Command command = new Command();
                    command.setTitle(String.format("🔄 %s", complexity));
                    command.setCommand("magic.showPerformanceInfo");
                    command.setArguments(List.of(uri, "loop", lineIndex, complexity));
                    
                    codeLenses.add(new CodeLens(range, command, "performance"));
                }
            }
            
            // 检测可能的性能问题
            if (line.contains("sleep(") || line.contains("Thread.sleep")) {
                Pattern sleepPattern = Pattern.compile("(sleep|Thread\\.sleep)\\s*\\(");
                Matcher sleepMatcher = sleepPattern.matcher(line);
                if (sleepMatcher.find()) {
                    Range range = new Range(
                        new Position(lineIndex, sleepMatcher.start()),
                        new Position(lineIndex, sleepMatcher.end() - 1)
                    );
                    
                    Command command = new Command();
                    command.setTitle("⚠️ Blocking Operation");
                    command.setCommand("magic.showPerformanceWarning");
                    command.setArguments(List.of(uri, "sleep", lineIndex));
                    
                    codeLenses.add(new CodeLens(range, command, "warning"));
                }
            }
        }
        
        return codeLenses;
    }
    
    /**
     * 计算函数引用次数
     */
    private int countFunctionReferences(String content, String functionName) {
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(functionName) + "\\s*\\(");
        Matcher matcher = pattern.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        // 减去函数定义本身
        return Math.max(0, count - 1);
    }
    
    /**
     * 分析循环复杂度
     */
    private String analyzeLoopComplexity(String content, int loopLineIndex) {
        String[] lines = content.split("\n");
        int nestedLoops = 0;
        int braceLevel = 0;
        boolean inLoop = false;
        
        // 从循环开始行向下分析
        for (int i = loopLineIndex; i < lines.length; i++) {
            String line = lines[i].trim();
            
            // 计算大括号层级
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    braceLevel++;
                    if (i == loopLineIndex) inLoop = true;
                } else if (c == '}') {
                    braceLevel--;
                    if (braceLevel == 0 && inLoop) {
                        break; // 循环结束
                    }
                }
            }
            
            // 检测嵌套循环
            if (inLoop && braceLevel > 1 && (line.startsWith("for") || line.startsWith("while"))) {
                nestedLoops++;
            }
            
            if (braceLevel == 0 && inLoop) {
                break;
            }
        }
        
        if (nestedLoops == 0) {
            return "O(n) - Linear";
        } else if (nestedLoops == 1) {
            return "O(n²) - Quadratic";
        } else {
            return "O(n^" + (nestedLoops + 1) + ") - High Complexity";
        }
    }
    
    public CompletableFuture<CodeLens> resolveCodeLens(CodeLens unresolved) {
        logger.debug("Resolve code lens requested");
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 如果已经有命令，直接返回
                if (unresolved.getCommand() != null) {
                    return unresolved;
                }
                
                // 根据data字段解析代码透镜
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
                        // 如果是函数名，显示引用信息
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
                
            } catch (Exception e) {
                logger.error("Error resolving code lens", e);
                return unresolved;
            }
        });
    }
    
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        logger.debug("Document formatting requested for: {}", params.getTextDocument().getUri());
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uri = params.getTextDocument().getUri();
                String content = getDocumentContent(uri);
                if (content == null) {
                    return List.of();
                }
                
                FormattingOptions options = params.getOptions();
                String formattedContent = formatMagicScript(content, options);
                
                if (!content.equals(formattedContent)) {
                    // 创建替换整个文档的TextEdit
                    String[] lines = content.split("\n");
                    Range range = new Range(
                        new Position(0, 0),
                        new Position(lines.length - 1, lines[lines.length - 1].length())
                    );
                    
                    return List.of(new TextEdit(range, formattedContent));
                }
                
                return List.of();
            } catch (Exception e) {
                logger.error("Error during document formatting", e);
                return List.of();
            }
        });
    }
    
    public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
        logger.debug("Range formatting requested for: {}", params.getTextDocument().getUri());
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uri = params.getTextDocument().getUri();
                String content = getDocumentContent(uri);
                if (content == null) {
                    return List.of();
                }
                
                Range range = params.getRange();
                FormattingOptions options = params.getOptions();
                
                // 提取指定范围的内容
                String[] lines = content.split("\n");
                StringBuilder rangeContent = new StringBuilder();
                
                int startLine = range.getStart().getLine();
                int endLine = range.getEnd().getLine();
                int startChar = range.getStart().getCharacter();
                int endChar = range.getEnd().getCharacter();
                
                for (int i = startLine; i <= endLine && i < lines.length; i++) {
                    String line = lines[i];
                    if (i == startLine && i == endLine) {
                        // 单行选择
                        if (startChar < line.length() && endChar <= line.length()) {
                            rangeContent.append(line.substring(startChar, endChar));
                        }
                    } else if (i == startLine) {
                        // 起始行
                        if (startChar < line.length()) {
                            rangeContent.append(line.substring(startChar));
                        }
                        rangeContent.append("\n");
                    } else if (i == endLine) {
                        // 结束行
                        if (endChar <= line.length()) {
                            rangeContent.append(line.substring(0, endChar));
                        } else {
                            rangeContent.append(line);
                        }
                    } else {
                        // 中间行
                        rangeContent.append(line);
                        if (i < endLine) {
                            rangeContent.append("\n");
                        }
                    }
                }
                
                String originalRangeContent = rangeContent.toString();
                String formattedRangeContent = formatMagicScript(originalRangeContent, options);
                
                if (!originalRangeContent.equals(formattedRangeContent)) {
                    return List.of(new TextEdit(range, formattedRangeContent));
                }
                
                return List.of();
            } catch (Exception e) {
                logger.error("Error during range formatting", e);
                return List.of();
            }
        });
    }
    
    public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(DocumentOnTypeFormattingParams params) {
        logger.debug("On type formatting requested for character: {}", params.getCh());
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uri = params.getTextDocument().getUri();
                String content = getDocumentContent(uri);
                if (content == null) {
                    return List.of();
                }
                
                Position position = params.getPosition();
                String ch = params.getCh();
                FormattingOptions options = params.getOptions();
                
                List<TextEdit> edits = new ArrayList<>();
                
                // 根据输入的字符执行不同的格式化操作
                switch (ch) {
                    case "}":
                        // 大括号闭合时，格式化整个代码块
                        edits.addAll(formatOnCloseBrace(content, position, options));
                        break;
                    case ";":
                        // 分号时，格式化当前行
                        edits.addAll(formatOnSemicolon(content, position, options));
                        break;
                    case "\n":
                        // 换行时，调整缩进
                        edits.addAll(formatOnNewline(content, position, options));
                        break;
                    case ")":
                        // 圆括号闭合时，格式化函数调用
                        edits.addAll(formatOnCloseParen(content, position, options));
                        break;
                }
                
                return edits;
            } catch (Exception e) {
                logger.error("Error during on-type formatting", e);
                return List.of();
            }
        });
    }
    
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        logger.debug("Rename requested at {}:{} to '{}'", 
                    params.getPosition().getLine(), 
                    params.getPosition().getCharacter(),
                    params.getNewName());
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uri = params.getTextDocument().getUri();
                Position position = params.getPosition();
                String newName = params.getNewName();
                
                // 验证新名称的有效性
                if (!isValidIdentifier(newName)) {
                    logger.warn("Invalid identifier name: {}", newName);
                    return new WorkspaceEdit();
                }
                
                // 首先尝试使用基于作用域的重命名
                if (textDocumentService != null) {
                    Map<String, List<TextEdit>> scopeBasedChanges = textDocumentService.renameWithScope(params.getTextDocument().getUri(), params.getPosition(), params.getNewName());
                    if (scopeBasedChanges != null && !scopeBasedChanges.isEmpty()) {
                        WorkspaceEdit scopeBasedEdit = new WorkspaceEdit();
                        scopeBasedEdit.setChanges(scopeBasedChanges);
                        logger.debug("Scope-based rename completed. {} files affected", scopeBasedChanges.size());
                        return scopeBasedEdit;
                    }
                }
                
                // 如果基于作用域的重命名没有找到结果，回退到原有的正则表达式搜索
                logger.debug("Falling back to regex-based rename");
                
                // 获取当前文档内容
                String content = getDocumentContent(uri);
                if (content == null) {
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
                    changes.put(uri, currentDocumentEdits);
                }
                
                // 在工作区的其他文档中查找引用并重命名
                findAndRenameInWorkspace(symbolToRename, newName, changes, uri);
                
                workspaceEdit.setChanges(changes);
                
                logger.debug("Regex-based rename operation completed. {} files affected", changes.size());
                return workspaceEdit;
                
            } catch (Exception e) {
                logger.error("Error during rename operation", e);
                return new WorkspaceEdit();
            }
        });
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
        Set<String> keywords = Set.of(
            "var", "let", "const", "function", "return", "if", "else", "for", "while", "do",
            "switch", "case", "default", "break", "continue", "try", "catch", "finally",
            "throw", "new", "this", "super", "class", "extends", "import", "export",
            "true", "false", "null", "undefined", "typeof", "instanceof", "in", "of",
            "async", "await", "yield", "delete", "void", "with", "debugger"
        );
        return keywords.contains(name);
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
    
    public CompletableFuture<List<FoldingRange>> foldingRange(FoldingRangeRequestParams params) {
        logger.debug("Folding range requested for: {}", params.getTextDocument().getUri());
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uri = params.getTextDocument().getUri();
                String content = getDocumentContent(uri);
                if (content == null) {
                    return List.of();
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
                return List.of();
            }
        });
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
    
    // ==================== 代码格式化相关方法 ====================
    
    /**
     * 格式化Magic Script代码
     */
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
                
                // 处理空行
                if (trimmedLine.isEmpty()) {
                    formattedLines.add("");
                    continue;
                }
                
                // 更新字符串和注释状态
                updateParsingState(trimmedLine, inBlockComment, inStringLiteral, stringDelimiter);
                
                // 如果在块注释或字符串字面量中，保持原始格式
                if (inBlockComment || inStringLiteral) {
                    formattedLines.add(line);
                    continue;
                }
                
                // 调整缩进级别
                int currentIndentLevel = calculateIndentLevel(trimmedLine, indentLevel);
                
                // 格式化当前行
                String formattedLine = formatLine(trimmedLine, currentIndentLevel, options);
                formattedLines.add(formattedLine);
                
                // 更新下一行的缩进级别
                indentLevel = updateIndentLevel(trimmedLine, currentIndentLevel);
            }
            
            String result = String.join("\n", formattedLines);
            
            // 应用格式化选项
            result = applyFormattingOptions(result, options);
            
            return result;
        } catch (Exception e) {
            logger.error("Error formatting Magic Script", e);
            return content; // 出错时返回原始内容
        }
    }
    
    /**
     * 更新解析状态（字符串和注释）
     */
    private void updateParsingState(String line, boolean inBlockComment, boolean inStringLiteral, char stringDelimiter) {
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            char nextCh = (i + 1 < line.length()) ? line.charAt(i + 1) : 0;
            
            if (!inStringLiteral) {
                // 检查块注释开始
                if (ch == '/' && nextCh == '*') {
                    inBlockComment = true;
                    i++; // 跳过下一个字符
                    continue;
                }
                
                // 检查块注释结束
                if (inBlockComment && ch == '*' && nextCh == '/') {
                    inBlockComment = false;
                    i++; // 跳过下一个字符
                    continue;
                }
                
                // 检查行注释
                if (ch == '/' && nextCh == '/') {
                    break; // 行注释，跳过行的其余部分
                }
                
                // 检查字符串开始
                if (!inBlockComment && (ch == '"' || ch == '\'' || ch == '`')) {
                    inStringLiteral = true;
                    stringDelimiter = ch;
                    continue;
                }
            } else {
                // 在字符串中，检查字符串结束
                if (ch == stringDelimiter && (i == 0 || line.charAt(i - 1) != '\\')) {
                    inStringLiteral = false;
                    stringDelimiter = 0;
                }
            }
        }
    }
    
    /**
     * 计算当前行的缩进级别
     */
    private int calculateIndentLevel(String trimmedLine, int currentIndentLevel) {
        // 减少缩进的情况
        if (trimmedLine.startsWith("}") || trimmedLine.startsWith("]") || 
            trimmedLine.startsWith(")") || trimmedLine.startsWith("case ") ||
            trimmedLine.startsWith("default:") || trimmedLine.startsWith("else") ||
            trimmedLine.startsWith("catch") || trimmedLine.startsWith("finally")) {
            return Math.max(0, currentIndentLevel - 1);
        }
        
        return currentIndentLevel;
    }
    
    /**
     * 更新下一行的缩进级别
     */
    private int updateIndentLevel(String trimmedLine, int currentIndentLevel) {
        // 增加缩进的情况
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
    
    /**
     * 格式化单行代码
     */
    private String formatLine(String line, int indentLevel, FormattingOptions options) {
        StringBuilder formatted = new StringBuilder();
        
        // 添加缩进
        String indent = createIndent(indentLevel, options);
        formatted.append(indent);
        
        // 格式化操作符周围的空格
        line = formatOperators(line);
        
        // 格式化逗号后的空格
        line = formatCommas(line);
        
        // 格式化分号
        line = formatSemicolons(line);
        
        // 格式化大括号
        line = formatBraces(line);
        
        // 格式化关键字后的空格
        line = formatKeywords(line);
        
        formatted.append(line);
        
        return formatted.toString();
    }
    
    /**
     * 创建缩进字符串
     */
    private String createIndent(int level, FormattingOptions options) {
        if (level <= 0) {
            return "";
        }
        
        if (options.isInsertSpaces()) {
            int tabSize = options.getTabSize();
            return " ".repeat(level * tabSize);
        } else {
            return "\t".repeat(level);
        }
    }
    
    /**
     * 格式化操作符周围的空格
     */
    private String formatOperators(String line) {
        // 二元操作符
        line = line.replaceAll("\\s*([+\\-*/%=!<>]+)\\s*", " $1 ");
        
        // 修复连续操作符
        line = line.replaceAll("\\s+([+\\-*/%=!<>])\\s+([+\\-*/%=!<>])\\s+", " $1$2 ");
        
        // 修复特殊情况
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
    
    /**
     * 格式化逗号后的空格
     */
    private String formatCommas(String line) {
        return line.replaceAll(",\\s*", ", ");
    }
    
    /**
     * 格式化分号
     */
    private String formatSemicolons(String line) {
        return line.replaceAll(";\\s+", "; ");
    }
    
    /**
     * 格式化大括号
     */
    private String formatBraces(String line) {
        // 左大括号前加空格
        line = line.replaceAll("\\s*\\{", " {");
        
        // 右大括号前不加空格（除非是在行首）
        if (!line.trim().startsWith("}")) {
            line = line.replaceAll("\\s*\\}", "}");
        }
        
        return line;
    }
    
    /**
     * 格式化关键字后的空格
     */
    private String formatKeywords(String line) {
        String[] keywords = {"if", "for", "while", "switch", "catch", "function", "return", "var", "let", "const"};
        
        for (String keyword : keywords) {
            line = line.replaceAll("\\b" + keyword + "\\s*\\(", keyword + " (");
            line = line.replaceAll("\\b" + keyword + "\\s+", keyword + " ");
        }
        
        return line;
    }
    
    /**
     * 应用格式化选项
     */
    private String applyFormattingOptions(String content, FormattingOptions options) {
        // 删除行尾空白
        if (options.isTrimTrailingWhitespace()) {
            content = content.replaceAll("[ \t]+$", "");
        }
        
        // 在文件末尾插入换行符
        if (options.isInsertFinalNewline() && !content.endsWith("\n")) {
            content += "\n";
        }
        
        return content;
    }
    
    /**
     * 大括号闭合时的格式化
     */
    private List<TextEdit> formatOnCloseBrace(String content, Position position, FormattingOptions options) {
        List<TextEdit> edits = new ArrayList<>();
        
        try {
            String[] lines = content.split("\n");
            int lineIndex = position.getLine();
            
            if (lineIndex >= 0 && lineIndex < lines.length) {
                String line = lines[lineIndex];
                
                // 找到匹配的开始大括号，格式化整个代码块
                int braceStart = findMatchingOpenBrace(lines, lineIndex, position.getCharacter());
                if (braceStart >= 0) {
                    // 格式化从开始大括号到当前位置的代码块
                    StringBuilder blockContent = new StringBuilder();
                    for (int i = braceStart; i <= lineIndex; i++) {
                        blockContent.append(lines[i]);
                        if (i < lineIndex) {
                            blockContent.append("\n");
                        }
                    }
                    
                    String formattedBlock = formatMagicScript(blockContent.toString(), options);
                    
                    Range range = new Range(
                        new Position(braceStart, 0),
                        new Position(lineIndex, lines[lineIndex].length())
                    );
                    
                    edits.add(new TextEdit(range, formattedBlock));
                }
            }
        } catch (Exception e) {
            logger.error("Error formatting on close brace", e);
        }
        
        return edits;
    }
    
    /**
     * 分号时的格式化
     */
    private List<TextEdit> formatOnSemicolon(String content, Position position, FormattingOptions options) {
        List<TextEdit> edits = new ArrayList<>();
        
        try {
            String[] lines = content.split("\n");
            int lineIndex = position.getLine();
            
            if (lineIndex >= 0 && lineIndex < lines.length) {
                String line = lines[lineIndex];
                String formattedLine = formatLine(line.trim(), 0, options);
                
                // 计算当前行的缩进
                int indentLevel = calculateLineIndent(lines, lineIndex);
                String indent = createIndent(indentLevel, options);
                formattedLine = indent + formattedLine.trim();
                
                if (!line.equals(formattedLine)) {
                    Range range = new Range(
                        new Position(lineIndex, 0),
                        new Position(lineIndex, line.length())
                    );
                    
                    edits.add(new TextEdit(range, formattedLine));
                }
            }
        } catch (Exception e) {
            logger.error("Error formatting on semicolon", e);
        }
        
        return edits;
    }
    
    /**
     * 换行时的格式化
     */
    private List<TextEdit> formatOnNewline(String content, Position position, FormattingOptions options) {
        List<TextEdit> edits = new ArrayList<>();
        
        try {
            String[] lines = content.split("\n");
            int lineIndex = position.getLine();
            
            if (lineIndex > 0 && lineIndex < lines.length) {
                // 计算新行的缩进
                int indentLevel = calculateLineIndent(lines, lineIndex);
                String indent = createIndent(indentLevel, options);
                
                String currentLine = lines[lineIndex];
                if (!currentLine.startsWith(indent)) {
                    String newLine = indent + currentLine.trim();
                    
                    Range range = new Range(
                        new Position(lineIndex, 0),
                        new Position(lineIndex, currentLine.length())
                    );
                    
                    edits.add(new TextEdit(range, newLine));
                }
            }
        } catch (Exception e) {
            logger.error("Error formatting on newline", e);
        }
        
        return edits;
    }
    
    /**
     * 圆括号闭合时的格式化
     */
    private List<TextEdit> formatOnCloseParen(String content, Position position, FormattingOptions options) {
        List<TextEdit> edits = new ArrayList<>();
        
        try {
            String[] lines = content.split("\n");
            int lineIndex = position.getLine();
            
            if (lineIndex >= 0 && lineIndex < lines.length) {
                String line = lines[lineIndex];
                
                // 格式化函数调用的参数
                String formattedLine = formatFunctionCall(line);
                
                if (!line.equals(formattedLine)) {
                    Range range = new Range(
                        new Position(lineIndex, 0),
                        new Position(lineIndex, line.length())
                    );
                    
                    edits.add(new TextEdit(range, formattedLine));
                }
            }
        } catch (Exception e) {
            logger.error("Error formatting on close paren", e);
        }
        
        return edits;
    }
    
    /**
     * 找到匹配的开始大括号
     */
    private int findMatchingOpenBrace(String[] lines, int endLine, int endChar) {
        int braceCount = 1;
        
        // 从当前行开始向上查找
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
    
    /**
     * 计算行的缩进级别
     */
    private int calculateLineIndent(String[] lines, int lineIndex) {
        int indentLevel = 0;
        
        for (int i = 0; i < lineIndex; i++) {
            String line = lines[i].trim();
            
            // 增加缩进的情况
            if (line.endsWith("{") || line.endsWith("[") ||
                line.startsWith("if ") || line.startsWith("for ") ||
                line.startsWith("while ") || line.startsWith("switch ") ||
                line.startsWith("try") || line.startsWith("catch") ||
                line.startsWith("finally") || line.startsWith("else") ||
                line.startsWith("case ") || line.startsWith("default:") ||
                line.startsWith("function ")) {
                indentLevel++;
            }
            
            // 减少缩进的情况
            if (line.startsWith("}") || line.startsWith("]") || 
                line.startsWith(")")) {
                indentLevel = Math.max(0, indentLevel - 1);
            }
        }
        
        return indentLevel;
    }
    
    /**
     * 格式化函数调用
     */
    private String formatFunctionCall(String line) {
        // 格式化函数参数之间的空格
        return line.replaceAll("\\(\\s*", "(")
                  .replaceAll("\\s*\\)", ")")
                  .replaceAll(",\\s*", ", ");
    }

    /**
     * 获取文档内容
     */
    private String getDocumentContent(String uri) {
        if (textDocumentService != null) {
            return textDocumentService.getDocumentContent(uri);
        }
        return fileContents.get(uri);
    }
}