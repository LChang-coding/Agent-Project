// Package output renders safe logs for humans and agents.
package output

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"
	"text/tabwriter"
	"time"

	"github.com/codeliu/ai-agent-scaffold/tools/grafana-log-cli/internal/logs"
)

// Result is the stable command output contract.
type Result struct {
	Query    string        `json:"query"`
	Range    TimeRange     `json:"range"`
	Expanded bool          `json:"expanded"`
	Entries  []logs.Entry  `json:"entries"`
	Analysis logs.Analysis `json:"analysis"`
}

// TimeRange is the actual queried range.
type TimeRange struct {
	Start time.Time `json:"start"`
	End   time.Time `json:"end"`
}

// Render writes one requested format to stdout.
func Render(writer io.Writer, format string, result Result, location *time.Location) error {
	switch strings.ToLower(format) {
	case "timeline":
		return renderTimeline(writer, result, location)
	case "table":
		return renderTable(writer, result, location)
	case "json":
		return encodeJSON(writer, result)
	case "jsonl":
		return renderJSONL(writer, result)
	case "raw":
		for _, entry := range result.Entries {
			if _, err := fmt.Fprintln(writer, entry.Line); err != nil {
				return err
			}
		}
		return nil
	default:
		return errors.New("output 只支持 timeline、table、json、jsonl 或 raw")
	}
}

func renderTimeline(writer io.Writer, result Result, location *time.Location) error {
	analysis := result.Analysis
	fmt.Fprintf(writer, "查询范围: %s → %s", formatTime(result.Range.Start, location),
		formatTime(result.Range.End, location))
	if result.Expanded {
		fmt.Fprint(writer, "（自动扩大）")
	}
	fmt.Fprintln(writer)
	fmt.Fprintf(writer, "日志: %d  失败: %d  降级: %d  取消: %d  未闭合阶段: %d\n",
		analysis.EntryCount, len(analysis.Failures), len(analysis.Degradations),
		len(analysis.Cancellations), len(analysis.IncompleteStages))
	if len(analysis.SlowStages) > 0 {
		fmt.Fprint(writer, "最慢阶段: ")
		for index, stage := range analysis.SlowStages {
			if index >= 5 {
				break
			}
			if index > 0 {
				fmt.Fprint(writer, "，")
			}
			fmt.Fprintf(writer, "%s=%dms", stage.Stage, stage.CostMs)
		}
		fmt.Fprintln(writer)
	}
	if len(analysis.IncompleteStages) > 0 {
		fmt.Fprint(writer, "未闭合: ")
		for index, stage := range analysis.IncompleteStages {
			if index > 0 {
				fmt.Fprint(writer, "，")
			}
			fmt.Fprintf(writer, "%s×%d", stage.Stage, stage.UnclosedCount)
		}
		fmt.Fprintln(writer)
	}
	fmt.Fprintln(writer)
	for _, entry := range result.Entries {
		fields := entry.Fields
		message := first(fields["eventName"], fields["message"], fields["event"])
		if message == "" {
			message = compact(entry.Line, 220)
		}
		fmt.Fprintf(writer, "%s  %-7s", formatTime(entry.Timestamp, location),
			first(fields["level"], detectLevel(entry.Line), "-"))
		if stage := fields["stage"]; stage != "" {
			fmt.Fprintf(writer, " [%s]", stage)
		}
		fmt.Fprintf(writer, " %s", message)
		if outcome := fields["outcome"]; outcome != "" {
			fmt.Fprintf(writer, " outcome=%s", outcome)
		}
		if cost := fields["costMs"]; cost != "" {
			fmt.Fprintf(writer, " costMs=%s", cost)
		}
		if code := fields["errorCode"]; code != "" {
			fmt.Fprintf(writer, " errorCode=%s", code)
		}
		fmt.Fprintln(writer)
	}
	return nil
}

func renderTable(writer io.Writer, result Result, location *time.Location) error {
	table := tabwriter.NewWriter(writer, 0, 4, 2, ' ', 0)
	fmt.Fprintln(table, "时间\t级别\t阶段\t结果\t耗时(ms)\t事件")
	for _, entry := range result.Entries {
		fields := entry.Fields
		message := first(fields["eventName"], fields["message"], fields["event"], compact(entry.Line, 160))
		fmt.Fprintf(table, "%s\t%s\t%s\t%s\t%s\t%s\n",
			formatTime(entry.Timestamp, location),
			first(fields["level"], detectLevel(entry.Line), "-"),
			first(fields["stage"], "-"),
			first(fields["outcome"], "-"),
			first(fields["costMs"], "-"),
			strings.ReplaceAll(message, "\t", " "),
		)
	}
	return table.Flush()
}

func encodeJSON(writer io.Writer, value any) error {
	encoder := json.NewEncoder(writer)
	encoder.SetEscapeHTML(false)
	encoder.SetIndent("", "  ")
	return encoder.Encode(value)
}

func renderJSONL(writer io.Writer, result Result) error {
	encoder := json.NewEncoder(writer)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(map[string]any{
		"type": "meta", "query": result.Query, "range": result.Range, "expanded": result.Expanded,
	}); err != nil {
		return err
	}
	for _, entry := range result.Entries {
		if err := encoder.Encode(map[string]any{"type": "entry", "entry": entry}); err != nil {
			return err
		}
	}
	return encoder.Encode(map[string]any{"type": "analysis", "analysis": result.Analysis})
}

// RenderDoctor writes a credential-free connectivity report.
func RenderDoctor(writer io.Writer, format string, report any) error {
	switch strings.ToLower(format) {
	case "json", "jsonl":
		return encodeJSON(writer, report)
	default:
		value, err := json.Marshal(report)
		if err != nil {
			return err
		}
		var fields map[string]any
		if err := json.Unmarshal(value, &fields); err != nil {
			return err
		}
		table := tabwriter.NewWriter(writer, 0, 4, 2, ' ', 0)
		for _, key := range []string{"status", "grafanaVersion", "datasourceUid", "datasourceType",
			"datasourceAccess", "configSource"} {
			if field, exists := fields[key]; exists {
				fmt.Fprintf(table, "%s\t%v\n", key, field)
			}
		}
		return table.Flush()
	}
}

func formatTime(value time.Time, location *time.Location) string {
	if location == nil {
		location = time.Local
	}
	return value.In(location).Format("2006-01-02 15:04:05.000")
}

func first(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}

func compact(value string, limit int) string {
	value = strings.Join(strings.Fields(value), " ")
	if len([]rune(value)) <= limit {
		return value
	}
	runes := []rune(value)
	return string(runes[:limit]) + "…"
}

func detectLevel(line string) string {
	for _, level := range []string{"ERROR", "WARN", "INFO", "DEBUG", "TRACE"} {
		if strings.Contains(line, " "+level+" ") {
			return level
		}
	}
	return ""
}

// Int safely formats an integer for extension renderers.
func Int(value int64) string {
	return strconv.FormatInt(value, 10)
}
