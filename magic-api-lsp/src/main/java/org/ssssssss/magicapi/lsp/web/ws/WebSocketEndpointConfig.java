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
        return ServerEndpointConfig.Builder.create(LspEndpoint.class, magicApiWebPrefix + "/lsp").build();
    }

    @Bean
    public ServerEndpointConfig debugEndpointConfig() {
        return ServerEndpointConfig.Builder.create(DebugEndpoint.class, magicApiWebPrefix + "/debug").build();
    }
}