/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import java.util.Optional;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/** Reactor-scoped caller identity used by nested Workflow tools. */
public final class WorkflowInvocationContext {

    private static final String KEY = WorkflowInvocationContext.class.getName() + ".request";

    private WorkflowInvocationContext() {}

    public static Context put(Context context, ChatRequest request) {
        return request == null ? context : context.put(KEY, request);
    }

    public static Optional<ChatRequest> request(ContextView context) {
        return context == null || !context.hasKey(KEY)
                ? Optional.empty()
                : Optional.ofNullable(context.get(KEY));
    }
}
