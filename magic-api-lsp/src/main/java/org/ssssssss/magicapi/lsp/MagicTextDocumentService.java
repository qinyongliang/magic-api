package org.ssssssss.magicapi.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
// Use fully-qualified Either to avoid classpath ambiguities
// 明确导入语义高亮相关类
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensRangeParams;
import org.ssssssss.script.MagicScriptEngine;
import org.ssssssss.script.MagicResourceLoader;
import org.ssssssss.script.ScriptClass;
import org.ssssssss.script.ScriptClass.ScriptMethod;
import org.ssssssss.script.reflection.JavaReflection;
import org.ssssssss.script.parsing.Parser;
import org.ssssssss.script.parsing.ast.Node;
import org.ssssssss.script.parsing.Span;
import org.ssssssss.script.parsing.VarScope;
import org.ssssssss.script.parsing.VarIndex;
import org.ssssssss.script.exception.MagicScriptException;
import org.ssssssss.script.compile.MagicScriptCompileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.lsp.provider.DiagnosticsProvider;
import org.ssssssss.magicapi.lsp.provider.CodeLensProvider;
import org.ssssssss.magicapi.lsp.provider.SemanticTokensProvider;
import org.ssssssss.magicapi.lsp.provider.HoverProvider;
import org.ssssssss.magicapi.lsp.provider.DocumentSymbolProvider;
import org.ssssssss.magicapi.lsp.provider.DefinitionProvider;
import org.ssssssss.magicapi.lsp.provider.ReferenceProvider;
import org.ssssssss.magicapi.lsp.provider.HighlightProvider;
import org.ssssssss.magicapi.lsp.provider.FoldingProvider;
import org.ssssssss.magicapi.lsp.provider.FormattingProvider;
import org.ssssssss.magicapi.lsp.provider.RenameProvider;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

public class MagicTextDocumentService implements TextDocumentService {
    
    private static final Logger logger = LoggerFactory.getLogger(MagicTextDocumentService.class);
    
    // 存储打开的文档
    private final Map<String, TextDocumentItem> documents = new HashMap<>();
    
    // 存储文档的解析结果和作用域信息
    private final Map<String, ParseResult> parseResults = new HashMap<>();
    
    // LSP客户端引用
    private LanguageClient client;
    private final DiagnosticsProvider diagnosticsProvider = new DiagnosticsProvider();
    private final CodeLensProvider codeLensProvider = new CodeLensProvider(diagnosticsProvider);
    private final SemanticTokensProvider semanticTokensProvider = new SemanticTokensProvider();
    private final HoverProvider hoverProvider = new HoverProvider();
    private final DocumentSymbolProvider documentSymbolProvider = new DocumentSymbolProvider();
    private final DefinitionProvider definitionProvider = new DefinitionProvider();
    private final ReferenceProvider referenceProvider = new ReferenceProvider();
    private final HighlightProvider highlightProvider = new HighlightProvider();
    private final FoldingProvider foldingProvider = new FoldingProvider();
    private final FormattingProvider formattingProvider = new FormattingProvider();
    private final RenameProvider renameProvider = new RenameProvider();

    // Magic 引擎数据缓存，降低频繁构建开销
    private volatile Map<String, ScriptClass> cachedScriptClasses;
    private volatile Map<String, ScriptClass> cachedExtensionClasses;
    private volatile List<ScriptMethod> cachedFunctions;
    private volatile Set<String> cachedModuleNames;

    // 解析结果缓存类
    private static class ParseResult {
        private final List<Node> nodes;
        private final VarScope rootScope;
        private final Map<Integer, VarScope> lineScopes; // 行号到作用域的映射
        private final long timestamp;
        
        public ParseResult(List<Node> nodes, VarScope rootScope, Map<Integer, VarScope> lineScopes) {
            this.nodes = nodes;
            this.rootScope = rootScope;
            this.lineScopes = lineScopes;
            this.timestamp = System.currentTimeMillis();
        }
        
        public List<Node> getNodes() { return nodes; }
        public VarScope getRootScope() { return rootScope; }
        public Map<Integer, VarScope> getLineScopes() { return lineScopes; }
        public long getTimestamp() { return timestamp; }
    }

    public void setClient(LanguageClient client) {
        this.client = client;
    }
    
    private void ensureMagicCaches() {
        if (cachedScriptClasses == null) {
            try {
                cachedScriptClasses = MagicScriptEngine.getScriptClassMap();
            } catch (Exception e) {
                cachedScriptClasses = Collections.emptyMap();
            }
        }
        if (cachedExtensionClasses == null) {
            try {
                cachedExtensionClasses = MagicScriptEngine.getExtensionScriptClass();
            } catch (Exception e) {
                cachedExtensionClasses = Collections.emptyMap();
            }
        }
        if (cachedFunctions == null) {
            try {
                cachedFunctions = MagicScriptEngine.getFunctions();
            } catch (Exception e) {
                cachedFunctions = Collections.emptyList();
            }
        }
        if (cachedModuleNames == null) {
            try {
                cachedModuleNames = MagicResourceLoader.getModuleNames();
            } catch (Exception e) {
                cachedModuleNames = Collections.emptySet();
            }
        }
    }

    private Map<String, List<ScriptMethod>> getMagicFunctionDetails() {
        ensureMagicCaches();
        Map<String, List<ScriptMethod>> details = new HashMap<>();
        for (ScriptMethod method : cachedFunctions) {
            details.computeIfAbsent(method.getName(), k -> new ArrayList<>()).add(method);
        }
        return details;
    }

    private Map<String, List<ScriptMethod>> getMagicClassMethodDetails() {
        ensureMagicCaches();
        Map<String, List<ScriptMethod>> result = new HashMap<>();
        // 基础类方法
        for (Map.Entry<String, ScriptClass> entry : cachedScriptClasses.entrySet()) {
            String className = entry.getKey();
            Set<ScriptMethod> ms = entry.getValue().getMethods();
            if (ms != null) {
                result.put(className, new ArrayList<>(ms));
            }
        }
        // 扩展方法合并
        for (Map.Entry<String, ScriptClass> entry : cachedExtensionClasses.entrySet()) {
            String className = entry.getKey();
            Set<ScriptMethod> ms = entry.getValue().getMethods();
            if (ms != null) {
                List<ScriptMethod> target = result.computeIfAbsent(className, k -> new ArrayList<>());
                for (ScriptMethod m : ms) {
                    if (!target.contains(m)) { // ScriptMethod 已实现 equals，避免重复
                        target.add(m);
                    }
                }
            }
        }
        return result;
    }

    private String formatMethodSignature(ScriptMethod method) {
        String params = method.getParameters().stream()
                .map(p -> {
                    String type = p.getType();
                    if (p.isVarArgs()) {
                        type = type + "...";
                    }
                    return type + " " + p.getName();
                })
                .collect(java.util.stream.Collectors.joining(", "));
        String sig = method.getName() + "(" + params + "): " + method.getReturnType();
        if (method.isDeprecated()) {
            sig += " [deprecated]";
        }
        return sig;
    }
    
    // 基础关键字 - 从magic-script Parser.java中获取
    private static final List<String> MAGIC_KEYWORDS = Arrays.asList(
        "import", "as", "var", "let", "const", "return", "break", "continue", "if", "for", 
        "in", "new", "true", "false", "null", "else", "try", "catch", "finally", "async", 
        "while", "exit", "and", "or", "throw", "function", "lambda"
    );
    
    // LINQ关键字 - 从magic-script Parser.java中获取
    private static final List<String> LINQ_KEYWORDS = Arrays.asList(
        "from", "join", "left", "group", "by", "as", "having", "and", "or", "in", 
        "where", "on", "limit", "offset", "select", "order", "desc", "asc"
    );
    
    // 操作符
    private static final List<String> OPERATORS = Arrays.asList(
        "+", "-", "*", "/", "%", "++", "--", "+=", "-=", "*=", "/=", "%=",
        "<", "<=", ">", ">=", "==", "!=", "===", "!==", "&&", "||", "!",
        "&", "|", "^", "<<", ">>", ">>>", "~", "?", ":", "?.", "...",
        "=", "=>", "::", "?:"
    );
    
    // 内置类型
    private static final List<String> BUILTIN_TYPES = Arrays.asList(
        "byte", "short", "int", "long", "float", "double", "boolean", "string",
        "BigDecimal", "Pattern", "Date", "List", "Map", "Set", "Array"
    );
    
    // 动态获取Magic Script函数
    private List<String> getMagicFunctions() {
        ensureMagicCaches();
        return cachedFunctions.stream()
            .map(ScriptMethod::getName)
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }
    
    // 动态获取Magic Script类和扩展方法
    private Map<String, List<String>> getMagicClassMethods() {

        ensureMagicCaches();
        Map<String, List<String>> classMethods = new HashMap<>();

        // 基础类方法
        for (Map.Entry<String, ScriptClass> entry : cachedScriptClasses.entrySet()) {
            String className = entry.getKey();
            ScriptClass scriptClass = entry.getValue();
            List<String> methods = scriptClass.getMethods().stream()
                    .map(ScriptMethod::getName)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
            classMethods.put(className, methods);
        }

        // 扩展方法
        for (Map.Entry<String, ScriptClass> entry : cachedExtensionClasses.entrySet()) {
            String className = entry.getKey();
            ScriptClass scriptClass = entry.getValue();
            List<String> methods = scriptClass.getMethods().stream()
                    .map(ScriptMethod::getName)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
            classMethods.merge(className, methods, (existing, newMethods) -> {
                Set<String> combined = new HashSet<>(existing);
                combined.addAll(newMethods);
                return combined.stream().sorted().collect(Collectors.toList());
            });
        }
        return classMethods;
    }

    // 获取代码片段补全
    private List<CompletionItem> getSnippetCompletions() {
        List<CompletionItem> snippets = new ArrayList<>();
        
        // for循环片段
        CompletionItem forLoop = new CompletionItem("for");
        forLoop.setKind(CompletionItemKind.Snippet);
        forLoop.setDetail("for循环");
        forLoop.setInsertText("for(${1:item} in ${2:collection}){\n\t${3:// 循环体}\n}");
        forLoop.setInsertTextFormat(InsertTextFormat.Snippet);
        snippets.add(forLoop);
        
        // for循环带索引
        CompletionItem forLoopWithIndex = new CompletionItem("fori");
        forLoopWithIndex.setKind(CompletionItemKind.Snippet);
        forLoopWithIndex.setDetail("for循环(带索引)");
        forLoopWithIndex.setInsertText("for(${1:index}, ${2:item} in ${3:collection}){\n\t${4:// 循环体}\n}");
        forLoopWithIndex.setInsertTextFormat(InsertTextFormat.Snippet);
        snippets.add(forLoopWithIndex);
        
        // if语句
        CompletionItem ifStatement = new CompletionItem("if");
        ifStatement.setKind(CompletionItemKind.Snippet);
        ifStatement.setDetail("if条件语句");
        ifStatement.setInsertText("if(${1:condition}){\n\t${2:// 条件为真时执行}\n}");
        ifStatement.setInsertTextFormat(InsertTextFormat.Snippet);
        snippets.add(ifStatement);
        
        // try-catch语句
        CompletionItem tryCatch = new CompletionItem("try");
        tryCatch.setKind(CompletionItemKind.Snippet);
        tryCatch.setDetail("try-catch异常处理");
        tryCatch.setInsertText("try{\n\t${1:// 可能抛出异常的代码}\n}catch(e){\n\t${2:// 异常处理}\n}");
        tryCatch.setInsertTextFormat(InsertTextFormat.Snippet);
        snippets.add(tryCatch);
        
        // lambda表达式
        CompletionItem lambda = new CompletionItem("lambda");
        lambda.setKind(CompletionItemKind.Snippet);
        lambda.setDetail("lambda表达式");
        lambda.setInsertText("(${1:param}) => ${2:expression}");
        lambda.setInsertTextFormat(InsertTextFormat.Snippet);
        snippets.add(lambda);
        
        // import语句
        CompletionItem importStatement = new CompletionItem("import");
        importStatement.setKind(CompletionItemKind.Snippet);
        importStatement.setDetail("导入Java类");
        importStatement.setInsertText("import '${1:java.package.ClassName}' as ${2:alias}");
        importStatement.setInsertTextFormat(InsertTextFormat.Snippet);
        snippets.add(importStatement);
        
        return snippets;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        TextDocumentItem document = params.getTextDocument();
        documents.put(document.getUri(), document);
        logger.info("Document opened: {}", document.getUri());
        
        // 创建解析结果和作用域信息
        updateParseResult(document.getUri(), document.getText());
        
        // 执行语法检查并发布诊断信息
        diagnosticsProvider.publishDiagnostics(client, document.getUri(), document.getText());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        TextDocumentItem document = documents.get(uri);
        if (document != null) {
            // 更新文档内容
            for (TextDocumentContentChangeEvent change : params.getContentChanges()) {
                if (change.getRange() == null) {
                    // 全文替换
                    document = new TextDocumentItem(document.getUri(), document.getLanguageId(), 
                        document.getVersion() + 1, change.getText());
                } else {
                    // 增量更新（简化处理）
                    document = new TextDocumentItem(document.getUri(), document.getLanguageId(), 
                        document.getVersion() + 1, change.getText());
                }
            }
            documents.put(uri, document);
            
            // 更新解析结果和作用域信息
            updateParseResult(uri, document.getText());
            
            // 重新验证文档并发布诊断信息
            diagnosticsProvider.publishDiagnostics(client, uri, document.getText());

            // 动态刷新语义标记与 CodeLens
            try {
                if (client != null) {
                    client.refreshSemanticTokens();
                    client.refreshCodeLenses();
                }
            } catch (Throwable t) {
                logger.debug("Client refresh methods not available: {}", t.getMessage());
            }
        }
    }
    
    /**
     * 更新文档的解析结果和作用域信息
     */
    private void updateParseResult(String uri, String content) {
        try {
            ParseResult parseResult = parseDocumentWithScope(content);
            parseResults.put(uri, parseResult);
            logger.debug("Updated parse result for document: {}", uri);
        } catch (Exception e) {
            logger.warn("Failed to update parse result for document {}: {}", uri, e.getMessage());
            // 移除旧的解析结果
            parseResults.remove(uri);
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        documents.remove(uri);
        parseResults.remove(uri); // 清理解析结果
        logger.info("Document closed: {}", uri);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        logger.info("Document saved: {}", params.getTextDocument().getUri());
    }

    /**
     * LSP 3.17 pull diagnostics implementation.
     * VS Code will call textDocument/diagnostic when the server advertises DiagnosticProvider.
     */
    @Override
    public CompletableFuture<DocumentDiagnosticReport> diagnostic(DocumentDiagnosticParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params != null && params.getTextDocument() != null ? params.getTextDocument().getUri() : null;
            TextDocumentItem document = uri != null ? documents.get(uri) : null;
            String content = document != null ? document.getText() : "";

            List<Diagnostic> diagnostics = diagnosticsProvider.validateDocumentContent(content);

            RelatedFullDocumentDiagnosticReport full = new RelatedFullDocumentDiagnosticReport(diagnostics);
            try {
                if (document != null) {
                    full.setResultId(String.valueOf(document.getVersion()));
                }
            } catch (Throwable ignore) {
                // resultId is optional; ignore if setter not available
            }

            return new DocumentDiagnosticReport(full);
        });
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            List<CompletionItem> items = new ArrayList<>();
            
            // 获取当前文档和位置信息
            String documentUri = params.getTextDocument().getUri();
            TextDocumentItem document = documents.get(documentUri);
            Position position = params.getPosition();
            
            if (document == null) {
                return Either.forLeft(items);
            }
            
            // 分析上下文
            String context = analyzeContext(document, position);
            
            // 获取当前位置的作用域信息
            VarScope currentScope = getScopeAtPosition(documentUri, position);
            
            // 根据上下文提供不同的补全
            if (context.contains("import")) {
                // 在import语句中，提供Java类补全
                addImportCompletions(items);
            } else if (context.contains(".")) {
                // 在方法调用上下文中，提供方法补全
                addMethodCompletions(items, context);
            } else {
                // 默认补全：关键字、函数、代码片段等
                addDefaultCompletions(items);
                
                // 添加基于作用域的变量补全
                addScopeBasedCompletions(items, currentScope, documentUri);
            }
            
            return Either.forLeft(items);
        });
    }

    /**
     * Completion resolve handler. VSCode calls this when resolveProvider=true.
     * For now, we return the item as-is. You can enrich documentation or details here.
     */
    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem params) {
        return CompletableFuture.completedFuture(params);
    }
    
    private String analyzeContext(TextDocumentItem document, Position position) {
        if (document == null) {
            return "";
        }
        
        String text = document.getText();
        String[] lines = text.split("\n");
        
        if (position.getLine() >= lines.length) {
            return "";
        }
        
        String currentLine = lines[position.getLine()];
        int character = Math.min(position.getCharacter(), currentLine.length());
        
        // 返回当前行光标前的内容作为上下文
        return currentLine.substring(0, character);
    }
    
    private void addImportCompletions(List<CompletionItem> items) {
        // 常用Java类的导入建议
        String[] commonClasses = {
            "java.util.List", "java.util.Map", "java.util.Set", "java.util.Date",
            "java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Double",
            "java.math.BigDecimal", "java.text.SimpleDateFormat", "java.util.regex.Pattern"
        };
        
        for (String className : commonClasses) {
            CompletionItem item = new CompletionItem(className);
            item.setKind(CompletionItemKind.Class);
            item.setDetail("Java Class");
            item.setInsertText("'" + className + "' as ${1:" + className.substring(className.lastIndexOf('.') + 1) + "}");
            item.setInsertTextFormat(InsertTextFormat.Snippet);
            items.add(item);
        }

        // 模块导入建议（不使用引号，语法：import module as alias）
        ensureMagicCaches();
        for (String moduleName : cachedModuleNames) {
            CompletionItem item = new CompletionItem(moduleName);
            item.setKind(CompletionItemKind.Module);
            item.setDetail("Magic Script 模块");
            item.setInsertText(moduleName + " as ${1:" + moduleName + "}");
            item.setInsertTextFormat(InsertTextFormat.Snippet);
            items.add(item);
        }

        // 函数导入建议（语法：import '@function' as alias）
        for (String function : getMagicFunctions()) {
            String label = "@" + function;
            CompletionItem item = new CompletionItem(label);
            item.setKind(CompletionItemKind.Function);
            item.setDetail("导入函数");
            item.setInsertText("'@" + function + "' as ${1:" + function + "}");
            item.setInsertTextFormat(InsertTextFormat.Snippet);
            items.add(item);
        }
    }
    
    private void addMethodCompletions(List<CompletionItem> items, String context) {
        // 分析上下文，确定可能的类型
        Map<String, List<String>> classMethods = getMagicClassMethods();
        
        // 简化的类型推断
        if (context.contains("\"") || context.contains("'")) {
            // 字符串方法
            addMethodsForType(items, "String", classMethods);
        } else if (context.contains("[") || context.contains("list") || context.contains("array")) {
            // 列表/数组方法
            addMethodsForType(items, "List", classMethods);
        } else if (context.contains("{") || context.contains("map")) {
            // Map方法
            addMethodsForType(items, "Map", classMethods);
        } else {
            // 添加所有可能的方法
            for (Map.Entry<String, List<String>> entry : classMethods.entrySet()) {
                addMethodsForType(items, entry.getKey(), classMethods);
            }
        }
    }
    
    private void addMethodsForType(List<CompletionItem> items, String typeName, Map<String, List<String>> classMethods) {
        List<String> methods = classMethods.get(typeName);
        Map<String, List<ScriptMethod>> detailed = getMagicClassMethodDetails();
        List<ScriptMethod> classDetail = detailed.getOrDefault(typeName, Collections.emptyList());
        if (methods != null) {
            for (String method : methods) {
                CompletionItem item = new CompletionItem(method);
                item.setKind(CompletionItemKind.Method);
                // 选择首个重载用于展示细节
                ScriptMethod first = null;
                for (ScriptMethod cand : classDetail) {
                    if (method.equals(cand.getName())) {
                        first = cand;
                        break;
                    }
                }
                item.setDetail(first != null ? (typeName + " " + formatMethodSignature(first)) : (typeName + " method"));
                item.setInsertText(method + "(${1})");
                item.setInsertTextFormat(InsertTextFormat.Snippet);
                items.add(item);
            }
        }
    }
    
    private void addDefaultCompletions(List<CompletionItem> items) {
        // 添加关键字补全
        for (String keyword : MAGIC_KEYWORDS) {
            CompletionItem item = new CompletionItem(keyword);
            item.setKind(CompletionItemKind.Keyword);
            item.setDetail("Magic Script Keyword");
            items.add(item);
        }
        
        // 添加LINQ关键字补全
        for (String keyword : LINQ_KEYWORDS) {
            CompletionItem item = new CompletionItem(keyword);
            item.setKind(CompletionItemKind.Keyword);
            item.setDetail("Magic Script LINQ 关键字");
            item.setInsertText(keyword);
            items.add(item);
        }
        
        // 添加操作符补全
        for (String operator : OPERATORS) {
            CompletionItem item = new CompletionItem(operator);
            item.setKind(CompletionItemKind.Operator);
            item.setDetail("Magic Script Operator");
            items.add(item);
        }
        
        // 添加内置类型补全
        for (String type : BUILTIN_TYPES) {
            CompletionItem item = new CompletionItem(type);
            item.setKind(CompletionItemKind.TypeParameter);
            item.setDetail("Magic Script Type");
            items.add(item);
        }
        
        // 添加函数补全
        for (String function : getMagicFunctions()) {
            CompletionItem item = new CompletionItem(function);
            item.setKind(CompletionItemKind.Function);
            item.setDetail("Magic Script Function");
            item.setInsertText(function + "(${1})");
            item.setInsertTextFormat(InsertTextFormat.Snippet);
            items.add(item);
        }
        
        // 添加代码片段补全
        items.addAll(getSnippetCompletions());
    }
    
    /**
     * 添加基于作用域的变量和函数补全
     */
    private void addScopeBasedCompletions(List<CompletionItem> items, VarScope currentScope, String documentUri) {
        if (currentScope == null) {
            return;
        }
        
        // 获取当前作用域及其父作用域中的所有变量
        Set<String> addedVariables = new HashSet<>();
        VarScope scope = currentScope;
        
        while (scope != null) {
            // 添加当前作用域中的变量
            List<String> variables = getVariablesInScope(scope);
            for (String variable : variables) {
                if (!addedVariables.contains(variable)) {
                    CompletionItem item = new CompletionItem(variable);
                    item.setKind(CompletionItemKind.Variable);
                    item.setDetail("Local Variable");
                    item.setInsertText(variable);
                    
                    // 根据作用域层级设置优先级
                    if (scope == currentScope) {
                        item.setSortText("0" + variable); // 当前作用域变量优先级最高
                    } else {
                        item.setSortText("1" + variable); // 父作用域变量优先级较低
                    }
                    
                    items.add(item);
                    addedVariables.add(variable);
                }
            }
            
            // 添加当前作用域中的函数
            addScopeFunctions(items, scope, addedVariables);
            
            // 移动到父作用域
            scope = scope.getParent();
        }
        
        // 添加全局函数和变量
        addGlobalCompletions(items, documentUri, addedVariables);
    }
    
    /**
     * 添加作用域中的函数补全
     */
    private void addScopeFunctions(List<CompletionItem> items, VarScope scope, Set<String> addedItems) {
        // 这里可以根据VarScope的实际API来获取函数信息
        // 由于VarScope主要处理变量，函数信息可能需要从AST节点中获取
        try {
            // 尝试获取作用域中定义的函数
            // VarScope继承自ArrayList<VarIndex>，可以直接遍历
            if (scope != null) {
                for (VarIndex varIndex : scope) {
                    String name = varIndex.getName();
                    if (name != null && !addedItems.contains(name)) {
                        // 检查是否为函数类型的变量
                        if (isFunctionVariable(varIndex)) {
                            CompletionItem item = new CompletionItem(name);
                            item.setKind(CompletionItemKind.Function);
                            item.setDetail("Local Function");
                            item.setInsertText(name + "(${1})");
                            item.setInsertTextFormat(InsertTextFormat.Snippet);
                            item.setSortText("0" + name);
                            items.add(item);
                            addedItems.add(name);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 如果VarScope API不支持，则跳过函数补全
            logger.debug("Failed to get functions from scope: {}", e.getMessage());
        }
    }
    
    /**
     * 检查变量是否为函数类型
     */
    private boolean isFunctionVariable(VarIndex varIndex) {
        // 这里需要根据magic-script的VarIndex实际API来判断
        // 暂时返回false，后续可以根据实际情况调整
        return false;
    }
    
    /**
     * 添加全局补全项
     */
    private void addGlobalCompletions(List<CompletionItem> items, String documentUri, Set<String> addedItems) {
        // 添加文档中定义的全局变量和函数
        ParseResult parseResult = parseResults.get(documentUri);
        if (parseResult != null && parseResult.nodes != null) {
            for (Node node : parseResult.nodes) {
                addGlobalSymbolsFromNode(items, node, addedItems);
            }
        }
    }
    
    /**
     * 从AST节点中提取全局符号
     */
    private void addGlobalSymbolsFromNode(List<CompletionItem> items, Node node, Set<String> addedItems) {
        if (node == null) {
            return;
        }
        
        try {
            // 检查是否为变量定义节点
            String varName = extractVariableNameFromNode(node);
            if (varName != null && !addedItems.contains(varName)) {
                CompletionItem item = new CompletionItem(varName);
                item.setKind(CompletionItemKind.Variable);
                item.setDetail("Global Variable");
                item.setInsertText(varName);
                item.setSortText("2" + varName); // 全局变量优先级最低
                items.add(item);
                addedItems.add(varName);
            }
            
            // 检查是否为函数定义节点
            String funcName = extractFunctionNameFromNode(node);
            if (funcName != null && !addedItems.contains(funcName)) {
                CompletionItem item = new CompletionItem(funcName);
                item.setKind(CompletionItemKind.Function);
                item.setDetail("Global Function");
                item.setInsertText(funcName + "(${1})");
                item.setInsertTextFormat(InsertTextFormat.Snippet);
                item.setSortText("2" + funcName); // 全局函数优先级最低
                items.add(item);
                addedItems.add(funcName);
            }
            
            // 递归处理子节点
            List<Node> children = getChildNodes(node);
            for (Node child : children) {
                addGlobalSymbolsFromNode(items, child, addedItems);
            }
        } catch (Exception e) {
            logger.debug("Failed to extract symbols from node: {}", e.getMessage());
        }
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String documentUri = params.getTextDocument().getUri();
            TextDocumentItem document = documents.get(documentUri);
            if (document == null) return null;
                String text = document.getText();
                Position position = params.getPosition();
            // 确保模块名缓存已初始化
            ensureMagicCaches();
            return hoverProvider.generateHover(
                    text,
                    position,
                    MAGIC_KEYWORDS,
                    LINQ_KEYWORDS,
                    OPERATORS,
                    getMagicFunctionDetails(),
                    getMagicClassMethodDetails(),
                    this::getKeywordDescription,
                    this::getOperatorDescription,
                    cachedModuleNames
            );
        });
    }
    
    private String getKeywordDescription(String keyword) {
        switch (keyword) {
            case "var": return "定义变量";
            case "if": return "条件语句";
            case "for": return "循环语句";
            case "in": return "与for配合使用，或用于成员检查";
            case "return": return "返回值并退出函数";
            case "break": return "跳出循环";
            case "continue": return "继续下一次循环";
            case "try": return "异常处理块";
            case "catch": return "捕获异常";
            case "finally": return "无论是否异常都会执行";
            case "import": return "导入Java类或模块";
            case "as": return "与import配合使用，定义别名";
            case "new": return "创建对象实例";
            case "async": return "异步调用";
            case "exit": return "终止脚本执行";
            default: return "Magic Script关键字";
        }
    }
    
    private String getOperatorDescription(String operator) {
        switch (operator) {
            case "+": return "加法运算或字符串连接";
            case "-": return "减法运算";
            case "*": return "乘法运算";
            case "/": return "除法运算";
            case "%": return "取模运算";
            case "==": return "相等比较";
            case "!=": return "不等比较";
            case "===": return "严格相等比较";
            case "!==": return "严格不等比较";
            case "&&": return "逻辑与";
            case "||": return "逻辑或";
            case "?.": return "可选链操作符";
            case "...": return "扩展运算符";
            case "=>": return "Lambda表达式";
            case "::": return "类型转换";
            default: return "Magic Script操作符";
        }
    }
    
    private String getFunctionDescription(String function) {
        switch (function) {
            case "uuid": return "生成UUID字符串，不包含'-'";
            case "not_null": return "判断对象是否不是NULL";
            case "is_null": return "判断对象是否是NULL";
            case "print": return "打印对象到控制台";
            case "println": return "换行打印对象到控制台";
            case "printf": return "格式化打印";
            case "now": return "获取当前时间";
            case "timestamp": return "获取当前时间戳(秒)";
            case "timestamp_ms": return "获取当前时间戳(毫秒)";
            case "range": return "生成数字范围";
            case "max": return "获取最大值";
            case "min": return "获取最小值";
            case "sum": return "求和";
            case "avg": return "求平均值";
            case "count": return "计数";
            default: return "Magic Script内置函数";
        }
    }

    @Override
    public CompletableFuture<List<org.eclipse.lsp4j.jsonrpc.messages.Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String documentUri = params.getTextDocument().getUri();
            TextDocumentItem document = documents.get(documentUri);
            if (document == null) {
                return new ArrayList<>();
            }
            String text = document.getText();
            return documentSymbolProvider.generate(text);
        });
    }
    
    // 辅助方法：获取位置处的单词
    private String getWordAtPosition(String text, Position position) {
        String[] lines = text.split("\n");
        if (position.getLine() >= lines.length) {
            return null;
        }
        
        String line = lines[position.getLine()];
        int character = position.getCharacter();
        
        if (character >= line.length()) {
            return null;
        }
        
        // 找到单词边界
        int start = character;
        int end = character;
        
        // 向前找到单词开始
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) {
            start--;
        }
        
        // 向后找到单词结束
        while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) {
            end++;
        }
        
        if (start < end) {
            return line.substring(start, end);
        }
        
        return null;
    }
    
    // 辅助方法：提取函数名
    
    
    /**
     * 验证文档并生成诊断信息
     */
    
    
    /**
     * 将MagicScriptException转换为LSP Diagnostic
     */
    
    
    /**
     * 将MagicScriptCompileException转换为LSP Diagnostic
     */
    
    
    /**
     * 将Span转换为LSP Range
     */
    
    
    /**
     * 发布诊断信息到客户端
     */
    


    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String documentUri = params.getTextDocument().getUri();
            TextDocumentItem document = documents.get(documentUri);
            Position position = params.getPosition();
            
            if (document != null) {
                String text = document.getText();
                String word = getWordAtPosition(text, position);
                if (word != null && !word.isEmpty()) {
                    List<Location> definitions = definitionProvider.findDefinitions(word, text, documentUri);
                    if (!definitions.isEmpty()) {
                        return Either.forLeft(definitions);
                    }
                }
            }
            
            return Either.forLeft(Collections.emptyList());
        });
    }
    
    /**
     * 查找符号定义
     */
    private List<Location> findDefinitions(String symbol, String text, String documentUri) {
        List<Location> locations = new ArrayList<>();
        String[] lines = text.split("\n");
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            
            // 查找变量定义
            if (isVariableDefinition(line, symbol)) {
                Range range = createRangeForSymbol(line, symbol, i);
                if (range != null) {
                    locations.add(new Location(documentUri, range));
                }
            }
            
            // 查找函数定义
            if (isFunctionDefinition(line, symbol)) {
                Range range = createRangeForSymbol(line, symbol, i);
                if (range != null) {
                    locations.add(new Location(documentUri, range));
                }
            }
            
            // 查找import定义
            if (isImportDefinition(line, symbol)) {
                Range range = createRangeForSymbol(line, symbol, i);
                if (range != null) {
                    locations.add(new Location(documentUri, range));
                }
            }
        }
        
        return locations;
    }
    
    /**
     * 检查是否是变量定义
     */
    private boolean isVariableDefinition(String line, String symbol) {
        String trimmed = line.trim();
        
        // 检查 var/let/const 定义
        if (trimmed.startsWith("var ") || trimmed.startsWith("let ") || trimmed.startsWith("const ")) {
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2) {
                String varName = parts[1];
                // 移除赋值符号
                int assignIndex = varName.indexOf('=');
                if (assignIndex > 0) {
                    varName = varName.substring(0, assignIndex);
                }
                return symbol.equals(varName);
            }
        }
        
        // 检查赋值语句（简单的变量定义）
        if (trimmed.contains("=") && !trimmed.contains("==") && !trimmed.contains("!=") && !trimmed.contains("<=") && !trimmed.contains(">=")) {
            String[] parts = trimmed.split("=");
            if (parts.length >= 2) {
                String varName = parts[0].trim();
                // 移除可能的类型声明
                if (varName.contains(" ")) {
                    String[] varParts = varName.split("\\s+");
                    varName = varParts[varParts.length - 1];
                }
                return symbol.equals(varName);
            }
        }
        
        return false;
    }
    
    /**
     * 检查是否是函数定义
     */
    private boolean isFunctionDefinition(String line, String symbol) {
        String trimmed = line.trim();
        
        if (trimmed.contains("function ")) {
            String[] parts = trimmed.split("\\s+");
            for (int i = 0; i < parts.length - 1; i++) {
                if ("function".equals(parts[i]) && i + 1 < parts.length) {
                    String funcName = parts[i + 1];
                    // 移除括号
                    int parenIndex = funcName.indexOf('(');
                    if (parenIndex > 0) {
                        funcName = funcName.substring(0, parenIndex);
                    }
                    return symbol.equals(funcName);
                }
            }
        }
        
        return false;
    }
    
    /**
     * 检查是否是import定义
     */
    private boolean isImportDefinition(String line, String symbol) {
        String trimmed = line.trim();
        
        if (trimmed.startsWith("import ")) {
            // 检查 import 'xxx' as yyy 中的 yyy
            if (trimmed.contains(" as ")) {
                String[] parts = trimmed.split(" as ");
                if (parts.length >= 2) {
                    String alias = parts[1].trim();
                    return symbol.equals(alias);
                }
            } else {
                // 检查 import xxx 中的 xxx
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 2) {
                    String name = parts[1];
                    // 移除引号
                    name = name.replace("'", "").replace("\"", "");
                    // 提取类名
                    if (name.contains(".")) {
                        name = name.substring(name.lastIndexOf('.') + 1);
                    }
                    return symbol.equals(name);
                }
            }
        }
        
        return false;
    }
    
    /**
     * 为符号创建Range
     */
    private Range createRangeForSymbol(String line, String symbol, int lineNumber) {
        int startIndex = line.indexOf(symbol);
        if (startIndex >= 0) {
            Position start = new Position(lineNumber, startIndex);
            Position end = new Position(lineNumber, startIndex + symbol.length());
            return new Range(start, end);
        }
        return null;
    }

    /**
     * 解析文档并构建作用域信息
     */
    private ParseResult parseDocumentWithScope(String content) {
        try {
            Parser parser = new Parser();
            List<Node> nodes = parser.parse(content);
            
            // 创建根作用域
            VarScope rootScope = new VarScope();
            Map<Integer, VarScope> lineScopes = new HashMap<>();
            
            // 遍历AST节点，构建作用域信息
            buildScopeInfo(nodes, rootScope, lineScopes, content);
            
            return new ParseResult(nodes, rootScope, lineScopes);
        } catch (Exception e) {
            logger.debug("Failed to parse document with scope", e);
            return new ParseResult(Collections.emptyList(), new VarScope(), new HashMap<>());
        }
    }
    
    /**
     * 构建作用域信息
     */
    private void buildScopeInfo(List<Node> nodes, VarScope currentScope, Map<Integer, VarScope> lineScopes, String content) {
        if (nodes == null) return;
        
        String[] lines = content.split("\n");
        
        for (Node node : nodes) {
            if (node == null) continue;
            
            Span span = node.getSpan();
            if (span != null && span.getLine() != null) {
                int lineNumber = span.getLine().getLineNumber() - 1; // 转换为0基索引
                lineScopes.put(lineNumber, currentScope);
                
                // 处理不同类型的节点
                processNodeForScope(node, currentScope, lineScopes, content, lines);
            }
            
            // 递归处理子节点
            List<Node> children = getChildNodes(node);
            if (!children.isEmpty()) {
                buildScopeInfo(children, currentScope, lineScopes, content);
            }
        }
    }
    
    /**
     * 处理节点以构建作用域信息
     */
    private void processNodeForScope(Node node, VarScope currentScope, Map<Integer, VarScope> lineScopes, String content, String[] lines) {
        String nodeType = node.getClass().getSimpleName();
        
        switch (nodeType) {
            case "VariableDefine":
            case "VarDefine":
                // 变量定义节点
                handleVariableDefine(node, currentScope);
                break;
            case "FunctionDefine":
                // 函数定义节点
                handleFunctionDefine(node, currentScope, lineScopes, content);
                break;
            case "BlockStatement":
            case "IfStatement":
            case "ForStatement":
            case "TryStatement":
                // 创建新的作用域
                VarScope newScope = new VarScope(currentScope);
                handleBlockScope(node, newScope, lineScopes, content);
                break;
            default:
                // 其他节点类型的处理
                break;
        }
    }
    
    /**
     * 处理变量定义
     */
    private void handleVariableDefine(Node node, VarScope scope) {
        try {
            // 通过反射获取变量名（因为不同版本的magic-script可能有不同的API）
            String varName = extractVariableNameFromNode(node);
            if (varName != null && !varName.isEmpty()) {
                // 使用scope的size作为index
                int index = scope.size();
                VarIndex varIndex = new VarIndex(varName, index, false, false); // name, index, reference, readonly
                // 直接添加到作用域列表，兼容旧版本 VarScope
                scope.add(varIndex);
            }
        } catch (Exception e) {
            logger.debug("Failed to handle variable define", e);
        }
    }

    /**
     * 计算作用域深度
     */
    private int calculateScopeDepth(VarScope scope) {
        int depth = 0;
        VarScope current = scope;
        while (current != null && current.getParent() != null) {
            depth++;
            current = current.getParent();
        }
        return depth;
    }
    
    /**
     * 处理函数定义
     */
    private void handleFunctionDefine(Node node, VarScope parentScope, Map<Integer, VarScope> lineScopes, String content) {
        try {
            String funcName = extractFunctionNameFromNode(node);
            if (funcName != null && !funcName.isEmpty()) {
                // 在父作用域中添加函数名
                int parentIndex = parentScope.size();
                VarIndex funcIndex = new VarIndex(funcName, parentIndex, false, false); // name, index, reference, readonly
                parentScope.add(funcIndex);
                
                // 为函数体创建新的作用域
                VarScope funcScope = new VarScope(parentScope);
                
                // 处理函数参数
                List<String> parameters = extractFunctionParameters(node);
                for (String param : parameters) {
                    int paramIndex = funcScope.size();
                    VarIndex paramVarIndex = new VarIndex(param, paramIndex, false, false); // name, index, reference, readonly
                    funcScope.add(paramVarIndex);
                }
                
                // 更新行作用域映射
                updateLineScopesForFunction(node, funcScope, lineScopes);
            }
        } catch (Exception e) {
            logger.debug("Failed to handle function define", e);
        }
    }
    
    /**
     * 处理块作用域
     */
    private void handleBlockScope(Node node, VarScope blockScope, Map<Integer, VarScope> lineScopes, String content) {
        try {
            // 更新块内所有行的作用域
            updateLineScopesForBlock(node, blockScope, lineScopes);
        } catch (Exception e) {
            logger.debug("Failed to handle block scope", e);
        }
    }
    
    /**
     * 从节点中提取变量名
     */
    private String extractVariableNameFromNode(Node node) {
        try {
            // 尝试通过反射获取变量名
            java.lang.reflect.Method getNameMethod = node.getClass().getMethod("getName");
            Object result = getNameMethod.invoke(node);
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            // 如果反射失败，尝试从span中提取
            return extractVariableNameFromSpan(node);
        }
    }
    
    /**
     * 从节点中提取函数名
     */
    private String extractFunctionNameFromNode(Node node) {
        try {
            // 尝试通过反射获取函数名
            java.lang.reflect.Method getNameMethod = node.getClass().getMethod("getName");
            Object result = getNameMethod.invoke(node);
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            // 如果反射失败，尝试从span中提取
            return extractFunctionNameFromSpan(node);
        }
    }
    
    /**
     * 从节点中提取函数参数
     */
    private List<String> extractFunctionParameters(Node node) {
        try {
            // 尝试通过反射获取参数列表
            java.lang.reflect.Method getParametersMethod = node.getClass().getMethod("getParameters");
            Object result = getParametersMethod.invoke(node);
            if (result instanceof List) {
                List<?> params = (List<?>) result;
                return params.stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
            }
        } catch (Exception e) {
            logger.debug("Failed to extract function parameters", e);
        }
        return Collections.emptyList();
    }
    
    /**
     * 从span中提取变量名
     */
    private String extractVariableNameFromSpan(Node node) {
        Span span = node.getSpan();
        if (span != null && span.getText() != null) {
            String text = span.getText().trim();
            // 简单的变量名提取逻辑
            if (text.startsWith("var ") || text.startsWith("let ") || text.startsWith("const ")) {
                String[] parts = text.split("\\s+");
                if (parts.length >= 2) {
                    String varName = parts[1];
                    int assignIndex = varName.indexOf('=');
                    if (assignIndex > 0) {
                        varName = varName.substring(0, assignIndex);
                    }
                    return varName;
                }
            }
        }
        return null;
    }
    
    /**
     * 从span中提取函数名
     */
    private String extractFunctionNameFromSpan(Node node) {
        Span span = node.getSpan();
        if (span != null && span.getText() != null) {
            String text = span.getText().trim();
            if (text.contains("function ")) {
                String[] parts = text.split("\\s+");
                for (int i = 0; i < parts.length - 1; i++) {
                    if ("function".equals(parts[i]) && i + 1 < parts.length) {
                        String funcName = parts[i + 1];
                        int parenIndex = funcName.indexOf('(');
                        if (parenIndex > 0) {
                            funcName = funcName.substring(0, parenIndex);
                        }
                        return funcName;
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * 获取节点的子节点
     */
    private List<Node> getChildNodes(Node node) {
        List<Node> children = new ArrayList<>();
        try {
            // 尝试通过反射获取子节点
            java.lang.reflect.Method getChildrenMethod = node.getClass().getMethod("getChildren");
            Object result = getChildrenMethod.invoke(node);
            if (result instanceof List) {
                List<?> childList = (List<?>) result;
                for (Object child : childList) {
                    if (child instanceof Node) {
                        children.add((Node) child);
                    }
                }
            }
        } catch (Exception e) {
            // 如果没有getChildren方法，尝试其他常见的方法名
            try {
                java.lang.reflect.Method getBodyMethod = node.getClass().getMethod("getBody");
                Object result = getBodyMethod.invoke(node);
                if (result instanceof Node) {
                    children.add((Node) result);
                } else if (result instanceof List) {
                    List<?> bodyList = (List<?>) result;
                    for (Object child : bodyList) {
                        if (child instanceof Node) {
                            children.add((Node) child);
                        }
                    }
                }
            } catch (Exception e2) {
                // 忽略，返回空列表
            }
        }
        return children;
    }
    
    /**
     * 更新函数的行作用域映射
     */
    private void updateLineScopesForFunction(Node funcNode, VarScope funcScope, Map<Integer, VarScope> lineScopes) {
        Span span = funcNode.getSpan();
        if (span != null && span.getLine() != null) {
            int startLine = span.getLine().getLineNumber() - 1;
            int endLine = span.getLine().getEndLineNumber() - 1;
            
            for (int line = startLine; line <= endLine; line++) {
                lineScopes.put(line, funcScope);
            }
        }
    }
    
    /**
     * 更新块的行作用域映射
     */
    private void updateLineScopesForBlock(Node blockNode, VarScope blockScope, Map<Integer, VarScope> lineScopes) {
        Span span = blockNode.getSpan();
        if (span != null && span.getLine() != null) {
            int startLine = span.getLine().getLineNumber() - 1;
            int endLine = span.getLine().getEndLineNumber() - 1;
            
            for (int line = startLine; line <= endLine; line++) {
                lineScopes.put(line, blockScope);
            }
        }
    }
    
    /**
     * 获取指定位置的作用域
     */
    private VarScope getScopeAtPosition(String documentUri, Position position) {
        ParseResult parseResult = parseResults.get(documentUri);
        if (parseResult != null) {
            VarScope scope = parseResult.getLineScopes().get(position.getLine());
            return scope != null ? scope : parseResult.getRootScope();
        }
        return new VarScope();
    }
    
    /**
     * 获取作用域中的所有变量
     */
    private List<String> getVariablesInScope(VarScope scope) {
        List<String> variables = new ArrayList<>();
        try {
            // 优先通过 VarScope 的列表直接获取变量名，避免依赖反射
            if (scope != null) {
                for (VarIndex varIndex : scope) {
                    String name = varIndex.getName();
                    if (name != null) {
                        variables.add(name);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to get variables in scope", e);
        }
        return variables;
    }

    /**
     * 基于作用域查找变量引用
     */
    public List<Location> findReferencesWithScope(String uri, Position position) {
        List<Location> references = new ArrayList<>();
        
        try {
            // 获取文档内容
            String content = getDocumentContent(uri);
            if (content == null) {
                return references;
            }
            
            // 获取解析结果
            ParseResult parseResult = parseResults.get(uri);
            if (parseResult == null) {
                return references;
            }
            
            // 获取光标位置的符号
            String symbol = getSymbolAtPosition(content, position);
            if (symbol == null || symbol.trim().isEmpty()) {
                return references;
            }
            
            // 获取符号定义的作用域
            VarScope definitionScope = findSymbolDefinitionScope(parseResult, symbol, position);
            if (definitionScope == null) {
                return references;
            }
            
            // 在定义作用域及其子作用域中查找引用
            findReferencesInScope(content, symbol, definitionScope, references, uri);
            
        } catch (Exception e) {
            logger.error("Error finding references with scope", e);
        }
        
        return references;
    }

    /**
     * 查找符号定义的作用域
     */
    private VarScope findSymbolDefinitionScope(ParseResult parseResult, String symbol, Position position) {
        // 获取当前位置的作用域
        VarScope currentScope = getScopeAtPosition(parseResult, position);
        
        // 向上查找包含该符号定义的作用域
        VarScope scope = currentScope;
        while (scope != null) {
            List<String> variables = getVariablesInScope(scope);
            if (variables.contains(symbol)) {
                return scope;
            }
            scope = scope.getParent();
        }
        
        return null;
    }

    /**
     * 在指定作用域及其子作用域中查找引用
     */
    private void findReferencesInScope(String content, String symbol, VarScope scope, 
                                     List<Location> references, String uri) {
        // 在当前作用域中查找引用
        findReferencesInScopeContent(content, symbol, scope, references, uri);
        
        // 旧版本 VarScope 可能不提供子作用域访问，先仅处理当前作用域
    }

    /**
     * 在作用域内容中查找符号引用
     */
    private void findReferencesInScopeContent(String content, String symbol, VarScope scope, 
                                            List<Location> references, String uri) {
        String[] lines = content.split("\n");
        
        // 由于VarScope没有直接的行范围信息，我们在整个文档中查找引用
        // 然后通过作用域检查来过滤结果
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            
            // 使用正则表达式查找符号引用
            Pattern pattern = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");
            Matcher matcher = pattern.matcher(line);
            
            while (matcher.find()) {
                // 验证这是一个有效的引用（不是字符串字面量或注释）
                if (isValidReference(line, matcher.start(), matcher.end())) {
                    Range range = new Range(
                        new Position(lineIndex, matcher.start()),
                        new Position(lineIndex, matcher.end())
                    );
                    references.add(new Location(uri, range));
                }
            }
        }
    }

    /**
     * 验证是否为有效的符号引用
     */
    private boolean isValidReference(String line, int start, int end) {
        // 检查是否在字符串字面量中
        if (isInStringLiteral(line, start)) {
            return false;
        }
        
        // 检查是否在注释中
        if (isInComment(line, start)) {
            return false;
        }
        
        return true;
    }

    /**
     * 检查位置是否在字符串字面量中
     */
    private boolean isInStringLiteral(String line, int position) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;
        
        for (int i = 0; i < position && i < line.length(); i++) {
            char c = line.charAt(i);
            
            if (escaped) {
                escaped = false;
                continue;
            }
            
            if (c == '\\') {
                escaped = true;
                continue;
            }
            
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }
        }
        
        return inSingleQuote || inDoubleQuote;
    }

    /**
     * 检查位置是否在注释中
     */
    private boolean isInComment(String line, int position) {
        // 检查单行注释
        int commentIndex = line.indexOf("//");
        if (commentIndex >= 0 && position >= commentIndex) {
            return true;
        }
        
        // 检查多行注释（简化处理）
        int blockCommentStart = line.indexOf("/*");
        int blockCommentEnd = line.indexOf("*/");
        if (blockCommentStart >= 0 && position >= blockCommentStart) {
            if (blockCommentEnd < 0 || position < blockCommentEnd) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 获取光标位置的符号
     */
    private String getSymbolAtPosition(String content, Position position) {
        String[] lines = content.split("\n");
        if (position.getLine() >= lines.length) {
            return null;
        }
        
        String line = lines[position.getLine()];
        int character = position.getCharacter();
        
        if (character >= line.length()) {
            return null;
        }
        
        // 找到符号的开始和结束位置
        int start = character;
        int end = character;
        
        // 向前查找符号开始
        while (start > 0 && isIdentifierChar(line.charAt(start - 1))) {
            start--;
        }
        
        // 向后查找符号结束
        while (end < line.length() && isIdentifierChar(line.charAt(end))) {
            end++;
        }
        
        if (start == end) {
            return null;
        }
        
        return line.substring(start, end);
    }

    /**
     * 判断字符是否为标识符字符
     */
    private boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /**
     * 获取文档内容
     */
    public String getDocumentContent(String uri) {
        TextDocumentItem document = documents.get(uri);
        return document != null ? document.getText() : null;
    }

    /**
     * 获取指定位置的作用域（重载方法）
     */
    private VarScope getScopeAtPosition(ParseResult parseResult, Position position) {
        int line = position.getLine();
        return parseResult.lineScopes.get(line);
    }

    /**
     * 基于作用域的重命名功能
     */
    public Map<String, List<TextEdit>> renameWithScope(String uri, Position position, String newName) {
        Map<String, List<TextEdit>> changes = new HashMap<>();
        
        try {
            // 获取当前文档的解析结果
            ParseResult parseResult = parseResults.get(uri);
            if (parseResult == null) {
                logger.debug("No parse result found for document: {}", uri);
                return changes;
            }
            
            // 获取当前位置的符号
            String content = getDocumentContent(uri);
            if (content == null) {
                return changes;
            }
            
            String symbol = getSymbolAtPosition(content, position);
            if (symbol == null || symbol.trim().isEmpty()) {
                logger.debug("No symbol found at position {}:{}", position.getLine(), position.getCharacter());
                return changes;
            }
            
            // 查找符号定义的作用域
            VarScope definitionScope = findSymbolDefinitionScope(parseResult, symbol, position);
            if (definitionScope == null) {
                logger.debug("No definition scope found for symbol: {}", symbol);
                return changes;
            }
            
            // 在当前文档中查找所有引用并重命名
            List<TextEdit> documentEdits = findAndRenameInDocument(content, symbol, newName, definitionScope);
            if (!documentEdits.isEmpty()) {
                changes.put(uri, documentEdits);
            }
            
            logger.debug("Scope-based rename completed for symbol '{}' to '{}'. {} edits found", 
                        symbol, newName, documentEdits.size());
            
        } catch (Exception e) {
            logger.error("Error during scope-based rename operation", e);
        }
        
        return changes;
    }

    /**
     * 在文档中查找并重命名符号（基于作用域）
     */
    private List<TextEdit> findAndRenameInDocument(String content, String oldName, String newName, VarScope definitionScope) {
        List<TextEdit> edits = new ArrayList<>();
        String[] lines = content.split("\n");
        
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            
            // 使用正则表达式查找符号的所有出现
            Pattern pattern = Pattern.compile("\\b" + Pattern.quote(oldName) + "\\b");
            Matcher matcher = pattern.matcher(line);
            
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                
                // 检查这个引用是否在正确的作用域中
                Position refPosition = new Position(lineIndex, start);
                if (isReferenceInScope(refPosition, oldName, definitionScope)) {
                    // 创建文本编辑
                    Range range = new Range(
                        new Position(lineIndex, start),
                        new Position(lineIndex, end)
                    );
                    TextEdit edit = new TextEdit(range, newName);
                    edits.add(edit);
                }
            }
        }
        
        return edits;
    }

    /**
     * 检查引用是否在指定作用域中
     */
    private boolean isReferenceInScope(Position position, String symbol, VarScope definitionScope) {
        try {
            // 获取引用位置的作用域
            VarScope currentScope = getScopeAtPosition(parseResults.get(getDocumentUri(position)), position);
            
            // 检查当前作用域或其父作用域是否包含定义作用域
            VarScope scope = currentScope;
            while (scope != null) {
                if (scope == definitionScope) {
                    return true;
                }
                
                // 检查当前作用域是否可以访问定义作用域中的变量
                if (canAccessVariable(scope, definitionScope, symbol)) {
                    return true;
                }
                
                scope = getParentScope(scope);
            }
            
            return false;
        } catch (Exception e) {
            logger.debug("Error checking reference scope", e);
            return false;
        }
    }

    /**
     * 检查作用域是否可以访问指定变量
     */
    private boolean canAccessVariable(VarScope currentScope, VarScope definitionScope, String symbol) {
        // 如果定义作用域是当前作用域的父作用域，则可以访问
        VarScope parent = getParentScope(currentScope);
        while (parent != null) {
            if (parent == definitionScope) {
                return true;
            }
            parent = getParentScope(parent);
        }
        
        // 如果定义作用域是全局作用域，则可以访问
        return isGlobalScope(definitionScope);
    }

    /**
     * 获取父作用域
     */
    private VarScope getParentScope(VarScope scope) {
        return scope == null ? null : scope.getParent();
    }

    /**
     * 检查是否为全局作用域
     */
    private boolean isGlobalScope(VarScope scope) {
        return getParentScope(scope) == null;
    }

    /**
     * 获取文档URI（辅助方法）
     */
    private String getDocumentUri(Position position) {
        // 这里需要根据实际情况获取文档URI
        // 由于Position对象本身不包含URI信息，这里返回null
        // 在实际使用中，应该从调用上下文中获取URI
        return null;
    }

    // ==================== 语义高亮支持 ====================

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uri = params.getTextDocument().getUri();
                String content = getDocumentContent(uri);
                if (content == null) {
                    return new SemanticTokens(Collections.emptyList());
                }
                ParseResult parseResult = parseResults.get(uri);
                Map<Integer, VarScope> lineScopes = parseResult != null ? parseResult.getLineScopes() : null;
                List<Integer> tokens = semanticTokensProvider.generateSemanticTokens(
                        content,
                        uri,
                        lineScopes,
                        getMagicFunctions()
                );
                return new SemanticTokens(tokens);
            } catch (Exception e) {
                logger.error("Error generating semantic tokens", e);
                return new SemanticTokens(Collections.emptyList());
            }
        });
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensRange(SemanticTokensRangeParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uri = params.getTextDocument().getUri();
                String content = getDocumentContent(uri);
                if (content == null) {
                    return new SemanticTokens(Collections.emptyList());
                }

                Range range = params.getRange();
                ParseResult parseResult = parseResults.get(uri);
                Map<Integer, VarScope> lineScopes = parseResult != null ? parseResult.getLineScopes() : null;
                List<Integer> tokens = semanticTokensProvider.generateSemanticTokensForRange(
                        content,
                        uri,
                        range,
                        lineScopes,
                        getMagicFunctions()
                );
                return new SemanticTokens(tokens);
            } catch (Exception e) {
                logger.error("Error generating semantic tokens for range", e);
                return new SemanticTokens(Collections.emptyList());
            }
        });
    }

    /**
     * 生成整个文档的语义标记
     */
    private List<Integer> generateSemanticTokens(String content, String uri) {
        List<SemanticToken> semanticTokens = new ArrayList<>();
        String[] lines = content.split("\n");
        
        ParseResult parseResult = parseResults.get(uri);
        
        boolean inTripleString = false;
        int tripleStartLine = -1;
        int tripleStartChar = 0;

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            VarScope currentScope = parseResult != null ? parseResult.lineScopes.get(lineIndex) : null;

            // 若当前不在三引号字符串内，且该行是 // 注释行，则直接按单行解析并跳过三引号逻辑
            if (!inTripleString) {
                String trimmed = line.trim();
                if (trimmed.startsWith("//")) {
                    analyzeLineForSemanticTokens(line, lineIndex, currentScope, semanticTokens, uri);
                    continue;
                }
            }

            if (inTripleString) {
                int closeIdx = indexOfTripleQuote(line, 0);
                if (closeIdx >= 0) {
                    // 在三引号内，分段添加字符串与插值
                    addStringAndInterpolationTokensInSegment(line, 0, closeIdx + 3, 0, lineIndex, semanticTokens);
                    inTripleString = false;
                    if (closeIdx + 3 < line.length()) {
                        String rest = line.substring(closeIdx + 3);
                        analyzeLineForSemanticTokens(rest, lineIndex, currentScope, semanticTokens, uri, closeIdx + 3);
                    }
                } else {
                    addStringAndInterpolationTokensInSegment(line, 0, line.length(), 0, lineIndex, semanticTokens);
                }
                continue;
            }

            int openIdx = indexOfTripleQuote(line, 0);
            if (openIdx < 0) {
                analyzeLineForSemanticTokens(line, lineIndex, currentScope, semanticTokens, uri);
            } else {
                // 若三引号位于 // 之后，视为注释内容，不进入三引号状态
                int slCommentIdx = line.indexOf("//");
                if (slCommentIdx >= 0 && slCommentIdx <= openIdx) {
                    analyzeLineForSemanticTokens(line, lineIndex, currentScope, semanticTokens, uri);
                    continue;
                }

                if (openIdx > 0) {
                    String before = line.substring(0, openIdx);
                    analyzeLineForSemanticTokens(before, lineIndex, currentScope, semanticTokens, uri, 0);
                }
                int closeIdx = indexOfTripleQuote(line, openIdx + 3);
                if (closeIdx >= 0) {
                    addStringAndInterpolationTokensInSegment(line, openIdx, closeIdx + 3, openIdx, lineIndex, semanticTokens);
                    if (closeIdx + 3 < line.length()) {
                        String after = line.substring(closeIdx + 3);
                        analyzeLineForSemanticTokens(after, lineIndex, currentScope, semanticTokens, uri, closeIdx + 3);
                    }
                } else {
                    addStringAndInterpolationTokensInSegment(line, openIdx, line.length(), openIdx, lineIndex, semanticTokens);
                    inTripleString = true;
                    tripleStartLine = lineIndex;
                    tripleStartChar = openIdx;
                }
            }
        }
        
        return encodeSemanticTokens(semanticTokens);
    }

    /**
     * 生成指定范围的语义标记
     */
    private List<Integer> generateSemanticTokensForRange(String content, String uri, Range range) {
        List<SemanticToken> semanticTokens = new ArrayList<>();
        String[] lines = content.split("\n");
        
        ParseResult parseResult = parseResults.get(uri);
        
        int startLine = range.getStart().getLine();
        int endLine = range.getEnd().getLine();
        
        for (int lineIndex = startLine; lineIndex <= endLine && lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            VarScope currentScope = parseResult != null ? parseResult.lineScopes.get(lineIndex) : null;
            
            // 对于起始行和结束行，需要考虑字符范围
            if (lineIndex == startLine || lineIndex == endLine) {
                int startChar = (lineIndex == startLine) ? range.getStart().getCharacter() : 0;
                int endChar = (lineIndex == endLine) ? range.getEnd().getCharacter() : line.length();
                
                if (startChar < line.length()) {
                    String linePart = line.substring(startChar, Math.min(endChar, line.length()));
                    analyzeLineForSemanticTokens(linePart, lineIndex, currentScope, semanticTokens, uri, startChar);
                }
            } else {
                analyzeLineForSemanticTokens(line, lineIndex, currentScope, semanticTokens, uri);
            }
        }
        
        return encodeSemanticTokens(semanticTokens);
    }

    /**
     * 分析单行代码的语义标记
     */
    private void analyzeLineForSemanticTokens(String line, int lineNumber, VarScope scope, 
                                            List<SemanticToken> tokens, String uri) {
        analyzeLineForSemanticTokens(line, lineNumber, scope, tokens, uri, 0);
    }

    /**
     * 分析单行代码的语义标记（带起始字符偏移）
     */
    private void analyzeLineForSemanticTokens(String line, int lineNumber, VarScope scope, 
                                            List<SemanticToken> tokens, String uri, int startOffset) {
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            
            // 跳过空白字符
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            
            // 处理字符串字面量
            if (c == '"' || c == '\'') {
                int start = i;
                int end = findStringEnd(line, i, c);
                // 使用分段方式添加字符串与插值，避免语义标记重叠
                addStringAndInterpolationTokensInSegment(line, start, end + 1, startOffset + start, lineNumber, tokens);
                i = end + 1;
                continue;
            }
            
            // 处理注释
            if (c == '/' && i + 1 < line.length()) {
                if (line.charAt(i + 1) == '/') {
                    // 单行注释
                    tokens.add(new SemanticToken(lineNumber, startOffset + i, line.length() - i, 
                                               SemanticTokenType.COMMENT, Collections.emptyList()));
                    break;
                } else if (line.charAt(i + 1) == '*') {
                    // 多行注释开始
                    int start = i;
                    int end = line.indexOf("*/", i + 2);
                    if (end == -1) {
                        end = line.length();
                    } else {
                        end += 2;
                    }
                    tokens.add(new SemanticToken(lineNumber, startOffset + start, end - start, 
                                               SemanticTokenType.COMMENT, Collections.emptyList()));
                    i = end;
                    continue;
                }
            }
            
            // 处理数字
            if (Character.isDigit(c)) {
                int start = i;
                while (i < line.length() && (Character.isDigit(line.charAt(i)) || line.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new SemanticToken(lineNumber, startOffset + start, i - start, 
                                           SemanticTokenType.NUMBER, Collections.emptyList()));
                continue;
            }
            
            // 处理标识符和关键字
            if (isIdentifierStart(c)) {
                int start = i;
                while (i < line.length() && isIdentifierChar(line.charAt(i))) {
                    i++;
                }
                
                String identifier = line.substring(start, i);
                SemanticTokenType tokenType = determineTokenType(identifier, scope, uri);
                List<SemanticTokenModifier> modifiers = determineTokenModifiers(identifier, scope, uri);
                
                tokens.add(new SemanticToken(lineNumber, startOffset + start, i - start, tokenType, modifiers));
                continue;
            }
            
            // 处理操作符
            if (OPERATORS.contains(String.valueOf(c))) {
                int start = i;
                String op = String.valueOf(c);
                
                // 检查多字符操作符
                if (i + 1 < line.length()) {
                    String twoChar = line.substring(i, i + 2);
                    if (OPERATORS.contains(twoChar)) {
                        op = twoChar;
                        i++;
                    }
                }
                
                tokens.add(new SemanticToken(lineNumber, startOffset + start, op.length(), 
                                           SemanticTokenType.OPERATOR, Collections.emptyList()));
                i++;
                continue;
            }
            
            i++;
        }
    }

    /**
     * 查找字符串结束位置
     */
    private int findStringEnd(String line, int start, char quote) {
        int i = start + 1;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == quote) {
                return i;
            }
            if (c == '\\' && i + 1 < line.length()) {
                i++; // 跳过转义字符
            }
            i++;
        }
        return line.length() - 1;
    }

    /**
     * 查找三引号的位置
     */
    private int indexOfTripleQuote(String line, int fromIndex) {
        return line.indexOf("\"\"\"", fromIndex);
    }

    /**
     * 在给定片段内查找并添加插值标记（#{} 与 ${}）
     */
    private void addInterpolationTokensInSegment(String line, int segmentStart, int segmentEnd, int segmentOffset,
                                                  int lineNumber, List<SemanticToken> tokens) {
        if (segmentStart >= segmentEnd) {
            return;
        }
        int i = segmentStart;
        while (i < segmentEnd) {
            char c = line.charAt(i);
            if ((c == '#' || c == '$') && i + 1 < segmentEnd && line.charAt(i + 1) == '{') {
                int braceOpen = i + 1;
                int j = braceOpen + 1;
                int braceDepth = 1;
                while (j < segmentEnd && braceDepth > 0) {
                    char cj = line.charAt(j);
                    if (cj == '{') braceDepth++;
                    else if (cj == '}') braceDepth--;
                    j++;
                }
                int braceClose = j - 1;
                if (braceDepth == 0 && braceOpen + 1 <= braceClose - 1) {
                    int varStart = braceOpen + 1;
                    int varLength = braceClose - varStart;
                    if (varLength > 0) {
                        tokens.add(new SemanticToken(lineNumber, segmentOffset + (varStart - segmentStart), varLength,
                                SemanticTokenType.VARIABLE, Collections.emptyList()));
                    }
                }
                i = j; // 跳过已处理的插值
                continue;
            }
            i++;
        }
    }

    /**
     * 在字符串片段内分段添加 STRING 与插值（#{}、${}）标记，避免与变量高亮重叠
     * segmentStart/segmentEnd 为片段在整行中的索引，segmentOffset 为该片段起始位置的绝对偏移
     */
    private void addStringAndInterpolationTokensInSegment(String line, int segmentStart, int segmentEnd, int segmentOffset,
                                                           int lineNumber, List<SemanticToken> tokens) {
        if (segmentStart >= segmentEnd) return;

        int cursor = segmentStart;
        int i = segmentStart;
        while (i < segmentEnd) {
            char c = line.charAt(i);
            boolean isInterpolationStart = (c == '#' || c == '$') && (i + 1 < segmentEnd) && line.charAt(i + 1) == '{';
            if (!isInterpolationStart) {
                i++;
                continue;
            }

            // 先提交 [cursor, i) 的字符串片段
            if (i > cursor) {
                tokens.add(new SemanticToken(lineNumber, segmentOffset + (cursor - segmentStart), i - cursor,
                        SemanticTokenType.STRING, Collections.emptyList()));
            }

            // 标记插值起始 "#{" 或 "${}" 的符号为 OPERATOR（可选）
            tokens.add(new SemanticToken(lineNumber, segmentOffset + (i - segmentStart), 2,
                    SemanticTokenType.OPERATOR, Collections.emptyList()));

            // 查找匹配的 '}' 并在其中高亮标识符
            int j = i + 2; // 跳过 #{ / ${
            int braceDepth = 1;
            while (j < segmentEnd && braceDepth > 0) {
                char cj = line.charAt(j);
                if (cj == '{') braceDepth++;
                else if (cj == '}') braceDepth--;
                j++;
            }
            int exprEndExclusive = j; // 指向 '}' 之后
            int exprStart = i + 2;
            int exprEnd = exprEndExclusive - 1; // '}' 的位置

            // 在表达式区域内为标识符添加 VARIABLE 高亮
            int k = exprStart;
            while (k <= exprEnd) {
                char ck = line.charAt(k);
                if (isIdentifierStart(ck)) {
                    int s = k;
                    k++;
                    while (k <= exprEnd && isIdentifierChar(line.charAt(k))) {
                        k++;
                    }
                    tokens.add(new SemanticToken(lineNumber, segmentOffset + (s - segmentStart), k - s,
                            SemanticTokenType.VARIABLE, Collections.emptyList()));
                } else {
                    k++;
                }
            }

            // 标记闭合 '}' 为 OPERATOR（可选）
            tokens.add(new SemanticToken(lineNumber, segmentOffset + (exprEnd - segmentStart), 1,
                    SemanticTokenType.OPERATOR, Collections.emptyList()));

            // 继续扫描
            i = exprEndExclusive;
            cursor = exprEndExclusive; // 下一段字符串起点
        }

        // 提交最后的字符串片段
        if (cursor < segmentEnd) {
            tokens.add(new SemanticToken(lineNumber, segmentOffset + (cursor - segmentStart), segmentEnd - cursor,
                    SemanticTokenType.STRING, Collections.emptyList()));
        }
    }

    /**
     * 判断字符是否可以作为标识符开始
     */
    private boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    /**
     * 确定标记类型
     */
    private SemanticTokenType determineTokenType(String identifier, VarScope scope, String uri) {
        // 检查关键字
        if (MAGIC_KEYWORDS.contains(identifier) || LINQ_KEYWORDS.contains(identifier)) {
            return SemanticTokenType.KEYWORD;
        }
        
        // 检查内置类型
        if (BUILTIN_TYPES.contains(identifier)) {
            return SemanticTokenType.TYPE;
        }
        
        // 检查是否为函数
        if (isFunctionInScope(identifier, scope)) {
            return SemanticTokenType.FUNCTION;
        }
        
        // 检查是否为变量
        if (isVariableInScope(identifier, scope)) {
            return SemanticTokenType.VARIABLE;
        }
        
        // 检查是否为参数
        if (isParameterInScope(identifier, scope)) {
            return SemanticTokenType.PARAMETER;
        }
        
        // 默认为标识符
        return SemanticTokenType.VARIABLE;
    }

    /**
     * 确定标记修饰符
     */
    private List<SemanticTokenModifier> determineTokenModifiers(String identifier, VarScope scope, String uri) {
        List<SemanticTokenModifier> modifiers = new ArrayList<>();
        
        if (isReadonlyVariable(identifier, scope)) {
            modifiers.add(SemanticTokenModifier.READONLY);
        }
        
        if (isStaticMember(identifier)) {
            modifiers.add(SemanticTokenModifier.STATIC);
        }
        
        if (isDefinition(identifier, scope)) {
            modifiers.add(SemanticTokenModifier.DEFINITION);
        }
        
        return modifiers;
    }

    /**
     * 检查标识符是否为作用域中的变量
     */
    private boolean isVariableInScope(String identifier, VarScope scope) {
        if (scope == null) return false;
        
        try {
            List<String> variables = getVariablesInScope(scope);
            return variables.contains(identifier);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查标识符是否为作用域中的函数
     */
    private boolean isFunctionInScope(String identifier, VarScope scope) {
        if (scope == null) return false;
        
        try {
            // 检查当前作用域的函数
            VarScope currentScope = scope;
            while (currentScope != null) {
                // 这里需要根据实际的VarScope实现来检查函数
                // 暂时使用简单的启发式方法
                if (getMagicFunctions().contains(identifier)) {
                    return true;
                }
                currentScope = getParentScope(currentScope);
            }
        } catch (Exception e) {
            // 忽略异常
        }
        
        return false;
    }

    /**
     * 检查标识符是否为参数
     */
    private boolean isParameterInScope(String identifier, VarScope scope) {
        // 这里需要根据实际的VarScope实现来检查参数
        // 暂时返回false，可以在后续完善
        return false;
    }

    /**
     * 检查变量是否为只读
     */
    private boolean isReadonlyVariable(String identifier, VarScope scope) {
        // 检查是否为常量（通常以大写字母命名）
        return identifier.equals(identifier.toUpperCase()) && identifier.length() > 1;
    }

    /**
     * 检查是否为静态成员
     */
    private boolean isStaticMember(String identifier) {
        // 这里可以根据实际需要实现静态成员检查
        return false;
    }

    /**
     * 检查是否为定义
     */
    private boolean isDefinition(String identifier, VarScope scope) {
        // 这里可以根据实际需要实现定义检查
        return false;
    }

    /**
     * 编码语义标记为LSP格式
     */
    private List<Integer> encodeSemanticTokens(List<SemanticToken> tokens) {
        List<Integer> encoded = new ArrayList<>();
        
        // 按行号和字符位置排序
        tokens.sort((a, b) -> {
            int lineCompare = Integer.compare(a.line, b.line);
            if (lineCompare != 0) return lineCompare;
            return Integer.compare(a.character, b.character);
        });
        
        int prevLine = 0;
        int prevChar = 0;
        
        for (SemanticToken token : tokens) {
            // 计算相对位置
            int deltaLine = token.line - prevLine;
            int deltaChar = (deltaLine == 0) ? token.character - prevChar : token.character;
            
            // 编码：deltaLine, deltaChar, length, tokenType, tokenModifiers
            encoded.add(deltaLine);
            encoded.add(deltaChar);
            encoded.add(token.length);
            encoded.add(token.type.ordinal());
            encoded.add(encodeModifiers(token.modifiers));
            
            prevLine = token.line;
            prevChar = token.character;
        }
        
        return encoded;
    }

    /**
     * 编码修饰符
     */
    private int encodeModifiers(List<SemanticTokenModifier> modifiers) {
        int encoded = 0;
        for (SemanticTokenModifier modifier : modifiers) {
            encoded |= (1 << modifier.ordinal());
        }
        return encoded;
    }

    // ==================== CodeLens 提供者 ====================
    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            String content = getDocumentContent(uri);
            if (content == null) {
                return Collections.emptyList();
            }
            return codeLensProvider.generateCodeLenses(uri, content);
        });
    }

    @Override
    public CompletableFuture<CodeLens> resolveCodeLens(CodeLens unresolved) {
        return CompletableFuture.completedFuture(codeLensProvider.resolveCodeLens(unresolved));
    }

    // ==================== References ====================
    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uri = params.getTextDocument().getUri();
                Position position = params.getPosition();

                // 首先尝试使用基于作用域的引用查找
                List<Location> scopeBasedReferences = findReferencesWithScope(uri, position);
                if (!scopeBasedReferences.isEmpty()) {
                    return scopeBasedReferences;
                }

                // 回退到基于 Provider 的查找
                String content = getDocumentContent(uri);
                if (content == null) {
                    return Collections.emptyList();
                }
                String symbol = referenceProvider.getSymbolAtPosition(content, position);
                if (symbol == null || symbol.trim().isEmpty()) {
                    return Collections.emptyList();
                }
                return referenceProvider.findReferences(symbol, uri);
            } catch (Exception e) {
                logger.error("Error finding references", e);
                return Collections.emptyList();
            }
        });
    }

    // ==================== Document Highlight ====================
    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(DocumentHighlightParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uri = params.getTextDocument().getUri();
                Position position = params.getPosition();

                String content = getDocumentContent(uri);
                if (content == null) {
                    return Collections.emptyList();
                }
                String symbol = referenceProvider.getSymbolAtPosition(content, position);
                if (symbol == null || symbol.trim().isEmpty()) {
                    return Collections.emptyList();
                }
                List<DocumentHighlight> highlights = highlightProvider.findSymbolHighlights(content, symbol);
                return highlights;
            } catch (Exception e) {
                logger.error("Error during document highlight", e);
                return Collections.emptyList();
            }
        });
    }

    // ==================== Formatting ====================
    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uri = params.getTextDocument().getUri();
                FormattingOptions options = params.getOptions();
                String content = getDocumentContent(uri);
                return formattingProvider.formatDocument(content, options);
            } catch (Exception e) {
                logger.error("Error during document formatting", e);
                return Collections.emptyList();
            }
        });
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uri = params.getTextDocument().getUri();
                Range range = params.getRange();
                FormattingOptions options = params.getOptions();
                String content = getDocumentContent(uri);
                return formattingProvider.formatRange(content, range, options);
            } catch (Exception e) {
                logger.error("Error during range formatting", e);
                return Collections.emptyList();
            }
        });
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(DocumentOnTypeFormattingParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uri = params.getTextDocument().getUri();
                Position position = params.getPosition();
                String ch = params.getCh();
                FormattingOptions options = params.getOptions();
                String content = getDocumentContent(uri);
                return formattingProvider.formatOnType(content, position, ch, options);
            } catch (Exception e) {
                logger.error("Error during on-type formatting", e);
                return Collections.emptyList();
            }
        });
    }

    // ==================== Folding Range ====================
    @Override
    public CompletableFuture<List<FoldingRange>> foldingRange(FoldingRangeRequestParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uri = params.getTextDocument().getUri();
                String content = getDocumentContent(uri);
                if (content == null) {
                    return Collections.emptyList();
                }
                return foldingProvider.createFoldingRanges(content);
            } catch (Exception e) {
                logger.error("Error creating folding ranges", e);
                return Collections.emptyList();
            }
        });
    }

    // ==================== Rename ====================
    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uri = params.getTextDocument().getUri();
                Position position = params.getPosition();
                String newName = params.getNewName();

                // 首先尝试使用基于作用域的重命名
                Map<String, List<TextEdit>> scopeBasedChanges = renameWithScope(uri, position, newName);
                if (scopeBasedChanges != null && !scopeBasedChanges.isEmpty()) {
                    WorkspaceEdit scopeBasedEdit = new WorkspaceEdit();
                    scopeBasedEdit.setChanges(scopeBasedChanges);
                    return scopeBasedEdit;
                }

                // 回退到 Provider 逻辑
                String content = getDocumentContent(uri);
                if (content == null) {
                    return new WorkspaceEdit();
                }
                return renameProvider.rename(content, position, newName, uri);
            } catch (Exception e) {
                logger.error("Error during rename operation", e);
                return new WorkspaceEdit();
            }
        });
    }

    // ==================== 语义标记相关的内部类 ====================

    /**
     * 语义标记
     */
    private static class SemanticToken {
        final int line;
        final int character;
        final int length;
        final SemanticTokenType type;
        final List<SemanticTokenModifier> modifiers;

        SemanticToken(int line, int character, int length, SemanticTokenType type, List<SemanticTokenModifier> modifiers) {
            this.line = line;
            this.character = character;
            this.length = length;
            this.type = type;
            this.modifiers = modifiers;
        }
    }

    /**
     * 语义标记类型枚举
     */
    private enum SemanticTokenType {
        NAMESPACE, TYPE, CLASS, ENUM, INTERFACE, STRUCT, TYPE_PARAMETER, PARAMETER,
        VARIABLE, PROPERTY, ENUM_MEMBER, EVENT, FUNCTION, METHOD, MACRO, KEYWORD,
        MODIFIER, COMMENT, STRING, NUMBER, REGEXP, OPERATOR
    }

    /**
     * 语义标记修饰符枚举
     */
    private enum SemanticTokenModifier {
        DECLARATION, DEFINITION, READONLY, STATIC, DEPRECATED, ABSTRACT, ASYNC,
        MODIFICATION, DOCUMENTATION, DEFAULT_LIBRARY
    }
}
