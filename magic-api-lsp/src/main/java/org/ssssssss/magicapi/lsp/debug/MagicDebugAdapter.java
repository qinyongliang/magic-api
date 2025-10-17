package org.ssssssss.magicapi.lsp.debug;

import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.core.model.Header;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.model.Parameter;
import org.ssssssss.magicapi.core.model.Path;
import org.ssssssss.magicapi.core.model.Options;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.utils.JsonUtils;
import org.ssssssss.script.MagicScript;
import org.ssssssss.script.MagicScriptDebugContext;
import org.ssssssss.script.MagicScriptEngine;
import org.ssssssss.script.exception.MagicScriptException;
import org.ssssssss.script.MagicScriptEngineFactory;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
    // Breakpoints per source path (normalized)
    private final Map<String, List<Integer>> sourceBreakpoints = new ConcurrentHashMap<>();
    
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
    private String normalizedCurrentSourcePath;
    private java.lang.Thread debugThread;
    private volatile boolean prepared = false;
    private volatile boolean started = false;
    
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
        // Note: VS Code extension may not forward configurationDone to server.
        // We keep this method but actual start is coordinated in launch & setBreakpoints.
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> launch(Map<String, Object> args) {
        logger.info("Launch debug session with args: {}", args);

        // Extract launch configuration
        String program = Objects.toString(args.get("program"), null); // In extension, this is fileId
        String fileKey = Objects.toString(args.get("fileKey"), null);
        if (fileKey == null) {
            fileKey = Objects.toString(args.get("programPath"), null);
        }
        if (fileKey == null) {
            fileKey = Objects.toString(args.get("path"), null);
        }
        if (fileKey == null) {
            fileKey = Objects.toString(args.get("target"), null);
        }

        logger.info("Launching Magic API script by id: {}", program);

        try {
            // Initialize Magic Script engine via factory to match constructor signature
            scriptEngine = new MagicScriptEngine(new MagicScriptEngineFactory());

            String scriptContent;
            // Prefer loading by id via MagicResourceService
            if (program != null) {
                MagicResourceService resourceService = MagicConfiguration.getMagicResourceService();
                MagicEntity entity = resourceService.file(program);
                if (entity != null) {
                    scriptContent = Objects.toString(entity.getScript(), "");
                    // Use canonical script path for matching and UI
                    String canonicalScriptPath = resourceService.getScriptName(entity) + ".ms";
                    normalizedCurrentSourcePath = normalizePath(canonicalScriptPath);
                    // Prefer showing a magic-api scheme path to the client for source linking
                    currentSourcePath = "magic-api:/" + normalizedCurrentSourcePath;
                    logger.debug("Launch current script: canonical={} normalized={} clientPath={}", canonicalScriptPath, normalizedCurrentSourcePath, currentSourcePath);

                    // Create debug context with current breakpoints
                    List<Integer> breakpointLines = new ArrayList<>();
                    List<Integer> directLines = sourceBreakpoints.get(normalizedCurrentSourcePath);
                    if (directLines != null) {
                        breakpointLines.addAll(directLines);
                    } else {
                        List<Integer> altLines = sourceBreakpoints.get(stripResourceTypePrefix(normalizedCurrentSourcePath));
                        if (altLines != null) breakpointLines.addAll(altLines);
                    }
                    debugContext = new MagicScriptDebugContext(breakpointLines);
                    debugContext.setScriptName(resourceService.getScriptName(entity));
                    // Populate default request variables if entity is ApiInfo
                    if (entity instanceof ApiInfo) {
                        ApiInfo apiInfo = (ApiInfo) entity;
                        Map<String, Object> header = new LinkedHashMap<>();
                        for (Header h : apiInfo.getHeaders()) {
                            header.put(h.getName(), h.getValue());
                        }
                        Map<String, Object> pathVars = new LinkedHashMap<>();
                        for (Path p : apiInfo.getPaths()) {
                            Object val = p.getValue();
                            if (val == null) val = p.getDefaultValue();
                            pathVars.put(p.getName(), val);
                        }
                        Map<String, Object> query = new LinkedHashMap<>();
                        for (Parameter p : apiInfo.getParameters()) {
                            Object val = p.getValue();
                            if (val == null) val = p.getDefaultValue();
                            query.put(p.getName(), val);
                        }
                        Object body = null;
                        String reqBody = apiInfo.getRequestBody();
                        if (reqBody != null && !reqBody.trim().isEmpty()) {
                            try {
                                body = JsonUtils.readValue(reqBody, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>(){});
                                if (body == null) {
                                    body = reqBody;
                                }
                            } catch (Exception ignore) {
                                body = reqBody;
                            }
                        }

                        debugContext.set("path", pathVars);
                        debugContext.set("header", header);
                        debugContext.set("query", query);
                        if (body != null) {
                            debugContext.set("body", body);
                        }

                        // Set default data source option into context (if exists)
                        String defaultDs = apiInfo.getOptionValue(Options.DEFAULT_DATA_SOURCE);
                        if (defaultDs != null) {
                            debugContext.set(Options.DEFAULT_DATA_SOURCE.getValue(), defaultDs);
                        }
                    }
                } else {
                    return CompletableFuture.completedFuture(null);                }
            } else {
                // No program provided; nothing to load
                logger.warn("Launch called without program id or path");
                return CompletableFuture.completedFuture(null);
            }

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
            // Defer starting execution to wait for breakpoints
            prepared = true;
            scheduleStartFallback();
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
        String normalizedPath = normalizePath(sourcePath);
        String normalizedPathNoType = stripResourceTypePrefix(normalizedPath);
        logger.debug("Normalized breakpoint source path: {} ; current script path: {}", normalizedPath, normalizedCurrentSourcePath);
        SourceBreakpoint[] sourceBreakpoints = args.getBreakpoints();

        List<Breakpoint> resultBreakpoints = new ArrayList<>();
        List<Integer> linesForSource = new ArrayList<>();

        if (sourceBreakpoints != null) {
            for (SourceBreakpoint sourceBreakpoint : sourceBreakpoints) {
                int id = nextBreakpointId.getAndIncrement();
                int line = sourceBreakpoint.getLine();
                
                breakpoints.put(id, sourceBreakpoint);
                // VS Code sends lines relative to original source.
                // We compile with a leading DEBUG_MARK line, so actual runtime lines shift by +1.
                linesForSource.add(line + 1);
                
                Breakpoint breakpoint = new Breakpoint();
                breakpoint.setId(id);
                breakpoint.setVerified(true);
                breakpoint.setLine(line);
                breakpoint.setSource(args.getSource());
                
                resultBreakpoints.add(breakpoint);
                
                logger.debug("Set breakpoint {} at line {}", id, line);
            }
            
            // Store per-source breakpoints
            this.sourceBreakpoints.put(normalizedPath, linesForSource);
            if (!normalizedPathNoType.equals(normalizedPath)) {
                this.sourceBreakpoints.put(normalizedPathNoType, new ArrayList<>(linesForSource));
            }
            // Update debug context if it's for current source
            boolean matchesCurrent = normalizedPath.equals(normalizedCurrentSourcePath)
                    || normalizedPathNoType.equals(stripResourceTypePrefix(normalizedCurrentSourcePath));
            if (debugContext != null && matchesCurrent) {
                logger.debug("Applying {} breakpoints to current debug context", linesForSource.size());
                debugContext.setBreakpoints(new ArrayList<>(linesForSource));
                maybeStartExecution();
            } else {
                logger.debug("Breakpoint source does not match current script. Skipping apply.");
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
        // 记录原始标准输出流，便于结束后恢复
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            logger.info("Starting script execution in debug mode");
            // 将 System.out/System.err 在当前脚本执行线程内桥接到 DAP 的 stdout/stderr
            final java.lang.Thread execThread = java.lang.Thread.currentThread();
            System.setOut(new DebugPrintStream(originalOut, "stdout", execThread));
            System.setErr(new DebugPrintStream(originalErr, "stderr", execThread));
            // Emit start event
            sendConsoleJson("start", Collections.singletonMap("normalizedSource", normalizedCurrentSourcePath));
            Object result = currentScript.execute(debugContext);
            logger.info("Script execution completed with result: \n{}", JsonUtils.toJsonString(result));
            // Emit final result as JSON to console
            sendConsoleJson("result", result);
            
            if (client != null) {
                TerminatedEventArguments terminatedEvent = new TerminatedEventArguments();
                client.terminated(terminatedEvent);
            }
        } catch (MagicScriptException e) {
            logger.error("Script execution failed", e);
            // Emit error to console
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("message", e.getMessage());
            sendConsoleJson("error", err);
            if (client != null) {
                TerminatedEventArguments terminatedEvent = new TerminatedEventArguments();
                client.terminated(terminatedEvent);
            }
        } catch (Exception e) {
            logger.error("Unexpected error during script execution", e);
            // Emit error to console
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("message", e.getMessage());
            sendConsoleJson("error", err);
            if (client != null) {
                TerminatedEventArguments terminatedEvent = new TerminatedEventArguments();
                client.terminated(terminatedEvent);
            }
        } finally {
            isRunning = false;
            // 恢复 System.out/System.err
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    /**
     * 将 System.out/System.err 桥接为 DAP OutputEvent（仅在指定线程内生效）
     */
    private class DebugPrintStream extends PrintStream {
        private final String category;
        private final java.lang.Thread targetThread;
        private final StringBuilder buffer = new StringBuilder();

        DebugPrintStream(PrintStream delegate, String category, java.lang.Thread targetThread) {
            super(delegate);
            this.category = category;
            this.targetThread = targetThread;
        }

        private void emit(String s) {
            if (client != null) {
                OutputEventArguments out = new OutputEventArguments();
                out.setCategory(category);
                out.setOutput(s);
                client.output(out);
            }
        }

        private boolean inTargetThread() {
            return java.lang.Thread.currentThread() == targetThread;
        }

        @Override
        public void write(int b) {
            super.write(b);
            if (inTargetThread()) {
                char ch = (char) b;
                buffer.append(ch);
                if (ch == '\n') {
                    emit(buffer.toString());
                    buffer.setLength(0);
                }
            }
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            super.write(buf, off, len);
            if (inTargetThread()) {
                String s = new String(buf, off, len, StandardCharsets.UTF_8);
                buffer.append(s);
                int idx;
                while ((idx = buffer.indexOf("\n")) >= 0) {
                    String line = buffer.substring(0, idx + 1);
                    emit(line);
                    buffer.delete(0, idx + 1);
                }
            }
        }

        @Override
        public void flush() {
            super.flush();
            if (inTargetThread() && buffer.length() > 0) {
                emit(buffer.toString());
                buffer.setLength(0);
            }
        }
    }

    private void maybeStartExecution() {
        synchronized (this) {
            if (started || !prepared) {
                return;
            }
            // Only start when we have breakpoints for current source
            List<Integer> bps = sourceBreakpoints.get(normalizedCurrentSourcePath);
            if (bps != null) {
                startDebugThread();
            }
        }
    }

    private void scheduleStartFallback() {
        new java.lang.Thread(() -> {
            int waited = 0;
            final int stepMs = 250;
            final int maxWaitMs = 5000; // cap to prevent indefinite hang if client never sends setBreakpoints
            try {
                while (!started && prepared && waited < maxWaitMs) {
                    synchronized (this) {
                        // Only start when we've received setBreakpoints for the current source (empty list counts)
                        boolean receivedForCurrent = false;
                        if (normalizedCurrentSourcePath != null) {
                            receivedForCurrent = sourceBreakpoints.containsKey(normalizedCurrentSourcePath)
                                    || sourceBreakpoints.containsKey(stripResourceTypePrefix(normalizedCurrentSourcePath));
                        }
                        if (receivedForCurrent) {
                            logger.debug("Breakpoints for current source have been received after {}ms; starting execution.", waited);
                            maybeStartExecution();
                            if (!started) {
                                startDebugThread();
                            }
                            return;
                        }
                    }
                    java.lang.Thread.sleep(stepMs);
                    waited += stepMs;
                }
            } catch (InterruptedException ignored) {
                java.lang.Thread.currentThread().interrupt();
            }
            synchronized (this) {
                if (!started && prepared) {
                    // Fallback: still start to avoid hanging, but log that breakpoints were not received
                    logger.warn("Breakpoints for current source not received within {}ms; starting without them.", maxWaitMs);
                    startDebugThread();
                }
            }
        }, "Magic Script Debug Start Scheduler").start();
    }

    private void startDebugThread() {
        if (started) return;
        started = true;
        debugThread = new java.lang.Thread(this::executeScript);
        debugThread.setName("Magic Script Debug Thread");
        debugThread.start();
    }
    
    /**
     * Send a console output event with JSON payload to VS Code.
     * The payload structure is: { event: <event>, source: <currentSourcePath>, data: <data> }
     */
    private void sendConsoleJson(String event, Object data) {
        if (client == null) return;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", event);
            payload.put("source", currentSourcePath);
            if (data != null) {
                payload.put("data", data);
            }
            OutputEventArguments out = new OutputEventArguments();
            out.setCategory("console");
            out.setOutput(JsonUtils.toJsonString(payload) + "\n");
            client.output(out);
        } catch (Exception ignore) {
            OutputEventArguments out = new OutputEventArguments();
            out.setCategory("console");
            out.setOutput("{\"event\":\"" + event + "\",\"source\":\"" + String.valueOf(currentSourcePath) + "\"}\n");
            client.output(out);
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
            // Runtime reports lines including the injected DEBUG_MARK header.
            // VS Code expects lines relative to original source, so shift back by -1.
            frame.setLine(range[0] - 1);
            frame.setColumn(range[1]);
            frame.setEndLine(range[2] - 1);
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
        // Emit pause snapshot to console in JSON
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("variables", new LinkedHashMap<>(currentVariables));
            snapshot.put("range", debugInfo.get("range"));
            sendConsoleJson("pause", snapshot);
        } catch (Exception e) {
            logger.debug("Failed to emit pause snapshot", e);
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

    private String normalizePath(String path) {
        if (path == null) return null;
        String p = path;
        // Trim custom scheme if present
        if (p.startsWith("magic-api:")) {
            p = p.substring("magic-api:".length());
        }
        // Decode percent-encoding early to prevent mismatches
        try {
            p = java.net.URLDecoder.decode(p, "UTF-8");
        } catch (Exception ignored) {
            // keep original if decoding fails
        }
        // Remove leading slashes for canonical matching
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        // Strip any request-mapping suffix appended to script names, e.g. "name(/path/to/api)"
        int idx = p.indexOf('(');
        if (idx >= 0) {
            String before = p.substring(0, idx);
            // Preserve .ms extension if it existed after the parentheses
            if (!before.endsWith(".ms") && p.contains(".ms")) {
                before = before + ".ms";
            }
            p = before;
        }
        // Trim whitespace just in case
        p = p.trim();
        return p;
    }

    private String stripResourceTypePrefix(String p) {
        if (p == null) return null;
        String s = p;
        while (s.startsWith("/")) s = s.substring(1);
        if (s.startsWith("api/")) return s.substring(4);
        if (s.startsWith("function/")) return s.substring(9);
        if (s.startsWith("datasource/")) return s.substring(11);
        if (s.startsWith("task/")) return s.substring(5);
        return s;
    }
}