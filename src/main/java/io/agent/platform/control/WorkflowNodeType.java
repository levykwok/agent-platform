/*
 * Copyright 2026 by the company contributors.
 */
package io.agent.platform.control;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Node types supported by the unified workflow model. */
public enum WorkflowNodeType {
    INPUT("workflow.input"),
    OUTPUT("workflow.output"),
    HTTP_REQUEST("http.request"),
    DATABASE_QUERY("database.query"),
    DATABASE_WRITE("database.write"),
    DATA_TRANSFORM("data.transform"),
    CONDITION("condition"),
    FOREACH("foreach"),
    PARALLEL("parallel"),
    JOIN("join"),
    HUMAN_APPROVAL("human.approval"),
    LLM_CHAT("llm.chat"),
    AGENT_INVOKE("agent.invoke"),
    REACT_AGENT("agent.react"),
    SKILL_INVOKE("skill.invoke"),
    MCP_INVOKE("mcp.invoke"),
    SUBFLOW_INVOKE("subflow.invoke"),
    MESSAGE_SEND("message.send"),
    RETURN("return");

    private final String value;

    WorkflowNodeType(String value) {
        this.value = value;
    }

    @JsonCreator
    public static WorkflowNodeType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return AGENT_INVOKE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (WorkflowNodeType type : values()) {
            if (type.value.equals(normalized) || type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported workflow node type: " + value);
    }

    @JsonValue
    public String value() {
        return value;
    }
}
