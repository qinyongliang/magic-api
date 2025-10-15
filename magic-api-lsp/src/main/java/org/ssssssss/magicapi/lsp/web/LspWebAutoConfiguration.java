package org.ssssssss.magicapi.lsp.web;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

@Configuration
@ComponentScan(basePackages = "org.ssssssss.magicapi.lsp.web")
public class LspWebAutoConfiguration {
    /**
     * Register ServerEndpointExporter to enable @ServerEndpoint endpoints
     * when running within a Spring Boot web application using an embedded
     * servlet container that supports WebSocket (e.g., Tomcat).
     */
    @Bean
    @ConditionalOnClass(ServerEndpointExporter.class)
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }
}