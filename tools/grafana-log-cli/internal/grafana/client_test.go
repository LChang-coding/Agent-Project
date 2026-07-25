package grafana

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/codeliu/ai-agent-scaffold/tools/grafana-log-cli/internal/config"
)

func TestHealthDatasourceAndQueryUseReadOnlyGrafanaProxy(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		requests.Add(1)
		if request.Method != http.MethodGet {
			t.Errorf("only GET is allowed, got %s", request.Method)
		}
		switch request.URL.Path {
		case "/api/health":
			fmt.Fprint(writer, `{"version":"11.0.0","database":"ok"}`)
		case "/api/datasources/uid/loki":
			assertBasicAuth(t, request)
			fmt.Fprint(writer, `{"uid":"loki","type":"loki","access":"proxy","name":"Loki"}`)
		case "/api/datasources/proxy/uid/loki/loki/api/v1/query_range":
			assertBasicAuth(t, request)
			if request.URL.Query().Get("query") != `{job="app"}` ||
				request.URL.Query().Get("direction") != "forward" {
				t.Errorf("query parameters lost: %s", request.URL.RawQuery)
			}
			fmt.Fprint(writer, `{"status":"success","data":{"resultType":"streams","result":[`+
				`{"stream":{"job":"app"},"values":[["1000000000","event=started"]]}`+
				`]}}`)
		default:
			http.NotFound(writer, request)
		}
	}))
	defer server.Close()

	client := NewClient(testConfig(server.URL), "test")
	health, err := client.Health(context.Background())
	if err != nil || health.Version != "11.0.0" {
		t.Fatalf("health failed: %#v %v", health, err)
	}
	if _, err := client.Datasource(context.Background()); err != nil {
		t.Fatal(err)
	}
	entries, err := client.QueryRange(context.Background(), QueryRequest{
		Query: `{job="app"}`, Start: time.Unix(0, 0), End: time.Unix(2, 0),
		Limit: 100, Direction: "forward",
	})
	if err != nil || len(entries) != 1 || entries[0].Line != "event=started" {
		t.Fatalf("query failed: %#v %v", entries, err)
	}
	if requests.Load() != 3 {
		t.Fatalf("unexpected request count: %d", requests.Load())
	}
}

func TestDatasourceRejectsWrongType(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		fmt.Fprint(writer, `{"uid":"loki","type":"prometheus","access":"proxy"}`)
	}))
	defer server.Close()
	_, err := NewClient(testConfig(server.URL), "test").Datasource(context.Background())
	assertAPIError(t, err, "GRAFANA_DATASOURCE_INVALID")
}

func TestHTTPErrorClassificationAndBoundedRetry(t *testing.T) {
	t.Run("unauthorized", func(t *testing.T) {
		server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
			http.Error(writer, "credential details must not surface", http.StatusUnauthorized)
		}))
		defer server.Close()
		_, err := NewClient(testConfig(server.URL), "test").Datasource(context.Background())
		assertAPIError(t, err, "GRAFANA_UNAUTHORIZED")
		if strings.Contains(err.Error(), "credential details") {
			t.Fatal("remote body leaked")
		}
	})

	t.Run("retry", func(t *testing.T) {
		var attempts atomic.Int32
		server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
			if attempts.Add(1) < 3 {
				http.Error(writer, "busy", http.StatusServiceUnavailable)
				return
			}
			fmt.Fprint(writer, `{"version":"11.0.0"}`)
		}))
		defer server.Close()
		health, err := NewClient(testConfig(server.URL), "test").Health(context.Background())
		if err != nil || health.Version != "11.0.0" || attempts.Load() != 3 {
			t.Fatalf("retry failed: health=%#v attempts=%d err=%v", health, attempts.Load(), err)
		}
	})
}

func TestQueryRangeRejectsMalformedResponses(t *testing.T) {
	tests := map[string]string{
		"invalid-json": `{`,
		"wrong-type":   `{"status":"success","data":{"resultType":"matrix","result":[]}}`,
		"bad-timestamp": `{"status":"success","data":{"resultType":"streams","result":[` +
			`{"stream":{},"values":[["bad","line"]]}]}}`,
	}
	for name, body := range tests {
		t.Run(name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
				fmt.Fprint(writer, body)
			}))
			defer server.Close()
			_, err := NewClient(testConfig(server.URL), "test").QueryRange(context.Background(),
				QueryRequest{Query: `{job="app"}`, Start: time.Unix(0, 0), End: time.Unix(5, 0),
					Limit: 2, Direction: "forward"})
			if err == nil {
				t.Fatal("expected protocol error")
			}
		})
	}
}

func TestQueryRangeAllPaginatesAndDeduplicatesBoundary(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		start, _ := strconv.ParseInt(request.URL.Query().Get("start"), 10, 64)
		values := `[["1000000000","one"],["2000000000","two"]]`
		if start >= 2_000_000_000 {
			values = `[["2000000000","two"],["3000000000","three"]]`
		}
		if start >= 3_000_000_000 {
			values = `[["3000000000","three"]]`
		}
		fmt.Fprintf(writer, `{"status":"success","data":{"resultType":"streams","result":[`+
			`{"stream":{"job":"app"},"values":%s}]}}`, values)
	}))
	defer server.Close()
	entries, err := NewClient(testConfig(server.URL), "test").QueryRangeAll(context.Background(),
		QueryRequest{Query: `{job="app"}`, Start: time.Unix(0, 0), End: time.Unix(5, 0),
			Limit: 2, Direction: "forward"}, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 3 || entries[2].Line != "three" {
		t.Fatalf("pagination/dedup failed: %#v", entries)
	}
}

func TestQueryRangeAllDetectsStall(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		fmt.Fprint(writer, `{"status":"success","data":{"resultType":"streams","result":[`+
			`{"stream":{},"values":[["1000000000","one"],["1000000000","one"]]}`+
			`]}}`)
	}))
	defer server.Close()
	_, err := NewClient(testConfig(server.URL), "test").QueryRangeAll(context.Background(),
		QueryRequest{Query: `{job="app"}`, Start: time.Unix(0, 0), End: time.Unix(5, 0),
			Limit: 2, Direction: "forward"}, 10)
	assertAPIError(t, err, "LOKI_PAGINATION_STALLED")
}

func assertBasicAuth(t *testing.T, request *http.Request) {
	t.Helper()
	expected := "Basic " + base64.StdEncoding.EncodeToString([]byte("user:secret"))
	if request.Header.Get("Authorization") != expected {
		t.Errorf("missing basic auth")
	}
}

func assertAPIError(t *testing.T, err error, code string) {
	t.Helper()
	var apiError *APIError
	if !errors.As(err, &apiError) || apiError.Code != code {
		t.Fatalf("expected %s, got %v", code, err)
	}
}

func testConfig(serverURL string) config.Config {
	return config.Config{
		SchemaVersion: 1, GrafanaURL: serverURL, DatasourceUID: "loki",
		AuthMode: "basic", Username: "user", Password: "secret",
		DefaultSelector: `{job="app"}`, DefaultTimezone: "UTC",
		RequestTimeout: "1s", QueryTimeout: "5s",
	}
}
