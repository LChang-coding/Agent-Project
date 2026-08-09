package cn.bugstack.ai.infrastructure.observability;

import cn.bugstack.ai.domain.observability.adapter.port.TraceLogQueryPort;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Grafana datasource proxy 的只读查询协议测试。 */
public class GrafanaTraceLogQueryAdapterTest {

    private HttpServer server;
    private final AtomicReference<CapturedRequest> request = new AtomicReference<>();
    private volatile byte[] responseBody;

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/datasources/proxy/uid/loki/loki/api/v1/query_range", this::handle);
        responseBody = successResponse(
                "traceId=trace-current tenantId=tenant-a password=super-secret event=done");
        server.start();
    }

    @After
    public void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    public void buildsServerControlledQueryAndRedactsReturnedSecrets() {
        GrafanaTraceLogQueryAdapter adapter = adapter("reader", "secret", 32_000);

        TraceLogQueryPort.QueryResult result = adapter.query(
                new TraceLogQueryPort.QueryCommand("tenant-a", "trace-current", 30, 25));

        Assert.assertEquals(1, result.entries().size());
        Assert.assertFalse(result.truncated());
        Assert.assertTrue(result.entries().get(0).line().contains("password=[REDACTED]"));
        Assert.assertFalse(result.entries().get(0).line().contains("super-secret"));
        Assert.assertEquals("GET", request.get().method());
        Assert.assertTrue(request.get().rawQuery().contains("limit=26"));
        Assert.assertTrue(request.get().decodedQuery().contains("traceId=trace-current"));
        Assert.assertTrue(request.get().decodedQuery().contains("tenantId=\"tenant-a\""));
        Assert.assertFalse(request.get().decodedQuery().contains("delete"));
        Assert.assertTrue(request.get().authorization().startsWith("Basic "));
        Assert.assertEquals("reader:secret", new String(Base64.getDecoder().decode(
                request.get().authorization().substring("Basic ".length())), StandardCharsets.UTF_8));
    }

    @Test
    public void returnsNewestRequestedLinesAndEnforcesTotalCharacterBudget() {
        responseBody = streamsResponse(new String[][]{
                {"1786240801000000000", "oldest"},
                {"1786240802000000000", "middle-secret"},
                {"1786240803000000000", "newest-secret"}
        });
        GrafanaTraceLogQueryAdapter adapter = adapter("", "", 10);

        TraceLogQueryPort.QueryResult result = adapter.query(
                new TraceLogQueryPort.QueryCommand("tenant-a", "trace-current", 30, 2));

        Assert.assertTrue(request.get().rawQuery().contains("limit=3"));
        Assert.assertTrue(result.truncated());
        Assert.assertEquals(1, result.entries().size());
        Assert.assertEquals("newest-sec", result.entries().get(0).line());
        Assert.assertTrue(result.entries().stream().mapToInt(entry -> entry.line().length()).sum() <= 10);
    }

    @Test
    public void redactsJsonColonBasicCookieAndDsnCredentials() {
        responseBody = successResponse("{\"password\":\"json-secret\",\"api_key\":\"key-secret\"} "
                + "Authorization: Basic dXNlcjpwYXNz dsn=postgres://dbuser:dbpass@db/internal "
                + "Cookie: session=browser-secret");
        GrafanaTraceLogQueryAdapter adapter = adapter("", "", 32_000);

        String line = adapter.query(new TraceLogQueryPort.QueryCommand(
                "tenant-a", "trace-current", 30, 10)).entries().get(0).line();

        Assert.assertFalse(line.contains("json-secret"));
        Assert.assertFalse(line.contains("key-secret"));
        Assert.assertFalse(line.contains("dXNlcjpwYXNz"));
        Assert.assertFalse(line.contains("dbpass"));
        Assert.assertFalse(line.contains("browser-secret"));
    }

    @Test
    public void rejectsMalformedLokiStructures() {
        responseBody = "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\"}}"
                .getBytes(StandardCharsets.UTF_8);
        assertCode("TRACE_LOG_RESPONSE_INVALID", () -> adapter("", "", 32_000).query(
                new TraceLogQueryPort.QueryCommand("tenant-a", "trace-current", 30, 10)));

        responseBody = ("{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":["
                + "{\"values\":[[123,{\"line\":\"not-text\"}]]}]}}")
                .getBytes(StandardCharsets.UTF_8);
        assertCode("TRACE_LOG_RESPONSE_INVALID", () -> adapter("", "", 32_000).query(
                new TraceLogQueryPort.QueryCommand("tenant-a", "trace-current", 30, 10)));
    }

    @Test
    public void rejectsUnsafeAuthenticationAndGrafanaUris() {
        assertCode("TRACE_LOG_CONFIG_INVALID", () -> adapter("reader", "", 32_000).query(
                new TraceLogQueryPort.QueryCommand("tenant-a", "trace-current", 30, 10)));
        assertInvalidConfig("http://grafana.internal", "reader", "secret");
        assertInvalidConfig("http://127.attacker.example", "reader", "secret");
        assertInvalidConfig("https://reader:secret@grafana.internal", "", "");
        assertInvalidConfig("https://grafana.internal?target=other", "", "");
        assertInvalidConfig("https://grafana.internal#fragment", "", "");
    }

    private GrafanaTraceLogQueryAdapter adapter(String username, String password, int maxModelCharacters) {
        return configured(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                username, password, maxModelCharacters);
    }

    private void assertInvalidConfig(String uri, String username, String password) {
        GrafanaTraceLogQueryAdapter adapter = configured(URI.create(uri), username, password, 32_000);
        assertCode("TRACE_LOG_CONFIG_INVALID", () -> adapter.query(
                new TraceLogQueryPort.QueryCommand("tenant-a", "trace-current", 30, 10)));
    }

    private GrafanaTraceLogQueryAdapter configured(URI uri, String username, String password,
                                                   int maxModelCharacters) {
        return new GrafanaTraceLogQueryAdapter(uri, "loki", username, password,
                "{job=\"ai-agent-scaffold\"}", Duration.ofSeconds(2), 64 * 1024, maxModelCharacters,
                new ObjectMapper(), HttpClient.newHttpClient());
    }

    private void handle(HttpExchange exchange) throws IOException {
        request.set(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getRawQuery(),
                java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8),
                exchange.getRequestHeaders().getFirst("Authorization")));
        byte[] body = responseBody;
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private byte[] successResponse(String line) {
        return streamsResponse(new String[][]{{"1786240803000000000", line}});
    }

    private byte[] streamsResponse(String[][] values) {
        try {
            List<List<String>> pairs = java.util.Arrays.stream(values)
                    .map(value -> List.of(value[0], value[1])).toList();
            return new ObjectMapper().writeValueAsBytes(Map.of("status", "success", "data",
                    Map.of("resultType", "streams", "result", List.of(
                            Map.of("stream", Map.of("job", "ai-agent-scaffold"), "values", pairs)))));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private void assertCode(String code, Runnable action) {
        try {
            action.run();
            Assert.fail("预期日志适配器拒绝非法请求或响应");
        } catch (AppException exception) {
            Assert.assertEquals(code, exception.getCode());
        }
    }

    private record CapturedRequest(String method, String rawQuery, String decodedQuery, String authorization) {
    }
}
