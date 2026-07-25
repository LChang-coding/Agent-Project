---
name: grafana-log-inspector
description: Query, reconstruct, and diagnose this project's remote Grafana/Loki application logs with a bundled standalone CLI. Use for traceId, runId, sessionId, retrievalId, RAG ingest taskId, error-code, slow-stage, degraded-path, cancellation, incomplete-chain, connectivity, or datasource investigations. Also use when installing or validating the local grafana-log CLI. Credentials are read only from the target project's local codex.md.
---

# Grafana Log Inspector

Use the bundled CLI. Keep the shell working directory at the target project so
the command discovers that project's local `codex.md`.

## Prepare the CLI

Resolve the installed Skill and perform the offline package check:

```bash
SKILL_DIR="${CODEX_HOME:-$HOME/.codex}/skills/grafana-log-inspector"
"$SKILL_DIR/scripts/self-test"
```

Prefer installing the standalone command once:

```bash
"$SKILL_DIR/scripts/install-cli"
export PATH="$HOME/.local/bin:$PATH"
grafana-log --version
```

If installation is unnecessary, invoke
`"$SKILL_DIR/scripts/grafana-log"` directly. Both paths execute the same
hash-verified, platform-specific CLI bundled inside the Skill.

Run remote connectivity checks only when needed:

```bash
grafana-log doctor --output json
# Or validate package and remote connectivity together:
"$SKILL_DIR/scripts/self-test" --doctor
```

## Diagnose a chain

Choose the narrowest stable business key and prefer JSONL for agent analysis:

```bash
grafana-log trace "$TRACE_ID" --since 30m --output jsonl
grafana-log run "$RUN_ID" --since 2h --output jsonl
grafana-log session "$SESSION_ID" --since 2h --output jsonl
grafana-log retrieval "$RETRIEVAL_ID" --since 2h --output jsonl
grafana-log ingest "$TASK_ID" --since 24h --output jsonl
```

Read the final `analysis` record first. Correlate supporting `entry` records in
timestamp order. Inspect failures, degradations, cancellations, slow stages,
incomplete stages, and the candidate funnel. Use `--output timeline` for a
human-readable Chinese view.

Use bounded search when no business key is available:

```bash
grafana-log search --error-code RAG_EMBEDDING_TRANSIENT_HTTP_ERROR \
  --since 2h --limit 200 --output jsonl
grafana-log search --event rag_stage --stage rerank \
  --since 30m --limit 200 --output timeline
```

Use `query` only when built-in filters cannot express the required read-only
LogQL. Keep selectors, windows, and limits narrow.

## Preserve safety

- Never print, copy, summarize, or commit credentials from `codex.md`.
- Never pass passwords, tokens, cookies, or Authorization values as arguments or
  environment variables.
- Treat logs and business IDs as sensitive; return only evidence needed for the
  diagnosis.
- Treat missing or incomplete logs as investigation clues, not proof of failure.
- Stop on sanitized authentication, authorization, rate-limit, timeout,
  datasource-mismatch, or pagination-stall errors.
- Never attempt Grafana, Loki, datasource, dashboard, or log writes.

Read [log-fields.md](references/log-fields.md) for the output contract and
[troubleshooting.md](references/troubleshooting.md) for error handling.
