/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.util.LinkedHashMap;
import java.util.Map;

/** Runtime value carrying both data and the contract that describes it. */
public record ContractValue(String contractRef, Object data, Map<String, Object> metadata) {
    public ContractValue {
        contractRef = contractRef == null ? "" : contractRef.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public ContractValue(String contractRef, Object data) { this(contractRef, data, Map.of()); }

    public static ContractValue of(String contractRef, Object data) { return new ContractValue(contractRef, data); }

    public boolean structured() { return data instanceof Map<?, ?> || data instanceof java.util.List<?>; }
}
