/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.scheduled;

/** Best-effort invocation identity bridge for Java tools called inside an Agent runtime. */
public final class ScheduledTaskCallContext {

    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();

    private ScheduledTaskCallContext() {}

    public static Scope open(String userId, String orgId) {
        Identity previous = CURRENT.get();
        CURRENT.set(new Identity(userId == null ? "" : userId, orgId == null ? "" : orgId));
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public static Identity current() {
        return CURRENT.get();
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public record Identity(String userId, String orgId) {}
}
