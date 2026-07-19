#!/usr/bin/env python3
"""Run a credential-safe MD/DOCX/PDF RAG business-chain evaluation through production HTTP APIs."""

from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
import secrets
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

import requests


SUCCESS = "0000"
TERMINAL_FAILURES = {"failed", "dead", "cancelled"}


def now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def dump(path: Path, value: object) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def append_jsonl(path: Path, value: object) -> None:
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")
        stream.flush()
        os.fsync(stream.fileno())


class Api:
    def __init__(self, base_url: str, token: str | None = None, timeout: int = 180):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers.update({"Accept": "application/json"})
        if token:
            self.session.headers.update({"Authorization": f"Bearer {token}"})

    def call(self, method: str, path: str, **kwargs) -> tuple[dict, dict]:
        started = time.perf_counter_ns()
        response = self.session.request(method, self.base_url + path, timeout=self.timeout, **kwargs)
        elapsed_ms = (time.perf_counter_ns() - started) // 1_000_000
        body = response.content
        transport = {
            "httpStatus": response.status_code,
            "elapsedMs": elapsed_ms,
            "responseBytes": len(body),
        }
        try:
            envelope = response.json()
        except ValueError as error:
            raise RuntimeError(f"non-JSON response {method} {path}: HTTP {response.status_code}") from error
        if response.status_code < 200 or response.status_code >= 300:
            raise RuntimeError(f"HTTP {response.status_code} for {method} {path}")
        if envelope.get("code") != SUCCESS:
            raise RuntimeError(f"business error {envelope.get('code')} for {method} {path}: {envelope.get('info')}")
        if envelope.get("data") is None:
            raise RuntimeError(f"missing data for {method} {path}")
        return envelope, transport


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8092/api")
    parser.add_argument("--fixture-dir", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--ingest-timeout-seconds", type=int, default=900)
    parser.add_argument("--request-timeout-seconds", type=int, default=180)
    parser.add_argument("--app-jar", type=Path, required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    fixture_dir = args.fixture_dir.resolve()
    out = args.out.resolve()
    if out.exists():
        raise SystemExit("output directory must not exist")
    out.mkdir(parents=True)
    fixture = json.loads((fixture_dir / "fixture.json").read_text(encoding="utf-8"))
    files = {
        "markdown": fixture_dir / "format-fidelity.md",
        "docx": fixture_dir / "format-fidelity.docx",
        "pdf": fixture_dir / "format-fidelity.pdf",
    }
    if any(not path.is_file() for path in files.values()):
        raise SystemExit("fixture file is missing")

    started_at = now()
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    manifest = {
        "schemaVersion": 1,
        "runId": args.run_id,
        "status": "running",
        "startedAt": started_at,
        "baseUrl": args.base_url,
        "codeRevision": revision,
        "appJarSha256": sha256(args.app_jar),
        "requestTimeoutSeconds": args.request_timeout_seconds,
        "ingestTimeoutSeconds": args.ingest_timeout_seconds,
        "uploadThreads": 1,
        "workerThreads": 1,
        "queryThreads": 1,
        "fixtureFiles": {
            name: {"file": path.name, "bytes": path.stat().st_size, "sha256": sha256(path)}
            for name, path in files.items()
        },
        "fixtureSpecSha256": sha256(fixture_dir / "fixture.json"),
        "questionCountPerFormat": len(fixture["questions"]),
        "noAnswerQuestionCountPerFormat": len(fixture.get("noAnswerQuestions", [])),
        "answerMetrics": "retrieval_evidence_term_coverage_only; no LLM answer judge",
        "cleanup": "keep synthetic tenant for audit",
    }
    dump(out / "manifest.json", manifest)

    suffix = f"{int(time.time())}-{secrets.token_hex(3)}"
    username = f"rag_format_{suffix}"
    password = secrets.token_urlsafe(24) + "Aa1!"
    public_api = Api(args.base_url, timeout=args.request_timeout_seconds)
    try:
        register = {
            "tenantName": f"RAG Format E2E {suffix}",
            "username": username,
            "password": password,
            "nickname": "RAG Format E2E",
            "email": f"{username}@example.invalid",
            "phone": "9" + str(secrets.randbelow(10**10)).zfill(10),
        }
        public_api.call("POST", "/v1/auth/register", json=register)
        login, _ = public_api.call("POST", "/v1/auth/login", json={"username": username, "password": password})
        token = login["data"]["token"]
        api = Api(args.base_url, token, args.request_timeout_seconds)
        manifest["syntheticUsername"] = username
        dump(out / "manifest.json", manifest)

        profile_payload = {
            "name": f"format_{args.run_id}_hybrid_rerank"[:96],
            "mode": "hybrid",
            "fusionStrategy": "rrf",
            "denseWeight": 1,
            "sparseWeight": 1,
            "denseTopK": 100,
            "sparseTopK": 100,
            "fusionTopK": 10,
            "rerankEnabled": True,
            "rerankTopK": 10,
            "finalTopK": 10,
            "neighborWindow": 0,
            "maxContextTokens": 4096,
            "scoreThreshold": None,
            "queryRewriteEnabled": False,
            "deduplicateEnabled": True,
        }
        profile, profile_transport = api.call("POST", "/v1/rag/retrieval-profiles", json=profile_payload)
        dump(out / "profile.json", {"response": profile, "transport": profile_transport})
        profile_id = profile["data"]["profileId"]

        format_results: dict[str, object] = {}
        all_query_results: list[dict] = []
        for format_name, file_path in files.items():
            format_dir = out / format_name
            format_dir.mkdir()
            kb, kb_transport = api.call("POST", "/v1/rag/knowledge-bases", json={
                "name": f"format_{args.run_id}_{format_name}"[:96],
                "description": f"Synthetic {format_name} parser fidelity fixture",
            })
            dump(format_dir / "knowledge-base.json", {"response": kb, "transport": kb_transport})
            kb_id = kb["data"]["knowledgeBaseId"]

            mime = {
                "markdown": "text/markdown",
                "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "pdf": "application/pdf",
            }[format_name]
            with file_path.open("rb") as stream:
                upload, upload_transport = api.call(
                    "POST", f"/v1/rag/knowledge-bases/{kb_id}/documents",
                    files={"file": (file_path.name, stream, mime)},
                )
            upload_transport["requestBytes"] = file_path.stat().st_size
            dump(format_dir / "upload.json", {"response": upload, "transport": upload_transport})
            task_id = upload["data"]["taskId"]
            document_id = upload["data"]["documentId"]

            poll_file = format_dir / "task-poll.jsonl"
            poll_started = time.perf_counter_ns()
            last_task = None
            while (time.perf_counter_ns() - poll_started) / 1_000_000_000 < args.ingest_timeout_seconds:
                task, task_transport = api.call("GET", f"/v1/rag/ingest-tasks/{task_id}")
                last_task = task["data"]
                append_jsonl(poll_file, {
                    "observedAt": now(),
                    "elapsedSinceUploadMs": (time.perf_counter_ns() - poll_started) // 1_000_000,
                    "transport": task_transport,
                    "task": last_task,
                })
                status = str(last_task.get("status", "")).lower()
                if status == "completed":
                    break
                if status in TERMINAL_FAILURES:
                    raise RuntimeError(f"{format_name} ingest ended {status}/{last_task.get('stage')}/{last_task.get('errorCode')}")
                time.sleep(1)
            else:
                raise RuntimeError(f"{format_name} ingest timed out")
            ingest_elapsed_ms = (time.perf_counter_ns() - poll_started) // 1_000_000

            documents, documents_transport = api.call("GET", f"/v1/rag/knowledge-bases/{kb_id}/documents")
            dump(format_dir / "documents.json", {"response": documents, "transport": documents_transport})
            document = next((item for item in documents["data"] if item.get("documentId") == document_id), None)
            if not document or str(document.get("status", "")).lower() != "ready":
                raise RuntimeError(f"{format_name} document not READY after completed task")

            target_id = f"format_{args.run_id}_{format_name}"[:120]
            binding, binding_transport = api.call("POST", "/v1/rag/bindings", json={
                "targetType": "workflow",
                "targetId": target_id,
                "knowledgeBaseId": kb_id,
                "profileId": profile_id,
                "required": True,
                "maxTokens": 4096,
                "priority": 100,
            })
            dump(format_dir / "binding.json", {"response": binding, "transport": binding_transport})

            format_queries = []
            for query in fixture["questions"]:
                debug, transport = api.call("POST", "/v1/rag/retrieval-debug", json={
                    "targetType": "workflow",
                    "targetId": target_id,
                    "query": query["question"],
                    "maxContextTokens": 4096,
                })
                dump(format_dir / f"{query['queryId']}.json", {"response": debug, "transport": transport})
                data = debug["data"]
                contexts = "\n".join(str(value.get("context", "")) for value in data.get("citations", []))
                missing_terms = [term for term in query["expectedTerms"] if term not in contexts]
                wrong_documents = sorted({
                    value.get("documentId") for value in data.get("citations", [])
                    if value.get("documentId") != document_id
                })
                result = {
                    "format": format_name,
                    "queryId": query["queryId"],
                    "question": query["question"],
                    "expectedAnswer": query["expectedAnswer"],
                    "expectedTerms": query["expectedTerms"],
                    "missingTerms": missing_terms,
                    "wrongDocumentIds": wrong_documents,
                    "citationCount": len(data.get("citations", [])),
                    "retrievalId": data.get("retrievalId"),
                    "degraded": bool(data.get("degraded")),
                    "metrics": data.get("metrics"),
                    "transport": transport,
                    "passed": not missing_terms and not wrong_documents and bool(data.get("citations"))
                              and not bool(data.get("degraded")),
                }
                append_jsonl(out / "query-results.jsonl", result)
                all_query_results.append(result)
                format_queries.append(result)

            for query in fixture.get("noAnswerQuestions", []):
                debug, transport = api.call("POST", "/v1/rag/retrieval-debug", json={
                    "targetType": "workflow",
                    "targetId": target_id,
                    "query": query["question"],
                    "maxContextTokens": 4096,
                })
                dump(format_dir / f"{query['queryId']}.json", {"response": debug, "transport": transport,
                    "judgement": "retrieval-only; not an LLM answer correctness metric"})

            format_results[format_name] = {
                "knowledgeBaseId": kb_id,
                "documentId": document_id,
                "taskId": task_id,
                "activeVersionId": document.get("activeVersionId"),
                "activeGeneration": document.get("activeGeneration"),
                "ingestElapsedMs": ingest_elapsed_ms,
                "task": last_task,
                "passedQueries": sum(1 for result in format_queries if result["passed"]),
                "totalQueries": len(format_queries),
            }

        passed = sum(1 for result in all_query_results if result["passed"])
        manifest.update({
            "status": "completed" if passed == len(all_query_results) else "completed_with_quality_failures",
            "finishedAt": now(),
            "formatResults": format_results,
            "retrievalEvidencePassed": passed,
            "retrievalEvidenceTotal": len(all_query_results),
        })
        dump(out / "summary.json", {
            "runId": args.run_id,
            "status": manifest["status"],
            "formats": format_results,
            "passed": passed,
            "total": len(all_query_results),
        })
        dump(out / "manifest.json", manifest)
        if passed != len(all_query_results):
            raise SystemExit(4)
    except BaseException as error:
        if manifest.get("status") == "running":
            manifest.update({"status": "failed", "finishedAt": now(),
                             "errorType": type(error).__name__, "error": str(error)[:1000]})
            dump(out / "manifest.json", manifest)
        raise
    finally:
        password = ""
        token = "" if "token" in locals() else ""


if __name__ == "__main__":
    main()
