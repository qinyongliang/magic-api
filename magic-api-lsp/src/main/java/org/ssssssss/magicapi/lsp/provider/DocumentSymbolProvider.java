package org.ssssssss.magicapi.lsp.provider;

import org.eclipse.lsp4j.*;

import java.util.ArrayList;
import java.util.List;

public class DocumentSymbolProvider {

    public List<org.eclipse.lsp4j.jsonrpc.messages.Either<SymbolInformation, DocumentSymbol>> generate(String text) {
        List<org.eclipse.lsp4j.jsonrpc.messages.Either<SymbolInformation, DocumentSymbol>> symbols = new ArrayList<>();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("function ") || line.contains(" function ")) {
                String functionName = extractFunctionName(line);
                if (functionName != null) {
                    Range range = new Range(new Position(i, 0), new Position(i, line.length()));
                    DocumentSymbol symbol = new DocumentSymbol();
                    symbol.setName(functionName);
                    symbol.setKind(SymbolKind.Function);
                    symbol.setRange(range);
                    symbol.setSelectionRange(range);
                    symbols.add(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(symbol));
                }
            }

            if (line.startsWith("var ") || line.startsWith("let ") || line.startsWith("const ")) {
                String variableName = extractVariableName(line);
                if (variableName != null) {
                    Range range = new Range(new Position(i, 0), new Position(i, line.length()));
                    DocumentSymbol symbol = new DocumentSymbol();
                    symbol.setName(variableName);
                    symbol.setKind(SymbolKind.Variable);
                    symbol.setRange(range);
                    symbol.setSelectionRange(range);
                    symbols.add(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(symbol));
                }
            }

            if (line.startsWith("import ")) {
                String importName = extractImportName(line);
                if (importName != null) {
                    Range range = new Range(new Position(i, 0), new Position(i, line.length()));
                    DocumentSymbol symbol = new DocumentSymbol();
                    symbol.setName(importName);
                    symbol.setKind(SymbolKind.Module);
                    symbol.setRange(range);
                    symbol.setSelectionRange(range);
                    symbols.add(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(symbol));
                }
            }
        }
        return symbols;
    }

    private String extractFunctionName(String line) {
        String[] parts = line.split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("function".equals(parts[i]) && i + 1 < parts.length) {
                String name = parts[i + 1];
                int parenIndex = name.indexOf('(');
                if (parenIndex > 0) {
                    name = name.substring(0, parenIndex);
                }
                return name;
            }
        }
        return null;
    }

    private String extractVariableName(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length >= 2) {
            String name = parts[1];
            int assignIndex = name.indexOf('=');
            if (assignIndex > 0) {
                name = name.substring(0, assignIndex);
            }
            return name;
        }
        return null;
    }

    private String extractImportName(String line) {
        if (line.contains(" as ")) {
            String[] parts = line.split(" as ");
            if (parts.length >= 2) {
                return parts[1].trim();
            }
        } else {
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) {
                String name = parts[1];
                name = name.replace("'", "").replace("\"", "");
                if (name.contains(".")) {
                    name = name.substring(name.lastIndexOf('.') + 1);
                }
                return name;
            }
        }
        return null;
    }
}

