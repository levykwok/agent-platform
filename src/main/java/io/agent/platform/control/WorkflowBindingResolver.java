/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Applies field bindings stored on data edges to runtime values. */
public final class WorkflowBindingResolver {
    public ContractValue resolve(ContractValue source, String targetContractRef, Map<String, Object> binding, String sourceNodeId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (source != null) metadata.putAll(source.metadata());
        if (sourceNodeId != null && !sourceNodeId.isBlank()) metadata.put("source_node", sourceNodeId);
        if (source == null) return new ContractValue(targetContractRef, null, metadata);
        metadata.put("mapped", binding != null && !binding.isEmpty());
        return new ContractValue(targetContractRef == null || targetContractRef.isBlank() ? source.contractRef() : targetContractRef,
                project(source.data(), binding), metadata);
    }

    private Object project(Object source, Map<String, Object> binding) {
        if (binding == null || binding.isEmpty()) return source;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : binding.entrySet()) result.put(entry.getKey(), resolveExpression(source, entry.getValue()));
        return result;
    }

    private Object resolveExpression(Object source, Object expression) {
        if (expression instanceof Map<?, ?> raw) {
            Map<String, Object> spec = normalize(raw);
            if (spec.containsKey("constant")) return spec.get("constant");
            String path = text(spec.get("source_path"));
            Object value = path.isBlank() ? null : readPath(source, path);
            return value == null && spec.containsKey("default") ? spec.get("default") : value;
        }
        if (expression instanceof String path && isPath(path)) return readPath(source, path);
        return expression;
    }

    private Object readPath(Object root, String expression) {
        String path = expression.trim();
        if ("$".equals(path) || path.isBlank()) return root;
        if (path.startsWith("$")) path = path.substring(1);
        if (path.startsWith(".")) path = path.substring(1);
        Object current = root;
        for (String segment : segments(path)) {
            if (current instanceof Map<?, ?> map) current = map.get(segment);
            else if (current instanceof List<?> list) {
                try { current = list.get(Integer.parseInt(segment)); } catch (RuntimeException ignored) { return null; }
            } else return null;
            if (current == null) return null;
        }
        return current;
    }

    private List<String> segments(String path) {
        List<String> result = new ArrayList<>();
        for (String token : path.split("\\.")) {
            if (token.isBlank()) continue;
            int start = token.indexOf('[');
            if (start < 0) result.add(token);
            else {
                if (start > 0) result.add(token.substring(0, start));
                int end = token.indexOf(']', start);
                if (end > start) result.add(token.substring(start + 1, end));
            }
        }
        return result;
    }

    private static boolean isPath(String value) { return value != null && (value.trim().equals("$") || value.trim().startsWith("$.")); }
    private static Map<String, Object> normalize(Map<?, ?> raw) { Map<String, Object> result = new LinkedHashMap<>(); raw.forEach((key, value) -> result.put(String.valueOf(key), value)); return result; }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
