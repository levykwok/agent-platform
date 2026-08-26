/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record OrchestrationPolicy(
        OrchestrationMode mode,
        List<SubagentBinding> subagents,
        List<RouteRule> routes,
        @JsonAlias("workflow") List<PipelineStep> pipeline,
        int maxSupervisorSteps,
        boolean supervisorParallelEnabled,
        int maxSupervisorParallelism,
        boolean routerDisableThinking) {

    public static final int DEFAULT_MAX_SUPERVISOR_STEPS = 5;
    public static final int MAX_SUPERVISOR_STEPS = 10;
    public static final boolean DEFAULT_ROUTER_DISABLE_THINKING = true;

    @JsonCreator
    public OrchestrationPolicy(
            @JsonProperty("mode") OrchestrationMode mode,
            @JsonProperty("subagents") List<SubagentBinding> subagents,
            @JsonProperty("routes") List<RouteRule> routes,
            @JsonProperty("pipeline") @JsonAlias("workflow") List<PipelineStep> pipeline,
            @JsonProperty("maxSupervisorSteps") int maxSupervisorSteps,
            @JsonProperty("supervisorParallelEnabled") boolean supervisorParallelEnabled,
            @JsonProperty("maxSupervisorParallelism") int maxSupervisorParallelism,
            @JsonProperty("routerDisableThinking") @JsonAlias("router_disable_thinking")
                    Boolean routerDisableThinking) {
        this(
                mode,
                subagents,
                routes,
                pipeline,
                maxSupervisorSteps,
                supervisorParallelEnabled,
                maxSupervisorParallelism,
                routerDisableThinking == null
                        ? DEFAULT_ROUTER_DISABLE_THINKING
                        : routerDisableThinking);
    }

    public OrchestrationPolicy(
            OrchestrationMode mode,
            List<SubagentBinding> subagents,
            List<RouteRule> routes) {
        this(mode, subagents, routes, List.of(), DEFAULT_MAX_SUPERVISOR_STEPS, false, 2);
    }

    public OrchestrationPolicy(
            OrchestrationMode mode,
            List<SubagentBinding> subagents,
            List<RouteRule> routes,
            List<PipelineStep> pipeline) {
        this(mode, subagents, routes, pipeline, DEFAULT_MAX_SUPERVISOR_STEPS, false, 2);
    }

    public OrchestrationPolicy(
            OrchestrationMode mode,
            List<SubagentBinding> subagents,
            List<RouteRule> routes,
            List<PipelineStep> pipeline,
            int maxSupervisorSteps) {
        this(mode, subagents, routes, pipeline, maxSupervisorSteps, false, 2);
    }

    public OrchestrationPolicy(
            OrchestrationMode mode,
            List<SubagentBinding> subagents,
            List<RouteRule> routes,
            List<PipelineStep> pipeline,
            int maxSupervisorSteps,
            boolean supervisorParallelEnabled,
            int maxSupervisorParallelism) {
        this(
                mode,
                subagents,
                routes,
                pipeline,
                maxSupervisorSteps,
                supervisorParallelEnabled,
                maxSupervisorParallelism,
                DEFAULT_ROUTER_DISABLE_THINKING);
    }

    public static OrchestrationPolicy single() {
        return new OrchestrationPolicy(
                OrchestrationMode.SINGLE,
                List.of(),
                List.of(),
                List.of(),
                DEFAULT_MAX_SUPERVISOR_STEPS,
                false,
                2,
                DEFAULT_ROUTER_DISABLE_THINKING);
    }

    public OrchestrationPolicy {
        mode = mode == null ? OrchestrationMode.SINGLE : mode;
        subagents = subagents == null ? List.of() : List.copyOf(subagents);
        routes = routes == null ? List.of() : List.copyOf(routes);
        pipeline = pipeline == null ? List.of() : List.copyOf(pipeline);
        maxSupervisorSteps =
                maxSupervisorSteps <= 0
                        ? DEFAULT_MAX_SUPERVISOR_STEPS
                        : Math.min(MAX_SUPERVISOR_STEPS, maxSupervisorSteps);
        maxSupervisorParallelism =
                maxSupervisorParallelism <= 0
                        ? 2
                        : Math.max(1, Math.min(8, maxSupervisorParallelism));
    }
}
