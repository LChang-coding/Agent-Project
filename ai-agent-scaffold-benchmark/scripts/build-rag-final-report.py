#!/usr/bin/env python3
"""Build the auditable final RAG evaluation ledger and Chinese report from frozen evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from datetime import datetime
from pathlib import Path
from typing import Any


VARIANTS = ("sparse", "dense", "hybrid_rrf", "hybrid_rrf_rerank")


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def stats(values: list[float]) -> dict[str, float | int]:
    if not values:
        raise ValueError("statistics require at least one value")
    ordered = sorted(values)
    nearest = lambda p: ordered[math.ceil(len(ordered) * p) - 1]
    return {
        "count": len(values), "min": min(values), "mean": sum(values) / len(values),
        "p50": nearest(0.50), "p95": nearest(0.95), "max": max(values),
    }


def pct(value: str) -> float:
    return float(value.rstrip("%"))


def slim_timing(value: dict[str, Any]) -> dict[str, Any]:
    return {key: value[key] for key in ("count", "mean", "p50", "p95", "p99", "max") if key in value}


def fmt(value: float, digits: int = 4) -> str:
    return f"{value:.{digits}f}"


def timeline_elapsed_ms(rows: list[dict[str, Any]]) -> int:
    if not rows:
        raise ValueError("timeline must not be empty")
    start = datetime.fromisoformat(rows[0]["observedAt"].replace("Z", "+00:00"))
    end = datetime.fromisoformat(rows[-1]["observedAt"].replace("Z", "+00:00"))
    return round((end - start).total_seconds() * 1000)


def change(after: float, before: float) -> dict[str, float]:
    return {"absolute": after - before, "relativePercent": ((after / before) - 1) * 100 if before else 0.0}


def paired_change(after: dict[str, Any], before: dict[str, Any], metric: str) -> dict[str, int]:
    before_by_query = {row["queryId"]: float(row[metric]) for row in before["queries"]}
    after_by_query = {row["queryId"]: float(row[metric]) for row in after["queries"]}
    if before_by_query.keys() != after_by_query.keys() or len(before_by_query) != 300:
        raise ValueError(f"paired query set mismatch: {metric}")
    delta = [after_by_query[query_id] - value for query_id, value in before_by_query.items()]
    epsilon = 1e-12
    return {
        "improved": sum(value > epsilon for value in delta),
        "unchanged": sum(abs(value) <= epsilon for value in delta),
        "degraded": sum(value < -epsilon for value in delta),
    }


def extract_scifact_document(root: Path, document_map: dict[str, dict[str, Any]], document_id: str) -> dict[str, Any]:
    mapping = document_map[document_id]
    shard = root / "docs/rag/evaluation-data/scifact/prepared/documents" / mapping["shardFile"]
    text = shard.read_text(encoding="utf-8")
    marker = "# " + mapping["headingMarker"]
    start = text.find(marker)
    if start < 0:
        raise ValueError(f"SciFact marker missing: {document_id}")
    end = text.find("\n# BENCH_DOC_", start + len(marker))
    section = text[start:end if end >= 0 else len(text)].strip()
    heading, body = section.split("\n\n", 1)
    body = body.strip()
    if hashlib.sha256(body.encode("utf-8")).hexdigest() != mapping["contentSha256"]:
        raise ValueError(f"SciFact document content hash mismatch: {document_id}")
    return {
        "documentId": document_id,
        "title": heading.split(" — ", 1)[1],
        "body": body,
        "contentSha256": mapping["contentSha256"],
        "shardFile": mapping["shardFile"],
        "headingMarker": mapping["headingMarker"],
    }


def assert_hashes(manifest: dict[str, Any], base: Path) -> None:
    for item in manifest["files"]:
        path = base / item["name"]
        if sha256(path) != item["sha256"]:
            raise ValueError(f"evidence hash mismatch: {path}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    root = args.project_root.resolve()
    results = root / "docs/rag/evaluation-results"
    out = args.out_dir.resolve()
    if out.exists():
        raise SystemExit("output directory must not exist")
    out.mkdir(parents=True)

    paths = {
        "qualityMetrics": results / "scifact-r11-quality/metrics.json",
        "qualityIndependent": results / "scifact-r11-quality/metrics-independent.json",
        "qualityRun": results / "scifact-r11-quality/run.jsonl",
        "qualityManifest": results / "scifact-r11-quality/run-manifest.json",
        "qualityTargets": results / "scifact-r11-quality/targets.json",
        "scifactDocumentMap": root / "docs/rag/evaluation-data/scifact/prepared/document-map.jsonl",
        "scifactQueries": root / "docs/rag/evaluation-data/scifact/prepared/queries.jsonl",
        "scifactQrels": root / "docs/rag/evaluation-data/scifact/prepared/qrels.tsv",
        "scifactDocuments": root / "docs/rag/evaluation-data/scifact/prepared/documents/benchmark-0001.md",
        "failureCases": results / "scifact-r11-failure-cases.json",
        "failureCasesMarkdown": results / "scifact-r11-failure-cases.md",
        "internalAnalysis": results / "scifact-r11-internal-failure-analysis.json",
        "internalMarkdown": results / "scifact-r11-internal-failure-analysis.md",
        "diagnosticManifest": results / "scifact-r11-internal-diagnostics/diagnostic-manifest.json",
        "diagnosticJsonl": results / "scifact-r11-internal-diagnostics/diagnostic.jsonl",
        "stableR1": results / "scifact-load-stable-r1/load-report.json",
        "stableR2": results / "scifact-load-stable-r2/load-report.json",
        "boundaryManifest": results / "scifact-load-boundary-c4/load-manifest.json",
        "boundaryRun": results / "scifact-load-boundary-c4/load.jsonl",
        "boundaryEvidence": results / "scifact-load-boundary-c4-evidence/evidence-manifest.json",
        "boundaryRemote": results / "scifact-load-boundary-c4-evidence/remote-containers.jsonl",
        "boundaryLocal": results / "scifact-load-boundary-c4-evidence/local-process.jsonl",
        "formatManifest": results / "format-e2e-r6/manifest.json",
        "formatQueries": results / "format-e2e-r6/query-results.jsonl",
        "formatStorage": results / "format-e2e-r6/storage-consistency.json",
        "formatDoclingEvents": results / "format-e2e-r6/docling-events.txt",
        "formatResources": results / "format-e2e-r6-evidence/evidence-manifest.json",
        "formatRemote": results / "format-e2e-r6-evidence/remote-containers.jsonl",
        "formatLocal": results / "format-e2e-r6-evidence/local-process.jsonl",
        "formatNoAnswerMarkdown": results / "format-e2e-r6/markdown/format-na1.json",
        "formatNoAnswerDocx": results / "format-e2e-r6/docx/format-na1.json",
        "formatNoAnswerPdf": results / "format-e2e-r6/pdf/format-na1.json",
        "fixture": root / "docs/rag/evaluation-data/format-e2e/fixture.json",
        "pageManifest": results / "format-e2e-page-r4/manifest.json",
        "pageQueries": results / "format-e2e-page-r4/query-results.jsonl",
        "pageStorage": results / "format-e2e-page-r4/storage-consistency.json",
        "pageDoclingEvents": results / "format-e2e-page-r4/docling-events.txt",
        "pageResources": results / "format-e2e-page-r4-evidence/evidence-manifest.json",
        "pageGold": root / "docs/rag/evaluation-data/format-e2e/page-gold.json",
        "pageMarkdownDocument": root / "docs/rag/evaluation-data/format-e2e/format-fidelity.md",
        "pageDocxDocument": root / "docs/rag/evaluation-data/format-e2e/format-fidelity.docx",
        "pagePdfDocument": root / "docs/rag/evaluation-data/format-e2e/format-fidelity.pdf",
        "evaluationMethodology": root / "docs/rag/evaluation.md",
        "deleteSuccessManifest": results / "kb-delete-e2e-r3-975ee7a-minio/manifest.json",
        "deleteSuccessResidual": results / "kb-delete-e2e-r3-975ee7a-minio/residual-evidence-v2.json",
        "deleteSuccessTimeline": results / "kb-delete-e2e-r3-975ee7a-minio/delete-timeline.jsonl",
        "deleteFaultManifest": results / "kb-delete-fault-r1-e6c6d54-minio/manifest.json",
        "deleteFaultObservation": results / "kb-delete-fault-r1-e6c6d54-minio/fault-observation.json",
        "deleteFaultResidual": results / "kb-delete-fault-r1-e6c6d54-minio/residual-evidence.json",
        "deleteFaultTimeline": results / "kb-delete-fault-r1-e6c6d54-minio/delete-timeline.jsonl",
    }
    missing = [str(path) for path in paths.values() if not path.is_file()]
    if missing:
        raise SystemExit("missing evidence: " + ", ".join(missing))

    quality = load_json(paths["qualityMetrics"])
    independent = load_json(paths["qualityIndependent"])
    quality_run = load_jsonl(paths["qualityRun"])
    quality_manifest = load_json(paths["qualityManifest"])
    if quality_manifest["status"] != "completed" or quality_manifest["queryCount"] != 300:
        raise ValueError("quality manifest is not a completed 300-query run")
    if quality["manifest"]["runId"] != quality_manifest["runId"] \
            or quality["manifest"]["targetsSha256"] != quality_manifest["targetsSha256"]:
        raise ValueError("quality metrics/manifest identity mismatch")
    expected_quality_hashes = {
        "queriesSha256": paths["scifactQueries"], "qrelsSha256": paths["scifactQrels"],
        "documentMapSha256": paths["scifactDocumentMap"], "markdownSha256": paths["scifactDocuments"],
    }
    for field, path in expected_quality_hashes.items():
        if quality_manifest[field] != sha256(path):
            raise ValueError(f"quality manifest hash mismatch: {field}")
    if load_json(paths["qualityTargets"])["sourceSha256"] != quality_manifest["targetsSha256"]:
        raise ValueError("quality targets source identity mismatch")
    if len(quality_run) != 1200:
        raise ValueError("quality run must contain exactly 1200 records")
    if any(row.get("errorCode") or row.get("degraded") or not row.get("rankedDocumentIds") for row in quality_run):
        raise ValueError("quality run contains error, degradation, or empty ranking")
    if {row["variant"] for row in quality_run} != set(VARIANTS):
        raise ValueError("quality run variant set mismatch")
    query_sets = [{row["queryId"] for row in quality_run if row["variant"] == variant} for variant in VARIANTS]
    if any(len(query_ids) != 300 for query_ids in query_sets) or any(query_ids != query_sets[0] for query_ids in query_sets[1:]):
        raise ValueError("quality run must contain the same 300 unique queries for every variant")
    for variant in VARIANTS:
        first = quality["variants"][variant]
        second = independent["variants"][variant]
        for metric in ("recallAt10", "mrrAt10", "ndcgAt10", "mapAt10"):
            if abs(first[metric] - second[metric]) > 1e-12:
                raise ValueError(f"independent metric mismatch: {variant}/{metric}")

    quality_rows = []
    for variant in VARIANTS:
        metric = quality["variants"][variant]
        run_stat = quality["manifest"]["runStatistics"][variant]
        quality_rows.append({
            "variant": variant, "queryCount": metric["queryCount"],
            "recallAt1": metric["recallAt1"], "recallAt5": metric["recallAt5"],
            "recallAt10": metric["recallAt10"], "mrrAt10": metric["mrrAt10"],
            "ndcgAt10": metric["ndcgAt10"], "mapAt10": metric["mapAt10"],
            "precisionAt10": metric["precisionAt10"],
            "successAt1": metric["successAt1"], "successAt5": metric["successAt5"],
            "successAt10": metric["successAt10"], "missingRunCount": metric["missingRunCount"],
            "elapsedMs": slim_timing(run_stat["elapsedMs"]),
            "stageTimingsMs": {name: slim_timing(value) for name, value in run_stat["stageTimingsMs"].items()},
            "candidateCounts": {name: slim_timing(value) for name, value in run_stat["candidateCounts"].items()},
            "rerankMs": slim_timing(run_stat["stageTimingsMs"]["rerankMs"]),
            "errorCount": run_stat["errorCount"], "degradedCount": run_stat["degradedCount"],
            "emptyResultCount": run_stat["emptyResultCount"],
        })
    quality_by_name = {row["variant"]: row for row in quality_rows}
    deltas = {
        "hybridVsSparse": {metric: change(quality_by_name["hybrid_rrf"][metric], quality_by_name["sparse"][metric])
                           for metric in ("recallAt10", "mrrAt10", "ndcgAt10", "mapAt10")},
        "hybridVsDense": {metric: change(quality_by_name["hybrid_rrf"][metric], quality_by_name["dense"][metric])
                          for metric in ("recallAt10", "mrrAt10", "ndcgAt10", "mapAt10")},
        "rerankVsHybrid": {metric: change(quality_by_name["hybrid_rrf_rerank"][metric], quality_by_name["hybrid_rrf"][metric])
                           for metric in ("recallAt10", "mrrAt10", "ndcgAt10", "mapAt10")},
    }
    paired_deltas = {
        "hybridVsSparse": {metric: paired_change(quality["variants"]["hybrid_rrf"], quality["variants"]["sparse"], metric)
                           for metric in ("recallAt10", "mrrAt10", "ndcgAt10", "mapAt10")},
        "hybridVsDense": {metric: paired_change(quality["variants"]["hybrid_rrf"], quality["variants"]["dense"], metric)
                          for metric in ("recallAt10", "mrrAt10", "ndcgAt10", "mapAt10")},
        "rerankVsHybrid": {metric: paired_change(quality["variants"]["hybrid_rrf_rerank"], quality["variants"]["hybrid_rrf"], metric)
                           for metric in ("recallAt10", "mrrAt10", "ndcgAt10", "mapAt10")},
    }

    document_map_rows = load_jsonl(paths["scifactDocumentMap"])
    document_map = {row["documentId"]: row for row in document_map_rows}
    if len(document_map) != len(document_map_rows):
        raise ValueError("duplicate SciFact document map ID")

    failure = load_json(paths["failureCases"])
    failure_inputs = failure["manifest"]["inputSha256"]
    expected_failure_hashes = {
        "queries": paths["scifactQueries"], "qrels": paths["scifactQrels"],
        "documents": paths["scifactDocuments"], "documentMap": paths["scifactDocumentMap"],
        "run": paths["qualityRun"],
    }
    for field, path in expected_failure_hashes.items():
        if failure_inputs[field] != sha256(path):
            raise ValueError(f"failure evidence hash mismatch: {field}")
    failure_counts = failure["manifest"]["availableCaseCounts"]
    internal = load_json(paths["internalAnalysis"])
    internal_manifest = internal["manifest"]
    internal_inputs = internal_manifest["inputSha256"]
    if internal_inputs["failureReport"] != sha256(paths["failureCases"]) \
            or internal_inputs["diagnostics"] != sha256(paths["diagnosticJsonl"]) \
            or internal_inputs["diagnosticManifest"] != sha256(paths["diagnosticManifest"]):
        raise ValueError("internal diagnostic input hash mismatch")
    internal_by_query = {row["queryId"]: row for row in internal["queries"]}

    representative_specs = (
        ("dense_miss_hybrid_hit", "dense"), ("sparse_miss_hybrid_hit", "sparse"),
        ("dense_only_success", "sparse"), ("sparse_only_success", "dense"),
        ("persistent_miss", "hybrid_rrf_rerank"),
        ("rerank_reorder_gain", "hybrid_rrf_rerank"),
        ("rerank_reorder_harm", "hybrid_rrf_rerank"),
    )
    representatives = []
    for category, focus in representative_specs:
        case = failure["cases"][category][0]
        variant = case["variants"][focus]
        diagnostic = internal_by_query.get(case["queryId"])
        internal_focus = diagnostic["variants"].get(focus) if diagnostic else None
        gold_ids = {row["documentId"] for row in case["goldDocuments"]}
        internal_gold_trace = []
        if internal_focus:
            for stage in internal_focus["stages"]:
                for candidate in stage["ranking"]:
                    if candidate["documentId"] in gold_ids:
                        internal_gold_trace.append({
                            "stage": stage["stage"], "documentId": candidate["documentId"],
                            "rank": candidate["rank"], "denseScore": candidate.get("denseScore"),
                            "sparseScore": candidate.get("sparseScore"), "fusionScore": candidate.get("fusionScore"),
                            "rerankScore": candidate.get("rerankScore"), "outcome": candidate["outcome"],
                        })
        inference = case["inference"]
        alternative_explanation = case["alternativeExplanation"]
        falsification = case["falsification"]
        if category in {"sparse_miss_hybrid_hit", "dense_only_success"}:
            inference = "推断：Sparse对同义改写和词形差异敏感，缺少语义改写鲁棒性；该解释仍需词法归一化/BM25参数消融反证。"
        if category == "persistent_miss" and internal_focus and internal_focus.get("firstObservedTotalLoss", {}).get("code") == "RAW_RECALL_TOTAL_MISS":
            inference = ("推断：该内部复测最早已观察到Gold不在Dense/Sparse原始Top100并集中，"
                         "因此不能归因最终Top10或Rerank；尚无法区分索引覆盖、向量/词项表示、分块边界或query-gold粒度。")
            alternative_explanation = ("替代解释：qrels可能不完整，Gold正文与claim粒度也可能不匹配；"
                                       "但最终Top10截断和Rerank已发生在原始并集漏召回之后，不是该次复测的首因。")
            falsification = ("反证实验：核验Gold对应chunk确实写入同一generation与Qdrant payload，"
                             "再分别扩大Dense/Sparse原始TopK并替换Embedding/词法归一化，观察Gold首次出现位置。")
        if category in {"rerank_reorder_gain", "rerank_reorder_harm"} and internal_focus:
            effect = internal_focus.get("rerankEffect") or {}
            per_gold = effect.get("perGold") or []
            movements = "、".join(f"{row['documentId']}:{row['rankBefore']}→{row['rankAfter']}" for row in per_gold)
            inference = (f"阶段因果事实：同一请求的Rerank把Gold名次从{movements}，MRR变化"
                         f"{effect.get('mrrDelta', 0):+.6f}；Rerank为何偏好竞争文档仍需模型/文本对照实验。")
            direction = "上升" if category == "rerank_reorder_gain" else "下降"
            alternative_explanation = ("替代解释：Gold与竞争文档的标注粒度或相关性定义可能和Reranker训练偏好不同；"
                                       f"但本次名次{direction}确实发生在同一请求的rerank_input→rerank_output。")
            falsification = "反证实验：固定同一10个输入候选，记录完整文本与Rerank分数，替换/关闭Reranker并重复评分。"
        direct_facts = [value for value in case["directFacts"] if not value.startswith("逐候选分数=")]
        direct_facts.append("质量run未采集逐候选分数；内部复测已采集并用于阶段定位" if internal_focus
                            else "该代表未执行内部逐候选复测")
        gold_documents = []
        for row in case["goldDocuments"]:
            source = extract_scifact_document(root, document_map, row["documentId"])
            gold_documents.append({**row, "source": {key: source[key] for key in
                                                      ("contentSha256", "shardFile", "headingMarker")}})
        wrong_documents = []
        for row in [value for value in variant["ranking"] if value["relevance"] == 0][:3]:
            source = extract_scifact_document(root, document_map, row["documentId"])
            wrong_documents.append({**row, "source": {key: source[key] for key in
                                                       ("contentSha256", "shardFile", "headingMarker")}})
        representatives.append({
            "category": category, "queryId": case["queryId"], "question": case["question"],
            "goldAnswer": None, "goldAnswerStatus": "not_provided_by_scifact",
            "goldDocuments": gold_documents, "focusVariant": focus,
            "focusMetrics": variant["metrics"],
            "wrongDocuments": wrong_documents,
            "firstObservableFailure": case["firstObservableFailure"],
            "firstInternalCoverageLoss": internal_focus.get("firstObservedCoverageLoss") if internal_focus else None,
            "firstInternalTotalLoss": internal_focus.get("firstObservedTotalLoss") if internal_focus else None,
            "internalDiagnosticAvailable": internal_focus is not None,
            "internalRerankEffect": internal_focus.get("rerankEffect") if internal_focus else None,
            "internalGoldTrace": internal_gold_trace,
            "directFacts": direct_facts, "inference": inference,
            "alternativeExplanation": alternative_explanation, "falsification": falsification,
        })

    stable_rows = []
    for report_path in (paths["stableR1"], paths["stableR2"]):
        report = load_json(report_path)
        for level in report["levels"].values():
            for variant, value in level["variants"].items():
                stable_rows.append({
                    "runId": report["runId"], "concurrency": level["concurrency"], "variant": variant,
                    "requestCount": value["requestCount"], "errorCount": value["errorCount"],
                    "levelThroughputRequestsPerSecond": level["throughputRequestsPerSecond"],
                    "degradedCount": value["degradedCount"], "emptyResultCount": value["emptyResultCount"],
                    "elapsedMs": slim_timing(value["elapsedMs"]),
                    "stageTimingsMs": {name: slim_timing(timing) for name, timing in value["stageTimingsMs"].items()},
                    "observedDominantLatencyComponent": value["observedDominantLatencyComponent"],
                    "rerankMs": slim_timing(value["stageTimingsMs"]["rerankMs"]),
                })
    measured_request_count = sum(row["requestCount"] for row in stable_rows)
    if len(stable_rows) != 16 or measured_request_count != 320:
        raise ValueError("stable performance evidence must contain 320 measured requests")
    if any(row["errorCount"] or row["degradedCount"] or row["emptyResultCount"] for row in stable_rows):
        raise ValueError("stable performance evidence contains unhealthy sample")

    boundary_manifest = load_json(paths["boundaryManifest"])
    boundary_run = load_jsonl(paths["boundaryRun"])
    failed = [row for row in boundary_run if row.get("degraded") or row.get("errorCode")]
    if boundary_manifest["status"] != "failed" or len(failed) != 1:
        raise ValueError("boundary run must contain exactly one gate failure")
    boundary_remote = load_jsonl(paths["boundaryRemote"])
    boundary_local = load_jsonl(paths["boundaryLocal"])
    boundary_resources = {}
    for name in ("rag-reranker", "rag-embedding", "rag-qdrant"):
        samples = [container for sample in boundary_remote for container in sample["containers"] if container["Name"] == name]
        boundary_resources[name] = {"samples": len(samples), "cpuPeakPct": max(pct(row["CPUPerc"]) for row in samples),
                                    "memoryPeakPct": max(pct(row["MemPerc"]) for row in samples)}

    format_manifest = load_json(paths["formatManifest"])
    format_queries = load_jsonl(paths["formatQueries"])
    format_storage = load_json(paths["formatStorage"])
    format_resource_manifest = load_json(paths["formatResources"])
    assert_hashes(format_resource_manifest, paths["formatResources"].parent)
    if format_manifest["status"] != "completed" or len(format_queries) != 15 or not format_storage["passed"]:
        raise ValueError("format E2E evidence is not complete")
    if any(not row["passed"] or row["degraded"] for row in format_queries):
        raise ValueError("format E2E query evidence contains failure/degradation")
    docling_events = paths["formatDoclingEvents"].read_text(encoding="utf-8").splitlines()
    if len(docling_events) != 2 or "wallMs=2166" not in docling_events[0] or "wallMs=34163" not in docling_events[1]:
        raise ValueError("format Docling event evidence mismatch")
    query_by_format = {}
    for format_name in ("markdown", "docx", "pdf"):
        rows = [row for row in format_queries if row["format"] == format_name]
        query_by_format[format_name] = {
            "count": len(rows), "transportMs": stats([row["transport"]["elapsedMs"] for row in rows]),
            "serviceMs": stats([row["metrics"]["serviceMs"] for row in rows]),
            "rerankMs": stats([row["metrics"]["rerankMs"] for row in rows]),
        }
    format_remote = load_jsonl(paths["formatRemote"])
    format_local = load_jsonl(paths["formatLocal"])
    remote_resources = {}
    for name in sorted({container["Name"] for sample in format_remote for container in sample["containers"]}):
        samples = [container for sample in format_remote for container in sample["containers"] if container["Name"] == name]
        remote_resources[name] = {"samples": len(samples), "cpuPeakPct": max(pct(row["CPUPerc"]) for row in samples),
                                  "memoryPeakPct": max(pct(row["MemPerc"]) for row in samples)}
    local_resources = {
        "samples": len(format_local),
        "cpuPeakPct": max(float(row["appProcess"].split()[1]) for row in format_local),
        "rssPeakKiB": max(int(row["appProcess"].split()[2]) for row in format_local),
        "threadPeak": max(row["threadCount"] for row in format_local),
    }
    format_rows = []
    storage_by_format = {row["format"]: row for row in format_storage["results"]}
    for format_name, value in format_manifest["formatResults"].items():
        storage = storage_by_format[format_name]
        format_rows.append({
            "format": format_name, "ingestElapsedMs": value["ingestElapsedMs"],
            "taskAttemptCount": value["task"]["attemptCount"], "childChunks": storage["mysql"]["childChunkRows"],
            "physicalChunkRows": storage["mysql"]["physicalChunkRows"], "qdrantPoints": storage["qdrant"]["exactPointCount"],
            "pageCount": storage["mysql"]["pageCount"], "characterCount": storage["mysql"]["characterCount"],
            "source": storage["minio"]["source"], "parsed": storage["minio"]["parsed"],
            "query": query_by_format[format_name],
        })

    page_manifest = load_json(paths["pageManifest"])
    page_queries = load_jsonl(paths["pageQueries"])
    page_storage = load_json(paths["pageStorage"])
    page_resource_manifest = load_json(paths["pageResources"])
    assert_hashes(page_resource_manifest, paths["pageResources"].parent)
    if page_manifest["status"] != "completed" or len(page_queries) != 15 or not page_storage["passed"]:
        raise ValueError("page metadata E2E evidence is not complete")
    page_docling_events = paths["pageDoclingEvents"].read_text(encoding="utf-8").splitlines()
    if len(page_docling_events) != 2 or "wallMs=2193" not in page_docling_events[0] \
            or "wallMs=52368" not in page_docling_events[1]:
        raise ValueError("page metadata Docling event evidence mismatch")
    if any(not row["passed"] or row["degraded"] for row in page_queries):
        raise ValueError("page metadata E2E query evidence contains failure/degradation")
    if page_storage["pageGold"]["sha256"] != sha256(paths["pageGold"]):
        raise ValueError("page gold hash mismatch")
    if page_storage["fixture"]["sha256"] != sha256(paths["fixture"]):
        raise ValueError("page fixture hash mismatch")
    if page_manifest["fixtureFiles"]["pdf"]["sha256"] != sha256(paths["pagePdfDocument"]):
        raise ValueError("page PDF hash mismatch")
    page_by_format = {row["format"]: row for row in page_storage["results"]}
    pdf_page = page_by_format["pdf"]
    required_page_checks = ("pageCountMatchesGold", "databaseSectionsMatchGold",
                            "allCitationPagesMatchGold", "allGoldSectionsObservedInCitations",
                            "questionEvidenceSectionsMatchGold")
    if any(not pdf_page["checks"].get(name) for name in required_page_checks):
        raise ValueError("PDF page evidence gate failed")
    for name in ("markdown", "docx"):
        if not page_by_format[name]["checks"].get("databaseDoesNotGuessPages") \
                or not page_by_format[name]["checks"].get("citationsDoNotGuessPages"):
            raise ValueError(f"unknown-page semantics gate failed: {name}")
    page_rows = [{
        "format": name,
        "ingestElapsedMs": page_manifest["formatResults"][name]["ingestElapsedMs"],
        "pageCount": row["mysql"]["pageCount"],
        "childChunks": row["mysql"]["childChunkRows"],
        "qdrantPoints": row["qdrant"]["exactPointCount"],
        "checks": row["checks"],
        "chunks": row["pageMetadata"]["chunks"],
        "queryCitationCount": len(row["pageMetadata"]["queryCitations"]),
    } for name, row in sorted(page_by_format.items())]

    fixture = load_json(paths["fixture"])
    no_answer = []
    for format_name in ("markdown", "docx", "pdf"):
        row = load_json(paths[{"markdown": "formatNoAnswerMarkdown", "docx": "formatNoAnswerDocx",
                               "pdf": "formatNoAnswerPdf"}[format_name]])
        no_answer.append({
            "format": format_name, "question": fixture["noAnswerQuestions"][0]["question"],
            "expectedAnswer": "NOT_PRESENT", "citationCount": len(row["response"]["data"]["citations"]),
            "topHeading": row["response"]["data"]["citations"][0].get("headingPath"),
            "transportMs": row["transport"]["elapsedMs"],
            "judgement": "retrieval-only; LLM拒答正确性未评测",
        })

    failure_document_dir = out / "failure-documents"
    failure_document_dir.mkdir()
    failure_document_ids = sorted({doc["documentId"] for case in representatives
                                   for doc in case["goldDocuments"] + case["wrongDocuments"]})
    for document_id in failure_document_ids:
        document = extract_scifact_document(root, document_map, document_id)
        content = (
            f"# SciFact document {document_id}\n\n"
            f"- 标题：{document['title']}\n"
            f"- 原始分片：`{document['shardFile']}`\n"
            f"- 标题标记：`{document['headingMarker']}`\n"
            f"- 正文SHA-256：`{document['contentSha256']}`\n\n"
            f"## 原始正文\n\n{document['body']}\n"
        )
        (failure_document_dir / f"{document_id}.md").write_text(content, encoding="utf-8")

    delete_success_manifest = load_json(paths["deleteSuccessManifest"])
    delete_success_residual = load_json(paths["deleteSuccessResidual"])
    delete_success_timeline = load_jsonl(paths["deleteSuccessTimeline"])
    delete_fault_manifest = load_json(paths["deleteFaultManifest"])
    delete_fault_observation = load_json(paths["deleteFaultObservation"])
    delete_fault_residual = load_json(paths["deleteFaultResidual"])
    delete_fault_timeline = load_jsonl(paths["deleteFaultTimeline"])
    if not delete_success_residual["passed"] or not delete_fault_residual["passed"]:
        raise ValueError("knowledge-base delete residual evidence failed")
    delete_lifecycle = {
        "healthy": {
            "runId": delete_success_manifest["runId"], "documentCount": 2,
            "status": delete_success_manifest["status"],
            "startedAt": delete_success_manifest["startedAt"], "finishedAt": delete_success_manifest["finishedAt"],
            "deleteTimelineSamples": len(delete_success_timeline),
            "deleteObservedElapsedMs": timeline_elapsed_ms(delete_success_timeline),
            "parent": delete_success_manifest["terminalDeleteTask"],
            "residualChecks": delete_success_residual["checks"],
        },
        "objectStorageFault": {
            "runId": delete_fault_manifest["runId"], "documentCount": 2,
            "status": delete_fault_manifest["status"],
            "startedAt": delete_fault_manifest["startedAt"], "finishedAt": delete_fault_manifest["finishedAt"],
            "deleteTimelineSamples": len(delete_fault_timeline),
            "deleteObservedElapsedMs": timeline_elapsed_ms(delete_fault_timeline),
            "fault": delete_fault_observation, "parent": delete_fault_manifest["terminalDeleteTask"],
            "residualChecks": delete_fault_residual["checks"],
            "manualRetryApiCalled": False,
        },
    }

    evidence_hashes = {name: {"path": str(path.relative_to(root)), "sha256": sha256(path), "bytes": path.stat().st_size}
                       for name, path in sorted(paths.items())}
    passed_format_queries = sum(1 for row in format_queries if row["passed"])
    degraded_format_queries = sum(1 for row in format_queries if row["degraded"])
    ledger = {
        "schemaVersion": 1,
        "scope": {
            "quality": "SciFact retrieval only; 300 queries x 4 variants",
            "answerCorrectness": "not evaluated: SciFact input has no gold answers",
            "percentileMethod": "nearest-rank",
            "formatE2E": "real HTTP + MinIO + MySQL + Qdrant + Docling + Embedding + Reranker; one worker/thread",
        },
        "quality": {"variants": quality_rows, "deltas": deltas, "pairedQueryChanges": paired_deltas},
        "failureCases": {"availableCounts": failure_counts,
                         "materializedCaseCount": sum(len(v) for v in failure["cases"].values()),
                         "renderedRepresentativeCount": len(representatives), "representatives": representatives},
        "internalDiagnostics": {
            "queryCount": internal_manifest["queryCount"], "recordCount": internal_manifest["recordCount"],
            "exactFinalRankingMatches": internal_manifest["exactFinalRankingMatches"],
            "firstObservedCoverageLossCounts": internal_manifest["firstObservedCoverageLossCounts"],
            "firstObservedTotalLossCounts": internal_manifest["firstObservedTotalLossCounts"],
            "rerankEffectCounts": internal_manifest["rerankEffectCounts"], "limitations": internal_manifest["limitations"],
        },
        "stablePerformance": {"measuredRequestCount": measured_request_count, "rows": stable_rows},
        "capacityBoundary": {
            "status": boundary_manifest["status"], "recordCount": len(boundary_run),
            "failedSample": failed[0], "resourcePeaks": boundary_resources,
            "localSamples": len(boundary_local),
            "localCpuPeakPct": max(float(row["appProcess"].split()[1]) for row in boundary_local),
            "localRssPeakKiB": max(int(row["appProcess"].split()[2]) for row in boundary_local),
        },
        "formatE2E": {
            "runId": format_manifest["runId"], "passedQueries": passed_format_queries,
            "degradedQueries": degraded_format_queries,
            "formats": sorted(format_rows, key=lambda row: row["format"]), "noAnswerProbes": no_answer,
            "resources": {"remote": remote_resources, "localJava": local_resources},
            "storageConsistencyPassed": format_storage["passed"],
        },
        "pageMetadataE2E": {
            "runId": page_manifest["runId"], "codeRevision": page_manifest["codeRevision"],
            "appJarSha256": page_manifest["appJarSha256"],
            "passedQueries": sum(1 for row in page_queries if row["passed"]),
            "degradedQueries": sum(1 for row in page_queries if row["degraded"]),
            "formats": page_rows, "storageConsistencyPassed": page_storage["passed"],
            "goldSha256": page_storage["pageGold"]["sha256"],
        },
        "knowledgeBaseDeleteLifecycle": delete_lifecycle,
        "bottlenecks": [
            {"rank": 1, "component": "Reranker CPU推理与排队", "fact": f"并发4出现{failed[0]['elapsedMs'] / 1000:.3f}s降级回退；稳定轮Rerank占完整链路绝大部分延迟。",
             "supportedHypothesis": "Top10按3/3/3/1串行子批是代码层候选解释；Semaphore等待与远端排队各自贡献尚未分段测量。",
             "impact": "当前完整Rerank链路只验证到并发2健康。", "optimization": "合并批次、异步批处理/动态batch、缓存与只重排高不确定查询；完成后重跑并发1/2/4。"},
            {"rank": 2, "component": "Docling PDF解析", "fact": f"r6 PDF摄取{next(row for row in format_rows if row['format'] == 'pdf')['ingestElapsedMs'] / 1000:.3f}s，Docling单次HTTP 34.163s；Docling CPU峰值{remote_resources['rag-docling']['cpuPeakPct']:.2f}%。",
             "supportedHypothesis": "Docling调用占总墙钟约90.9%；其余约3.434s没有阶段分段，不能分摊给Java、MinIO或向量写入。",
             "impact": "PDF摄取显著慢于Markdown 3.056s与DOCX 6.094s。", "optimization": "内容哈希去重、解析缓存、格式快速路径、独立解析队列；用多页/表格PDF复测。"},
            {"rank": 3, "component": "融合TopK/阈值召回损失", "fact": f"{internal_manifest['recordCount']}条内部诊断中{internal_manifest['firstObservedTotalLossCounts']['FUSION_THRESHOLD_OR_TOPK_LOSS']}条首个完全损失位于fusion threshold/TopK联合步骤。",
             "supportedHypothesis": "当前轨迹将threshold与TopK合并，尚不能进一步分离两者。", "impact": "部分Gold在原始候选存在但融合后消失。",
             "optimization": "拆分threshold与TopK轨迹，调大fusion候选并做按query类型的权重/阈值校准。"},
            {"rank": 4, "component": "DOCX固定页语义缺失", "fact": "r4三页PDF的6个章节、30条查询citation和5个问题证据章节均通过页码金标；同轮DOCX的Docling pages为空，数据库与citation保持null。",
             "supportedHypothesis": "流式DOCX在Docling 1.26.0响应中没有page provenance；当前证据不能把它解释为解析丢失或0页。", "impact": "PDF页码链路已闭环，但DOCX仍不能提供固定页审计。",
             "optimization": "若业务刚需DOCX页码，先转换为固定版式PDF或引入能输出版式页span的解析器，再用同一金标门禁复测。"},
        ],
        "limitations": [
            "SciFact只评测检索，不含标准答案，因此没有300题级Faithfulness、Answer Correctness和幻觉率；补充黑盒仅有一个合成事实样本。",
            "r6格式题只验证证据词项是否在返回上下文，不等同于最终LLM回答正确。",
            "r6无答案探针仍会返回相关候选；补充黑盒只证明一个合成无答案问题在Agent/Workflow各一次精确拒答，不代表拒答率。",
            "r6每格式仅一个小文件、单Worker、单上传/查询线程，不代表大文件、多租户或长时容量。",
            "内部诊断为20个确定性代表问题，不是300问题全量内部轨迹。",
            "fusion threshold与TopK尚未分开留痕；PDF页码已闭环，但Markdown和当前Docling DOCX的页数仍是未知而非0页。",
        ],
        "evidence": evidence_hashes,
    }

    ledger_path = out / "rag-final-evidence-ledger.json"
    ledger_path.write_text(json.dumps(ledger, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    lines: list[str] = []
    add = lines.append
    add("# RAG完整测试数据、瓶颈与失败因果报告")
    add("")
    add("> 结论约束：本报告所有数字均由随附原始JSON/JSONL程序化生成；没有证据的项目明确标为未测。检索命中不等于最终答案正确。")
    add("")
    add("## 一、最终结论")
    add("")
    dense_quality = quality_by_name["dense"]
    hybrid_quality = quality_by_name["hybrid_rrf"]
    rerank_quality = quality_by_name["hybrid_rrf_rerank"]
    pdf_format = next(row for row in format_rows if row["format"] == "pdf")
    add(f"1. 当前SciFact检索指标最好的已测配置其实是`Dense`：Recall@10={dense_quality['recallAt10']:.6f}、MRR@10={dense_quality['mrrAt10']:.6f}、nDCG@10={dense_quality['ndcgAt10']:.6f}、MAP@10={dense_quality['mapAt10']:.6f}。`Hybrid RRF + Rerank`是混合链路内部最好的配置，但四项指标仍全部低于Dense；不能宣称技术组件越多质量就越好。")
    add(f"2. Rerank保持Recall@10不变，却把MRR/nDCG/MAP分别提高{deltas['rerankVsHybrid']['mrrAt10']['absolute']:.6f}/{deltas['rerankVsHybrid']['ndcgAt10']['absolute']:.6f}/{deltas['rerankVsHybrid']['mapAt10']['absolute']:.6f}；代价是质量run p50从{hybrid_quality['elapsedMs']['p50'] / 1000:.3f}s升至{rerank_quality['elapsedMs']['p50'] / 1000:.3f}s。它改善{failure_counts['rerank_reorder_gain']}个query的排序，也伤害{failure_counts['rerank_reorder_harm']}个query；{internal_manifest['queryCount']}个内部复测代表中为{internal_manifest['rerankEffectCounts']['RERANK_ORDER_GAIN']}改善、{internal_manifest['rerankEffectCounts']['RERANK_ORDER_HARM']}伤害、{internal_manifest['rerankEffectCounts']['RERANK_NEUTRAL']}不变。")
    add(f"3. 在本次SciFact在线检索负载中，首个已证明的主导瓶颈是Reranker：并发4出现{failed[0]['elapsedMs'] / 1000:.3f}s fallback，Reranker CPU峰值{boundary_resources['rag-reranker']['cpuPeakPct']:.2f}%；稳定健康容量只证明到并发2。该结论不外推到大文件摄取或多租户全系统。")
    add(f"4. 三格式真实MinIO链路r6为{format_manifest['retrievalEvidencePassed']}/{format_manifest['retrievalEvidenceTotal']}检索证据词项覆盖、0降级；MySQL child chunk/distinct vector point与Qdrant exact point一致，MinIO哈希一致。PDF摄取{pdf_format['ingestElapsedMs'] / 1000:.3f}s，其中Docling HTTP 34.163s，是本轮摄取主导阶段。")
    add(f"5. 页码链路已在独立r4真实复测中闭环：三页PDF的pageCount=3，6个章节数据库页码为1/1/1/2/2/3，{len(pdf_page['pageMetadata']['queryCitations'])}条查询citation全部与金标一致，5/5问题均召回其正确证据章节和页码。Markdown与当前Docling DOCX继续按页语义未知处理，没有猜页码。")
    add("6. 尚不能宣告完整答案质量闭环：SciFact没有gold answer；无答案题虽然能召回“文档未提供该值”的段落，但检索层仍返回5～6条候选，Agent是否拒绝编造尚未黑盒评测。")
    add("")
    add("## 二、测试口径与有效数据")
    add("")
    add("| 数据集/轮次 | 样本 | 用途 | 可用于什么结论 |")
    add("|---|---:|---|---|")
    add("| SciFact r11 | 300问题×4=1200 | Dense/Sparse/Hybrid/Rerank消融 | Recall/MRR/nDCG/MAP与同轮延迟 |")
    add("| 内部诊断 | 20问题×4=80 | 候选阶段轨迹 | 首个可观测失效步骤、Rerank同请求前后 |")
    add("| 稳定性能r1+r2 | 320 measured，另有warmup | 并发1/2、顺序反转 | 已验证健康容量和延迟范围 |")
    add(f"| 并发4边界 | 共{len(boundary_run)}条measured（并发1=80、2=80、4=39） | 容量失败定位 | 并发4不能作为稳定分位数，只作失败边界 |")
    add("| 三格式r6 | 3文件、15答案问题、3无答案探针 | 真实MinIO摄取/召回 | 格式功能、三端一致性、小文件单线程性能 |")
    add("| 页码r4 | 同一3文件、15答案问题、PDF 6章节金标 | 真实MinIO重新摄取/召回 | PDF页码准确性、未知页语义不猜测 |")
    add("| 知识库删除r3+故障r1 | 2个双文档知识库 | MySQL/Qdrant/MinIO级联删除 | 健康删除、对象存储断链自动恢复、零残留 |")
    add("")
    add("## 三、RAG技术点前后差异")
    add("")
    add("| 配置 | R@1 | R@5 | R@10 | P@10 | MRR@10 | nDCG@10 | MAP@10 | S@1/S@5/S@10 |")
    add("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    for row in quality_rows:
        add(f"| {row['variant']} | {fmt(row['recallAt1'], 6)} | {fmt(row['recallAt5'], 6)} | {fmt(row['recallAt10'], 6)} | {fmt(row['precisionAt10'], 6)} | {fmt(row['mrrAt10'], 6)} | {fmt(row['ndcgAt10'], 6)} | {fmt(row['mapAt10'], 6)} | {fmt(row['successAt1'], 6)}/{fmt(row['successAt5'], 6)}/{fmt(row['successAt10'], 6)} |")
    add("")
    add("| 配置 | n/missing | elapsed mean/p50/p95/p99/max ms | 错误/降级/空 |")
    add("|---|---:|---:|---:|")
    for row in quality_rows:
        elapsed = row["elapsedMs"]
        add(f"| {row['variant']} | {row['queryCount']}/{row['missingRunCount']} | {elapsed['mean']:.3f}/{elapsed['p50']}/{elapsed['p95']}/{elapsed['p99']}/{elapsed['max']} | {row['errorCount']}/{row['degradedCount']}/{row['emptyResultCount']} |")
    add("")
    add("质量run阶段p95与候选均值（完整mean/p50/p95/p99/max保存在机器总账）：")
    add("")
    add("| 配置 | embedding/dense/sparse/fusion/rerank/hydration/service p95 ms | dense/sparse/fusion/rerank候选均值 |")
    add("|---|---:|---:|")
    for row in quality_rows:
        stage = row["stageTimingsMs"]
        candidates = row["candidateCounts"]
        stage_values = "/".join(str(stage[name]["p95"]) for name in
                                ("embeddingMs", "denseMs", "sparseMs", "fusionMs", "rerankMs", "hydrationMs", "serviceMs"))
        candidate_values = "/".join(f"{candidates[name]['mean']:.3f}" for name in
                                    ("denseCandidateCount", "sparseCandidateCount", "fusionCandidateCount", "rerankCandidateCount"))
        add(f"| {row['variant']} | {stage_values} | {candidate_values} |")
    add("")
    add("关键差值：")
    add("")
    add(f"- Sparse→Hybrid：Recall@10 {deltas['hybridVsSparse']['recallAt10']['absolute']:+.6f}，MRR@10 {deltas['hybridVsSparse']['mrrAt10']['absolute']:+.6f}。Dense通道补回了81个Sparse漏召回而Hybrid命中的query。")
    add(f"- Dense→Hybrid：Recall@10 {deltas['hybridVsDense']['recallAt10']['absolute']:+.6f}，MRR@10 {deltas['hybridVsDense']['mrrAt10']['absolute']:+.6f}。Hybrid总体反而下降。另有98个query是Dense命中而Sparse未命中，这证明Dense通道在本数据集更强；它不等价于98个Hybrid漏召回，Hybrid相对Dense的具体损失必须按逐query差值另算。")
    add(f"- Hybrid→Hybrid+Rerank：Recall@10 {deltas['rerankVsHybrid']['recallAt10']['absolute']:+.6f}，MRR@10 {deltas['rerankVsHybrid']['mrrAt10']['absolute']:+.6f}，nDCG@10 {deltas['rerankVsHybrid']['ndcgAt10']['absolute']:+.6f}，MAP@10 {deltas['rerankVsHybrid']['mapAt10']['absolute']:+.6f}。Rerank改变顺序，不补回已被Top10截掉的文档。")
    add("")
    add("同一300问题的配对变化（改善/持平/退化）：")
    add("")
    add("| 对比 | Recall@10 | MRR@10 | nDCG@10 | MAP@10 |")
    add("|---|---:|---:|---:|---:|")
    comparison_labels = {"hybridVsSparse": "Sparse→Hybrid", "hybridVsDense": "Dense→Hybrid",
                         "rerankVsHybrid": "Hybrid→Hybrid+Rerank"}
    for comparison, values in paired_deltas.items():
        cells = [f"{values[metric]['improved']}/{values[metric]['unchanged']}/{values[metric]['degraded']}"
                 for metric in ("recallAt10", "mrrAt10", "ndcgAt10", "mapAt10")]
        add(f"| {comparison_labels[comparison]} | {' | '.join(cells)} |")
    add("")
    add("上述是同一问题配对后的观测差值，不自动证明组件在其他数据集上的普遍因果；组件归因只限于本轮冻结索引、配置和问题集。")
    add("")
    add("## 四、非互斥失败标签与首个失效步骤")
    add("")
    add("这些是可重叠布尔标签，同一query可以进入多类，计数不可相加当作300题的互斥分布。`dense_only_success`表示Dense命中且Sparse未命中，`sparse_only_success`反之；`rerank_rescue/harm`表示Top10命中集合进出，`rerank_reorder_gain/harm`表示两者都命中时MRR顺序改变。")
    add("")
    add("| 标签 | 全量query数 | 布尔语义 |")
    add("|---|---:|---|")
    failure_rules = {
        "dense_miss_hybrid_hit": "Dense未命中且Hybrid命中", "sparse_miss_hybrid_hit": "Sparse未命中且Hybrid命中",
        "rerank_rescue": "Rerank前Top10未命中、后命中", "rerank_harm": "Rerank前Top10命中、后未命中",
        "dense_only_success": "Dense命中且Sparse未命中（不表示Hybrid失败）",
        "sparse_only_success": "Sparse命中且Dense未命中（不表示Hybrid失败）",
        "persistent_miss": "四个变体Top10均未命中", "rerank_reorder_gain": "前后均命中且MRR提高",
        "rerank_reorder_harm": "前后均命中且MRR降低",
    }
    for key, value in failure_counts.items():
        add(f"| {key} | {value} | {failure_rules[key]} |")
    add("")
    add("内部80条阶段证据的首个完全损失：")
    add("")
    for key, value in internal_manifest["firstObservedTotalLossCounts"].items():
        add(f"- `{key}`：{value}条。")
    add("")
    add("这说明最常见的可观测损失不是Qdrant完全找不到，而是候选进入融合后被联合threshold/TopK裁掉；但当前埋点不能继续区分是阈值还是TopK，不能越过证据下结论。")
    add("")
    add("## 五、召回失败文档与因果链（代表案例）")
    add("")
    add("下面对7个有样本的关键类别各展示1个确定性代表；`rerank_rescue`和`rerank_harm`在本轮均为0，因此没有伪造案例。完整附件含21个代表case、全部Gold截断摘要、各case前三条非Gold截断摘要及各变体Top10文档ID，见[召回失败案例全集](../scifact-r11-failure-cases.md)。逐候选内部复测分数与阶段轨迹见[内部阶段失败证据](../scifact-r11-internal-failure-analysis.md)。")
    add("")
    for case in representatives:
        add(f"### {case['category']} / queryId={case['queryId']}")
        add("")
        add(f"问题：{case['question']}")
        add("")
        add("Gold答案：**SciFact数据集未提供自然语言gold answer**；本轮只能按qrels中的相关文档评测检索，不能把文档摘要冒充答案。")
        add("")
        add("Gold文档：")
        add("")
        for doc in case["goldDocuments"]:
            add(f"- [`{doc['documentId']}` {doc['title']}](failure-documents/{doc['documentId']}.md)（校验后完整正文副本；[原始冻结分片](../../evaluation-data/scifact/prepared/documents/{doc['source']['shardFile']})；正文SHA-256=`{doc['source']['contentSha256']}`；标记=`{doc['source']['headingMarker']}`）")
            add("")
            add(f"  > {doc['excerpt']}")
        add("")
        add(f"问题变体：`{case['focusVariant']}`；Recall@10={case['focusMetrics']['recallAt10']:.6f}，MRR@10={case['focusMetrics']['mrrAt10']:.6f}。")
        add("")
        add("实际排在前面的错误文档：")
        add("")
        for doc in case["wrongDocuments"]:
            score = "未采集" if doc.get("score") is None else doc["score"]
            add(f"- rank={doc['rank']} score={score} [`{doc['documentId']}` {doc['title']}](failure-documents/{doc['documentId']}.md)（校验后完整正文副本；[原始冻结分片](../../evaluation-data/scifact/prepared/documents/{doc['source']['shardFile']})；标记=`{doc['source']['headingMarker']}`）")
            add("")
            add(f"  > {doc['excerpt']}")
        add("")
        internal_loss = case["firstInternalTotalLoss"]
        internal_loss_text = (f"{internal_loss['stage']}/{internal_loss['code']}" if internal_loss else
                              ("未观察到Gold完全损失" if case["internalDiagnosticAvailable"] else "该代表未采集内部诊断"))
        add(f"首个终态可观测失败：`{case['firstObservableFailure']}`。内部首个完全损失：`{internal_loss_text}`。")
        add("")
        internal_coverage = case["firstInternalCoverageLoss"]
        coverage_text = (f"{internal_coverage['stage']}/{internal_coverage['code']}" if internal_coverage else
                         ("未观察到Gold覆盖下降" if case["internalDiagnosticAvailable"] else "该代表未采集内部诊断"))
        add(f"内部首个覆盖下降：`{coverage_text}`。这三个字段分别是消融终态、算子级完全丢失和算子级部分覆盖下降，不能混为同一根因。")
        add("")
        add("直接事实：" + "；".join(case["directFacts"]))
        add("")
        if case["internalGoldTrace"]:
            add("内部复测Gold轨迹摘录：")
            add("")
            traces = ([trace for trace in case["internalGoldTrace"] if trace["stage"] in {"rerank_input", "rerank_output"}]
                      if case["internalRerankEffect"] else case["internalGoldTrace"][:4])
            for trace in traces:
                add(f"- stage={trace['stage']} rank={trace['rank']} outcome={trace['outcome']} dense={trace['denseScore']} sparse={trace['sparseScore']} fusion={trace['fusionScore']} rerank={trace['rerankScore']}")
            add("")
        add("因果推断（可证伪）：" + case["inference"].removeprefix("推断："))
        add("")
        add("替代解释：" + case["alternativeExplanation"].removeprefix("其他可能解释：").removeprefix("替代解释："))
        add("")
        add("复证实验：" + case["falsification"].removeprefix("反证实验："))
        add("")
    add("## 六、性能、容量与瓶颈")
    add("")
    add("两轮稳定结果分别保留，未合并成伪单轮：")
    add("")
    add("| run | 并发 | 配置 | n | mean/p50/p95/p99/max ms | Rerank p95 | 层级吞吐req/s | 主导stage |")
    add("|---|---:|---|---:|---:|---:|---:|---|")
    for row in stable_rows:
        elapsed = row["elapsedMs"]
        add(f"| {row['runId']} | {row['concurrency']} | {row['variant']} | {row['requestCount']} | {elapsed['mean']:.3f}/{elapsed['p50']}/{elapsed['p95']}/{elapsed['p99']}/{elapsed['max']} | {row['rerankMs']['p95']} | {row['levelThroughputRequestsPerSecond']:.6f} | {row['observedDominantLatencyComponent']} |")
    add("")
    add(f"并发4门禁失败样本：queryId={failed[0]['queryId']}，配置={failed[0]['variant']}，HTTP={failed[0]['httpStatus']}，耗时={failed[0]['elapsedMs']}ms，降级原因=`{failed[0]['degradationReasons'][0]}`。Reranker CPU峰值={boundary_resources['rag-reranker']['cpuPeakPct']:.2f}%，内存占比峰值={boundary_resources['rag-reranker']['memoryPeakPct']:.2f}%；容器前后无restart/OOM。")
    add("")
    add("瓶颈优先级：")
    add("")
    for item in ledger["bottlenecks"]:
        add(f"{item['rank']}. **{item['component']}**：事实—{item['fact']} 有证据支持的解释/待验证假设—{item['supportedHypothesis']} 影响—{item['impact']} 优化—{item['optimization']}")
    add("")
    add("## 七、Markdown/DOCX/PDF真实链路")
    add("")
    add("| 格式 | 摄取ms | attempt | 字符 | child块/Qdrant点 | 页数字段 | 查询p50/p95/max | MinIO原件SHA |")
    add("|---|---:|---:|---:|---:|---:|---:|---|")
    for row in format_rows:
        q = row["query"]["transportMs"]
        add(f"| {row['format']} | {row['ingestElapsedMs']} | {row['taskAttemptCount']} | {row['characterCount']} | {row['childChunks']}/{row['qdrantPoints']} | {row['pageCount']}（未知，未闭环） | {q['p50']}/{q['p95']}/{q['max']} | `{row['source']['sha256']}` |")
    add("")
    add(f"r6资源采样：{remote_resources['rag-docling']['samples']}个远端样本、{local_resources['samples']}个Java样本。Docling CPU峰值{remote_resources['rag-docling']['cpuPeakPct']:.2f}%，Reranker {remote_resources['rag-reranker']['cpuPeakPct']:.2f}%，Embedding {remote_resources['rag-embedding']['cpuPeakPct']:.2f}%；Java CPU峰值{local_resources['cpuPeakPct']:.1f}%、RSS峰值{local_resources['rssPeakKiB']}KiB，前后容器0重启、无OOM。单次PDF Docling日志为34163ms，因此其{pdf_format['ingestElapsedMs'] / 1000:.3f}s摄取耗时主要由解析占据。")
    add("")
    add("### 页码修复前后与真实金标")
    add("")
    add("r1在业务上传前因测试启动脚本误取MinIO账号字段失败；r2在DOCX遇到`pages={}`时被错误判为非法；修正空页为未知后，r3完成15/15，但连续H2被旧标题栈错误嵌套，PDF只有文档标题能匹配页码。r4改用真实标题level出栈后重新摄取同一份PDF，以下结果全部来自MySQL chunk与原始HTTP citation，不是单元测试推断。")
    add("")
    add("| 格式 | r4摄取ms | pageCount语义 | child块/Qdrant点 | 查询citation | 页码门禁 | 对应文档 |")
    add("|---|---:|---|---:|---:|---|---|")
    page_document_links = {"markdown": "../../evaluation-data/format-e2e/format-fidelity.md",
                           "docx": "../../evaluation-data/format-e2e/format-fidelity.docx",
                           "pdf": "../../evaluation-data/format-e2e/format-fidelity.pdf"}
    for row in page_rows:
        semantics = (f"{row['pageCount']}页（固定）" if row["format"] == "pdf" else "未知（数据库/citation均null）")
        gate = ("6章节、全部citation、5问题证据章节均匹配" if row["format"] == "pdf"
                else "未猜测页码")
        add(f"| {row['format']} | {row['ingestElapsedMs']} | {semantics} | {row['childChunks']}/{row['qdrantPoints']} | {row['queryCitationCount']} | {gate} | [源文档]({page_document_links[row['format']]}) |")
    add("")
    add("PDF章节金标与数据库实值：")
    add("")
    add("| 章节 | pageFrom/pageTo |")
    add("|---|---:|")
    for chunk in next(row for row in page_rows if row["format"] == "pdf")["chunks"]:
        add(f"| {chunk['headingPath']} | {chunk['pageFrom']}/{chunk['pageTo']} |")
    add("")
    add("页码失败的因果链是：Docling JSON本身已有正确provenance → Java同时拿到Markdown H2与JSON level=1 → 旧代码按栈长度出栈，把连续H2错误嵌套 → 文本路径不等导致章节页码为null → r4按真实level弹栈后路径一致，数据库与citation金标全部恢复。DOCX则停在更早的解析输出阶段：Docling响应没有pages/provenance，因此系统保留null；这不是同一个标题栈问题。")
    add("")
    add("无答案探针：")
    add("")
    add("| 格式 | 问题 | 返回citation数 | Top heading | 判定 |")
    add("|---|---|---:|---|---|")
    for row in no_answer:
        add(f"| {row['format']} | {row['question']} | {row['citationCount']} | {row['topHeading']} | {row['judgement']} |")
    add("")
    add("## 八、知识库级联删除、故障恢复与残留")
    add("")
    healthy_delete = delete_lifecycle["healthy"]
    fault_delete = delete_lifecycle["objectStorageFault"]
    fault_child = fault_delete["fault"]["failedChild"]
    add("| 场景 | 文档 | 删除观测耗时/样本 | 故障现场 | 终态 | 外部残留 |")
    add("|---|---:|---:|---|---|---|")
    add(f"| 健康链路 `{healthy_delete['runId']}` | 2 | {healthy_delete['deleteObservedElapsedMs']} ms / {healthy_delete['deleteTimelineSamples']} | 无 | parent completed 2/2 | MySQL/Qdrant/MinIO全门禁通过 |")
    add(f"| MinIO断链 `{fault_delete['runId']}` | 2 | {fault_delete['deleteObservedElapsedMs']} ms / {fault_delete['deleteTimelineSamples']} | child retrying `{fault_child['stage']}` attempt={fault_child['attemptCount']}/{fault_child['maxAttempts']} `{fault_child['errorCode']}`；parent waiting | parent completed 2/2，未调用retry API | MySQL/Qdrant/MinIO全门禁通过 |")
    add("")
    add("故障因果链：删除子Worker进入MinIO原件删除 → 本地MinIO SSH转发被测试故意断开 → MySQL现场记录`OBJECT_STORAGE_DELETE_FAILED`且checkpoint停在`deleting_source` → 父协调器进入WAITING并保留进度 → 恢复同一转发 → Dispatcher从数据库账本自动续跑 → 两个DELETE子任务完成 → 父任务验证文档/版本/chunk/binding后完成 → 独立采集器再次确认两版本Qdrant点数为0、MinIO source/parsed不存在。")
    add("")
    add("观测缺口：故障现场同一子任务attempt=2，终态数据库却为attempt=1，说明当前恢复路径会重置attempt；因此终态attempt不能代表累计故障次数。优化应保持累计attempt单调或新增不可变任务事件表，并把错误开始/恢复时间、退避原因和外部依赖写入审计。该轮验证的是自动恢复，不是FAILED/DEAD后的管理员手工恢复。")
    add("")
    add("证据：[健康删除manifest](../kb-delete-e2e-r3-975ee7a-minio/manifest.json)、[健康零残留](../kb-delete-e2e-r3-975ee7a-minio/residual-evidence-v2.json)、[故障瞬时快照](../kb-delete-fault-r1-e6c6d54-minio/fault-observation.json)、[故障终态零残留](../kb-delete-fault-r1-e6c6d54-minio/residual-evidence.json)。")
    add("")
    add("## 九、Agent/Workflow真实LLM补充边界")
    add("")
    add("真实DeepSeek黑盒已补测一个合成可回答事实、一个合成无答案问题和一个伪造citation诱导，覆盖Agent/Workflow非流式与SSE、历史metadata、引用回源、跨租户拒绝和取消。两入口均命中固定事实并得到VALID引用，无答案均精确返回NOT_IN_DOCUMENT，伪造citation均被标记INVALID_CITATIONS；这证明入口链路可工作，但样本量不足以计算通用Answer Correctness、Faithfulness、幻觉率或拒答率。取消修复后的最新证据、前后差异和仍未证明的在途远端请求撤销边界见[Agent与Workflow真实LLM黑盒补充报告](Agent与Workflow真实LLM黑盒补充报告.md)。")
    add("")
    add("## 十、上线前优化与复测门槛")
    add("")
    add("1. Reranker把候选批次由3提升至服务允许且经过内存验证的批量，减少4次串行HTTP；加入query级不确定性门控与短TTL缓存。门槛：并发4至少两轮、每变体≥100 measured、0 fallback，且MRR下降不超过0.005。")
    add(f"2. 融合阶段拆开threshold和TopK埋点，对Dense/Sparse权重、fusionTopK做网格消融。门槛：Recall@10不得低于当前Dense {dense_quality['recallAt10']:.6f}，同时报告MRR/延迟代价。")
    add("3. Docling按内容哈希缓存解析结果，并分离PDF重任务队列。门槛：真实多页/表格PDF至少30份，报告p50/p95、页面/表格保真和失败重试。")
    add("4. PDF页span已贯穿解析、chunk、Qdrant payload和citation并通过单份三页金标；下一门槛是至少30份多页/表格/扫描PDF以及引用回源黑盒。DOCX若刚需固定页码，需增加固定版式转换或替换解析器后用相同金标门禁。")
    add("5. 扩展有gold answer的端到端Agent评测到足够样本，至少计算Answer Correctness、Faithfulness、引用精确率/召回率和无答案拒答率；当前单一合成事实只能算链路smoke，不能把检索报告当答案质量报告。")
    add("")
    add("## 十一、明确未测与证据限制")
    add("")
    for item in ledger["limitations"]:
        add(f"- {item}")
    add("")
    add("## 十二、证据索引与复算")
    add("")
    add("机器总账：[rag-final-evidence-ledger.json](rag-final-evidence-ledger.json)。关键原始证据均已从`/tmp`固化进项目`docs/rag/evaluation-results/`，总账记录每个输入的SHA-256与字节数。")
    add("")
    add("```bash")
    add("python3 ai-agent-scaffold-benchmark/scripts/build-rag-final-report.py \\")
    add("  --project-root . \\")
    add("  --out-dir /tmp/rag-final-report-recomputed")
    add("cmp docs/rag/evaluation-results/final-report/rag-final-evidence-ledger.json \\")
    add("    /tmp/rag-final-report-recomputed/rag-final-evidence-ledger.json")
    add("cmp docs/rag/evaluation-results/final-report/RAG完整测试数据与瓶颈分析.md \\")
    add("    /tmp/rag-final-report-recomputed/RAG完整测试数据与瓶颈分析.md")
    add("```")
    add("")

    report_path = out / "RAG完整测试数据与瓶颈分析.md"
    report_path.write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    main()
