// Package config loads the CLI's bounded local configuration.
package config

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/spf13/viper"
)

const (
	beginMarker = "<!-- grafana-log-cli-config-v1:begin -->"
	endMarker   = "<!-- grafana-log-cli-config-v1:end -->"
)

// Config contains Grafana access settings. Authentication values are loaded only
// from the bounded codex.md block and are never accepted as CLI flags.
type Config struct {
	SchemaVersion   int    `json:"schemaVersion"`
	GrafanaURL      string `json:"grafanaUrl"`
	DatasourceUID   string `json:"datasourceUid"`
	AuthMode        string `json:"authMode"`
	Username        string `json:"username"`
	Password        string `json:"password"`
	Token           string `json:"token,omitempty"`
	DefaultSelector string `json:"defaultSelector"`
	DefaultTimezone string `json:"defaultTimezone"`
	RequestTimeout  string `json:"requestTimeout"`
	QueryTimeout    string `json:"queryTimeout"`
	TLSSkipVerify   bool   `json:"tlsSkipVerify"`
	CodexFile       string `json:"-"`
}

// RuntimeSettings are non-sensitive overrides that may come from flags or
// GRAFANA_LOG_* environment variables.
type RuntimeSettings struct {
	CodexFile     string
	GrafanaURL    string
	DatasourceUID string
	Selector      string
	Timeout       time.Duration
}

// Load reads exactly one structured configuration block from codex.md.
func Load(settings RuntimeSettings) (Config, error) {
	codexFile, err := resolveCodexFile(settings.CodexFile)
	if err != nil {
		return Config{}, err
	}
	data, err := os.ReadFile(codexFile)
	if err != nil {
		return Config{}, fmt.Errorf("读取本机 codex.md 失败: %w", err)
	}
	payload, err := extractBlock(data)
	if err != nil {
		return Config{}, err
	}
	var cfg Config
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&cfg); err != nil {
		return Config{}, fmt.Errorf("grafana-log-cli 配置块不是合法 JSON: %w", err)
	}
	cfg.CodexFile = codexFile
	applyNonSensitiveOverrides(&cfg, settings)
	if err := cfg.Validate(); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

func extractBlock(data []byte) ([]byte, error) {
	text := string(data)
	begin := strings.Index(text, beginMarker)
	if begin < 0 {
		return nil, errors.New("codex.md 缺少 grafana-log-cli-config-v1 配置块")
	}
	afterBegin := text[begin+len(beginMarker):]
	if strings.Contains(afterBegin, beginMarker) {
		return nil, errors.New("codex.md 包含重复的 grafana-log-cli 配置块")
	}
	end := strings.Index(afterBegin, endMarker)
	if end < 0 {
		return nil, errors.New("codex.md 的 grafana-log-cli 配置块没有结束标记")
	}
	block := strings.TrimSpace(afterBegin[:end])
	if !strings.HasPrefix(block, "```json") || !strings.HasSuffix(block, "```") {
		return nil, errors.New("grafana-log-cli 配置必须位于 json fenced code block 中")
	}
	block = strings.TrimSpace(strings.TrimSuffix(strings.TrimPrefix(block, "```json"), "```"))
	if block == "" {
		return nil, errors.New("grafana-log-cli 配置块为空")
	}
	return []byte(block), nil
}

func resolveCodexFile(explicit string) (string, error) {
	if explicit != "" {
		return absoluteExisting(explicit)
	}
	if value := os.Getenv("GRAFANA_LOG_CODEX_FILE"); value != "" {
		return absoluteExisting(value)
	}
	current, err := os.Getwd()
	if err != nil {
		return "", fmt.Errorf("读取当前目录失败: %w", err)
	}
	for {
		candidate := filepath.Join(current, "codex.md")
		if info, statErr := os.Stat(candidate); statErr == nil && !info.IsDir() {
			return filepath.Abs(candidate)
		}
		parent := filepath.Dir(current)
		if parent == current {
			break
		}
		current = parent
	}
	return "", errors.New("未找到 codex.md；请在项目目录运行或设置 GRAFANA_LOG_CODEX_FILE")
}

func absoluteExisting(path string) (string, error) {
	absolute, err := filepath.Abs(path)
	if err != nil {
		return "", fmt.Errorf("解析 codex.md 路径失败: %w", err)
	}
	info, err := os.Stat(absolute)
	if err != nil {
		return "", fmt.Errorf("codex.md 不可访问: %w", err)
	}
	if info.IsDir() {
		return "", errors.New("codex.md 路径不能是目录")
	}
	return absolute, nil
}

func applyNonSensitiveOverrides(cfg *Config, settings RuntimeSettings) {
	v := viper.New()
	v.SetEnvPrefix("GRAFANA_LOG")
	v.SetEnvKeyReplacer(strings.NewReplacer("-", "_"))
	v.AutomaticEnv()
	v.SetDefault("grafana_url", cfg.GrafanaURL)
	v.SetDefault("datasource_uid", cfg.DatasourceUID)
	v.SetDefault("selector", cfg.DefaultSelector)
	if settings.GrafanaURL != "" {
		v.Set("grafana_url", settings.GrafanaURL)
	}
	if settings.DatasourceUID != "" {
		v.Set("datasource_uid", settings.DatasourceUID)
	}
	if settings.Selector != "" {
		v.Set("selector", settings.Selector)
	}
	cfg.GrafanaURL = strings.TrimRight(v.GetString("grafana_url"), "/")
	cfg.DatasourceUID = v.GetString("datasource_uid")
	cfg.DefaultSelector = v.GetString("selector")
	if settings.Timeout > 0 {
		cfg.QueryTimeout = settings.Timeout.String()
	}
}

// Validate rejects ambiguous or unsafe runtime settings.
func (cfg Config) Validate() error {
	if cfg.SchemaVersion != 1 {
		return fmt.Errorf("不支持的 grafana-log-cli 配置版本: %d", cfg.SchemaVersion)
	}
	parsed, err := url.Parse(cfg.GrafanaURL)
	if err != nil || parsed.Scheme == "" || parsed.Host == "" || parsed.User != nil {
		return errors.New("grafanaUrl 必须是无内嵌凭据的绝对 HTTP(S) URL")
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return errors.New("grafanaUrl 只支持 http 或 https")
	}
	if strings.ContainsAny(cfg.DatasourceUID, "/?#\r\n") || cfg.DatasourceUID == "" {
		return errors.New("datasourceUid 非法")
	}
	if err := ValidateSelector(cfg.DefaultSelector); err != nil {
		return err
	}
	switch cfg.AuthMode {
	case "basic":
		if cfg.Username == "" || cfg.Password == "" {
			return errors.New("basic 认证缺少用户名或密码")
		}
	case "bearer":
		if cfg.Token == "" {
			return errors.New("bearer 认证缺少 token")
		}
	default:
		return errors.New("authMode 只支持 basic 或 bearer")
	}
	for name, raw := range map[string]string{
		"requestTimeout": cfg.RequestTimeout,
		"queryTimeout":   cfg.QueryTimeout,
	} {
		value, parseErr := time.ParseDuration(raw)
		if parseErr != nil || value < time.Second || value > 5*time.Minute {
			return fmt.Errorf("%s 必须位于 1s 到 5m", name)
		}
	}
	return nil
}

// ValidateSelector accepts one bounded Loki stream selector without pipelines.
func ValidateSelector(value string) error {
	trimmed := strings.TrimSpace(value)
	if len(trimmed) < 2 || len(trimmed) > 2048 || trimmed[0] != '{' || trimmed[len(trimmed)-1] != '}' {
		return errors.New("selector 必须是单个 Loki stream selector")
	}
	if strings.ContainsAny(trimmed, "\r\n|") {
		return errors.New("selector 不能包含换行或 LogQL pipeline")
	}
	return nil
}

// RequestDuration returns the configured short HTTP timeout.
func (cfg Config) RequestDuration() time.Duration {
	value, _ := time.ParseDuration(cfg.RequestTimeout)
	return value
}

// QueryDuration returns the configured query timeout.
func (cfg Config) QueryDuration() time.Duration {
	value, _ := time.ParseDuration(cfg.QueryTimeout)
	return value
}
