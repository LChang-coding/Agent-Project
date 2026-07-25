// Package logql builds bounded LogQL expressions for trusted CLI commands.
package logql

import (
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"

	"github.com/codeliu/ai-agent-scaffold/tools/grafana-log-cli/internal/config"
)

var safeField = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]{0,63}$`)

// ExactField builds a query that first narrows by a literal token and then
// verifies the parsed logfmt field exactly.
func ExactField(selector, field, value string) (string, error) {
	if err := config.ValidateSelector(selector); err != nil {
		return "", err
	}
	if !safeField.MatchString(field) {
		return "", errors.New("日志字段名非法")
	}
	if value == "" || len(value) > 512 || strings.ContainsAny(value, "\r\n\x00") {
		return "", errors.New("日志字段值非法")
	}
	token := field + "=" + value
	return fmt.Sprintf(`%s |= %s | logfmt | %s=%s`,
		selector, quote(token), field, quote(value)), nil
}

// Search builds a safe LogQL pipeline from literal contains filters.
func Search(selector string, contains []string) (string, error) {
	if err := config.ValidateSelector(selector); err != nil {
		return "", err
	}
	query := selector
	for _, value := range contains {
		if value == "" {
			continue
		}
		if len(value) > 512 || strings.ContainsAny(value, "\r\n\x00") {
			return "", errors.New("搜索条件非法")
		}
		query += " |= " + quote(value)
	}
	return query, nil
}

func quote(value string) string {
	encoded, _ := json.Marshal(value)
	return string(encoded)
}
