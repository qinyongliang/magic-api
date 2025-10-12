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
    
    /**
     * Start the Language Server on a specific port (for socket communication)
     * 
     * @param port The port to listen on
     */
    public static void startServerOnPort(int port) {
        logger.info("Starting Magic API Language Server on port {}", port);
        
        try {
            java.net.ServerSocket serverSocket = new java.net.ServerSocket(port);
            logger.info("Language Server listening on port {}", port);
            
            while (true) {
                java.net.Socket clientSocket = serverSocket.accept();
                logger.info("Client connected from {}", clientSocket.getRemoteSocketAddress());
                
                // Handle each client in a separate thread
                new Thread(() -> {
                    try {
                        startServer(clientSocket.getInputStream(), clientSocket.getOutputStream());
                    } catch (Exception e) {
                        logger.error("Error handling client connection", e);
                    } finally {
                        try {
                            clientSocket.close();
                        } catch (Exception e) {
                            logger.warn("Error closing client socket", e);
                        }
                    }
                }).start();
            }
        } catch (Exception e) {
            logger.error("Failed to start Language Server on port {}", port, e);
            throw new RuntimeException(e);
        }
    }
}