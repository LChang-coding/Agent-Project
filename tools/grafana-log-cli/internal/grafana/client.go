// Package grafana provides a read-only Grafana and Loki datasource client.
package grafana

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"net/url"
	"path"
	"strconv"
	"strings"
	"time"

	"github.com/codeliu/ai-agent-scaffold/tools/grafana-log-cli/internal/config"
)

const (
	maxResponseBytes = 16 << 20
	maxAttempts      = 3
)

// APIError is a sanitized remote protocol failure.
type APIError struct {
	Code       string
	HTTPStatus int
	Retryable  bool
	Message    string
}

func (e *APIError) Error() string {
	if e.HTTPStatus > 0 {
		return fmt.Sprintf("%s: %s (HTTP %d)", e.Code, e.Message, e.HTTPStatus)
	}
	return fmt.Sprintf("%s: %s", e.Code, e.Message)
}

// Client calls only Grafana GET endpoints.
type Client struct {
	cfg        config.Config
	httpClient *http.Client
	userAgent  string
}

// NewClient creates a bounded HTTP client.
func NewClient(cfg config.Config, version string) *Client {
	dialer := &net.Dialer{
		Timeout:   cfg.RequestDuration(),
		KeepAlive: 30 * time.Second,
	}
	transport := &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		DialContext:           dialer.DialContext,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          20,
		MaxIdleConnsPerHost:   10,
		IdleConnTimeout:       60 * time.Second,
		TLSHandshakeTimeout:   cfg.RequestDuration(),
		ResponseHeaderTimeout: cfg.QueryDuration(),
		TLSClientConfig: &tls.Config{
			MinVersion:         tls.VersionTLS12,
			InsecureSkipVerify: cfg.TLSSkipVerify, // Explicit local-only setting from codex.md.
		},
	}
	return &Client{
		cfg: cfg,
		httpClient: &http.Client{
			Transport: transport,
			Timeout:   cfg.QueryDuration(),
		},
		userAgent: "grafana-log/" + version,
	}
}

// Health returns Grafana's public health information.
func (c *Client) Health(ctx context.Context) (Health, error) {
	var result Health
	if err := c.getJSON(ctx, "/api/health", nil, &result, false); err != nil {
		return Health{}, err
	}
	return result, nil
}

// Datasource verifies that the configured datasource is a proxy Loki datasource.
func (c *Client) Datasource(ctx context.Context) (Datasource, error) {
	var result Datasource
	endpoint := "/api/datasources/uid/" + url.PathEscape(c.cfg.DatasourceUID)
	if err := c.getJSON(ctx, endpoint, nil, &result, true); err != nil {
		return Datasource{}, err
	}
	if result.UID != c.cfg.DatasourceUID || result.Type != "loki" || result.Access != "proxy" {
		return Datasource{}, &APIError{
			Code:    "GRAFANA_DATASOURCE_INVALID",
			Message: "配置的数据源不是预期的 Loki proxy datasource",
		}
	}
	return result, nil
}

// QueryRange executes one Loki range query page.
func (c *Client) QueryRange(ctx context.Context, request QueryRequest) ([]Entry, error) {
	if err := request.Validate(); err != nil {
		return nil, err
	}
	values := url.Values{}
	values.Set("query", request.Query)
	values.Set("start", strconv.FormatInt(request.Start.UnixNano(), 10))
	values.Set("end", strconv.FormatInt(request.End.UnixNano(), 10))
	values.Set("limit", strconv.Itoa(request.Limit))
	values.Set("direction", request.Direction)
	endpoint := path.Join("/api/datasources/proxy/uid", c.cfg.DatasourceUID,
		"loki/api/v1/query_range")
	var response queryRangeResponse
	if err := c.getJSON(ctx, endpoint, values, &response, true); err != nil {
		return nil, err
	}
	if response.Status != "success" {
		return nil, &APIError{
			Code:    "LOKI_QUERY_FAILED",
			Message: "Loki 返回非成功业务状态",
		}
	}
	if response.Data.ResultType != "streams" {
		return nil, &APIError{
			Code:    "LOKI_RESULT_TYPE_UNSUPPORTED",
			Message: "日志查询返回了非 streams 结果",
		}
	}
	entries := make([]Entry, 0)
	for _, stream := range response.Data.Result {
		for _, pair := range stream.Values {
			if len(pair) != 2 {
				return nil, &APIError{Code: "LOKI_RESPONSE_INVALID", Message: "日志值结构非法"}
			}
			nanoseconds, err := strconv.ParseInt(pair[0], 10, 64)
			if err != nil || nanoseconds < 0 {
				return nil, &APIError{Code: "LOKI_RESPONSE_INVALID", Message: "日志时间戳非法"}
			}
			entries = append(entries, Entry{
				Timestamp: time.Unix(0, nanoseconds).UTC(),
				Line:      pair[1],
				Labels:    cloneMap(stream.Stream),
			})
		}
	}
	return entries, nil
}

// QueryRangeAll performs timestamp-based pagination with deduplication and a
// hard result bound. Loki has no opaque cursor for log streams, so a page that
// cannot advance at one timestamp fails explicitly instead of dropping data.
func (c *Client) QueryRangeAll(ctx context.Context, request QueryRequest, maxLines int) ([]Entry, error) {
	if maxLines < 1 || maxLines > 100_000 {
		return nil, errors.New("maxLines 必须位于 1 到 100000")
	}
	seen := make(map[string]struct{})
	result := make([]Entry, 0, min(maxLines, request.Limit))
	cursor := request
	for len(result) < maxLines {
		remaining := maxLines - len(result)
		cursor.Limit = min(cursor.Limit, remaining)
		pageEntries, err := c.QueryRange(ctx, cursor)
		if err != nil {
			return nil, err
		}
		if len(pageEntries) == 0 {
			break
		}
		added := 0
		var boundary time.Time
		for _, entry := range pageEntries {
			key := entry.Timestamp.Format(time.RFC3339Nano) + "\x00" + canonicalLabels(entry.Labels) +
				"\x00" + entry.Line
			if _, exists := seen[key]; exists {
				continue
			}
			seen[key] = struct{}{}
			result = append(result, entry)
			added++
			if len(result) == maxLines {
				break
			}
		}
		if request.Direction == "backward" {
			boundary = oldest(pageEntries)
		} else {
			boundary = newest(pageEntries)
		}
		if len(pageEntries) < cursor.Limit {
			break
		}
		if added == 0 {
			return nil, &APIError{
				Code:    "LOKI_PAGINATION_STALLED",
				Message: "同一时间戳日志超过分页能力，已停止以避免静默漏数或死循环",
			}
		}
		if request.Direction == "backward" {
			if !boundary.Before(cursor.End) {
				return nil, &APIError{Code: "LOKI_PAGINATION_STALLED", Message: "反向分页游标没有推进"}
			}
			cursor.End = boundary
		} else {
			if !boundary.After(cursor.Start) {
				return nil, &APIError{Code: "LOKI_PAGINATION_STALLED", Message: "正向分页游标没有推进"}
			}
			cursor.Start = boundary
		}
	}
	return result, nil
}

func (c *Client) getJSON(ctx context.Context, endpoint string, values url.Values,
	target any, authenticated bool) error {
	base, _ := url.Parse(c.cfg.GrafanaURL)
	base.Path = strings.TrimRight(base.Path, "/") + endpoint
	base.RawQuery = values.Encode()
	var last error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, base.String(), nil)
		if err != nil {
			return &APIError{Code: "GRAFANA_REQUEST_INVALID", Message: "无法构造 Grafana 请求"}
		}
		request.Header.Set("Accept", "application/json")
		request.Header.Set("User-Agent", c.userAgent)
		if authenticated {
			c.applyAuth(request)
		}
		response, err := c.httpClient.Do(request)
		if err != nil {
			last = classifyTransportError(err)
			if attempt < maxAttempts && isRetryable(last) {
				if err := waitRetry(ctx, attempt, 0); err != nil {
					return err
				}
				continue
			}
			return last
		}
		retryAfter := parseRetryAfter(response.Header.Get("Retry-After"))
		body, readErr := readBounded(response.Body)
		closeErr := response.Body.Close()
		if readErr != nil {
			return readErr
		}
		if closeErr != nil {
			return &APIError{Code: "GRAFANA_RESPONSE_CLOSE_FAILED", Message: "关闭响应失败"}
		}
		if response.StatusCode < 200 || response.StatusCode >= 300 {
			last = classifyHTTP(response.StatusCode)
			if attempt < maxAttempts && isRetryable(last) {
				if err := waitRetry(ctx, attempt, retryAfter); err != nil {
					return err
				}
				continue
			}
			return last
		}
		decoder := json.NewDecoder(strings.NewReader(string(body)))
		if err := decoder.Decode(target); err != nil {
			return &APIError{Code: "GRAFANA_RESPONSE_INVALID", Message: "Grafana 返回非预期 JSON"}
		}
		return nil
	}
	return last
}

func (c *Client) applyAuth(request *http.Request) {
	if c.cfg.AuthMode == "basic" {
		request.SetBasicAuth(c.cfg.Username, c.cfg.Password)
		return
	}
	request.Header.Set("Authorization", "Bearer "+c.cfg.Token)
}

func readBounded(reader io.Reader) ([]byte, error) {
	data, err := io.ReadAll(io.LimitReader(reader, maxResponseBytes+1))
	if err != nil {
		return nil, &APIError{Code: "GRAFANA_RESPONSE_READ_FAILED", Message: "读取 Grafana 响应失败"}
	}
	if len(data) > maxResponseBytes {
		return nil, &APIError{Code: "GRAFANA_RESPONSE_TOO_LARGE", Message: "Grafana 响应超过 16 MiB 上限"}
	}
	return data, nil
}

func classifyTransportError(err error) error {
	if errors.Is(err, context.DeadlineExceeded) {
		return &APIError{Code: "GRAFANA_TIMEOUT", Retryable: true, Message: "Grafana 请求超时"}
	}
	return &APIError{Code: "GRAFANA_UNAVAILABLE", Retryable: true, Message: "Grafana 网络连接失败"}
}

func classifyHTTP(status int) error {
	switch status {
	case http.StatusUnauthorized:
		return &APIError{Code: "GRAFANA_UNAUTHORIZED", HTTPStatus: status, Message: "Grafana 认证失败"}
	case http.StatusForbidden:
		return &APIError{Code: "GRAFANA_FORBIDDEN", HTTPStatus: status, Message: "Grafana 拒绝访问数据源"}
	case http.StatusNotFound:
		return &APIError{Code: "GRAFANA_NOT_FOUND", HTTPStatus: status, Message: "Grafana 接口或数据源不存在"}
	case http.StatusTooManyRequests:
		return &APIError{Code: "GRAFANA_RATE_LIMITED", HTTPStatus: status, Retryable: true,
			Message: "Grafana 请求被限流"}
	default:
		return &APIError{
			Code:       "GRAFANA_HTTP_ERROR",
			HTTPStatus: status,
			Retryable:  status >= 500,
			Message:    "Grafana 返回错误状态",
		}
	}
}

func isRetryable(err error) bool {
	var apiError *APIError
	return errors.As(err, &apiError) && apiError.Retryable
}

func waitRetry(ctx context.Context, attempt int, retryAfter time.Duration) error {
	delay := retryAfter
	if delay <= 0 {
		delay = time.Duration(math.Pow(2, float64(attempt-1))) * 200 * time.Millisecond
	}
	if delay > 3*time.Second {
		delay = 3 * time.Second
	}
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return &APIError{Code: "GRAFANA_CANCELLED", Message: "Grafana 请求已取消"}
	case <-timer.C:
		return nil
	}
}

func parseRetryAfter(raw string) time.Duration {
	seconds, err := strconv.Atoi(raw)
	if err != nil || seconds < 0 {
		return 0
	}
	return time.Duration(seconds) * time.Second
}

func oldest(entries []Entry) time.Time {
	value := entries[0].Timestamp
	for _, entry := range entries[1:] {
		if entry.Timestamp.Before(value) {
			value = entry.Timestamp
		}
	}
	return value
}

func newest(entries []Entry) time.Time {
	value := entries[0].Timestamp
	for _, entry := range entries[1:] {
		if entry.Timestamp.After(value) {
			value = entry.Timestamp
		}
	}
	return value
}

func canonicalLabels(labels map[string]string) string {
	keys := make([]string, 0, len(labels))
	for key := range labels {
		keys = append(keys, key)
	}
	sortStrings(keys)
	var builder strings.Builder
	for _, key := range keys {
		builder.WriteString(key)
		builder.WriteByte('=')
		builder.WriteString(labels[key])
		builder.WriteByte('\x00')
	}
	return builder.String()
}

func cloneMap(source map[string]string) map[string]string {
	result := make(map[string]string, len(source))
	for key, value := range source {
		result[key] = value
	}
	return result
}

func sortStrings(values []string) {
	for index := 1; index < len(values); index++ {
		for current := index; current > 0 && values[current] < values[current-1]; current-- {
			values[current], values[current-1] = values[current-1], values[current]
		}
	}
}

// Health is the safe Grafana health payload.
type Health struct {
	Database string `json:"database"`
	Version  string `json:"version"`
	Commit   string `json:"commit"`
}

// Datasource contains non-sensitive datasource metadata.
type Datasource struct {
	ID        int64  `json:"id"`
	UID       string `json:"uid"`
	Name      string `json:"name"`
	Type      string `json:"type"`
	Access    string `json:"access"`
	URL       string `json:"url"`
	IsDefault bool   `json:"isDefault"`
}

// QueryRequest describes a bounded Loki range query.
type QueryRequest struct {
	Query     string
	Start     time.Time
	End       time.Time
	Limit     int
	Direction string
}

// Validate checks query bounds.
func (request QueryRequest) Validate() error {
	if strings.TrimSpace(request.Query) == "" || len(request.Query) > 16_384 {
		return errors.New("LogQL 不能为空且不能超过 16384 字符")
	}
	if request.Start.IsZero() || request.End.IsZero() || !request.Start.Before(request.End) {
		return errors.New("查询时间范围非法")
	}
	if request.End.Sub(request.Start) > 31*24*time.Hour {
		return errors.New("单次查询时间范围不能超过 31 天")
	}
	if request.Limit < 1 || request.Limit > 5_000 {
		return errors.New("单页 limit 必须位于 1 到 5000")
	}
	if request.Direction != "forward" && request.Direction != "backward" {
		return errors.New("direction 只支持 forward 或 backward")
	}
	return nil
}

// Entry is one raw Loki log entry.
type Entry struct {
	Timestamp time.Time         `json:"timestamp"`
	Line      string            `json:"line"`
	Labels    map[string]string `json:"labels"`
}

type queryRangeResponse struct {
	Status string `json:"status"`
	Data   struct {
		ResultType string `json:"resultType"`
		Result     []struct {
			Stream map[string]string `json:"stream"`
			Values [][]string        `json:"values"`
		} `json:"result"`
	} `json:"data"`
}
