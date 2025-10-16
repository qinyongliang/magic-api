package org.ssssssss.magicapi.lsp.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.core.config.MagicConfiguration;

import java.util.Map;

/**
 * Centralized configuration handler for Magic API LSP.
 */
public class ConfigurationService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationService.class);

    public static void apply(JsonObject config, MagicConfiguration magicConfiguration) {
        if (config == null) return;

        // magic-api section
        if (config.has("magic-api")) {
            JsonElement magicApiConfig = config.get("magic-api");
            if (magicApiConfig.isJsonObject()) {
                JsonObject magicApiObj = magicApiConfig.getAsJsonObject();

                // debug
                if (magicApiObj.has("debug")) {
                    JsonObject debug = magicApiObj.getAsJsonObject("debug");
                    if (debug.has("timeout")) {
                        int timeout = debug.get("timeout").getAsInt();
                        logger.info("Debug timeout changed to: {} seconds", timeout);
                        if (magicConfiguration != null) {
                            magicConfiguration.setDebugTimeout(timeout);
                        }
                    }
                }

                // resource
                if (magicApiObj.has("resource")) {
                    JsonObject resource = magicApiObj.getAsJsonObject("resource");
                    if (resource.has("type")) {
                        logger.info("Resource type changed to: {}", resource.get("type").getAsString());
                    }
                    if (resource.has("location")) {
                        logger.info("Resource location changed to: {}", resource.get("location").getAsString());
                    }
                    if (resource.has("datasource")) {
                        logger.info("Resource datasource changed to: {}", resource.get("datasource").getAsString());
                    }
                }

                // security
                if (magicApiObj.has("security")) {
                    JsonObject security = magicApiObj.getAsJsonObject("security");
                    if (security.has("username")) {
                        logger.info("Security username configuration changed");
                    }
                    if (security.has("password")) {
                        logger.info("Security password configuration changed");
                    }
                }

                // cache
                if (magicApiObj.has("cache")) {
                    JsonObject cache = magicApiObj.getAsJsonObject("cache");
                    if (cache.has("enable")) {
                        logger.info("Cache enable changed to: {}", cache.get("enable").getAsBoolean());
                    }
                    if (cache.has("capacity")) {
                        logger.info("Cache capacity changed to: {}", cache.get("capacity").getAsInt());
                    }
                    if (cache.has("ttl")) {
                        logger.info("Cache TTL changed to: {} ms", cache.get("ttl").getAsLong());
                    }
                }

                // script execution knobs
                if (magicApiObj.has("threadPoolExecutorSize")) {
                    logger.info("Thread pool executor size changed to: {}", magicApiObj.get("threadPoolExecutorSize").getAsInt());
                }
                if (magicApiObj.has("compileCacheSize")) {
                    logger.info("Compile cache size changed to: {}", magicApiObj.get("compileCacheSize").getAsInt());
                }
            }
        }

        // lsp section
        if (config.has("magicScript")) {
            JsonElement lspConfig = config.get("magicScript");
            if (lspConfig.isJsonObject()) {
                JsonObject lsp = lspConfig.getAsJsonObject();
                if (lsp.has("completion")) {
                    JsonObject completion = lsp.getAsJsonObject("completion");
                    if (completion.has("enabled")) {
                        logger.info("Completion enabled changed to: {}", completion.get("enabled").getAsBoolean());
                    }
                }
                if (lsp.has("hover")) {
                    JsonObject hover = lsp.getAsJsonObject("hover");
                    if (hover.has("enabled")) {
                        logger.info("Hover enabled changed to: {}", hover.get("enabled").getAsBoolean());
                    }
                }
                if (lsp.has("validation")) {
                    JsonObject validation = lsp.getAsJsonObject("validation");
                    if (validation.has("enabled")) {
                        logger.info("Validation enabled changed to: {}", validation.get("enabled").getAsBoolean());
                    }
                }
                if (lsp.has("definition")) {
                    JsonObject definition = lsp.getAsJsonObject("definition");
                    if (definition.has("enabled")) {
                        logger.info("Definition enabled changed to: {}", definition.get("enabled").getAsBoolean());
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static void apply(Map<String, Object> config, MagicConfiguration magicConfiguration) {
        if (config == null) return;

        if (config.containsKey("magic-api")) {
            Object magicApiObj = config.get("magic-api");
            if (magicApiObj instanceof Map) {
                Map<String, Object> magicApi = (Map<String, Object>) magicApiObj;
                Object debugObj = magicApi.get("debug");
                if (debugObj instanceof Map) {
                    Map<String, Object> debug = (Map<String, Object>) debugObj;
                    Object timeoutObj = debug.get("timeout");
                    if (timeoutObj instanceof Number) {
                        int timeout = ((Number) timeoutObj).intValue();
                        logger.info("Debug timeout changed to: {} seconds", timeout);
                        if (magicConfiguration != null) {
                            magicConfiguration.setDebugTimeout(timeout);
                        }
                    }
                }

                Object resourceObj = magicApi.get("resource");
                if (resourceObj instanceof Map) {
                    Map<String, Object> res = (Map<String, Object>) resourceObj;
                    if (res.containsKey("type")) {
                        logger.info("Resource type changed to: {}", res.get("type"));
                    }
                    if (res.containsKey("location")) {
                        logger.info("Resource location changed to: {}", res.get("location"));
                    }
                }
            }
        }
    }
}