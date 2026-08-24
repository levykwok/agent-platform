/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Small, dependency-free JSON Schema subset for Agent business outputs.
 * Supports type, required, properties, items, enum and additionalProperties=false.
 */
public final class AgentResultSchemaValidator {

    private AgentResultSchemaValidator() {}

    public static List<String> validate(Object value, Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        validateAt(value, schema, "$.data", errors);
        return List.copyOf(errors);
    }

    private static void validateAt(
            Object value, Map<String, Object> schema, String path, List<String> errors) {
        String type = String.valueOf(schema.getOrDefault("type", "")).strip();
        if (!type.isBlank() && !matchesType(value, type)) {
            errors.add(path + " must be " + type);
            return;
        }
        Object enumValues = schema.get("enum");
        if (enumValues instanceof List<?> allowed && !allowed.contains(value)) {
            errors.add(path + " is not one of the allowed values");
        }
        if (value instanceof Map<?, ?> object) {
            Object requiredValue = schema.get("required");
            if (requiredValue instanceof List<?> required) {
                for (Object key : required) {
                    String name = String.valueOf(key);
                    if (!object.containsKey(name)) {
                        errors.add(path + "." + name + " is required");
                    }
                }
            }
            Map<String, Object> properties = stringMap(schema.get("properties"));
            for (Map.Entry<String, Object> property : properties.entrySet()) {
                if (object.containsKey(property.getKey()) && property.getValue() instanceof Map<?, ?> raw) {
                    validateAt(
                            object.get(property.getKey()),
                            stringMap(raw),
                            path + "." + property.getKey(),
                            errors);
                }
            }
            if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                for (Object key : object.keySet()) {
                    if (!properties.containsKey(String.valueOf(key))) {
                        errors.add(path + "." + key + " is not allowed");
                    }
                }
            }
        } else if (value instanceof List<?> array && schema.get("items") instanceof Map<?, ?> raw) {
            Map<String, Object> itemSchema = stringMap(raw);
            for (int index = 0; index < array.size(); index++) {
                validateAt(array.get(index), itemSchema, path + "[" + index + "]", errors);
            }
        }
    }

    private static boolean matchesType(Object value, String type) {
        return switch (type.toLowerCase()) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Byte
                    || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> true;
        };
    }

    private static Map<String, Object> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
