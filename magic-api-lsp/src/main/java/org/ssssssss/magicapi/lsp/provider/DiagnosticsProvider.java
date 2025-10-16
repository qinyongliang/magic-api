package org.ssssssss.magicapi.lsp.provider;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.services.LanguageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.script.compile.MagicScriptCompileException;
import org.ssssssss.script.exception.MagicScriptException;
import org.ssssssss.script.parsing.Parser;
import org.ssssssss.script.parsing.Span;
import org.ssssssss.script.parsing.ast.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * DiagnosticsProvider 提供将 magic-script 语法/编译错误转换为 LSP Diagnostic 的能力，
 * 并负责按需向 LanguageClient 发布诊断。
 */
public class DiagnosticsProvider {

    private static final Logger logger = LoggerFactory.getLogger(DiagnosticsProvider.class);

    /**
     * 验证文档并生成诊断信息
     */
    public List<Diagnostic> validateDocumentContent(String content) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        try {
            Parser parser = new Parser();
            List<Node> nodes = parser.parse(content);
            // TODO: 可在此处补充更多语义检查逻辑（如未使用变量、未闭合结构等）
        } catch (MagicScriptException e) {
            Diagnostic diagnostic = convertExceptionToDiagnostic(e, content);
            if (diagnostic != null) {
                diagnostics.add(diagnostic);
            }
        } catch (MagicScriptCompileException e) {
            Diagnostic diagnostic = convertCompileExceptionToDiagnostic(e, content);
            if (diagnostic != null) {
                diagnostics.add(diagnostic);
            }
        } catch (Exception e) {
            Diagnostic diagnostic = new Diagnostic();
            diagnostic.setRange(new Range(new Position(0, 0), new Position(0, 0)));
            diagnostic.setSeverity(DiagnosticSeverity.Error);
            diagnostic.setMessage("语法解析错误: " + e.getMessage());
            diagnostic.setSource("magic-script");
            diagnostics.add(diagnostic);
        }

        return diagnostics;
    }

    /**
     * 发布诊断信息到客户端
     */
    public void publishDiagnostics(LanguageClient client, String uri, String content) {
        if (client == null || uri == null) {
            return;
        }
        List<Diagnostic> diagnostics = validateDocumentContent(content);
        PublishDiagnosticsParams params = new PublishDiagnosticsParams();
        params.setUri(uri);
        params.setDiagnostics(diagnostics);
        try {
            client.publishDiagnostics(params);
        } catch (Throwable t) {
            logger.debug("Failed to publish diagnostics: {}", t.getMessage());
        }
    }

    /**
     * 将 MagicScriptException 转换为 LSP Diagnostic
     */
    public Diagnostic convertExceptionToDiagnostic(MagicScriptException e, String content) {
        Span location = e.getLocation();
        if (location == null) {
            return null;
        }

        Diagnostic diagnostic = new Diagnostic();
        Range range = convertSpanToRange(location, content);
        diagnostic.setRange(range);
        diagnostic.setSeverity(DiagnosticSeverity.Error);
        diagnostic.setMessage(e.getSimpleMessage() != null ? e.getSimpleMessage() : e.getMessage());
        diagnostic.setSource("magic-script");
        return diagnostic;
    }

    /**
     * 将 MagicScriptCompileException 转换为 LSP Diagnostic
     */
    public Diagnostic convertCompileExceptionToDiagnostic(MagicScriptCompileException e, String content) {
        Throwable cause = e.getCause();
        if (cause instanceof MagicScriptException) {
            return convertExceptionToDiagnostic((MagicScriptException) cause, content);
        }
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.setRange(new Range(new Position(0, 0), new Position(0, 0)));
        diagnostic.setSeverity(DiagnosticSeverity.Error);
        diagnostic.setMessage("编译错误: " + e.getMessage());
        diagnostic.setSource("magic-script");
        return diagnostic;
    }

    /**
     * 将 Span 转换为 LSP Range
     */
    public Range convertSpanToRange(Span span, String content) {
        Span.Line line = span.getLine();
        int startLine = Math.max(0, line.getLineNumber() - 1);
        int endLine = Math.max(0, line.getEndLineNumber() - 1);
        int startCol = Math.max(0, line.getStartCol() - 1);
        int endCol = Math.max(0, line.getEndCol());
        return new Range(new Position(startLine, startCol), new Position(endLine, endCol));
    }
}