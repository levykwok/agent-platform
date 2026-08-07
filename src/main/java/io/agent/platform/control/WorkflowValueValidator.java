/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Validates runtime values against a port schema. */
public final class WorkflowValueValidator {
    public WorkflowValueValidationResult validate(WorkflowPort port, ContractValue value) {
        List<String> errors = new ArrayList<>();
        if (port == null) return new WorkflowValueValidationResult(false, List.of("port is required"));
        if (value == null || value.data() == null) {
            if (port.required()) errors.add("required input is missing");
            return new WorkflowValueValidationResult(errors.isEmpty(), errors);
        }
        if (!port.contractRef().isBlank() && !value.contractRef().isBlank() && !port.contractRef().equals(value.contractRef())) {
            errors.add("contract mismatch: expected " + port.contractRef() + ", received " + value.contractRef());
        }
        validateSchema(port.schema(), value.data(), "$", errors);
        return new WorkflowValueValidationResult(errors.isEmpty(), errors);
    }

    private void validateSchema(Map<String, Object> schema, Object value, String path, List<String> errors) {
        if (schema == null || schema.isEmpty() || value == null) return;
        String type = text(schema.get("type"));
        if (!matches(type, value)) { errors.add(path + " must be " + type + ", received " + value.getClass().getSimpleName()); return; }
        if (value instanceof Map<?, ?> object) {
            for (String required : strings(schema.get("required"))) if (!object.containsKey(required) || object.get(required) == null) errors.add(path + "." + required + " is required");
            Object properties = schema.get("properties");
            if (properties instanceof Map<?, ?> propertyMap) propertyMap.forEach((key, propertySchema) -> {
                Object propertyValue = object.get(key);
                if (propertyValue != null && propertySchema instanceof Map<?, ?> raw) validateSchema(cast(raw), propertyValue, path + "." + key, errors);
            });
        } else if (value instanceof List<?> list && schema.get("items") instanceof Map<?, ?> raw) {
            for (int i = 0; i < list.size(); i++) validateSchema(cast(raw), list.get(i), path + "[" + i + "]", errors);
        }
    }

    private static boolean matches(String type, Object value) {
        return switch (type) {
            case "" -> true;
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || (value instanceof Number number && number.doubleValue() % 1 == 0);
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            default -> true;
        };
    }

    private static List<String> strings(Object value) { return value instanceof List<?> list ? list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList() : List.of(); }
    private static Map<String, Object> cast(Map<?, ?> raw) { Map<String, Object> result = new java.util.LinkedHashMap<>(); raw.forEach((key, value) -> result.put(String.valueOf(key), value)); return result; }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
