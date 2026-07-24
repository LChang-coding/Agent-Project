#!/usr/bin/env python3
"""Run auditable real-LLM RAG checks through Agent/Workflow production HTTP entrypoints."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import subprocess
import threading
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


class Api:
    def __init__(self, base_url: str, token: str | None = None, timeout: int = 240):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers.update({"Accept": "application/json"})
        if token:
            self.session.headers.update({"Authorization": f"Bearer {token}"})

    def raw(self, method: str, path: str, **kwargs) -> tuple[dict, dict]:
        started = time.perf_counter_ns()
        response = self.session.request(method, self.base_url + path, timeout=self.timeout, **kwargs)
        elapsed_ms = (time.perf_counter_ns() - started) // 1_000_000
        try:
            envelope = response.json()
        except ValueError as error:
            raise RuntimeError(f"non-JSON response {method} {path}: HTTP {response.status_code}") from error
        return envelope, {"httpStatus": response.status_code, "elapsedMs": elapsed_ms,
                          "responseBytes": len(response.content)}

    def call(self, method: str, path: str, **kwargs) -> tuple[dict, dict]:
        envelope, transport = self.raw(method, path, **kwargs)
        if transport["httpStatus"] < 200 or transport["httpStatus"] >= 300:
            raise RuntimeError(f"HTTP {transport['httpStatus']} for {method} {path}")
        if envelope.get("code") != SUCCESS:
            raise RuntimeError(f"business error {envelope.get('code')} for {method} {path}: {envelope.get('info')}")
        if envelope.get("data") is None:
            raise RuntimeError(f"missing data for {method} {path}")
        return envelope, transport

    def sse(self, path: str, payload: dict, on_run=None, max_wall_seconds: int | None = None,
            read_timeout_seconds: int | None = None) -> tuple[list[dict], dict]:
        started = time.perf_counter_ns()
        timeout = (10, read_timeout_seconds or self.timeout)
        with self.session.post(self.base_url + path, json=payload, stream=True, timeout=timeout,
                               headers={"Accept": "text/event-stream"}) as response:
            response.raise_for_status()
            # SSE defaults to ISO-8859-1 in requests when the response omits an explicit charset.
            response.encoding = "utf-8"
            events: list[dict] = []
            event_name = "message"
            data_lines: list[str] = []
            for raw_line in response.iter_lines(decode_unicode=True):
                line = raw_line or ""
                if line.startswith("event:"):
                    event_name = line[6:].strip()
                elif line.startswith("data:"):
                    data_lines.append(line[5:].lstrip())
                elif not line and data_lines:
                    raw_data = "\n".join(data_lines)
                    try:
                        data = json.loads(raw_data)
                    except json.JSONDecodeError:
                        data = raw_data
                    item = {"event": event_name, "data": data, "observedAt": now()}
                    events.append(item)
                    if event_name == "run" and on_run:
                        on_run(data)
                    event_name, data_lines = "message", []
                    if max_wall_seconds and (time.perf_counter_ns() - started) / 1_000_000_000 >= max_wall_seconds:
                        events.append({"event": "harness_timeout", "data": {"maxWallSeconds": max_wall_seconds},
                                       "observedAt": now()})
                        break
        return events, {"httpStatus": response.status_code,
                        "elapsedMs": (time.perf_counter_ns() - started) // 1_000_000}


def register(base_url: str, suffix: str, timeout: int) -> tuple[Api, str]:
    username = f"rag_aw_{suffix}"
    password = secrets.token_urlsafe(24) + "Aa1!"
    public = Api(base_url, timeout=timeout)
    public.call("POST", "/v1/auth/register", json={
        "tenantName": f"RAG Agent Workflow E2E {suffix}", "username": username,
        "password": password, "nickname": "RAG E2E", "email": f"{username}@example.invalid",
        "phone": "8" + str(secrets.randbelow(10**10)).zfill(10),
    })
    login, _ = public.call("POST", "/v1/auth/login", json={"username": username, "password": password})
    return Api(base_url, login["data"]["token"], timeout), username


def create_workflow(api: Api, suffix: str, model: str) -> tuple[str, int]:
    created, _ = api.call("POST", "/v1/workflows", json={
        "workflowName": f"RAG E2E {suffix}", "description": "real LLM citation E2E",
        "defaultModelCode": model, "visibility": "private",
    })
    workflow_id = created["data"]["workflowId"]
    graph = {"mode": "sequential", "rootNodeId": "rag_answer", "nodes": [{
        "nodeId": "rag_answer", "nodeType": "llm", "name": "RAG answer",
        "description": "Answer only from injected RAG context",
        "instruction": "只使用注入的 rag_context 回答。不得猜测。有事实答案时必须原样附上该 source 的 citation_id；无证据时只回答 NOT_IN_DOCUMENT。",
        "modelCode": model, "mcpIds": [], "skillIds": [], "maxIterations": 1, "x": 0, "y": 0,
    }], "edges": []}
    api.call("POST", f"/v1/workflows/{workflow_id}/draft", json={
        "workflowName": f"RAG E2E {suffix}", "description": "real LLM citation E2E",
        "defaultModelCode": model, "visibility": "private", "graph": graph,
    })
    published, _ = api.call("POST", f"/v1/workflows/{workflow_id}/publish")
    return workflow_id, int(published["data"]["version"])


def create_session(api: Api, target_type: str, target_id: str, version: int | None, model: str) -> str:
    payload = {"userId": "ignored"}
    if target_type == "workflow":
        payload.update({"workflowId": target_id, "workflowVersion": version, "modelCode": model})
    else:
        payload["agentId"] = target_id
    response, _ = api.call("POST", "/v1/create_session", json=payload)
    return response["data"]["sessionId"]


def configure_session_rag(api: Api, session_id: str, mode: str,
                          selected_binding_ids: list[str] | None = None) -> dict:
    """Persist and re-read a session RAG policy before any chat run is created."""
    current, current_transport = api.call("GET", f"/v1/sessions/{session_id}/rag-setting")
    expected_revision = int(current["data"]["revision"])
    payload = {
        "mode": mode,
        "selectedBindingIds": selected_binding_ids or [],
        "expectedRevision": expected_revision,
    }
    updated, update_transport = api.call(
        "PATCH", f"/v1/sessions/{session_id}/rag-setting", json=payload)
    reread, reread_transport = api.call("GET", f"/v1/sessions/{session_id}/rag-setting")
    expected_selected = sorted(selected_binding_ids or []) if mode == "MANUAL" else []
    data = reread["data"]
    checks = {
        "modePersisted": data.get("mode") == mode,
        "revisionAdvancedOnce": int(data.get("revision", -1)) == expected_revision + 1,
        "selectedBindingsPersisted": sorted(data.get("selectedBindingIds") or []) == expected_selected,
        "bindingConfigured": bool(data.get("bindingConfigured")) if mode != "OFF" else True,
        "updateAndReadAgree": updated.get("data") == reread.get("data"),
    }
    if not all(checks.values()):
        raise RuntimeError(f"session RAG policy verification failed: {checks}")
    return {
        "before": current,
        "beforeTransport": current_transport,
        "update": updated,
        "updateTransport": update_transport,
        "reread": reread,
        "rereadTransport": reread_transport,
        "checks": checks,
    }


def chat_payload(target_type: str, target_id: str, version: int | None, model: str,
                 session_id: str, message: str, run_id: str | None = None) -> dict:
    payload = {"userId": "ignored", "sessionId": session_id, "message": message, "attachmentIds": []}
    if run_id:
        payload["requestedRunId"] = run_id
    if target_type == "workflow":
        payload.update({"workflowId": target_id, "workflowVersion": version, "modelCode": model})
    else:
        payload["agentId"] = target_id
    return payload


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8093/api")
    parser.add_argument("--fixture-dir", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--app-jar", type=Path, required=True)
    parser.add_argument("--agent-id", default="100003")
    parser.add_argument("--model", default="deepseek-v4-flash")
    parser.add_argument("--timeout-seconds", type=int, default=300)
    parser.add_argument("--ingest-timeout-seconds", type=int, default=600)
    args = parser.parse_args()
    fixture_dir, out = args.fixture_dir.resolve(), args.out.resolve()
    if out.exists():
        raise SystemExit("output directory must not exist")
    out.mkdir(parents=True)
    spec_path, document_path = fixture_dir / "spec.json", fixture_dir / "citation-fixture.md"
    spec = json.loads(spec_path.read_text(encoding="utf-8"))
    manifest = {
        "schemaVersion": 1, "status": "running", "startedAt": now(),
        "codeRevision": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "appJarSha256": sha256(args.app_jar.resolve()), "specSha256": sha256(spec_path),
        "documentSha256": sha256(document_path), "baseUrl": args.base_url,
        "agentId": args.agent_id, "model": args.model, "threads": 1,
        "judge": "deterministic exact-term/citation-set/envelope checks; no LLM-as-judge",
    }
    dump(out / "manifest.json", manifest)
    suffix = f"{int(time.time())}-{secrets.token_hex(3)}"
    try:
        api, username = register(args.base_url, suffix, args.timeout_seconds)
        manifest["syntheticUsername"] = username
        agents, agents_transport = api.call("GET", "/v1/query_ai_agent_config_list")
        dump(out / "agents.json", {"response": agents, "transport": agents_transport})
        if args.agent_id not in {str(item.get("agentId")) for item in agents["data"]}:
            raise RuntimeError(f"agent {args.agent_id} is unavailable")

        profile, profile_transport = api.call("POST", "/v1/rag/retrieval-profiles", json={
            "name": f"rag_aw_{suffix}", "mode": "hybrid", "fusionStrategy": "rrf",
            "denseWeight": 1, "sparseWeight": 1, "denseTopK": 20, "sparseTopK": 20,
            "fusionTopK": 10, "rerankEnabled": False, "rerankTopK": 10, "finalTopK": 5,
            "neighborWindow": 0, "maxContextTokens": 2048, "scoreThreshold": None,
            "queryRewriteEnabled": False, "deduplicateEnabled": True,
        })
        dump(out / "profile.json", {"response": profile, "transport": profile_transport})
        profile_id = profile["data"]["profileId"]
        kb, kb_transport = api.call("POST", "/v1/rag/knowledge-bases", json={
            "name": f"rag_aw_{suffix}", "description": "real LLM Agent/Workflow citation fixture"})
        dump(out / "knowledge-base.json", {"response": kb, "transport": kb_transport})
        kb_id = kb["data"]["knowledgeBaseId"]
        with document_path.open("rb") as stream:
            uploaded, upload_transport = api.call("POST", f"/v1/rag/knowledge-bases/{kb_id}/documents",
                                                   files={"file": (document_path.name, stream, "text/markdown")})
        dump(out / "upload.json", {"response": uploaded, "transport": upload_transport})
        task_id, document_id = uploaded["data"]["taskId"], uploaded["data"]["documentId"]
        poll: list[dict] = []
        deadline = time.monotonic() + args.ingest_timeout_seconds
        while time.monotonic() < deadline:
            task, transport = api.call("GET", f"/v1/rag/ingest-tasks/{task_id}")
            poll.append({"observedAt": now(), "task": task["data"], "transport": transport})
            state = str(task["data"].get("status", "")).lower()
            if state == "completed":
                break
            if state in TERMINAL_FAILURES:
                raise RuntimeError(f"ingest failed: {task['data']}")
            time.sleep(1)
        else:
            raise RuntimeError("ingest timed out")
        dump(out / "ingest-poll.json", poll)

        workflow_id, workflow_version = create_workflow(api, suffix, args.model)
        targets = {"workflow": (workflow_id, workflow_version), "agent": (args.agent_id, None)}
        bindings = {}
        for target_type, (target_id, _) in targets.items():
            binding, transport = api.call("POST", "/v1/rag/bindings", json={
                "targetType": target_type, "targetId": target_id, "knowledgeBaseId": kb_id,
                "profileId": profile_id, "required": True, "maxTokens": 2048, "priority": 100})
            bindings[target_type] = {"response": binding, "transport": transport}
        dump(out / "bindings.json", bindings)

        results: dict[str, dict] = {}
        for target_type, (target_id, version) in targets.items():
            target_out: dict[str, object] = {}
            for case_name in ("answerable", "noAnswer", "fakeCitation"):
                session_id = create_session(api, target_type, target_id, version, args.model)
                target_out[f"{case_name}RagSetting"] = configure_session_rag(
                    api, session_id, "AUTO")
                response, transport = api.call("POST", "/v1/chat", json=chat_payload(
                    target_type, target_id, version, args.model, session_id, spec[case_name]["question"]))
                history, history_transport = api.call("GET", f"/v1/sessions/{session_id}/messages?limit=100")
                data = response["data"]
                answer = str(data.get("content") or "").strip()
                validation = data.get("citationValidation") or {}
                checks = {
                    "historyHasAssistantMessage": any(item.get("messageId") == data.get("messageId")
                                                       for item in history["data"].get("items", [])),
                    "historyCitationMatches": any(item.get("messageId") == data.get("messageId") and
                                                   item.get("citationValidation") == data.get("citationValidation")
                                                   for item in history["data"].get("items", [])),
                }
                if case_name == "answerable":
                    checks["requiredTermsPresent"] = all(term in answer for term in spec[case_name]["requiredTerms"])
                    checks["citationValid"] = validation.get("status") == "VALID" and bool(validation.get("usedCitationIds"))
                    checks["citationSubset"] = set(validation.get("usedCitationIds") or []) <= set(validation.get("allowedCitationIds") or [])
                    if validation.get("usedCitationIds") and data.get("messageId"):
                        citation_id = validation["usedCitationIds"][0]
                        source, source_transport = api.call("GET", f"/v1/sessions/{session_id}/messages/{data['messageId']}/citations/{citation_id}")
                        target_out["answerableCitationSource"] = {"response": source, "transport": source_transport,
                                                                  "containsRequiredTerm": all(term in str(source["data"].get("excerpt") or "")
                                                                                              for term in spec[case_name]["requiredTerms"])}
                elif case_name == "noAnswer":
                    checks["exactRefusal"] = answer == spec[case_name]["expectedExact"]
                else:
                    checks["fakeCitationObserved"] = spec[case_name]["fakeCitationId"] in answer
                    checks["fakeCitationRejected"] = (validation.get("status") == "INVALID_CITATIONS" and
                                                      spec[case_name]["fakeCitationId"] in (validation.get("invalidCitationIds") or []))
                target_out[case_name] = {"response": response, "transport": transport,
                                         "history": history, "historyTransport": history_transport, "checks": checks}
                results[target_type] = target_out
                dump(out / "partial-results.json", results)

            stream_session = create_session(api, target_type, target_id, version, args.model)
            target_out["streamRagSetting"] = configure_session_rag(api, stream_session, "AUTO")
            events, stream_transport = api.sse("/v1/chat_stream", chat_payload(
                target_type, target_id, version, args.model, stream_session, spec["answerable"]["question"]),
                max_wall_seconds=90 if target_type == "agent" else 180)
            event_names = [event["event"] for event in events]
            stream_answer = "".join(str(event["data"]) for event in events if event["event"] == "message")
            target_out["stream"] = {"events": events, "transport": stream_transport, "checks": {
                "singleSessionEvent": event_names.count("session") == 1,
                "singleRunEvent": event_names.count("run") == 1,
                "singleCitationValidationEvent": event_names.count("citation_validation") == 1,
                "requiredTermsPresent": all(term in stream_answer for term in spec["answerable"]["requiredTerms"]),
                "harnessTimeout": "harness_timeout" in event_names,
            }}
            results[target_type] = target_out
            dump(out / "partial-results.json", results)

        manual_session = create_session(
            api, "workflow", workflow_id, workflow_version, args.model)
        workflow_binding_id = bindings["workflow"]["response"]["data"]["bindingId"]
        manual_setting = configure_session_rag(
            api, manual_session, "MANUAL", [workflow_binding_id])
        manual_response, manual_transport = api.call(
            "POST", "/v1/chat", json=chat_payload(
                "workflow", workflow_id, workflow_version, args.model, manual_session,
                spec["answerable"]["question"]))
        manual_validation = manual_response["data"].get("citationValidation") or {}
        manual_check = {
            "requiredTermsPresent": all(
                term in str(manual_response["data"].get("content") or "")
                for term in spec["answerable"]["requiredTerms"]),
            "citationValid": manual_validation.get("status") == "VALID"
                             and bool(manual_validation.get("usedCitationIds")),
        }
        if not all(manual_check.values()):
            raise RuntimeError(f"manual RAG chat verification failed: {manual_check}")
        dump(out / "manual-rag-chat.json", {
            "sessionId": manual_session,
            "setting": manual_setting,
            "response": manual_response,
            "transport": manual_transport,
            "checks": manual_check,
        })
        dump(out / "agent-workflow-results.json", results)

        foreign_api, foreign_username = register(args.base_url, suffix + "x", args.timeout_seconds)
        workflow_answer = results["workflow"]["answerable"]["response"]["data"]
        used = (workflow_answer.get("citationValidation") or {}).get("usedCitationIds") or []
        if used:
            foreign, foreign_transport = foreign_api.raw("GET", "/v1/sessions/{}/messages/{}/citations/{}".format(
                workflow_answer["sessionId"], workflow_answer["messageId"], used[0]))
            dump(out / "cross-tenant-negative.json", {"syntheticUsername": foreign_username,
                                                       "response": foreign, "transport": foreign_transport,
                                                       "denied": foreign.get("code") != SUCCESS})

        cancel_session = create_session(api, "workflow", workflow_id, workflow_version, args.model)
        cancel_rag_setting = configure_session_rag(api, cancel_session, "AUTO")
        cancel_run_id = "run_e2e_" + secrets.token_hex(12)
        cancel_record: dict[str, object] = {
            "requestedRunId": cancel_run_id,
            "sessionId": cancel_session,
            "ragSetting": cancel_rag_setting,
        }
        run_seen = threading.Event()

        def cancel_on_run(data):
            cancel_record["runEvent"] = data
            run_seen.set()
            cancel_record["cancelRequestedAt"] = now()
            response, transport = api.raw("POST", f"/v1/runs/{cancel_run_id}/cancel", json={"reason": "e2e cancellation"})
            cancel_record["cancelReturnedAt"] = now()
            cancel_record["cancelResponse"], cancel_record["cancelTransport"] = response, transport

        long_prompt = spec["answerable"]["question"] + " 回答前先详细列出100条分析，然后再给出结论。"
        try:
            events, transport = api.sse("/v1/chat_stream", chat_payload(
                "workflow", workflow_id, workflow_version, args.model, cancel_session, long_prompt, cancel_run_id),
                cancel_on_run, max_wall_seconds=30, read_timeout_seconds=30)
        except requests.RequestException as error:
            events = [{"event": "harness_transport_timeout", "data": {"type": type(error).__name__},
                       "observedAt": now()}]
            transport = {"httpStatus": 200, "elapsedMs": None, "transportError": type(error).__name__}
            cancel_record["streamDidNotTerminateAfterCancel"] = True
        cancel_record["events"], cancel_record["streamTransport"] = events, transport
        cancel_record["streamReturnedAt"] = now()
        cancel_record["runEventObserved"] = run_seen.is_set()
        history, history_transport = api.call("GET", f"/v1/sessions/{cancel_session}/messages?limit=100")
        cancel_record["history"], cancel_record["historyTransport"] = history, history_transport
        cancel_record["checks"] = {
            "cancelAccepted": (cancel_record.get("cancelResponse") or {}).get("code") == SUCCESS,
            "cancelledStatus": ((cancel_record.get("cancelResponse") or {}).get("data") or {}).get("status") == "cancelled",
            "noCompletedAssistantForCancelledRun": not any(item.get("runId") == cancel_run_id and item.get("role") == "assistant"
                                                             for item in history["data"].get("items", [])),
            "noCitationTerminalEvent": not any(item["event"] == "citation_validation" for item in events),
            "streamTerminatedWithin30Seconds": not any(item["event"] in {"harness_timeout", "harness_transport_timeout"}
                                                           for item in events),
        }
        dump(out / "cancel.json", cancel_record)

        manifest.update({"status": "completed", "completedAt": now(), "knowledgeBaseId": kb_id,
                         "documentId": document_id, "workflowId": workflow_id,
                         "workflowVersion": workflow_version})
        dump(out / "manifest.json", manifest)
    except Exception as error:
        manifest.update({"status": "failed", "completedAt": now(), "errorType": type(error).__name__,
                         "error": str(error)})
        dump(out / "manifest.json", manifest)
        raise


if __name__ == "__main__":
    main()
