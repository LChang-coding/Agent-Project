package logql

import (
	"strings"
	"testing"
)

func TestExactFieldEscapesUserInput(t *testing.T) {
	query, err := ExactField(`{job="app"}`, "traceId", `abc" |~ ".*`)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Count(query, "| logfmt |") != 1 {
		t.Fatalf("unexpected pipeline: %s", query)
	}
	if !strings.Contains(query, `traceId="abc\" |~ \".*"`) {
		t.Fatalf("value was not JSON escaped: %s", query)
	}
}

func TestBuildersRejectUnsafeInput(t *testing.T) {
	if _, err := ExactField(`{job="app"}`, "trace-id", "abc"); err == nil {
		t.Fatal("unsafe field should fail")
	}
	if _, err := ExactField(`{job="app"} |= "x"`, "traceId", "abc"); err == nil {
		t.Fatal("selector pipeline should fail")
	}
	if _, err := Search(`{job="app"}`, []string{"hello\nworld"}); err == nil {
		t.Fatal("newline should fail")
	}
}
