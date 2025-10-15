package org.ssssssss.magicapi.lsp.web.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.websocket.server.ServerEndpointConfig;

@Configuration
public class WebSocketEndpointConfig {

    @Value("${magic-api.web:/}")
    private String magicApiWebPrefix;

    @Bean
    public ServerEndpointConfig lspEndpointConfig() {
        String base = normalizePrefix(magicApiWebPrefix);
        return ServerEndpointConfig.Builder.create(LspEndpoint.class, base + "/lsp").build();
    }

    @Bean
    public ServerEndpointConfig debugEndpointConfig() {
        String base = normalizePrefix(magicApiWebPrefix);
        return ServerEndpointConfig.Builder.create(DebugEndpoint.class, base + "/debug").build();
    }

    private String normalizePrefix(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return "";
        }
        String p = prefix.trim();
        // Ensure leading slash
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        // Remove trailing slash except for root
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        // For root "/", return empty base so paths become "/lsp" etc.
        return "/".equals(p) ? "" : p;
    }
}