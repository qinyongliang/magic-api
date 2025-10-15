package org.ssssssss.magicapi.lsp.web.ws;

import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.websocket.WebSocketLauncherBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.ssssssss.magicapi.lsp.debug.MagicDebugAdapter;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;

/**
 * WebSocket endpoint for DAP server on single host:port.
 * Path: /debug
 */
@Component
public class DebugEndpoint {

    private static final Logger logger = LoggerFactory.getLogger(DebugEndpoint.class);

    private volatile Future<?> listening;

    @OnOpen
    public void onOpen(Session session) {
        logger.info("Debug WebSocket connection opened: {}", session.getId());
        try {
            MagicDebugAdapter adapter = new MagicDebugAdapter();
            WebSocketLauncherBuilder<IDebugProtocolClient> builder = new WebSocketLauncherBuilder<>();
            builder.setSession(session);
            // Use default WebSocket transport provided by the builder for lsp4j 0.21.x
            builder.setLocalService(adapter);
            builder.setRemoteInterface(IDebugProtocolClient.class);
            builder.setExecutorService(Executors.newCachedThreadPool());
            Launcher<IDebugProtocolClient> launcher = builder.create();
            IDebugProtocolClient client = launcher.getRemoteProxy();
            adapter.connect(client);
            listening = launcher.startListening();
            logger.info("Debug WebSocket session {} started listening", session.getId());
        } catch (Throwable t) {
            logger.error("Failed to start Debug over WebSocket for session {}", session.getId(), t);
            try { session.close(); } catch (Throwable ignored) {}
        }
    }

    @OnError
    public void onError(Session session, Throwable t) {
        logger.error("Debug WebSocket error on session {}", session != null ? session.getId() : "n/a", t);
    }

    @OnClose
    public void onClose(Session session) {
        logger.info("Debug WebSocket connection closed: {}", session.getId());
        Future<?> f = listening;
        if (f != null) {
            try { f.cancel(true); } catch (Throwable ignored) {}
        }
    }
}