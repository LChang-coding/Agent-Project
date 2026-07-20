#!/usr/bin/env python3
"""Collect credential-free MySQL, Qdrant and MinIO residual evidence for a KB delete run."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import requests
from minio import Minio
from minio.error import S3Error


SAFE_ID = re.compile(r"[A-Za-z0-9_-]{1,160}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--mysql-host", default="127.0.0.1")
    parser.add_argument("--mysql-port", type=int, default=13306)
    parser.add_argument("--mysql-user", default="ai_agent_app")
    parser.add_argument("--mysql-database", default="ai_agent_scaffold")
    parser.add_argument("--mysql-ssl-mode", default="REQUIRED")
    parser.add_argument("--qdrant-url", default="http://127.0.0.1:16333")
    parser.add_argument("--qdrant-collection", required=True)
    parser.add_argument("--minio-endpoint", required=True)
    return parser.parse_args()


def safe(value: str, name: str) -> str:
    if not SAFE_ID.fullmatch(value or ""):
        raise SystemExit(f"unsafe {name}")
    return value


def mysql_rows(config: argparse.Namespace, query: str) -> list[list[str]]:
    environment = dict(os.environ)
    password = environment.pop("MYSQL_PASSWORD", None)
    if not password:
        raise SystemExit("MYSQL_PASSWORD is required")
    environment["MYSQL_PWD"] = password
    command = ["mysql", f"--ssl-mode={config.mysql_ssl_mode}", f"-h{config.mysql_host}",
               f"-P{config.mysql_port}", f"-u{config.mysql_user}", "--batch", "--raw",
               "--skip-column-names", config.mysql_database, "-e", query]
    output = subprocess.check_output(command, env=environment, text=True)
    return [line.split("\t") for line in output.splitlines() if line]


def scalar(config: argparse.Namespace, query: str) -> int:
    rows = mysql_rows(config, query)
    if len(rows) != 1 or len(rows[0]) != 1:
        raise SystemExit("unexpected scalar result")
    return int(rows[0][0])


def object_exists(client: Minio, bucket: str, key: str) -> bool:
    if not bucket or not key:
        return False
    try:
        client.stat_object(bucket, key)
        return True
    except S3Error as error:
        if error.code in {"NoSuchKey", "NoSuchObject", "NoSuchBucket"}:
            return False
        raise


def main() -> None:
    config = parse_args()
    if config.out.exists():
        raise SystemExit("output file must not exist")
    manifest = json.loads(config.manifest.read_text(encoding="utf-8"))
    if manifest.get("status") != "completed":
        raise SystemExit("delete run is not completed")
    username = safe(manifest.get("syntheticUsername"), "username")
    kb_id = safe(manifest.get("knowledgeBaseId"), "knowledgeBaseId")
    task_id = safe(manifest.get("deleteTaskId"), "deleteTaskId")
    version_ids = [safe(value, "versionId") for value in manifest.get("versionIds", [])]
    if len(version_ids) != 2:
        raise SystemExit("exactly two versions are required")

    tenant_rows = mysql_rows(config,
        "SELECT tu.tenant_id FROM user_account ua JOIN tenant_user tu ON tu.user_id=ua.user_id "
        f"WHERE ua.username='{username}' AND ua.deleted=0 AND tu.deleted=0 LIMIT 1")
    if len(tenant_rows) != 1:
        raise SystemExit("synthetic tenant not found")
    tenant_id = safe(tenant_rows[0][0], "tenantId")
    versions_sql = ",".join(f"'{value}'" for value in version_ids)

    kb_rows = mysql_rows(config, f"SELECT status,deleted,revision FROM rag_knowledge_base "
                                     f"WHERE tenant_id='{tenant_id}' AND kb_id='{kb_id}'")
    task_rows = mysql_rows(config, f"SELECT status,JSON_UNQUOTE(JSON_EXTRACT(checkpoint,'$.stage')),"
         f"JSON_EXTRACT(checkpoint,'$.totalDocuments'),JSON_EXTRACT(checkpoint,'$.completedDocuments'),"
         f"attempt_count,row_version,IFNULL(error_code,'') FROM rag_knowledge_base_delete_task "
         f"WHERE tenant_id='{tenant_id}' AND task_id='{task_id}' AND deleted=0")
    version_rows = mysql_rows(config, f"SELECT version_id,status,deleted,source_bucket,source_object_key,"
         f"IFNULL(parsed_bucket,''),IFNULL(parsed_object_key,'') FROM rag_document_version "
         f"WHERE tenant_id='{tenant_id}' AND version_id IN ({versions_sql}) ORDER BY version_id")

    counts = {
        "documentRows": scalar(config, f"SELECT COUNT(*) FROM rag_document WHERE tenant_id='{tenant_id}' AND kb_id='{kb_id}'"),
        "nonDeletedDocuments": scalar(config, f"SELECT COUNT(*) FROM rag_document WHERE tenant_id='{tenant_id}' AND kb_id='{kb_id}' AND status<>'deleted' AND deleted=0"),
        "nonDeletedVersions": scalar(config, f"SELECT COUNT(*) FROM rag_document_version WHERE tenant_id='{tenant_id}' AND kb_id='{kb_id}' AND status<>'deleted' AND deleted=0"),
        "physicalChunkRows": scalar(config, f"SELECT COUNT(*) FROM rag_chunk WHERE tenant_id='{tenant_id}' AND kb_id='{kb_id}'"),
        "activeIngestTasks": scalar(config, f"SELECT COUNT(*) FROM rag_ingest_task WHERE tenant_id='{tenant_id}' AND kb_id='{kb_id}' AND deleted=0 AND status IN ('pending','running','retrying','cancel_requested')"),
        "completedDeleteChildren": scalar(config, f"SELECT COUNT(*) FROM rag_ingest_task WHERE tenant_id='{tenant_id}' AND kb_id='{kb_id}' AND deleted=0 AND operation='delete' AND status='completed'"),
        "activeBindings": scalar(config, f"SELECT COUNT(*) FROM rag_agent_binding WHERE tenant_id='{tenant_id}' AND kb_id='{kb_id}' AND deleted=0 AND status='active'"),
        "disabledBindings": scalar(config, f"SELECT COUNT(*) FROM rag_agent_binding WHERE tenant_id='{tenant_id}' AND kb_id='{kb_id}' AND status='disabled'"),
    }

    access_key = os.environ.get("MINIO_ACCESS_KEY")
    secret_key = os.environ.get("MINIO_SECRET_KEY")
    if not access_key or not secret_key:
        raise SystemExit("MINIO_ACCESS_KEY and MINIO_SECRET_KEY are required")
    minio = Minio(config.minio_endpoint, access_key=access_key, secret_key=secret_key, secure=False)
    versions = []
    for row in version_rows:
        version_id, status, deleted, source_bucket, source_key, parsed_bucket, parsed_key = row
        count_response = requests.post(
            f"{config.qdrant_url.rstrip('/')}/collections/{config.qdrant_collection}/points/count",
            json={"exact": True, "filter": {"must": [
                {"key": "tenant_id", "match": {"value": tenant_id}},
                {"key": "version_id", "match": {"value": version_id}},
            ]}}, timeout=30)
        count_response.raise_for_status()
        versions.append({
            "versionId": version_id, "status": status, "deleted": int(deleted),
            "qdrantExactPointCount": int(count_response.json()["result"]["count"]),
            "minioSourceExists": object_exists(minio, source_bucket, source_key),
            "minioParsedExists": object_exists(minio, parsed_bucket, parsed_key),
        })

    checks = {
        "knowledgeBaseTombstoned": len(kb_rows) == 1 and kb_rows[0][0:2] == ["deleted", "0"],
        "parentTaskCompleted": len(task_rows) == 1 and task_rows[0][0].lower() == "completed"
                               and task_rows[0][1].lower() == "completed" and task_rows[0][2:4] == ["2", "2"],
        "twoDocumentTombstonesRemain": counts["documentRows"] == 2,
        "noNonDeletedDocuments": counts["nonDeletedDocuments"] == 0,
        "noNonDeletedVersions": counts["nonDeletedVersions"] == 0,
        "noPhysicalChunks": counts["physicalChunkRows"] == 0,
        "noActiveIngestTasks": counts["activeIngestTasks"] == 0,
        "twoCompletedDeleteChildren": counts["completedDeleteChildren"] == 2,
        "noActiveBindings": counts["activeBindings"] == 0,
        "bindingDisabled": counts["disabledBindings"] == 1,
        "allVersionTombstonesRemain": len(version_rows) == 2 and all(row[1:3] == ["deleted", "0"] for row in version_rows),
        "noQdrantPoints": len(versions) == 2 and all(value["qdrantExactPointCount"] == 0 for value in versions),
        "noMinioObjects": len(versions) == 2 and all(not value["minioSourceExists"] and not value["minioParsedExists"] for value in versions),
    }
    payload = {
        "schemaVersion": 1, "capturedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "runId": manifest["runId"], "tenantId": tenant_id, "knowledgeBaseId": kb_id,
        "deleteTaskId": task_id, "collection": config.qdrant_collection,
        "mysql": {"knowledgeBase": kb_rows, "parentTask": task_rows, "counts": counts},
        "versions": versions, "checks": checks, "passed": all(checks.values()),
    }
    config.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if not payload["passed"]:
        raise SystemExit(4)


if __name__ == "__main__":
    main()
