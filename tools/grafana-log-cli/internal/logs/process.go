// Package logs parses, redacts, orders, and diagnoses Loki log entries.
package logs

import (
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/codeliu/ai-agent-scaffold/tools/grafana-log-cli/internal/grafana"
)

var (
	sensitiveAssignment = regexp.MustCompile(`(?i)\b(authorization|password|passwd|pwd|api[_-]?key|access[_-]?token|refresh[_-]?token|cookie|set-cookie|secret)=("[^"]*"|'[^']*'|[^\s]+)`)
	bearerToken         = regexp.MustCompile(`(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+`)
	jwtToken            = regexp.MustCompile(`\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\b`)
)

// Entry is a safe parsed log record.
type Entry struct {
	Timestamp time.Time         `json:"timestamp"`
	Line      string            `json:"line"`
	Labels    map[string]string `json:"labels,omitempty"`
	Fields    map[string]string `json:"fields,omitempty"`
}

// Process redacts, parses, deduplicates, and stably orders raw entries.
func Process(raw []grafana.Entry) []Entry {
	result := make([]Entry, 0, len(raw))
	seen := make(map[string]struct{})
	for _, value := range raw {
		line := Redact(value.Line)
		labels := redactMap(value.Labels)
		key := value.Timestamp.Format(time.RFC3339Nano) + "\x00" + canonical(labels) + "\x00" + line
		if _, exists := seen[key]; exists {
			continue
		}
		seen[key] = struct{}{}
		result = append(result, Entry{
			Timestamp: value.Timestamp.UTC(),
			Line:      line,
			Labels:    labels,
			Fields:    redactMap(ParseLogfmt(value.Line)),
		})
	}
	sort.SliceStable(result, func(left, right int) bool {
		if result[left].Timestamp.Equal(result[right].Timestamp) {
			return canonical(result[left].Labels)+result[left].Line <
				canonical(result[right].Labels)+result[right].Line
		}
		return result[left].Timestamp.Before(result[right].Timestamp)
	})
	return result
}

// Redact removes common credential shapes from arbitrary log text.
func Redact(value string) string {
	value = bearerToken.ReplaceAllString(value, "Bearer [REDACTED]")
	value = sensitiveAssignment.ReplaceAllString(value, "$1=[REDACTED]")
	value = jwtToken.ReplaceAllString(value, "[REDACTED_JWT]")
	return value
}

func redactMap(source map[string]string) map[string]string {
	if len(source) == 0 {
		return nil
	}
	result := make(map[string]string, len(source))
	for key, value := range source {
		if isSensitiveKey(key) {
			result[key] = "[REDACTED]"
		} else {
			result[key] = Redact(value)
		}
	}
	return result
}

func isSensitiveKey(key string) bool {
	normalized := strings.ToLower(strings.ReplaceAll(strings.ReplaceAll(key, "-", ""), "_", ""))
	switch normalized {
	case "authorization", "password", "passwd", "pwd", "apikey", "accesstoken",
		"refreshtoken", "cookie", "setcookie", "secret":
		return true
	default:
		return false
	}
}

// ParseLogfmt extracts well-formed key=value tokens from a mixed application
// line. Tokens before the first logfmt field are safely skipped.
func ParseLogfmt(line string) map[string]string {
	result := make(map[string]string)
	for index := 0; index < len(line); {
		for index < len(line) && unicode.IsSpace(rune(line[index])) {
			index++
		}
		if index >= len(line) {
			break
		}
		keyStart := index
		for index < len(line) && isKeyChar(line[index]) {
			index++
		}
		if index == keyStart || index >= len(line) || line[index] != '=' {
			for index < len(line) && !unicode.IsSpace(rune(line[index])) {
				index++
			}
			continue
		}
		key := line[keyStart:index]
		index++
		value, next, ok := parseValue(line, index)
		if !ok {
			break
		}
		result[key] = value
		index = next
	}
	return result
}

func parseValue(line string, index int) (string, int, bool) {
	if index >= len(line) {
		return "", index, true
	}
	if line[index] != '"' {
		start := index
		for index < len(line) && !unicode.IsSpace(rune(line[index])) {
			index++
		}
		return line[start:index], index, true
	}
	index++
	var builder strings.Builder
	for index < len(line) {
		character := line[index]
		if character == '"' {
			return builder.String(), index + 1, true
		}
		if character == '\\' && index+1 < len(line) {
			index++
			switch line[index] {
			case 'n':
				builder.WriteByte('\n')
			case 'r':
				builder.WriteByte('\r')
			case 't':
				builder.WriteByte('\t')
			default:
				builder.WriteByte(line[index])
			}
			index++
			continue
		}
		builder.WriteByte(character)
		index++
	}
	return "", index, false
}

func isKeyChar(value byte) bool {
	return value == '_' || value == '-' || value == '.' ||
		value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z' ||
		value >= '0' && value <= '9'
}

func canonical(values map[string]string) string {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	var builder strings.Builder
	for _, key := range keys {
		builder.WriteString(key)
		builder.WriteByte('=')
		builder.WriteString(values[key])
		builder.WriteByte('\x00')
	}
	return builder.String()
}

func int64Field(fields map[string]string, key string) (int64, bool) {
	value, exists := fields[key]
	if !exists {
		return 0, false
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	return parsed, err == nil
}
