package cli

import (
	"testing"
	"time"
)

func TestParseRange(t *testing.T) {
	now := time.Date(2026, 7, 25, 12, 0, 0, 0, time.UTC)
	start, end, err := parseRange("30m", "now", now)
	if err != nil || !start.Equal(now.Add(-30*time.Minute)) || !end.Equal(now) {
		t.Fatalf("duration range failed: %v %s %s", err, start, end)
	}
	start, end, err = parseRange("2026-07-25T10:00:00Z", "2026-07-25T11:00:00Z", now)
	if err != nil || end.Sub(start) != time.Hour {
		t.Fatalf("absolute range failed: %v %s %s", err, start, end)
	}
	for _, invalid := range []string{"0s", "32d", "tomorrow"} {
		if _, _, err := parseRange(invalid, "now", now); err == nil {
			t.Fatalf("expected invalid since: %s", invalid)
		}
	}
}

func TestRawLogQLValidationIsBoundedButContentAgnostic(t *testing.T) {
	if err := validateReadOnlyLogQL(`{job="app"} |= "delete request"`); err != nil {
		t.Fatalf("read-only endpoint may search ordinary words: %v", err)
	}
	for _, invalid := range []string{"", "\x00", string(make([]byte, 16_385))} {
		if err := validateReadOnlyLogQL(invalid); err == nil {
			t.Fatal("expected invalid LogQL")
		}
	}
}
