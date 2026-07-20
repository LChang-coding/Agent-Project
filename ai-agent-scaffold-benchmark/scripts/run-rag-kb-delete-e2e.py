#!/usr/bin/env python3
"""Run an auditable two-document knowledge-base cascade-delete E2E through public HTTP APIs."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

import requests


SUCCESS = "0000"
FAILURES = {"failed", "dead", "cancelled"}


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


def append(path: Path, value: object) -> None:
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")
        stream.flush()
        os.fsync(stream.fileno())


class Api:
    def __init__(self, base_url: str, token: str | None = None, timeout: int = 180):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers["Accept"] = "application/json"
        if token:
            self.session.headers["Authorization"] = f"Bearer {token}"

    def call(self, method: str, path: str, **kwargs) -> tuple[dict, dict]:
        started = time.perf_counter_ns()
        response = self.session.request(method, self.base_url + path, timeout=self.timeout, **kwargs)
        transport = {
            "httpStatus": response.status_code,
            "elapsedMs": (time.perf_counter_ns() - started) // 1_000_000,
            "responseBytes": len(response.content),
        }
        try:
            envelope = response.json()
        except ValueError as error:
            raise RuntimeError(f"non-JSON response {method} {path}: HTTP {response.status_code}") from error
        if not 200 <= response.status_code < 300:
            raise RuntimeError(f"HTTP {response.status_code} for {method} {path}")
        if envelope.get("code") != SUCCESS:
            raise RuntimeError(f"business error {envelope.get('code')} for {method} {path}: {envelope.get('info')}")
        if envelope.get("data") is None:
            raise RuntimeError(f"missing data for {method} {path}")
        return envelope, transport


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8092/api")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--app-jar", type=Path, required=True)
    parser.add_argument("--files", type=Path, nargs=2, required=True)
    parser.add_argument("--timeout-seconds", type=int, default=900)
    parser.add_argument("--pause-before-delete-file", type=Path)
    return parser.parse_args()


def wait_ingest(api: Api, task_id: str, timeline: Path, timeout: int) -> dict:
    started = time.monotonic()
    while time.monotonic() - started < timeout:
        response, transport = api.call("GET", f"/v1/rag/ingest-tasks/{task_id}")
        task = response["data"]
        append(timeline, {"observedAt": now(), "transport": transport, "task": task})
        status = str(task.get("status", "")).lower()
        if status == "completed":
            return task
        if status in FAILURES:
            raise RuntimeError(f"ingest ended {status}/{task.get('stage')}/{task.get('errorCode')}")
        time.sleep(1)
    raise RuntimeError(f"ingest timed out after {timeout}s")


def wait_delete(api: Api, task_id: str, timeline: Path, timeout: int) -> dict:
    started = time.monotonic()
    while time.monotonic() - started < timeout:
        response, transport = api.call("GET", f"/v1/rag/knowledge-base-delete-tasks/{task_id}")
        task = response["data"]
        append(timeline, {"observedAt": now(), "transport": transport, "task": task})
        status = str(task.get("status", "")).lower()
        if status == "completed" or status in {"failed", "dead"}:
            return task
        time.sleep(1)
    raise RuntimeError(f"knowledge-base delete timed out after {timeout}s")


def main() -> None:
    args = parse_args()
    out = args.out.resolve()
    files = [path.resolve() for path in args.files]
    if out.exists():
        raise SystemExit("output directory must not exist")
    if any(not path.is_file() for path in files):
        raise SystemExit("input file is missing")
    out.mkdir(parents=True)
    manifest = {
        "schemaVersion": 1,
        "runId": args.run_id,
        "status": "running",
        "startedAt": now(),
        "baseUrl": args.base_url,
        "codeRevision": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "appJarSha256": sha256(args.app_jar),
        "harnessSha256": sha256(Path(__file__).resolve()),
        "workerThreads": 1,
        "uploadThreads": 1,
        "pollThreads": 1,
        "timeoutSeconds": args.timeout_seconds,
        "inputFiles": [{"name": path.name, "bytes": path.stat().st_size, "sha256": sha256(path)} for path in files],
        "cleanup": "keep synthetic tenant and tombstones for audit",
    }
    dump(out / "manifest.json", manifest)
    suffix = f"{int(time.time())}-{secrets.token_hex(3)}"
    username = f"rag_kb_delete_{suffix}"
    password = secrets.token_urlsafe(24) + "Aa1!"
    public = Api(args.base_url)
    try:
        public.call("POST", "/v1/auth/register", json={
            "tenantName": f"RAG KB Delete E2E {suffix}", "username": username,
            "password": password, "nickname": "RAG KB Delete E2E",
            "email": f"{username}@example.invalid", "phone": "8" + str(secrets.randbelow(10**10)).zfill(10),
        })
        login, _ = public.call("POST", "/v1/auth/login", json={"username": username, "password": password})
        api = Api(args.base_url, login["data"]["token"])
        manifest["syntheticUsername"] = username

        profile, transport = api.call("POST", "/v1/rag/retrieval-profiles", json={
            "name": f"kb_delete_{args.run_id}"[:96], "mode": "hybrid", "fusionStrategy": "rrf",
            "denseWeight": 1, "sparseWeight": 1, "denseTopK": 20, "sparseTopK": 20,
            "fusionTopK": 10, "rerankEnabled": False, "rerankTopK": 10, "finalTopK": 5,
            "neighborWindow": 0, "maxContextTokens": 2048, "scoreThreshold": None,
            "queryRewriteEnabled": False, "deduplicateEnabled": True,
        })
        dump(out / "profile.json", {"response": profile, "transport": transport})

        workflow, transport = api.call("POST", "/v1/workflows", json={
            "workflowName": f"KB delete target {suffix}", "description": "cascade delete binding target",
            "defaultModelCode": "deepseek-v4-flash", "visibility": "private",
        })
        workflow_id = workflow["data"]["workflowId"]
        node_id = f"node_{suffix}"
        draft, draft_transport = api.call("POST", f"/v1/workflows/{workflow_id}/draft", json={
            "workflowName": f"KB delete target {suffix}", "description": "cascade delete binding target",
            "defaultModelCode": "deepseek-v4-flash", "visibility": "private",
            "graph": {"mode": "sequential", "rootNodeId": node_id, "nodes": [{
                "nodeId": node_id, "nodeType": "llm", "name": "E2E node",
                "instruction": "Return test evidence.", "modelCode": "deepseek-v4-flash",
                "mcpIds": [], "skillIds": [], "maxIterations": 1,
            }], "edges": []},
        })
        published, publish_transport = api.call("POST", f"/v1/workflows/{workflow_id}/publish")
        dump(out / "workflow.json", {"create": workflow, "createTransport": transport,
             "draft": draft, "draftTransport": draft_transport,
             "published": published, "publishTransport": publish_transport})

        kb, transport = api.call("POST", "/v1/rag/knowledge-bases", json={
            "name": f"kb_delete_{args.run_id}"[:96], "description": "two-document real cascade-delete E2E",
        })
        kb_id = kb["data"]["knowledgeBaseId"]
        dump(out / "knowledge-base.json", {"response": kb, "transport": transport})

        uploads = []
        for index, path in enumerate(files, 1):
            mime = "application/pdf" if path.suffix.lower() == ".pdf" else "text/markdown"
            with path.open("rb") as stream:
                upload, upload_transport = api.call("POST", f"/v1/rag/knowledge-bases/{kb_id}/documents",
                    files={"file": (path.name, stream, mime)})
            task = wait_ingest(api, upload["data"]["taskId"], out / f"ingest-{index}-timeline.jsonl", args.timeout_seconds)
            evidence = {"response": upload, "transport": upload_transport, "terminalTask": task}
            dump(out / f"upload-{index}.json", evidence)
            uploads.append(evidence)

        documents, transport = api.call("GET", f"/v1/rag/knowledge-bases/{kb_id}/documents")
        if len(documents["data"]) != 2 or any(item.get("status") != "ready" for item in documents["data"]):
            raise RuntimeError("two uploaded documents are not READY")
        dump(out / "documents-before-delete.json", {"response": documents, "transport": transport})

        binding, transport = api.call("POST", "/v1/rag/bindings", json={
            "targetType": "workflow", "targetId": workflow_id, "knowledgeBaseId": kb_id,
            "profileId": profile["data"]["profileId"], "required": True, "maxTokens": 2048, "priority": 100,
        })
        dump(out / "binding.json", {"response": binding, "transport": transport})

        if args.pause_before_delete_file:
            signal = args.pause_before_delete_file.resolve()
            continuation = Path(str(signal) + ".continue")
            dump(signal, {"readyAt": now(), "knowledgeBaseId": kb_id,
                          "documentIds": [item["response"]["data"]["documentId"] for item in uploads]})
            wait_started = time.monotonic()
            while not continuation.exists():
                if time.monotonic() - wait_started >= args.timeout_seconds:
                    raise RuntimeError("timed out waiting for before-delete continuation signal")
                time.sleep(0.2)
            manifest["beforeDeleteResumedAt"] = now()

        refreshed_bases, refreshed_transport = api.call("GET", "/v1/rag/knowledge-bases")
        refreshed_kb = next((item for item in refreshed_bases["data"]
                             if item.get("knowledgeBaseId") == kb_id), None)
        if not refreshed_kb:
            raise RuntimeError("knowledge base disappeared before delete acceptance")
        dump(out / "knowledge-base-before-delete.json",
             {"knowledgeBase": refreshed_kb, "transport": refreshed_transport})
        accepted, transport = api.call("POST", f"/v1/rag/knowledge-bases/{kb_id}/delete-tasks",
                                       json={"expectedRevision": refreshed_kb["revision"]})
        dump(out / "delete-accepted.json", {"response": accepted, "transport": transport})
        terminal = wait_delete(api, accepted["data"]["taskId"], out / "delete-timeline.jsonl", args.timeout_seconds)
        dump(out / "delete-terminal.json", terminal)
        manifest.update({
            "status": "completed" if terminal.get("status") == "completed" else "delete_terminal_failure",
            "finishedAt": now(), "knowledgeBaseId": kb_id, "workflowId": workflow_id,
            "bindingId": binding["data"]["bindingId"], "deleteTaskId": accepted["data"]["taskId"],
            "documentIds": [item["response"]["data"]["documentId"] for item in uploads],
            "versionIds": [item["response"]["data"]["versionId"] for item in uploads],
            "terminalDeleteTask": terminal,
        })
        dump(out / "manifest.json", manifest)
        if terminal.get("status") != "completed":
            raise SystemExit(4)
    except BaseException as error:
        if manifest.get("status") == "running":
            manifest.update({"status": "failed", "finishedAt": now(),
                             "errorType": type(error).__name__, "error": str(error)[:1000]})
            dump(out / "manifest.json", manifest)
        raise
    finally:
        password = ""


if __name__ == "__main__":
    main()
