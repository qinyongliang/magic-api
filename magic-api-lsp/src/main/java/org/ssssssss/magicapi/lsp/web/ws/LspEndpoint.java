package org.ssssssss.magicapi.lsp.web.ws;

import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.websocket.WebSocketLauncherBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.ssssssss.magicapi.lsp.MagicLanguageServer;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;

/**
 * WebSocket endpoint for LSP server on single host:port.
 * Path: /lsp
 */
@Component
public class LspEndpoint {

    private static final Logger logger = LoggerFactory.getLogger(LspEndpoint.class);

    private volatile Future<?> listening;

    @OnOpen
    public void onOpen(Session session) {
        logger.info("LSP WebSocket connection opened: {}", session.getId());
        try {
            MagicLanguageServer server = new MagicLanguageServer();
            WebSocketLauncherBuilder<LanguageClient> builder = new WebSocketLauncherBuilder<>();
            builder.setSession(session);
            // Use default WebSocket transport provided by the builder for lsp4j 0.21.x
            builder.setLocalService(server);
            builder.setRemoteInterface(LanguageClient.class);
            builder.setExecutorService(Executors.newCachedThreadPool());
            Launcher<LanguageClient> launcher = builder.create();
            LanguageClient client = launcher.getRemoteProxy();
            server.connect(client);
            listening = launcher.startListening();
            logger.info("LSP WebSocket session {} started listening", session.getId());
        } catch (Throwable t) {
            logger.error("Failed to start LSP over WebSocket for session {}", session.getId(), t);
            try { session.close(); } catch (Throwable ignored) {}
        }
    }

    @OnError
    public void onError(Session session, Throwable t) {
        logger.error("LSP WebSocket error on session {}", session != null ? session.getId() : "n/a", t);
    }

    @OnClose
    public void onClose(Session session) {
        logger.info("LSP WebSocket connection closed: {}", session.getId());
        Future<?> f = listening;
        if (f != null) {
            try { f.cancel(true); } catch (Throwable ignored) {}
        }
    }
}