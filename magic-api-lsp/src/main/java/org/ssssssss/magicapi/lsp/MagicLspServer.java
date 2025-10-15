package org.ssssssss.magicapi.lsp;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Future;

/**
 * Magic API Language Server Launcher
 * 
 * @author magic-api-team
 */
public class MagicLspServer {
    
    private static final Logger logger = LoggerFactory.getLogger(MagicLspServer.class);
    
    public static void main(String[] args) {
        logger.info("Starting Magic API Language Server...");
        
        try {
            startServer(System.in, System.out);
        } catch (Exception e) {
            logger.error("Failed to start Magic API Language Server", e);
            System.exit(1);
        }
    }
    
    /**
     * Start the Language Server with custom input/output streams
     * 
     * @param in  Input stream for LSP communication
     * @param out Output stream for LSP communication
     */
    public static void startServer(InputStream in, OutputStream out) {
        logger.info("Initializing Magic API Language Server...");
        
        // Create the language server instance
        MagicLanguageServer server = new MagicLanguageServer();
        
        // Create the launcher
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out);
        
        // Connect the client to the server
        LanguageClient client = launcher.getRemoteProxy();
        server.connect(client);
        
        logger.info("Magic API Language Server started successfully");
        
        // Start listening for client requests
        Future<?> startListening = launcher.startListening();
        
        try {
            // Wait for the server to finish
            startListening.get();
        } catch (Exception e) {
            logger.error("Language Server execution interrupted", e);
        }
        
        logger.info("Magic API Language Server stopped");
    }
}