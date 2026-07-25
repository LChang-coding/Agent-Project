package logs

import (
	"sort"
	"strings"
	"time"
)

// Analysis summarizes one bounded log result without inventing root causes.
type Analysis struct {
	EntryCount       int               `json:"entryCount"`
	StartedAt        *time.Time        `json:"startedAt,omitempty"`
	EndedAt          *time.Time        `json:"endedAt,omitempty"`
	ObservedDuration string            `json:"observedDuration"`
	TraceIDs         []string          `json:"traceIds,omitempty"`
	RunIDs           []string          `json:"runIds,omitempty"`
	SessionIDs       []string          `json:"sessionIds,omitempty"`
	RetrievalIDs     []string          `json:"retrievalIds,omitempty"`
	TaskIDs          []string          `json:"taskIds,omitempty"`
	Failures         []Issue           `json:"failures,omitempty"`
	Degradations     []Issue           `json:"degradations,omitempty"`
	Cancellations    []Issue           `json:"cancellations,omitempty"`
	SlowStages       []StageTiming     `json:"slowStages,omitempty"`
	IncompleteStages []IncompleteStage `json:"incompleteStages,omitempty"`
	CandidateFunnel  []FunnelStep      `json:"candidateFunnel,omitempty"`
	TerminalEvents   []string          `json:"terminalEvents,omitempty"`
}

// Issue is one evidence-backed abnormal record.
type Issue struct {
	Timestamp time.Time `json:"timestamp"`
	Event     string    `json:"event,omitempty"`
	Stage     string    `json:"stage,omitempty"`
	ErrorCode string    `json:"errorCode,omitempty"`
	Message   string    `json:"message,omitempty"`
}

// StageTiming is one measured stage cost.
type StageTiming struct {
	Timestamp time.Time `json:"timestamp"`
	Stage     string    `json:"stage"`
	CostMs    int64     `json:"costMs"`
	Outcome   string    `json:"outcome,omitempty"`
}

// IncompleteStage indicates observed starts without matching terminal records.
type IncompleteStage struct {
	Stage         string `json:"stage"`
	UnclosedCount int    `json:"unclosedCount"`
}

// FunnelStep records candidate count movement.
type FunnelStep struct {
	Timestamp   time.Time `json:"timestamp"`
	Stage       string    `json:"stage"`
	InputCount  int64     `json:"inputCount"`
	OutputCount int64     `json:"outputCount"`
}

// Analyze derives an evidence-only summary from processed entries.
func Analyze(entries []Entry) Analysis {
	result := Analysis{EntryCount: len(entries), ObservedDuration: "0s"}
	if len(entries) == 0 {
		return result
	}
	started := entries[0].Timestamp
	ended := entries[len(entries)-1].Timestamp
	result.StartedAt = &started
	result.EndedAt = &ended
	result.ObservedDuration = ended.Sub(started).String()

	ids := map[string]map[string]struct{}{
		"traceId": {}, "runId": {}, "sessionId": {}, "retrievalId": {}, "taskId": {},
	}
	openStages := make(map[string]int)
	for _, entry := range entries {
		fields := entry.Fields
		for key := range ids {
			if value := fields[key]; value != "" {
				ids[key][value] = struct{}{}
			}
		}
		event := fields["event"]
		stage := fields["stage"]
		outcome := strings.ToLower(fields["outcome"])
		message := firstNonEmpty(fields["message"], fields["eventName"])
		errorCode := fields["errorCode"]
		level := strings.ToUpper(firstNonEmpty(fields["level"], detectLevel(entry.Line)))

		issue := Issue{
			Timestamp: entry.Timestamp,
			Event:     event,
			Stage:     stage,
			ErrorCode: errorCode,
			Message:   message,
		}
		if errorCode != "" || level == "ERROR" || outcome == "failed" || fields["success"] == "false" {
			result.Failures = append(result.Failures, issue)
		}
		if fields["degraded"] == "true" || outcome == "degraded" ||
			strings.Contains(strings.ToLower(event), "degraded") {
			result.Degradations = append(result.Degradations, issue)
		}
		if outcome == "cancelled" || strings.Contains(strings.ToLower(event), "cancel") ||
			strings.Contains(message, "取消") {
			result.Cancellations = append(result.Cancellations, issue)
		}
		if cost, ok := int64Field(fields, "costMs"); ok && cost >= 0 && stage != "" {
			result.SlowStages = append(result.SlowStages, StageTiming{
				Timestamp: entry.Timestamp,
				Stage:     stage,
				CostMs:    cost,
				Outcome:   outcome,
			})
		}
		input, hasInput := int64Field(fields, "inputCount")
		output, hasOutput := int64Field(fields, "outputCount")
		if stage != "" && hasInput && hasOutput {
			result.CandidateFunnel = append(result.CandidateFunnel, FunnelStep{
				Timestamp: entry.Timestamp,
				Stage:     stage, InputCount: input, OutputCount: output,
			})
		}
		if stage != "" {
			switch {
			case outcome == "started" || strings.HasSuffix(event, "_started"):
				openStages[stage]++
			case isStageTerminal(event, outcome, message):
				if openStages[stage] > 0 {
					openStages[stage]--
				}
			}
		}
		if event == "model_call" && openStages["provider_request"] > 0 {
			openStages["provider_request"]--
		}
		if isTerminalEvent(event) {
			result.TerminalEvents = append(result.TerminalEvents, event)
		}
	}
	result.TraceIDs = sortedSet(ids["traceId"])
	result.RunIDs = sortedSet(ids["runId"])
	result.SessionIDs = sortedSet(ids["sessionId"])
	result.RetrievalIDs = sortedSet(ids["retrievalId"])
	result.TaskIDs = sortedSet(ids["taskId"])
	sort.SliceStable(result.SlowStages, func(left, right int) bool {
		if result.SlowStages[left].CostMs == result.SlowStages[right].CostMs {
			return result.SlowStages[left].Timestamp.Before(result.SlowStages[right].Timestamp)
		}
		return result.SlowStages[left].CostMs > result.SlowStages[right].CostMs
	})
	if len(result.SlowStages) > 10 {
		result.SlowStages = result.SlowStages[:10]
	}
	for stage, count := range openStages {
		if count > 0 {
			result.IncompleteStages = append(result.IncompleteStages,
				IncompleteStage{Stage: stage, UnclosedCount: count})
		}
	}
	sort.Slice(result.IncompleteStages, func(left, right int) bool {
		return result.IncompleteStages[left].Stage < result.IncompleteStages[right].Stage
	})
	result.TerminalEvents = uniqueStrings(result.TerminalEvents)
	return result
}

func isTerminalOutcome(value string) bool {
	switch value {
	case "completed", "success", "failed", "cancelled", "skipped", "degraded":
		return true
	default:
		return false
	}
}

func isTerminalEvent(event string) bool {
	switch event {
	case "rag_retrieve", "model_call":
		return true
	}
	for _, suffix := range []string{"_completed", "_failed", "_cancelled"} {
		if strings.HasSuffix(event, suffix) {
			return true
		}
	}
	return false
}

func isStageTerminal(event, outcome, message string) bool {
	if isTerminalOutcome(outcome) || strings.Contains(message, "完成") {
		return true
	}
	return strings.HasSuffix(event, "_completed") || strings.HasSuffix(event, "_failed") ||
		strings.HasSuffix(event, "_cancelled")
}

func detectLevel(line string) string {
	for _, level := range []string{"ERROR", "WARN", "INFO", "DEBUG", "TRACE"} {
		if strings.Contains(line, " "+level+" ") || strings.HasPrefix(line, level+" ") {
			return level
		}
	}
	return ""
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}

func sortedSet(values map[string]struct{}) []string {
	result := make([]string, 0, len(values))
	for value := range values {
		result = append(result, value)
	}
	sort.Strings(result)
	return result
}

func uniqueStrings(values []string) []string {
	seen := make(map[string]struct{})
	result := make([]string, 0, len(values))
	for _, value := range values {
		if _, exists := seen[value]; exists {
			continue
		}
		seen[value] = struct{}{}
		result = append(result, value)
	}
	return result
}
