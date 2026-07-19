package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Benchmark 专用的并发安全 JWT 刷新器；凭据和响应正文绝不进入异常。 */
final class RefreshingLoginTokenProvider implements RagBenchmarkHttpClient.BearerTokenProvider {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI loginUri;
    private final String username;
    private final String password;
    private final Duration timeout;
    private final int maxResponseBytes;
    private volatile String token;

    RefreshingLoginTokenProvider(HttpClient httpClient, ObjectMapper objectMapper, URI baseUri,
                                 String token, String username, String password,
                                 Duration timeout, int maxResponseBytes) {
        if (httpClient == null || objectMapper == null || baseUri == null || blank(token)
                || blank(username) || blank(password) || timeout == null || timeout.isZero()
                || timeout.isNegative() || maxResponseBytes < 1024) {
            throw new IllegalArgumentException("benchmark刷新凭据参数非法");
        }
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.loginUri = URI.create(baseUri.toString().replaceAll("/+$", "") + "/v1/auth/login");
        this.token = token;
        this.username = username;
        this.password = password;
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public String currentToken() {
        return token;
    }

    @Override
    public synchronized String refresh(String rejectedToken) throws IOException, InterruptedException {
        if (!token.equals(rejectedToken)) return token;
        byte[] payload = objectMapper.writeValueAsBytes(Map.of("username", username, "password", password));
        HttpRequest request = HttpRequest.newBuilder(loginUri).timeout(timeout)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] body = readBounded(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RagBenchmarkHttpClient.BenchmarkProtocolException(
                    "RAG_BENCHMARK_AUTH_REFRESH_FAILED", "benchmark认证刷新失败");
        }
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(body);
        } catch (RuntimeException exception) {
            throw new RagBenchmarkHttpClient.BenchmarkProtocolException(
                    "RAG_BENCHMARK_AUTH_REFRESH_FAILED", "benchmark认证刷新响应非法");
        }
        String next = envelope.path("data").path("token").asText();
        if (!"0000".equals(envelope.path("code").asText()) || blank(next)) {
            throw new RagBenchmarkHttpClient.BenchmarkProtocolException(
                    "RAG_BENCHMARK_AUTH_REFRESH_FAILED", "benchmark认证刷新失败");
        }
        token = next;
        return next;
    }

    private byte[] readBounded(InputStream input) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                if (output.size() + read > maxResponseBytes) {
                    throw new RagBenchmarkHttpClient.BenchmarkProtocolException(
                            "RAG_BENCHMARK_AUTH_REFRESH_FAILED", "benchmark认证刷新响应过大");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public String toString() {
        return "RefreshingLoginTokenProvider";
    }
}
