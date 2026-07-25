package output

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/codeliu/ai-agent-scaffold/tools/grafana-log-cli/internal/logs"
)

func TestAllOutputFormats(t *testing.T) {
	entry := logs.Entry{
		Timestamp: time.Date(2026, 7, 25, 1, 2, 3, 0, time.UTC),
		Line:      `event=rag_completed eventName="RAG完成" stage=retrieval costMs=12`,
		Fields: map[string]string{
			"event": "rag_completed", "eventName": "RAG完成", "stage": "retrieval", "costMs": "12",
		},
	}
	result := Result{
		Query: `{job="app"}`, Range: TimeRange{Start: entry.Timestamp, End: entry.Timestamp},
		Entries: []logs.Entry{entry}, Analysis: logs.Analyze([]logs.Entry{entry}),
	}
	for _, format := range []string{"timeline", "table", "json", "jsonl", "raw"} {
		t.Run(format, func(t *testing.T) {
			var output bytes.Buffer
			if err := Render(&output, format, result, time.UTC); err != nil {
				t.Fatal(err)
			}
			if !strings.Contains(output.String(), "RAG完成") &&
				!strings.Contains(output.String(), "rag_completed") {
				t.Fatalf("missing event in %s output: %s", format, output.String())
			}
			if format == "json" {
				var decoded Result
				if err := json.Unmarshal(output.Bytes(), &decoded); err != nil {
					t.Fatal(err)
				}
			}
		})
	}
	if err := Render(&bytes.Buffer{}, "xml", result, time.UTC); err == nil {
		t.Fatal("unknown output must fail")
	}
}

func TestJSONLContract(t *testing.T) {
	var output bytes.Buffer
	result := Result{
		Query: `{job="app"}`, Entries: []logs.Entry{{Line: "safe"}},
		Analysis: logs.Analysis{EntryCount: 1},
	}
	if err := Render(&output, "jsonl", result, time.UTC); err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(output.String()), "\n")
	if len(lines) != 3 || !strings.Contains(lines[0], `"type":"meta"`) ||
		!strings.Contains(lines[2], `"type":"analysis"`) {
		t.Fatalf("unexpected jsonl contract: %s", output.String())
	}
}
