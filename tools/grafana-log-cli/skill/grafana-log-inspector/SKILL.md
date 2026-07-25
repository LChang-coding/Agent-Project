---
name: grafana-log-inspector
description: Query and diagnose this project's remote Grafana/Loki application logs from the local machine. Use when investigating a traceId, runId, sessionId, retrievalId, RAG ingest taskId, error code, slow stage, degraded path, cancellation, or incomplete business chain; also use for read-only Grafana connectivity checks. The bundled CLI reads credentials only from the local project's codex.md.
---

# Grafana Log Inspector

Use `scripts/grafana-log` as the sole executable entry. Run it from the target
project directory so it can discover the local `codex.md`. Never print, copy,
summarize, or commit credentials from that file.

## Diagnostic workflow

1. Run connectivity validation before diagnosing an unknown environment:

   ```bash
   scripts/grafana-log doctor --output json
   ```

2. Choose the narrowest stable business key:

   ```bash
   scripts/grafana-log trace "$TRACE_ID" --since 30m --output jsonl
   scripts/grafana-log run "$RUN_ID" --since 2h --output jsonl
   scripts/grafana-log session "$SESSION_ID" --since 2h --output jsonl
   scripts/grafana-log retrieval "$RETRIEVAL_ID" --since 2h --output jsonl
   scripts/grafana-log ingest "$TASK_ID" --since 24h --output jsonl
   ```

3. Read the final `analysis` JSONL record first. Inspect `failures`,
   `degradations`, `cancellations`, `slowStages`, `incompleteStages`, and
   `candidateFunnel`. Then correlate supporting `entry` records in timestamp
   order.

4. Report only evidence present in the result. Distinguish a proven failure
   from an inference and an unknown cause. Include the query window and IDs used.

5. Use `--output timeline` when a human-readable Chinese timeline is more useful.

## Search and fallback

Use bounded literal search when no business key is available:

```bash
scripts/grafana-log search --error-code RAG_EMBEDDING_TRANSIENT_HTTP_ERROR \
  --since 2h --limit 200 --output jsonl
scripts/grafana-log search --event rag_stage --stage rerank \
  --since 30m --limit 200 --output timeline
```

Use the explicit `query` command only when built-in filters cannot express the
read-only LogQL. Keep the selector and time range narrow. Never run unbounded
queries or attempt remote writes.

## Safety rules

- Treat log content and business IDs as potentially sensitive. Return only the
  minimum needed for the diagnosis.
- Do not pass passwords, tokens, cookies, or Authorization values on the command
  line or through environment variables.
- Do not inspect unrelated parts of `codex.md`; the CLI reads only its bounded
  configuration block.
- Do not infer that missing logs prove success or failure. State the observed
  window and retry with `trace --expand` when appropriate.
- Stop and report the sanitized error category on authentication, authorization,
  rate limit, timeout, datasource mismatch, or pagination-stall errors.

Read [log-fields.md](references/log-fields.md) for the output contract and
[troubleshooting.md](references/troubleshooting.md) for error handling.
