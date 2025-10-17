package org.ssssssss.magicapi.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.SemanticTokenTypes;
import org.eclipse.lsp4j.SemanticTokenModifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Magic API Language Server Implementation
 * 
 * @author magic-api-team
 */
public class MagicLanguageServer implements LanguageServer, LanguageClientAware {
    
    private static final Logger logger = LoggerFactory.getLogger(MagicLanguageServer.class);
    
    private LanguageClient client;
    private MagicTextDocumentService textDocumentService;
    private MagicWorkspaceService workspaceService;
    
    public MagicLanguageServer() {
        this.textDocumentService = new MagicTextDocumentService();
        this.workspaceService = new MagicWorkspaceService();
        logger.info("Magic API Language Server initialized");
    }
    
    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        logger.info("Initializing Magic API Language Server");
        
        // Server capabilities
        ServerCapabilities capabilities = new ServerCapabilities();
        
        // Text document sync
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        
        // Completion support
        CompletionOptions completionOptions = new CompletionOptions();
        completionOptions.setResolveProvider(true);
        completionOptions.setTriggerCharacters(java.util.Arrays.asList(".", "(", " "));
        capabilities.setCompletionProvider(completionOptions);
        
        // Hover support
        capabilities.setHoverProvider(true);
        
        // Document symbol support
        capabilities.setDocumentSymbolProvider(true);
        
        // Diagnostic support
        capabilities.setDiagnosticProvider(new DiagnosticRegistrationOptions());
        
        // Formatting support
        capabilities.setDocumentFormattingProvider(true);
        
        // Definition support
        capabilities.setDefinitionProvider(true);
        
        // References support
        capabilities.setReferencesProvider(true);

        // CodeLens provider support
        CodeLensOptions codeLensOptions = new CodeLensOptions();
        codeLensOptions.setResolveProvider(true);
        capabilities.setCodeLensProvider(codeLensOptions);

        // Semantic tokens provider (full & range) with legend aligned to tokenizer
        SemanticTokensLegend legend = new SemanticTokensLegend(
                java.util.Arrays.asList(
                        SemanticTokenTypes.Namespace,
                        SemanticTokenTypes.Type,
                        SemanticTokenTypes.Class,
                        SemanticTokenTypes.Enum,
                        SemanticTokenTypes.Interface,
                        SemanticTokenTypes.Struct,
                        SemanticTokenTypes.TypeParameter,
                        SemanticTokenTypes.Parameter,
                        SemanticTokenTypes.Variable,
                        SemanticTokenTypes.Property,
                        SemanticTokenTypes.EnumMember,
                        SemanticTokenTypes.Event,
                        SemanticTokenTypes.Function,
                        SemanticTokenTypes.Method,
                        SemanticTokenTypes.Macro,
                        SemanticTokenTypes.Keyword,
                        SemanticTokenTypes.Modifier,
                        SemanticTokenTypes.Comment,
                        SemanticTokenTypes.String,
                        SemanticTokenTypes.Number,
                        SemanticTokenTypes.Regexp,
                        SemanticTokenTypes.Operator
                ),
                java.util.Arrays.asList(
                        SemanticTokenModifiers.Declaration,
                        SemanticTokenModifiers.Definition,
                        SemanticTokenModifiers.Readonly,
                        SemanticTokenModifiers.Static,
                        SemanticTokenModifiers.Deprecated,
                        SemanticTokenModifiers.Abstract,
                        SemanticTokenModifiers.Async,
                        SemanticTokenModifiers.Modification,
                        SemanticTokenModifiers.Documentation,
                        SemanticTokenModifiers.DefaultLibrary
                )
        );
        SemanticTokensWithRegistrationOptions semanticTokensOptions = new SemanticTokensWithRegistrationOptions(legend, true, true);
        capabilities.setSemanticTokensProvider(semanticTokensOptions);
        
        InitializeResult result = new InitializeResult(capabilities);
        
        // Server info
        ServerInfo serverInfo = new ServerInfo();
        serverInfo.setName("Magic API Language Server");
        serverInfo.setVersion("1.0.0");
        result.setServerInfo(serverInfo);
        
        logger.info("Magic API Language Server initialized successfully");
        return CompletableFuture.completedFuture(result);
    }
    
    @Override
    public CompletableFuture<Object> shutdown() {
        logger.info("Shutting down Magic API Language Server");
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public void exit() {
        // LSP 'exit' 通知表示客户端会话结束。对于嵌入式 Web 应用，不能直接终止整个 JVM。
        // 这里仅记录日志并让传输层（WebSocket/STDIO）自行关闭，保持服务端进程存活。
        logger.info("Magic API Language Server exit requested by client; keeping server alive (no System.exit)");
    }
    
    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }
    
    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }
    
    @Override
    public void connect(LanguageClient client) {
        this.client = client;
        this.textDocumentService.setClient(client);
        this.workspaceService.setClient(client);
        logger.info("Language client connected");
    }
    
    public LanguageClient getClient() {
        return client;
    }

    /**
     * Handle client trace configuration changes to avoid UnsupportedOperationException.
     * LSP 3.16+: client may send $/setTrace with values: 'off' | 'messages' | 'verbose'.
     */
    @Override
    public void setTrace(SetTraceParams params) {
        try {
            String value = params != null && params.getValue() != null ? params.getValue().toString() : "null";
            logger.info("SetTrace notification received: {}", value);
        } catch (Throwable ignore) {
            // No-op: keep server stable even if params shape differs
        }
    }
}