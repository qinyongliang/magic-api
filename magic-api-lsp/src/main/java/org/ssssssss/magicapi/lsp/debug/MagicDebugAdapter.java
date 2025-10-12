package org.ssssssss.magicapi.lsp.debug;

import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.script.MagicScript;
import org.ssssssss.script.MagicScriptContext;
import org.ssssssss.script.MagicScriptDebugContext;
import org.ssssssss.script.MagicScriptEngine;
import org.ssssssss.script.exception.MagicScriptException;
import org.ssssssss.script.MagicScriptEngineFactory;
import org.ssssssss.script.parsing.Span;
import org.ssssssss.script.runtime.MagicScriptRuntime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Magic API Debug Adapter Implementation
 * 
 * @author magic-api-team
 */
public class MagicDebugAdapter implements IDebugProtocolServer {
    
    private static final Logger logger = LoggerFactory.getLogger(MagicDebugAdapter.class);
    
    private IDebugProtocolClient client;
    private final AtomicInteger nextBreakpointId = new AtomicInteger(1);
    private final Map<Integer, SourceBreakpoint> breakpoints = new HashMap<>();
    private final Map<String, String> sourceFiles = new HashMap<>();
    
    // Debug session state
    private boolean isRunning = false;
    private boolean isPaused = false;
    private int currentThreadId = 1;
    private final List<StackFrame> callStack = new ArrayList<>();
    
    // Magic Script debug context
    private MagicScriptDebugContext debugContext;
    private MagicScript currentScript;
    private MagicScriptEngine scriptEngine;
    private final Map<String, Object> currentVariables = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> lineBreakpoints = new ConcurrentHashMap<>();
    private String currentSourcePath;
    private java.lang.Thread debugThread;
    
    public void connect(IDebugProtocolClient client) {
        this.client = client;
        logger.info("Debug adapter connected to client");
    }
    
    @Override
    public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
        logger.info("Debug adapter initialize request received");
        
        Capabilities capabilities = new Capabilities();
        capabilities.setSupportsConfigurationDoneRequest(true);
        capabilities.setSupportsEvaluateForHovers(true);
        capabilities.setSupportsStepBack(false);
        capabilities.setSupportsSetVariable(true);
        capabilities.setSupportsRestartFrame(false);
        capabilities.setSupportsGotoTargetsRequest(false);
        capabilities.setSupportsStepInTargetsRequest(false);
        capabilities.setSupportsCompletionsRequest(true);
        capabilities.setSupportsModulesRequest(false);
        capabilities.setSupportsRestartRequest(true);
        capabilities.setSupportsExceptionOptions(false);
        capabilities.setSupportsValueFormattingOptions(true);
        capabilities.setSupportsExceptionInfoRequest(false);
        capabilities.setSupportTerminateDebuggee(true);
        capabilities.setSupportSuspendDebuggee(true);
        capabilities.setSupportsDelayedStackTraceLoading(false);
        capabilities.setSupportsLoadedSourcesRequest(false);
        capabilities.setSupportsLogPoints(true);
        capabilities.setSupportsTerminateThreadsRequest(false);
        capabilities.setSupportsSetExpression(false);
        capabilities.setSupportsTerminateRequest(true);
        capabilities.setSupportsDataBreakpoints(false);
        capabilities.setSupportsReadMemoryRequest(false);
        capabilities.setSupportsWriteMemoryRequest(false);
        capabilities.setSupportsDisassembleRequest(false);
        capabilities.setSupportsSteppingGranularity(false);
        capabilities.setSupportsInstructionBreakpoints(false);
        
        return CompletableFuture.completedFuture(capabilities);
    }
    
    @Override
    public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments args) {
        logger.info("Configuration done");
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> launch(Map<String, Object> args) {
        logger.info("Launch debug session with args: {}", args);
        
        // Extract launch configuration
        String program = (String) args.get("program");
        String workspaceRoot = (String) args.get("workspaceRoot");
        
        logger.info("Launching Magic API script: {}", program);
        
        try {
            // Initialize Magic Script engine via factory to match constructor signature
            scriptEngine = new MagicScriptEngine(new MagicScriptEngineFactory());
            currentSourcePath = program;
            
            // Read script content
            String scriptContent = Files.readString(Paths.get(program));
            
            // Create debug context with current breakpoints
            List<Integer> breakpointLines = new ArrayList<>(lineBreakpoints.values());
            debugContext = new MagicScriptDebugContext(breakpointLines);
            
            // Set debug callback
            debugContext.setCallback(this::onDebugPause);
            
            // Compile script with debug support
            String debugScript = MagicScript.DEBUG_MARK + scriptContent;
            currentScript = MagicScript.create(debugScript, scriptEngine);
            
            // Initialize debug session
            isRunning = true;
            isPaused = false;
            
            // Send initialized event
            if (client != null) {
                client.initialized();
            }
            
            // Start script execution in separate thread
            debugThread = new java.lang.Thread(this::executeScript);
            debugThread.setName("Magic Script Debug Thread");
            debugThread.start();
            
        } catch (IOException e) {
            logger.error("Failed to read script file: {}", program, e);
            if (client != null) {
                TerminatedEventArguments terminatedEvent = new TerminatedEventArguments();
                client.terminated(terminatedEvent);
            }
        } catch (Exception e) {
            logger.error("Failed to initialize debug session", e);
            if (client != null) {
                TerminatedEventArguments terminatedEvent = new TerminatedEventArguments();
                client.terminated(terminatedEvent);
            }
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> attach(Map<String, Object> args) {
        logger.info("Attach debug session with args: {}", args);
        
        // TODO: Implement attach functionality for running Magic API instances
        
        isRunning = true;
        isPaused = false;
        
        if (client != null) {
            client.initialized();
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> restart(RestartArguments args) {
        logger.info("Restart debug session");
        
        // TODO: Implement restart functionality
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> disconnect(DisconnectArguments args) {
        logger.info("Disconnect debug session");
        
        isRunning = false;
        isPaused = false;
        breakpoints.clear();
        callStack.clear();
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> terminate(TerminateArguments args) {
        logger.info("Terminate debug session");
        
        isRunning = false;
        
        if (client != null) {
            TerminatedEventArguments terminatedEvent = new TerminatedEventArguments();
            client.terminated(terminatedEvent);
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<SetBreakpointsResponse> setBreakpoints(SetBreakpointsArguments args) {
        logger.info("Set breakpoints for source: {}", args.getSource().getPath());
        
        String sourcePath = args.getSource().getPath();
        SourceBreakpoint[] sourceBreakpoints = args.getBreakpoints();
        
        List<Breakpoint> resultBreakpoints = new ArrayList<>();
        
        // Clear existing breakpoints for this source
        lineBreakpoints.clear();
        
        if (sourceBreakpoints != null) {
            for (SourceBreakpoint sourceBreakpoint : sourceBreakpoints) {
                int id = nextBreakpointId.getAndIncrement();
                int line = sourceBreakpoint.getLine();
                
                breakpoints.put(id, sourceBreakpoint);
                lineBreakpoints.put(id, line);
                
                Breakpoint breakpoint = new Breakpoint();
                breakpoint.setId(id);
                breakpoint.setVerified(true);
                breakpoint.setLine(line);
                breakpoint.setSource(args.getSource());
                
                resultBreakpoints.add(breakpoint);
                
                logger.debug("Set breakpoint {} at line {}", id, line);
            }
            
            // Update debug context if it exists
            if (debugContext != null) {
                List<Integer> breakpointLines = new ArrayList<>(lineBreakpoints.values());
                debugContext.setBreakpoints(breakpointLines);
            }
        }
        
        SetBreakpointsResponse response = new SetBreakpointsResponse();
        response.setBreakpoints(resultBreakpoints.toArray(new Breakpoint[0]));
        
        return CompletableFuture.completedFuture(response);
    }
    
    @Override
    public CompletableFuture<SetExceptionBreakpointsResponse> setExceptionBreakpoints(SetExceptionBreakpointsArguments args) {
        logger.info("Set exception breakpoints: {}", Arrays.toString(args.getFilters()));
        
        // TODO: Implement exception breakpoints
        SetExceptionBreakpointsResponse response = new SetExceptionBreakpointsResponse();
        response.setBreakpoints(new Breakpoint[0]);
        return CompletableFuture.completedFuture(response);
    }
    
    @Override
    public CompletableFuture<ContinueResponse> continue_(ContinueArguments args) {
        logger.info("Continue execution for thread: {}", args.getThreadId());
        
        isPaused = false;
        
        if (debugContext != null) {
            debugContext.setStepInto(false);
            try {
                debugContext.singal();
            } catch (InterruptedException e) {
                logger.warn("Interrupted while continuing execution", e);
                java.lang.Thread.currentThread().interrupt();
            }
        }
        
        if (client != null) {
            ContinuedEventArguments continuedEvent = new ContinuedEventArguments();
            continuedEvent.setThreadId(args.getThreadId());
            client.continued(continuedEvent);
        }
        ContinueResponse response = new ContinueResponse();
        response.setAllThreadsContinued(Boolean.TRUE);
        return CompletableFuture.completedFuture(response);
    }
    
    @Override
    public CompletableFuture<Void> next(NextArguments args) {
        logger.info("Step over for thread: {}", args.getThreadId());
        
        isPaused = false;
        
        if (debugContext != null) {
            debugContext.setStepInto(true);
            try {
                debugContext.singal();
            } catch (InterruptedException e) {
                logger.warn("Interrupted while stepping over", e);
                java.lang.Thread.currentThread().interrupt();
            }
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> stepIn(StepInArguments args) {
        logger.info("Step into for thread: {}", args.getThreadId());
        
        isPaused = false;
        
        if (debugContext != null) {
            debugContext.setStepInto(true);
            try {
                debugContext.singal();
            } catch (InterruptedException e) {
                logger.warn("Interrupted while stepping into", e);
                java.lang.Thread.currentThread().interrupt();
            }
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> stepOut(StepOutArguments args) {
        logger.info("Step out for thread: {}", args.getThreadId());
        
        isPaused = false;
        
        if (debugContext != null) {
            debugContext.setStepInto(false);
            try {
                debugContext.singal();
            } catch (InterruptedException e) {
                logger.warn("Interrupted while stepping out", e);
                java.lang.Thread.currentThread().interrupt();
            }
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> pause(PauseArguments args) {
        logger.info("Pause execution for thread: {}", args.getThreadId());
        
        isPaused = true;
        
        if (client != null) {
            StoppedEventArguments stoppedEvent = new StoppedEventArguments();
            stoppedEvent.setReason("pause");
            stoppedEvent.setThreadId(args.getThreadId());
            client.stopped(stoppedEvent);
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<StackTraceResponse> stackTrace(StackTraceArguments args) {
        logger.info("Stack trace requested for thread: {}", args.getThreadId());
        
        StackTraceResponse response = new StackTraceResponse();
        response.setStackFrames(callStack.toArray(new StackFrame[0]));
        response.setTotalFrames(callStack.size());
        
        return CompletableFuture.completedFuture(response);
    }
    
    @Override
    public CompletableFuture<ScopesResponse> scopes(ScopesArguments args) {
        logger.info("Scopes requested for frame: {}", args.getFrameId());
        
        List<Scope> scopes = new ArrayList<>();
        
        // Local variables scope
        Scope localScope = new Scope();
        localScope.setName("Local");
        localScope.setVariablesReference(1);
        localScope.setExpensive(false);
        scopes.add(localScope);
        
        // Global variables scope
        Scope globalScope = new Scope();
        globalScope.setName("Global");
        globalScope.setVariablesReference(2);
        globalScope.setExpensive(false);
        scopes.add(globalScope);
        
        ScopesResponse response = new ScopesResponse();
        response.setScopes(scopes.toArray(new Scope[0]));
        
        return CompletableFuture.completedFuture(response);
    }
    
    @Override
    public CompletableFuture<VariablesResponse> variables(VariablesArguments args) {
        logger.info("Variables requested for reference: {}", args.getVariablesReference());
        
        List<Variable> variables = new ArrayList<>();
        
        // Show variables from current debug context
        for (Map.Entry<String, Object> entry : currentVariables.entrySet()) {
            Variable variable = new Variable();
            variable.setName(entry.getKey());
            
            Object value = entry.getValue();
            if (value != null) {
                variable.setValue(formatVariableValue(value));
                variable.setType(value.getClass().getSimpleName());
            } else {
                variable.setValue("null");
                variable.setType("null");
            }
            
            variables.add(variable);
        }
        
        // If no variables available, show a placeholder
        if (variables.isEmpty()) {
            Variable placeholder = new Variable();
            placeholder.setName("(no variables)");
            placeholder.setValue("No variables in current scope");
            placeholder.setType("info");
            variables.add(placeholder);
        }
        
        VariablesResponse response = new VariablesResponse();
        response.setVariables(variables.toArray(new Variable[0]));
        
        return CompletableFuture.completedFuture(response);
    }
    
    @Override
    public CompletableFuture<ThreadsResponse> threads() {
        logger.debug("Threads requested");
        
        List<org.eclipse.lsp4j.debug.Thread> threads = new ArrayList<>();
        
        org.eclipse.lsp4j.debug.Thread mainThread = new org.eclipse.lsp4j.debug.Thread();
        mainThread.setId(currentThreadId);
        mainThread.setName("Magic Script Main Thread");
        threads.add(mainThread);
        
        ThreadsResponse response = new ThreadsResponse();
        response.setThreads(threads.toArray(new org.eclipse.lsp4j.debug.Thread[0]));
        
        return CompletableFuture.completedFuture(response);
    }
    
    @Override
    public CompletableFuture<EvaluateResponse> evaluate(EvaluateArguments args) {
        logger.info("Evaluate expression: {}", args.getExpression());
        
        EvaluateResponse response = new EvaluateResponse();
        
        try {
            if (debugContext != null) {
                // Evaluate expression in current debug context
                Object result = debugContext.eval(args.getExpression(), currentVariables);
                
                response.setResult(formatVariableValue(result));
                response.setType(result != null ? result.getClass().getSimpleName() : "null");
            } else {
                response.setResult("Debug context not available");
                response.setType("error");
            }
        } catch (Exception e) {
            logger.warn("Failed to evaluate expression: {}", args.getExpression(), e);
            response.setResult("Error: " + e.getMessage());
            response.setType("error");
        }
        
        return CompletableFuture.completedFuture(response);
    }
    
    /**
     * Execute the magic script in debug mode
     */
    private void executeScript() {
        try {
            logger.info("Starting script execution in debug mode");
            Object result = currentScript.execute(debugContext);
            logger.info("Script execution completed with result: {}", result);
            
            if (client != null) {
                TerminatedEventArguments terminatedEvent = new TerminatedEventArguments();
                client.terminated(terminatedEvent);
            }
        } catch (MagicScriptException e) {
            logger.error("Script execution failed", e);
            if (client != null) {
                TerminatedEventArguments terminatedEvent = new TerminatedEventArguments();
                client.terminated(terminatedEvent);
            }
        } catch (Exception e) {
            logger.error("Unexpected error during script execution", e);
            if (client != null) {
                TerminatedEventArguments terminatedEvent = new TerminatedEventArguments();
                client.terminated(terminatedEvent);
            }
        } finally {
            isRunning = false;
        }
    }
    
    /**
     * Called when script execution pauses at a breakpoint or step
     */
    private void onDebugPause(Map<String, Object> debugInfo) {
        logger.info("Debug pause triggered");
        
        isPaused = true;
        
        // Update current variables
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variables = (List<Map<String, Object>>) debugInfo.get("variables");
        currentVariables.clear();
        
        if (variables != null) {
            for (Map<String, Object> var : variables) {
                String name = (String) var.get("name");
                Object value = var.get("value");
                currentVariables.put(name, value);
            }
        }
        
        // Update call stack
        callStack.clear();
        int[] range = (int[]) debugInfo.get("range");
        if (range != null && range.length >= 4) {
            StackFrame frame = new StackFrame();
            frame.setId(1);
            frame.setName("Magic Script");
            frame.setLine(range[0]);
            frame.setColumn(range[1]);
            frame.setEndLine(range[2]);
            frame.setEndColumn(range[3]);
            
            Source source = new Source();
            source.setPath(currentSourcePath);
            source.setName("magic-script");
            frame.setSource(source);
            
            callStack.add(frame);
        }
        
        // Send stopped event to client
        if (client != null) {
            StoppedEventArguments stoppedEvent = new StoppedEventArguments();
            stoppedEvent.setReason("breakpoint");
            stoppedEvent.setThreadId(currentThreadId);
            stoppedEvent.setAllThreadsStopped(true);
            client.stopped(stoppedEvent);
        }
    }
    
    /**
     * Format variable value for display
     */
    private String formatVariableValue(Object value) {
        if (value == null) {
            return "null";
        }
        
        if (value instanceof String) {
            return "\"" + value + "\"";
        }
        
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        
        if (value instanceof Collection) {
            Collection<?> collection = (Collection<?>) value;
            return String.format("[%d items]", collection.size());
        }
        
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            return String.format("{%d entries}", map.size());
        }
        
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            return String.format("[%d items]", length);
        }
        
        return value.toString();
    }
}