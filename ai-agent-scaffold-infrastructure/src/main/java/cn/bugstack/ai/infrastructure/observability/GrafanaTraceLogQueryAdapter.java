package cn.bugstack.ai.infrastructure.observability;

import cn.bugstack.ai.domain.observability.adapter.port.TraceLogQueryPort;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 通过 Grafana 的 Loki datasource proxy 查询当前运行日志。
 *
 * <p>这里只发送 GET 请求。查询表达式由服务端固定拼装为“应用日志 + 当前 Trace ID + 当前租户”，
 * 模型无法传入查询语句、数据源或 Grafana 地址。</p>
 */
@Component
public class GrafanaTraceLogQueryAdapter implements TraceLogQueryPort {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Pattern SAFE_DATASOURCE = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern IPV4_LOOPBACK = Pattern.compile("127(?:\\.\\d{1,3}){3}");
    private static final int MAX_QUERY_LIMIT = 500;
    private static final int MAX_MODEL_CHARACTERS = 64_000;
    private static final int MAX_LINE_CHARACTERS = 4_000;
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])((?:\\\"|')?(?:authorization|password|passwd|pwd|api[_-]?key|token|"
                    + "access[_-]?token|refresh[_-]?token|client[_-]?secret|private[_-]?key|secret|dsn|"
                    + "database[_-]?url|jdbc[_-]?url)(?:\\\"|')?\\s*[:=]\\s*)"
                    + "(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}]+)");
    private static final Pattern COOKIE_HEADER = Pattern.compile(
            "(?i)\\b(cookie|set-cookie)\\s*[:=]\\s*(\\\"[^\\\"]*\\\"|'[^']*'|[^\\r\\n]+)");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern BASIC_TOKEN = Pattern.compile("(?i)\\bBasic\\s+[A-Za-z0-9+/=]+");
    private static final Pattern JWT_TOKEN = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\b");
    private static final Pattern URI_CREDENTIALS = Pattern.compile(
            "(?i)\\b([a-z][a-z0-9+.-]*://)[^\\s/@:]+:[^\\s/@]+@");

    private final URI grafanaUrl;
    private final String datasourceUid;
    private final String username;
    private final String password;
    private final String selector;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final int maxModelCharacters;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /** 使用部署配置创建只读 Grafana 客户端。 */
    @Autowired
    public GrafanaTraceLogQueryAdapter(
            @Value("${ai.observability.grafana.url:http://127.0.0.1:13000}") URI grafanaUrl,
            @Value("${ai.observability.grafana.datasource-uid:loki}") String datasourceUid,
            @Value("${ai.observability.grafana.username:}") String username,
            @Value("${ai.observability.grafana.password:}") String password,
            @Value("${ai.observability.grafana.selector:{job=\"ai-agent-scaffold\"}}") String selector,
            @Value("${ai.observability.grafana.timeout:15s}") Duration timeout,
            @Value("${ai.observability.grafana.max-response-bytes:1048576}") int maxResponseBytes,
            @Value("${ai.observability.grafana.max-model-characters:32000}") int maxModelCharacters,
            ObjectMapper objectMapper) {
        this(grafanaUrl, datasourceUid, username, password, selector, timeout, maxResponseBytes, maxModelCharacters,
                objectMapper, HttpClient.newBuilder().connectTimeout(timeout).build());
    }

    GrafanaTraceLogQueryAdapter(URI grafanaUrl, String datasourceUid, String username, String password,
                                String selector, Duration timeout, int maxResponseBytes, int maxModelCharacters,
                                ObjectMapper objectMapper, HttpClient httpClient) {
        this.grafanaUrl = grafanaUrl;
        this.datasourceUid = datasourceUid;
        this.username = username;
        this.password = password;
        this.selector = selector;
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
        this.maxModelCharacters = maxModelCharacters;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /** 查询、解析、脱敏并按时间升序返回日志。 */
    @Override
    public QueryResult query(QueryCommand command) {
        validate(command);
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofMinutes(command.lookbackMinutes()));
        String logql = selector + " |= " + quote("traceId=" + command.traceId())
                + " | logfmt | traceId=" + quote(command.traceId())
                + " | tenantId=" + quote(command.tenantId());
        // Loki 多取一条只用于判断是否还有更早日志，最终给 Agent 的条数仍不超过 limit。
        URI endpoint = queryUri(logql, start, end, command.limit() + 1);
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Accept", "application/json").GET();
        if (!blank(username) && !blank(password)) {
            String token = Base64.getEncoder().encodeToString((safe(username) + ":" + safe(password))
                    .getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + token);
        }

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AppException("TRACE_LOG_QUERY_INTERRUPTED", "日志查询被中断", exception);
        } catch (IOException exception) {
            throw new AppException("TRACE_LOG_SERVICE_UNAVAILABLE", "日志服务暂时不可用", exception);
        }
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(maxResponseBytes + 1);
            if (bytes.length > maxResponseBytes) {
                throw new AppException("TRACE_LOG_RESPONSE_TOO_LARGE", "日志服务返回内容超过限制");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AppException("TRACE_LOG_HTTP_ERROR", "日志服务返回 HTTP " + response.statusCode());
            }
            return parse(bytes, command.limit());
        } catch (IOException exception) {
            throw new AppException("TRACE_LOG_RESPONSE_INVALID", "读取日志服务响应失败", exception);
        }
    }

    /** 解析 Loki streams 响应；任何结构异常都明确失败，不把不完整数据交给 Agent。 */
    private QueryResult parse(byte[] bytes, int limit) {
        try {
            JsonNode root = objectMapper.readTree(bytes);
            JsonNode data = root == null ? null : root.get("data");
            JsonNode result = data == null ? null : data.get("result");
            if (root == null || !root.isObject() || !root.hasNonNull("status")
                    || !root.get("status").isTextual() || !"success".equals(root.get("status").textValue())
                    || data == null || !data.isObject() || !data.hasNonNull("resultType")
                    || !data.get("resultType").isTextual()
                    || !"streams".equals(data.get("resultType").textValue())
                    || result == null || !result.isArray()) {
                throw new AppException("TRACE_LOG_RESPONSE_INVALID", "日志服务响应格式不正确");
            }
            List<LogEntry> entries = new ArrayList<>();
            boolean lineWasTruncated = false;
            for (JsonNode stream : result) {
                JsonNode values = stream == null || !stream.isObject() ? null : stream.get("values");
                if (values == null || !values.isArray()) {
                    throw new AppException("TRACE_LOG_RESPONSE_INVALID", "日志记录格式不正确");
                }
                for (JsonNode pair : values) {
                    if (!pair.isArray() || pair.size() != 2 || !pair.get(0).isTextual()
                            || !pair.get(1).isTextual()) {
                        throw new AppException("TRACE_LOG_RESPONSE_INVALID", "日志记录格式不正确");
                    }
                    long nanos = Long.parseLong(pair.get(0).asText());
                    Instant timestamp = Instant.ofEpochSecond(nanos / 1_000_000_000L,
                            nanos % 1_000_000_000L);
                    String original = pair.get(1).textValue();
                    // 先脱敏再裁剪，避免把一枚凭证截成无法识别、但仍可利用的半截原文。
                    String redacted = redact(original);
                    String bounded = truncate(redacted, MAX_LINE_CHARACTERS);
                    lineWasTruncated |= bounded.length() < redacted.length();
                    entries.add(new LogEntry(timestamp, bounded));
                }
            }
            entries.sort(Comparator.comparing(LogEntry::timestamp));
            boolean truncated = lineWasTruncated || entries.size() > limit;
            // backward 查询返回的是最新窗口；多于 limit 时丢掉最早的那条探测记录。
            if (entries.size() > limit) {
                entries = new ArrayList<>(entries.subList(entries.size() - limit, entries.size()));
            }

            // 从最新日志开始消耗总字符预算，避免返回大对象绕过 ToolGateway 的文本裁剪。
            List<LogEntry> withinBudget = new ArrayList<>();
            int remainingCharacters = maxModelCharacters;
            for (int index = entries.size() - 1; index >= 0; index--) {
                LogEntry entry = entries.get(index);
                if (remainingCharacters == 0) {
                    truncated = true;
                    break;
                }
                String line = entry.line();
                if (line.length() > remainingCharacters) {
                    line = line.substring(0, remainingCharacters);
                    truncated = true;
                }
                withinBudget.add(new LogEntry(entry.timestamp(), line));
                remainingCharacters -= line.length();
            }
            withinBudget.sort(Comparator.comparing(LogEntry::timestamp));
            return new QueryResult(withinBudget, truncated);
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AppException("TRACE_LOG_RESPONSE_INVALID", "日志服务响应格式不正确", exception);
        }
    }

    /** 校验所有能影响远程请求范围的配置和身份字段。 */
    private void validate(QueryCommand command) {
        if (command == null || !SAFE_IDENTIFIER.matcher(safe(command.tenantId())).matches()
                || !SAFE_IDENTIFIER.matcher(safe(command.traceId())).matches()
                || command.lookbackMinutes() < 1 || command.lookbackMinutes() > 120
                || command.limit() < 1 || command.limit() > MAX_QUERY_LIMIT) {
            throw new AppException("TRACE_LOG_QUERY_INVALID", "日志查询身份不合法");
        }
        boolean hasUsername = !blank(username);
        boolean hasPassword = !blank(password);
        boolean authenticationConfigured = hasUsername && hasPassword;
        if (grafanaUrl == null || !("http".equalsIgnoreCase(grafanaUrl.getScheme())
                || "https".equalsIgnoreCase(grafanaUrl.getScheme())) || grafanaUrl.getHost() == null
                || grafanaUrl.getUserInfo() != null || grafanaUrl.getRawQuery() != null
                || grafanaUrl.getRawFragment() != null || hasUsername != hasPassword
                || authenticationConfigured && "http".equalsIgnoreCase(grafanaUrl.getScheme())
                && !isLoopbackHost(grafanaUrl.getHost())
                || !SAFE_DATASOURCE.matcher(safe(datasourceUid)).matches()
                || selector == null || selector.length() > 512 || selector.indexOf('\n') >= 0
                || !selector.startsWith("{") || !selector.endsWith("}")
                || timeout == null || timeout.isZero() || timeout.isNegative()
                || maxResponseBytes < 1 || maxResponseBytes > 16 * 1024 * 1024
                || maxModelCharacters < 1 || maxModelCharacters > MAX_MODEL_CHARACTERS) {
            throw new AppException("TRACE_LOG_CONFIG_INVALID", "日志查询配置不合法");
        }
    }

    /** 构造唯一允许访问的 datasource proxy range-query 地址。 */
    private URI queryUri(String logql, Instant start, Instant end, int limit) {
        String base = grafanaUrl.toString().replaceAll("/+$", "");
        String query = "query=" + encode(logql) + "&start=" + start.toEpochMilli() * 1_000_000L
                + "&end=" + end.toEpochMilli() * 1_000_000L + "&limit=" + limit + "&direction=backward";
        return URI.create(base + "/api/datasources/proxy/uid/" + datasourceUid
                + "/loki/api/v1/query_range?" + query);
    }

    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String redact(String value) {
        String redacted = COOKIE_HEADER.matcher(value).replaceAll("$1=[REDACTED]");
        redacted = URI_CREDENTIALS.matcher(redacted).replaceAll("$1[REDACTED]@");
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("Bearer [REDACTED]");
        redacted = BASIC_TOKEN.matcher(redacted).replaceAll("Basic [REDACTED]");
        redacted = SENSITIVE_ASSIGNMENT.matcher(redacted).replaceAll("$1[REDACTED]");
        return JWT_TOKEN.matcher(redacted).replaceAll("[REDACTED_JWT]");
    }

    /** Basic 认证只允许经 HTTPS 或本机 HTTP 发送，避免明文凭证离开本机。 */
    private boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host) || "::1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host) || IPV4_LOOPBACK.matcher(host).matches();
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) return value;
        return maxLength <= 3 ? value.substring(0, maxLength) : value.substring(0, maxLength - 3) + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
