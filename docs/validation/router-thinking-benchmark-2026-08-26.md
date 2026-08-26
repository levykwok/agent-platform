# Router Thinking Benchmark — 2026-08-26

## Result

Router thinking is disabled by default. A historical Router definition without the
`routerDisableThinking` property resolved to `true`; setting the property explicitly to
`false` restored the model's default thinking behavior.

The benchmark used the same published Router, prompt, decision model, SQLite snapshot,
and isolated application instance for both groups. Each SSE request was cancelled after
the `router_decision` event so downstream Agent latency was excluded.

| Policy | Decision 1 | Decision 2 | Decision 3 | Average | Median |
| --- | ---: | ---: | ---: | ---: | ---: |
| thinking disabled (default) | 3,020 ms | 1,314 ms | 8,333 ms | 4,222 ms | 3,020 ms |
| model-default thinking | 12,794 ms | 12,158 ms | 11,478 ms | 12,143 ms | 12,158 ms |

Disabling thinking reduced mean Router decision latency by 7,921 ms (about 65%) in this
sample, making the decision about 2.9 times faster. All six decisions used the LLM and
selected the same target Agent. The disabled group still showed provider-side latency
variance, so this setting reduces expected latency but does not impose a latency bound.

## Automated validation

- `mvn -o test`: 98 tests, 0 failures, 0 errors.
- `npm run build`: Vue type-check and Vite production build passed.
- Policy tests cover a missing property defaulting to `true` and explicit `false` being
  preserved.
- Runtime tests verify that only Router decision requests receive
  `enable_thinking=false` and that SSE decision events expose `thinking_disabled`.
