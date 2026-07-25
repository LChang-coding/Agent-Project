// Package cli defines the grafana-log command surface.
package cli

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/spf13/cobra"

	"github.com/codeliu/ai-agent-scaffold/tools/grafana-log-cli/internal/config"
	"github.com/codeliu/ai-agent-scaffold/tools/grafana-log-cli/internal/grafana"
	"github.com/codeliu/ai-agent-scaffold/tools/grafana-log-cli/internal/logql"
	"github.com/codeliu/ai-agent-scaffold/tools/grafana-log-cli/internal/logs"
	"github.com/codeliu/ai-agent-scaffold/tools/grafana-log-cli/internal/output"
)

// Version is replaced by -ldflags during release builds.
var Version = "dev"

type options struct {
	codexFile     string
	grafanaURL    string
	datasourceUID string
	selector      string
	timeout       time.Duration
	output        string
	since         string
	until         string
	limit         int
	pageSize      int
	direction     string
	expand        bool
}

type application struct {
	options options
	stdout  io.Writer
	stderr  io.Writer
	now     func() time.Time
}

// NewRootCommand constructs the CLI without global mutable state.
func NewRootCommand(stdout, stderr io.Writer) *cobra.Command {
	app := &application{
		stdout: stdout,
		stderr: stderr,
		now:    time.Now,
	}
	root := &cobra.Command{
		Use:           "grafana-log",
		Short:         "通过 Grafana 安全查询并诊断远端 Loki 日志",
		SilenceUsage:  true,
		SilenceErrors: true,
		Version:       Version,
	}
	flags := root.PersistentFlags()
	flags.StringVar(&app.options.codexFile, "codex-file", "", "本机 codex.md 路径")
	flags.StringVar(&app.options.grafanaURL, "grafana-url", "", "非敏感 Grafana URL 覆盖")
	flags.StringVar(&app.options.datasourceUID, "datasource-uid", "", "Loki datasource UID 覆盖")
	flags.StringVar(&app.options.selector, "selector", "", "Loki stream selector 覆盖")
	flags.DurationVar(&app.options.timeout, "timeout", 0, "查询总超时覆盖")
	flags.StringVarP(&app.options.output, "output", "o", "timeline",
		"输出格式: timeline|table|json|jsonl|raw")
	flags.StringVar(&app.options.since, "since", "30m", "开始时间，支持时长或 RFC3339")
	flags.StringVar(&app.options.until, "until", "now", "结束时间，支持 now 或 RFC3339")
	flags.IntVar(&app.options.limit, "limit", 5000, "最大日志行数")
	flags.IntVar(&app.options.pageSize, "page-size", 1000, "单页日志行数")
	flags.StringVar(&app.options.direction, "direction", "forward", "查询方向: forward|backward")

	root.AddCommand(app.doctorCommand())
	root.AddCommand(app.keyCommand("trace", "traceId", "按 TraceId 还原完整业务链路", true))
	root.AddCommand(app.keyCommand("run", "runId", "按 RunId 查询运行链路", false))
	root.AddCommand(app.keyCommand("session", "sessionId", "按 SessionId 查询会话日志", false))
	root.AddCommand(app.keyCommand("retrieval", "retrievalId", "按 RetrievalId 查询检索链路", false))
	root.AddCommand(app.keyCommand("ingest", "taskId", "按摄取 TaskId 查询摄取链路", false))
	root.AddCommand(app.searchCommand())
	root.AddCommand(app.queryCommand())
	return root
}

func (app *application) doctorCommand() *cobra.Command {
	return &cobra.Command{
		Use:   "doctor",
		Short: "检查本机配置、Grafana 和 Loki 数据源",
		Args:  cobra.NoArgs,
		RunE: func(command *cobra.Command, _ []string) error {
			cfg, client, err := app.runtime()
			if err != nil {
				return err
			}
			ctx, cancel := context.WithTimeout(command.Context(), cfg.QueryDuration())
			defer cancel()
			health, err := client.Health(ctx)
			if err != nil {
				return err
			}
			datasource, err := client.Datasource(ctx)
			if err != nil {
				return err
			}
			report := struct {
				Status           string `json:"status"`
				GrafanaVersion   string `json:"grafanaVersion"`
				DatasourceUID    string `json:"datasourceUid"`
				DatasourceType   string `json:"datasourceType"`
				DatasourceAccess string `json:"datasourceAccess"`
				ConfigSource     string `json:"configSource"`
			}{
				Status: "ok", GrafanaVersion: health.Version, DatasourceUID: datasource.UID,
				DatasourceType: datasource.Type, DatasourceAccess: datasource.Access,
				ConfigSource: cfg.CodexFile,
			}
			return output.RenderDoctor(app.stdout, app.options.output, report)
		},
	}
}

func (app *application) keyCommand(name, field, short string, trace bool) *cobra.Command {
	command := &cobra.Command{
		Use:   name + " <id>",
		Short: short,
		Args:  cobra.ExactArgs(1),
		RunE: func(command *cobra.Command, args []string) error {
			cfg, client, err := app.runtime()
			if err != nil {
				return err
			}
			query, err := logql.ExactField(cfg.DefaultSelector, field, args[0])
			if err != nil {
				return err
			}
			return app.executeQuery(command.Context(), cfg, client, query, trace)
		},
	}
	if trace {
		command.Flags().BoolVar(&app.options.expand, "expand", true,
			"无结果时自动扩大时间窗口，最大 72 小时")
	}
	return command
}

func (app *application) searchCommand() *cobra.Command {
	var keyword, level, event, stage, errorCode, tenantID string
	command := &cobra.Command{
		Use:   "search",
		Short: "按安全的字面条件搜索日志",
		Args:  cobra.NoArgs,
		RunE: func(command *cobra.Command, _ []string) error {
			cfg, client, err := app.runtime()
			if err != nil {
				return err
			}
			contains := []string{
				keyword,
				pair("level", strings.ToUpper(level)),
				pair("event", event),
				pair("stage", stage),
				pair("errorCode", errorCode),
				pair("tenantId", tenantID),
			}
			query, err := logql.Search(cfg.DefaultSelector, contains)
			if err != nil {
				return err
			}
			return app.executeQuery(command.Context(), cfg, client, query, false)
		},
	}
	flags := command.Flags()
	flags.StringVar(&keyword, "keyword", "", "日志必须包含的字面文本")
	flags.StringVar(&level, "level", "", "日志级别")
	flags.StringVar(&event, "event", "", "结构化事件")
	flags.StringVar(&stage, "stage", "", "业务阶段")
	flags.StringVar(&errorCode, "error-code", "", "安全错误码")
	flags.StringVar(&tenantID, "tenant-id", "", "租户ID")
	return command
}

func (app *application) queryCommand() *cobra.Command {
	return &cobra.Command{
		Use:   "query <logql>",
		Short: "显式执行原始只读 LogQL range query",
		Args:  cobra.ExactArgs(1),
		RunE: func(command *cobra.Command, args []string) error {
			cfg, client, err := app.runtime()
			if err != nil {
				return err
			}
			if err := validateReadOnlyLogQL(args[0]); err != nil {
				return err
			}
			return app.executeQuery(command.Context(), cfg, client, args[0], false)
		},
	}
}

func (app *application) runtime() (config.Config, *grafana.Client, error) {
	cfg, err := config.Load(config.RuntimeSettings{
		CodexFile: app.options.codexFile, GrafanaURL: app.options.grafanaURL,
		DatasourceUID: app.options.datasourceUID, Selector: app.options.selector,
		Timeout: app.options.timeout,
	})
	if err != nil {
		return config.Config{}, nil, err
	}
	if app.options.limit < 1 || app.options.limit > 100_000 {
		return config.Config{}, nil, errors.New("limit 必须位于 1 到 100000")
	}
	if app.options.pageSize < 1 || app.options.pageSize > 5_000 {
		return config.Config{}, nil, errors.New("page-size 必须位于 1 到 5000")
	}
	return cfg, grafana.NewClient(cfg, Version), nil
}

func (app *application) executeQuery(parent context.Context, cfg config.Config,
	client *grafana.Client, query string, allowExpand bool) error {
	start, end, err := parseRange(app.options.since, app.options.until, app.now())
	if err != nil {
		return err
	}
	location, err := time.LoadLocation(cfg.DefaultTimezone)
	if err != nil {
		return fmt.Errorf("默认时区不可用: %w", err)
	}
	queryTimeout := cfg.QueryDuration()
	ctx, cancel := context.WithTimeout(parent, queryTimeout)
	defer cancel()
	actualStart := start
	expanded := false
	var entries []grafana.Entry
	windows := []time.Duration{end.Sub(start)}
	if allowExpand && app.options.expand {
		for _, window := range []time.Duration{2 * time.Hour, 12 * time.Hour, 24 * time.Hour, 72 * time.Hour} {
			if window > windows[len(windows)-1] {
				windows = append(windows, window)
			}
		}
	}
	for index, window := range windows {
		actualStart = end.Add(-window)
		expanded = index > 0
		request := grafana.QueryRequest{
			Query: query, Start: actualStart, End: end,
			Limit: app.options.pageSize, Direction: app.options.direction,
		}
		entries, err = client.QueryRangeAll(ctx, request, app.options.limit)
		if err != nil {
			return err
		}
		if len(entries) > 0 {
			break
		}
	}
	processed := logs.Process(entries)
	result := output.Result{
		Query:    query,
		Range:    output.TimeRange{Start: actualStart, End: end},
		Expanded: expanded,
		Entries:  processed,
		Analysis: logs.Analyze(processed),
	}
	return output.Render(app.stdout, app.options.output, result, location)
}

func parseRange(since, until string, now time.Time) (time.Time, time.Time, error) {
	end := now
	if until != "now" {
		parsed, err := time.Parse(time.RFC3339, until)
		if err != nil {
			return time.Time{}, time.Time{}, errors.New("until 必须是 now 或 RFC3339")
		}
		end = parsed
	}
	if duration, err := time.ParseDuration(since); err == nil {
		if duration <= 0 || duration > 31*24*time.Hour {
			return time.Time{}, time.Time{}, errors.New("since 时长必须位于 0 到 31 天")
		}
		return end.Add(-duration), end, nil
	}
	start, err := time.Parse(time.RFC3339, since)
	if err != nil || !start.Before(end) || end.Sub(start) > 31*24*time.Hour {
		return time.Time{}, time.Time{}, errors.New("since 必须是有效时长或早于 until 的 RFC3339")
	}
	return start, end, nil
}

func pair(key, value string) string {
	if value == "" {
		return ""
	}
	return key + "=" + value
}

func validateReadOnlyLogQL(query string) error {
	trimmed := strings.TrimSpace(query)
	if trimmed == "" || len(trimmed) > 16_384 || strings.ContainsRune(trimmed, '\x00') {
		return errors.New("LogQL 非法")
	}
	return nil
}

// Execute runs the root command and returns a sanitized process exit code.
func Execute() int {
	root := NewRootCommand(os.Stdout, os.Stderr)
	if err := root.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, "grafana-log:", logs.Redact(err.Error()))
		var apiError *grafana.APIError
		if errors.As(err, &apiError) {
			switch apiError.Code {
			case "GRAFANA_UNAUTHORIZED", "GRAFANA_FORBIDDEN":
				return 3
			case "GRAFANA_TIMEOUT", "GRAFANA_UNAVAILABLE", "GRAFANA_RATE_LIMITED":
				return 4
			}
		}
		return 2
	}
	return 0
}

// ParseExitCode supports shell wrappers that need stable numeric results.
func ParseExitCode(value string) int {
	code, err := strconv.Atoi(value)
	if err != nil {
		return 2
	}
	return code
}
