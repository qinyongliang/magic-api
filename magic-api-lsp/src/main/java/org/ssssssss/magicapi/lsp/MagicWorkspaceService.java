package org.ssssssss.magicapi.lsp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.commons.collections.ListUtils;
import org.apache.commons.compress.utils.Lists;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.lsp4j.*;
// Use fully-qualified Either to avoid classpath ambiguities
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
import org.ssssssss.magicapi.lsp.config.ConfigurationService;
import org.ssssssss.magicapi.lsp.provider.SymbolProvider;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Magic API Workspace Service Implementation
 * 
 * @author magic-api-team
 */
public class MagicWorkspaceService implements WorkspaceService {
    
    private static final Logger logger = LoggerFactory.getLogger(MagicWorkspaceService.class);
    
    
    
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
    
    // Workspace 级提供者（仅符号查询）
    private final SymbolProvider symbolProvider = new SymbolProvider();
    
    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }
    
    public void setTextDocumentService(MagicTextDocumentService textDocumentService) {
        this.textDocumentService = textDocumentService;
    }
    
    public void setClient(LanguageClient client) {
        this.client = client;
    }
    
    public CompletableFuture<org.eclipse.lsp4j.jsonrpc.messages.Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) {
        logger.debug("Workspace symbol search requested: {}", params.getQuery());
        
        return CompletableFuture.supplyAsync(() -> {
            List<SymbolInformation> symbols = symbolProvider.searchWorkspaceSymbols(params.getQuery());
            return org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(symbols);
        });
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
        ConfigurationService.apply(config, magicConfiguration);
    }
    
    /**
     * 处理Map格式的配置
     */
    private void handleMagicApiConfiguration(Map<String, Object> config) {
        ConfigurationService.apply(config, magicConfiguration);
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
        // 已迁移到 ConfigurationService.apply(JsonObject,...)
        ConfigurationService.apply(lspConfig, magicConfiguration);
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
    
    
}