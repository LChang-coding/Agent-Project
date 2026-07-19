#!/usr/bin/env python3
"""Collect credential-free MySQL/Qdrant/MinIO consistency evidence for a format E2E run."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import requests
from minio import Minio


SAFE_ID = re.compile(r"[A-Za-z0-9_-]{1,128}")


def args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--mysql-host", default="127.0.0.1")
    parser.add_argument("--mysql-port", type=int, default=13307)
    parser.add_argument("--mysql-user", default="ai_agent_app")
    parser.add_argument("--mysql-database", default="ai_agent_scaffold")
    parser.add_argument("--qdrant-url", default="http://127.0.0.1:16333")
    parser.add_argument("--qdrant-collection", required=True)
    parser.add_argument("--minio-endpoint", required=True)
    return parser.parse_args()


def require_id(value: str, name: str) -> str:
    if not SAFE_ID.fullmatch(value or ""):
        raise SystemExit(f"unsafe {name}")
    return value


def mysql_rows(config: argparse.Namespace, query: str) -> list[list[str]]:
    env = dict(os.environ)
    password = env.pop("MYSQL_PASSWORD", None)
    if not password:
        raise SystemExit("MYSQL_PASSWORD is required")
    env["MYSQL_PWD"] = password
    command = ["mysql", f"-h{config.mysql_host}", f"-P{config.mysql_port}",
               f"-u{config.mysql_user}", "--batch", "--raw", "--skip-column-names",
               config.mysql_database, "-e", query]
    output = subprocess.check_output(command, env=env, text=True)
    return [line.split("\t") for line in output.splitlines() if line]


def sha256_object(client: Minio, bucket: str, key: str) -> tuple[int, str]:
    response = client.get_object(bucket, key)
    digest = hashlib.sha256()
    size = 0
    try:
        for block in response.stream(1024 * 1024):
            size += len(block)
            digest.update(block)
    finally:
        response.close()
        response.release_conn()
    return size, digest.hexdigest()


def main() -> None:
    config = args()
    if config.out.exists():
        raise SystemExit("output file must not exist")
    manifest = json.loads(config.manifest.read_text(encoding="utf-8"))
    if manifest.get("status") != "completed":
        raise SystemExit("format run is not completed")
    username = require_id(manifest.get("syntheticUsername"), "username")
    formats = manifest.get("formatResults") or {}
    version_to_format = {
        require_id(value["activeVersionId"], "versionId"): require_id(name, "format")
        for name, value in formats.items()
    }
    tenant_rows = mysql_rows(config,
        "SELECT tu.tenant_id FROM user_account ua JOIN tenant_user tu ON tu.user_id=ua.user_id "
        f"WHERE ua.username='{username}' AND ua.deleted=0 AND tu.deleted=0 LIMIT 1")
    if len(tenant_rows) != 1:
        raise SystemExit("synthetic tenant not found")
    tenant_id = require_id(tenant_rows[0][0], "tenantId")
    version_list = ",".join(f"'{value}'" for value in version_to_format)
    query = f"""
SELECT v.version_id,v.document_id,v.source_bucket,v.source_object_key,v.size_bytes,v.content_hash,
       IFNULL(v.parsed_bucket,''),IFNULL(v.parsed_object_key,''),IFNULL(v.page_count,0),
       IFNULL(v.character_count,0),v.chunk_count,v.status,IFNULL(v.metadata,'{{}}'),
       IFNULL(d.page_count,0),IFNULL(d.chunk_count,0),
       (SELECT COUNT(*) FROM rag_chunk c WHERE c.tenant_id=v.tenant_id AND c.version_id=v.version_id AND c.deleted=0),
       (SELECT COUNT(*) FROM rag_chunk c WHERE c.tenant_id=v.tenant_id AND c.version_id=v.version_id
          AND c.deleted=0 AND c.vector_point_id IS NOT NULL),
       (SELECT COUNT(DISTINCT c.vector_point_id) FROM rag_chunk c WHERE c.tenant_id=v.tenant_id
          AND c.version_id=v.version_id AND c.deleted=0 AND c.vector_point_id IS NOT NULL)
FROM rag_document_version v JOIN rag_document d
  ON d.tenant_id=v.tenant_id AND d.document_id=v.document_id AND d.deleted=0
WHERE v.tenant_id='{tenant_id}' AND v.version_id IN ({version_list}) AND v.deleted=0
ORDER BY v.version_id
"""
    rows = mysql_rows(config, query)
    if len(rows) != len(version_to_format):
        raise SystemExit("version row count mismatch")

    access_key = os.environ.get("MINIO_ACCESS_KEY")
    secret_key = os.environ.get("MINIO_SECRET_KEY")
    if not access_key or not secret_key:
        raise SystemExit("MINIO_ACCESS_KEY and MINIO_SECRET_KEY are required")
    minio = Minio(config.minio_endpoint, access_key=access_key, secret_key=secret_key, secure=False)
    results = []
    for row in rows:
        (version_id, document_id, source_bucket, source_key, source_size, source_hash,
         parsed_bucket, parsed_key, page_count, character_count, chunk_count, status,
         metadata_json, document_page_count, document_chunk_count, physical_chunks,
         child_chunks, distinct_points) = row
        metadata = json.loads(metadata_json)
        source_actual_size, source_actual_hash = sha256_object(minio, source_bucket, source_key)
        parsed_actual_size, parsed_actual_hash = sha256_object(minio, parsed_bucket, parsed_key)
        count_response = requests.post(
            f"{config.qdrant_url.rstrip('/')}/collections/{config.qdrant_collection}/points/count",
            json={"exact": True, "filter": {"must": [
                {"key": "tenant_id", "match": {"value": tenant_id}},
                {"key": "version_id", "match": {"value": version_id}},
            ]}}, timeout=30)
        count_response.raise_for_status()
        qdrant_count = int(count_response.json()["result"]["count"])
        expected_chunk_count = int(chunk_count)
        checks = {
            "ready": status == "ready",
            "sourceSizeMatches": source_actual_size == int(source_size),
            "sourceHashMatches": source_actual_hash == source_hash,
            "parsedSizeMatches": parsed_actual_size == int(metadata.get("parsedSizeBytes", -1)),
            "parsedHashMatches": parsed_actual_hash == metadata.get("parsedContentHash"),
            "versionAndDocumentChunkCountMatch": expected_chunk_count == int(document_chunk_count),
            "childAndDistinctVectorPointCountMatch": int(child_chunks) == int(distinct_points),
            "mysqlAndQdrantCountMatch": expected_chunk_count == qdrant_count == int(distinct_points),
        }
        results.append({
            "format": version_to_format[version_id], "tenantId": tenant_id,
            "documentId": document_id, "versionId": version_id, "status": status,
            "mysql": {"pageCount": int(page_count), "characterCount": int(character_count),
                      "versionChunkCount": expected_chunk_count,
                      "documentPageCount": int(document_page_count),
                      "documentChunkCount": int(document_chunk_count),
                      "physicalChunkRows": int(physical_chunks), "childChunkRows": int(child_chunks),
                      "distinctVectorPointIds": int(distinct_points)},
            "minio": {
                "source": {"exists": True, "bytes": source_actual_size, "sha256": source_actual_hash},
                "parsed": {"exists": True, "bytes": parsed_actual_size, "sha256": parsed_actual_hash},
            },
            "qdrant": {"exactPointCount": qdrant_count}, "checks": checks,
            "passed": all(checks.values()),
        })
    payload = {
        "schemaVersion": 1,
        "capturedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "runId": manifest["runId"], "collection": config.qdrant_collection,
        "results": sorted(results, key=lambda value: value["format"]),
    }
    payload["passed"] = all(value["passed"] for value in payload["results"])
    config.out.parent.mkdir(parents=True, exist_ok=True)
    config.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                          encoding="utf-8")
    if not payload["passed"]:
        raise SystemExit(4)


if __name__ == "__main__":
    main()
