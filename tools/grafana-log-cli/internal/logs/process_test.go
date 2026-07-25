package logs

import (
	"strings"
	"testing"
	"time"

	"github.com/codeliu/ai-agent-scaffold/tools/grafana-log-cli/internal/grafana"
)

func TestProcessSortsDeduplicatesParsesAndRedacts(t *testing.T) {
	now := time.Date(2026, 7, 25, 1, 2, 3, 0, time.UTC)
	line := `INFO event=rag_stage message="检索 完成" password=hunter2 authorization="Bearer abc.def"`
	raw := []grafana.Entry{
		{Timestamp: now.Add(time.Second), Line: line, Labels: map[string]string{"cookie": "sid=1"}},
		{Timestamp: now, Line: `INFO event=rag_started traceId=t1`},
		{Timestamp: now.Add(time.Second), Line: line, Labels: map[string]string{"cookie": "sid=1"}},
	}
	entries := Process(raw)
	if len(entries) != 2 || entries[0].Fields["traceId"] != "t1" {
		t.Fatalf("unexpected process result: %#v", entries)
	}
	last := entries[1]
	if strings.Contains(last.Line, "hunter2") || last.Labels["cookie"] != "[REDACTED]" {
		t.Fatalf("secret leaked: %#v", last)
	}
	if last.Fields["message"] != "检索 完成" || last.Fields["password"] != "[REDACTED]" {
		t.Fatalf("logfmt parsing/redaction failed: %#v", last.Fields)
	}
}

func TestAnalyzeEvidenceAndTerminalSemantics(t *testing.T) {
	base := time.Date(2026, 7, 25, 0, 0, 0, 0, time.UTC)
	entries := []Entry{
		entry(base, `event=rag_retrieve_started stage=retrieval outcome=started traceId=t1 retrievalId=r1`),
		entry(base.Add(time.Second), `event=rag_stage stage=dense outcome=started inputCount=20 outputCount=10`),
		entry(base.Add(2*time.Second), `event=rag_stage stage=rerank outcome=completed costMs=950 inputCount=10 outputCount=3`),
		entry(base.Add(3*time.Second), `event=rag_retrieve_failed stage=retrieval outcome=failed errorCode=RAG_DOWN success=false message="远端失败"`),
		entry(base.Add(4*time.Second), `event=model_call_started stage=provider_request message="模型调用已开始"`),
		entry(base.Add(5*time.Second), `event=model_call message="模型调用完成" costMs=1000`),
	}
	analysis := Analyze(entries)
	if len(analysis.Failures) != 1 || len(analysis.IncompleteStages) != 1 {
		t.Fatalf("expected one failure and one unclosed dense stage: %#v", analysis)
	}
	if analysis.IncompleteStages[0].Stage != "dense" {
		t.Fatalf("wrong incomplete stage: %#v", analysis.IncompleteStages)
	}
	if len(analysis.TerminalEvents) != 2 || analysis.TerminalEvents[0] != "rag_retrieve_failed" ||
		analysis.TerminalEvents[1] != "model_call" {
		t.Fatalf("sub-stage completion must not become chain terminal: %#v", analysis.TerminalEvents)
	}
	if len(analysis.SlowStages) != 1 || len(analysis.CandidateFunnel) != 2 {
		t.Fatalf("timing/funnel missing: %#v", analysis)
	}
}

func TestRedactBearerAndJWT(t *testing.T) {
	value := Redact("Authorization=Bearer abc.def.ghi token eyJabcde.abcdef.abcdef")
	if strings.Contains(value, "abc.def.ghi") || strings.Contains(value, "eyJabcde") {
		t.Fatalf("credential shape leaked: %s", value)
	}
}

func entry(timestamp time.Time, line string) Entry {
	return Entry{Timestamp: timestamp, Line: line, Fields: ParseLogfmt(line)}
}
