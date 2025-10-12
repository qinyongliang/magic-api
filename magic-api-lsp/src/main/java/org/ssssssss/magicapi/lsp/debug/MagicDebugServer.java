package org.ssssssss.magicapi.lsp.debug;

import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Future;

/**
 * Magic API Debug Server Launcher
 * 
 * @author magic-api-team
 */
public class MagicDebugServer {
    
    private static final Logger logger = LoggerFactory.getLogger(MagicDebugServer.class);
    
    public static void main(String[] args) {
        logger.info("Starting Magic API Debug Server...");
        
        try {
            startDebugServer(System.in, System.out);
        } catch (Exception e) {
            logger.error("Failed to start Magic API Debug Server", e);
            System.exit(1);
        }
    }
    
    /**
     * Start the Debug Server with custom input/output streams
     * 
     * @param in  Input stream for Debug Adapter Protocol communication
     * @param out Output stream for Debug Adapter Protocol communication
     */
    public static void startDebugServer(InputStream in, OutputStream out) {
        logger.info("Initializing Magic API Debug Server...");
        
        // Create the debug adapter instance
        MagicDebugAdapter debugAdapter = new MagicDebugAdapter();
        
        // Create the launcher for Debug Adapter Protocol
        Launcher<IDebugProtocolClient> launcher = DSPLauncher.createServerLauncher(debugAdapter, in, out);
        
        // Connect the client to the debug adapter
        IDebugProtocolClient client = launcher.getRemoteProxy();
        debugAdapter.connect(client);
        
        logger.info("Magic API Debug Server started successfully");
        
        // Start listening for client requests
        Future<?> startListening = launcher.startListening();
        
        try {
            // Wait for the server to finish
            startListening.get();
        } catch (Exception e) {
            logger.error("Debug Server execution interrupted", e);
        }
        
        logger.info("Magic API Debug Server stopped");
    }
    
    /**
     * Start the Debug Server on a specific port (for socket communication)
     * 
     * @param port The port to listen on
     */
    public static void startDebugServerOnPort(int port) {
        logger.info("Starting Magic API Debug Server on port {}", port);
        
        try {
            java.net.ServerSocket serverSocket = new java.net.ServerSocket(port);
            logger.info("Debug Server listening on port {}", port);
            
            while (true) {
                java.net.Socket clientSocket = serverSocket.accept();
                logger.info("Debug client connected from {}", clientSocket.getRemoteSocketAddress());
                
                // Handle each client in a separate thread
                new java.lang.Thread(() -> {
                    try {
                        startDebugServer(clientSocket.getInputStream(), clientSocket.getOutputStream());
                    } catch (Exception e) {
                        logger.error("Error handling debug client connection", e);
                    } finally {
                        try {
                            clientSocket.close();
                        } catch (Exception e) {
                            logger.warn("Error closing debug client socket", e);
                        }
                    }
                }).start();
            }
        } catch (Exception e) {
            logger.error("Failed to start Debug Server on port {}", port, e);
            throw new RuntimeException(e);
        }
    }
}