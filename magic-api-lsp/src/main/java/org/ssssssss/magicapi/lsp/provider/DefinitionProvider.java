package org.ssssssss.magicapi.lsp.provider;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.ArrayList;
import java.util.List;

public class DefinitionProvider {

    public List<Location> findDefinitions(String symbol, String text, String documentUri) {
        List<Location> locations = new ArrayList<>();
        String[] lines = text.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (isVariableDefinition(line, symbol)) {
                Range range = createRangeForSymbol(line, symbol, i);
                if (range != null) {
                    locations.add(new Location(documentUri, range));
                }
            }

            if (isFunctionDefinition(line, symbol)) {
                Range range = createRangeForSymbol(line, symbol, i);
                if (range != null) {
                    locations.add(new Location(documentUri, range));
                }
            }

            if (isImportDefinition(line, symbol)) {
                Range range = createRangeForSymbol(line, symbol, i);
                if (range != null) {
                    locations.add(new Location(documentUri, range));
                }
            }
        }

        return locations;
    }

    private boolean isVariableDefinition(String line, String symbol) {
        String trimmed = line.trim();
        if (trimmed.startsWith("var ") || trimmed.startsWith("let ") || trimmed.startsWith("const ")) {
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2) {
                String varName = parts[1];
                int assignIndex = varName.indexOf('=');
                if (assignIndex > 0) {
                    varName = varName.substring(0, assignIndex);
                }
                return symbol.equals(varName);
            }
        }
        if (trimmed.contains("=") && !trimmed.contains("==") && !trimmed.contains("!=") && !trimmed.contains("<=") && !trimmed.contains(">=")) {
            String[] parts = trimmed.split("=");
            if (parts.length >= 2) {
                String varName = parts[0].trim();
                if (varName.contains(" ")) {
                    String[] varParts = varName.split("\\s+");
                    varName = varParts[varParts.length - 1];
                }
                return symbol.equals(varName);
            }
        }
        return false;
    }

    private boolean isFunctionDefinition(String line, String symbol) {
        String trimmed = line.trim();
        if (trimmed.contains("function ")) {
            String[] parts = trimmed.split("\\s+");
            for (int i = 0; i < parts.length - 1; i++) {
                if ("function".equals(parts[i]) && i + 1 < parts.length) {
                    String funcName = parts[i + 1];
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

    private boolean isImportDefinition(String line, String symbol) {
        String trimmed = line.trim();
        if (trimmed.startsWith("import ")) {
            if (trimmed.contains(" as ")) {
                String[] parts = trimmed.split(" as ");
                if (parts.length >= 2) {
                    String alias = parts[1].trim();
                    return symbol.equals(alias);
                }
            } else {
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 2) {
                    String name = parts[1];
                    name = name.replace("'", "").replace("\"", "");
                    if (name.contains(".")) {
                        name = name.substring(name.lastIndexOf('.') + 1);
                    }
                    return symbol.equals(name);
                }
            }
        }
        return false;
    }

    private Range createRangeForSymbol(String line, String symbol, int lineNumber) {
        int startIndex = line.indexOf(symbol);
        if (startIndex >= 0) {
            Position start = new Position(lineNumber, startIndex);
            Position end = new Position(lineNumber, startIndex + symbol.length());
            return new Range(start, end);
        }
        return null;
    }
}


