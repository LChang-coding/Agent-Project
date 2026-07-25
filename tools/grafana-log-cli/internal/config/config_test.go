package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestLoadBoundedConfigAndOverrides(t *testing.T) {
	t.Setenv("GRAFANA_LOG_GRAFANA_URL", "https://override.example")
	t.Setenv("GRAFANA_LOG_DATASOURCE_UID", "override-loki")
	t.Setenv("GRAFANA_LOG_SELECTOR", `{job="override"}`)
	t.Setenv("GRAFANA_LOG_PASSWORD", "must-not-be-used")

	file := writeCodex(t, validDocument())
	cfg, err := Load(RuntimeSettings{CodexFile: file, Timeout: 45 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	if cfg.GrafanaURL != "https://override.example" || cfg.DatasourceUID != "override-loki" {
		t.Fatalf("non-sensitive overrides not applied: %#v", cfg)
	}
	if cfg.DefaultSelector != `{job="override"}` || cfg.QueryTimeout != "45s" {
		t.Fatalf("selector/timeout overrides not applied: %#v", cfg)
	}
	if cfg.Password != "local-secret" {
		t.Fatal("authentication must only come from codex.md")
	}
	if !filepath.IsAbs(cfg.CodexFile) {
		t.Fatal("config source must be absolute")
	}
}

func TestLoadRejectsMalformedOrAmbiguousBlocks(t *testing.T) {
	tests := map[string]string{
		"missing":   "# no block",
		"duplicate": validDocument() + "\n" + validDocument(),
		"unknown": strings.Replace(validDocument(), `"tlsSkipVerify": false`,
			`"tlsSkipVerify": false, "unexpected": true`, 1),
		"unfenced": beginMarker + `{"schemaVersion":1}` + endMarker,
	}
	for name, document := range tests {
		t.Run(name, func(t *testing.T) {
			_, err := Load(RuntimeSettings{CodexFile: writeCodex(t, document)})
			if err == nil {
				t.Fatal("expected config error")
			}
			if strings.Contains(err.Error(), "local-secret") {
				t.Fatal("error leaked credential")
			}
		})
	}
}

func TestValidateRejectsUnsafeSettings(t *testing.T) {
	cfg := validConfig()
	cfg.GrafanaURL = "https://user:pass@example.test"
	if err := cfg.Validate(); err == nil {
		t.Fatal("embedded credentials must be rejected")
	}
	cfg = validConfig()
	cfg.DefaultSelector = `{job="app"} |= "secret"`
	if err := cfg.Validate(); err == nil {
		t.Fatal("selector pipeline must be rejected")
	}
	cfg = validConfig()
	cfg.DatasourceUID = "../other"
	if err := cfg.Validate(); err == nil {
		t.Fatal("unsafe datasource uid must be rejected")
	}
}

func writeCodex(t *testing.T, content string) string {
	t.Helper()
	file := filepath.Join(t.TempDir(), "codex.md")
	if err := os.WriteFile(file, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return file
}

func validDocument() string {
	return `# Local
` + beginMarker + `
` + "```json" + `
{
  "schemaVersion": 1,
  "grafanaUrl": "https://grafana.example",
  "datasourceUid": "loki",
  "authMode": "basic",
  "username": "local-user",
  "password": "local-secret",
  "defaultSelector": "{job=\"app\"}",
  "defaultTimezone": "Asia/Shanghai",
  "requestTimeout": "2s",
  "queryTimeout": "30s",
  "tlsSkipVerify": false
}
` + "```" + `
` + endMarker
}

func validConfig() Config {
	return Config{
		SchemaVersion: 1, GrafanaURL: "https://grafana.example", DatasourceUID: "loki",
		AuthMode: "basic", Username: "user", Password: "secret",
		DefaultSelector: `{job="app"}`, DefaultTimezone: "Asia/Shanghai",
		RequestTimeout: "2s", QueryTimeout: "30s",
	}
}
