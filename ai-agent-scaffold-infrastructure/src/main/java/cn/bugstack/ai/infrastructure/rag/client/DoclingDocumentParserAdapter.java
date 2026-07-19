package cn.bugstack.ai.infrastructure.rag.client;

import cn.bugstack.ai.domain.rag.adapter.port.RagDocumentParserPort;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Docling 文档解析 HTTP 适配器。
 * <p>Markdown 在本地有界流式读取，PDF 和 DOCX 以 multipart 文件发布器流式上传。</p>
 */
@Component
@Slf4j
public class DoclingDocumentParserAdapter implements RagDocumentParserPort {

    private static final long MAX_DOCUMENT_BYTES = 50L * 1024 * 1024;
    private static final int TEXT_BUFFER_CHARS = 8 * 1024;
    private static final String PDF_MIME = "application/pdf";
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String MARKDOWN_MIME = "text/markdown";
    private static final String LOCAL_MARKDOWN_PARSER = "local-markdown-java17";

    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Semaphore concurrency;

    /**
     * 创建 Docling 文档解析适配器。
     *
     * @param properties RAG 连接与资源配置
     * @param objectMapper JSON 编解码器
     */
    @Autowired
    public DoclingDocumentParserAdapter(RagProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(connectTimeout(properties.getDocling().getTimeout())).build());
    }

    DoclingDocumentParserAdapter(RagProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.concurrency = new Semaphore(properties.getDocling().getMaxConcurrency(), true);
    }

    /**
     * 解析受控目录中的 Markdown、PDF 或 DOCX 文档。
     *
     * @param command 文档解析命令
     * @return 规范化 Markdown 和章节信息
     */
    @Override
    public ParsedDocument parse(ParseCommand command) {
        if (command == null) {
            throw new AppException("RAG_DOCUMENT_COMMAND_INVALID", "文档解析命令不能为空");
        }
        Path path = validateControlledFile(command);
        String mimeType = normalizeMimeType(command.mimeType());
        if (MARKDOWN_MIME.equals(mimeType)) {
            return parseMarkdown(command, path);
        }
        if (!PDF_MIME.equals(mimeType) && !DOCX_MIME.equals(mimeType)) {
            throw new AppException("RAG_DOCUMENT_FORMAT_UNSUPPORTED", "仅支持 Markdown、PDF 和 DOCX 文档解析");
        }
        return parseWithDocling(command, path, mimeType);
    }

    private ParsedDocument parseMarkdown(ParseCommand command, Path path) {
        RagProperties.Docling config = properties.getDocling();
        if (command.contentLength() > config.getMaxResponseBytes()) {
            throw new AppException("RAG_DOCUMENT_TEXT_TOO_LARGE", "Markdown 文档超过本地解析上限");
        }
        String markdown = readUtf8Bounded(path, config.getMaxResponseBytes());
        return parsedDocument(markdown, LOCAL_MARKDOWN_PARSER, command,
                Map.of("parser", "local", "mimeType", MARKDOWN_MIME));
    }

    private ParsedDocument parseWithDocling(ParseCommand command, Path path, String mimeType) {
        RagProperties.Docling config = properties.getDocling();
        TeiEmbeddingAdapter.requireApiKey(config.getApiKey());
        validateRemoteFileName(command.fileName());
        long adapterStarted = System.nanoTime();
        TeiEmbeddingAdapter.acquire(concurrency, config.getTimeout(), "Docling");
        long permitWaitMs = elapsedMillis(adapterStarted);
        try {
            HttpRequest request = buildRequest(command, path, mimeType, config);
            TransportResponse transport = sendWithOneRetry(request);
            HttpResponse<InputStream> response = transport.response();
            byte[] body = readBounded(response.body(), config.getMaxResponseBytes());
            if (response.statusCode() != 200) {
                throw new AppException("RAG_DOCLING_HTTP_ERROR", "Docling 服务返回状态 " + response.statusCode());
            }
            ConvertDocumentResponse payload = readResponse(body);
            if (!"success".equalsIgnoreCase(payload.status()) || payload.document() == null
                    || payload.document().markdown() == null || payload.document().markdown().isBlank()) {
                throw new AppException("RAG_DOCLING_RESPONSE_INVALID", "Docling 未返回可用的 Markdown 文档");
            }
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("parser", "docling");
            metadata.put("mimeType", mimeType);
            metadata.put("status", payload.status());
            metadata.put("transportAttempts", Integer.toString(transport.attempts()));
            metadata.put("transportWallMs", Long.toString(transport.wallMs()));
            metadata.put("permitWaitMs", Long.toString(permitWaitMs));
            metadata.put("adapterWallMs", Long.toString(elapsedMillis(adapterStarted)));
            if (payload.document().fileName() != null && !payload.document().fileName().isBlank()) {
                metadata.put("sourceFileName", payload.document().fileName());
            }
            if (payload.processingTime() != null && Double.isFinite(payload.processingTime())) {
                metadata.put("processingTimeSeconds", Double.toString(payload.processingTime()));
            }
            return parsedDocument(payload.document().markdown(), config.getParserRevision(), command, metadata);
        } catch (AppException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("RAG_DOCLING_INTERRUPTED", "Docling 请求被中断", e);
        } catch (IOException e) {
            throw new AppException("RAG_DOCLING_UNAVAILABLE", "Docling 服务调用失败", e);
        } finally {
            concurrency.release();
        }
    }

    private HttpRequest buildRequest(ParseCommand command, Path path, String mimeType,
                                     RagProperties.Docling config) throws IOException {
        String boundary = "ai-agent-rag-" + UUID.randomUUID();
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
                textPart(boundary, "target_type", "inbody"),
                textPart(boundary, "from_formats", PDF_MIME.equals(mimeType) ? "pdf" : "docx"),
                textPart(boundary, "to_formats", "md"),
                textPart(boundary, "do_ocr", Boolean.toString(command.ocrEnabled())),
                textPart(boundary, "force_ocr", "false"),
                textPart(boundary, "include_images", "false"),
                textPart(boundary, "include_page_images", "false"),
                textPart(boundary, "page_range", "1"),
                textPart(boundary, "page_range", Integer.toString(config.getMaxPages())),
                textPart(boundary, "document_timeout", Long.toString(documentTimeoutSeconds(config.getTimeout()))),
                fileHeader(boundary, command.fileName(), mimeType),
                HttpRequest.BodyPublishers.ofFile(path),
                bytes("\r\n--" + boundary + "--\r\n"));
        return HttpRequest.newBuilder(endpoint(config.getEndpoint(), "convert/file"))
                .timeout(config.getTimeout())
                .header("X-Api-Key", config.getApiKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(body)
                .build();
    }

    private TransportResponse sendWithOneRetry(HttpRequest request)
            throws IOException, InterruptedException {
        long started = System.nanoTime();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            long wallMs = elapsedMillis(started);
            log.info("event=rag_docling_http attempt=1 outcome=response status={} wallMs={}",
                    response.statusCode(), wallMs);
            return new TransportResponse(response, 1, wallMs);
        } catch (HttpTimeoutException timeout) {
            log.warn("event=rag_docling_http attempt=1 outcome=timeout wallMs={}", elapsedMillis(started));
            throw timeout;
        } catch (IOException firstFailure) {
            log.warn("event=rag_docling_http attempt=1 outcome=io_failure errorType={} wallMs={}",
                    firstFailure.getClass().getSimpleName(), elapsedMillis(started));
            try {
                HttpResponse<InputStream> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());
                long wallMs = elapsedMillis(started);
                log.info("event=rag_docling_http attempt=2 outcome=response status={} wallMs={}",
                        response.statusCode(), wallMs);
                return new TransportResponse(response, 2, wallMs);
            } catch (IOException retryFailure) {
                log.warn("event=rag_docling_http attempt=2 outcome={} errorType={} wallMs={}",
                        retryFailure instanceof HttpTimeoutException ? "timeout" : "io_failure",
                        retryFailure.getClass().getSimpleName(), elapsedMillis(started));
                retryFailure.addSuppressed(firstFailure);
                throw retryFailure;
            }
        }
    }

    private static Duration connectTimeout(Duration requestTimeout) {
        Duration upperBound = Duration.ofSeconds(10);
        return requestTimeout.compareTo(upperBound) < 0 ? requestTimeout : upperBound;
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS
                .toMillis(System.nanoTime() - startedNanos));
    }

    private HttpRequest.BodyPublisher textPart(String boundary, String name, String value) {
        return bytes("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n");
    }

    private HttpRequest.BodyPublisher fileHeader(String boundary, String fileName, String mimeType) {
        return bytes("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + mimeType + "\r\n\r\n");
    }

    private HttpRequest.BodyPublisher bytes(String value) {
        return HttpRequest.BodyPublishers.ofByteArray(value.getBytes(StandardCharsets.UTF_8));
    }

    private long documentTimeoutSeconds(Duration timeout) {
        return Math.max(1L, timeout.toSeconds());
    }

    private record TransportResponse(HttpResponse<InputStream> response, int attempts, long wallMs) {
    }

    private URI endpoint(URI base, String path) {
        return URI.create(base.toString().replaceAll("/+$", "") + "/" + path);
    }

    private Path validateControlledFile(ParseCommand command) {
        Path path = command.contentPath().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(path)) {
            throw new AppException("RAG_DOCUMENT_PATH_INVALID", "待解析文档路径不可读");
        }
        if (command.contentLength() > MAX_DOCUMENT_BYTES) {
            throw new AppException("RAG_DOCUMENT_TOO_LARGE", "单个解析文档不能超过 50 MiB");
        }
        try {
            if (Files.size(path) != command.contentLength()) {
                throw new AppException("RAG_DOCUMENT_SIZE_MISMATCH", "待解析文档长度与声明值不一致");
            }
        } catch (IOException e) {
            throw new AppException("RAG_DOCUMENT_READ_FAILED", "无法读取待解析文档长度", e);
        }
        return path;
    }

    private String normalizeMimeType(String mimeType) {
        return mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private void validateRemoteFileName(String fileName) {
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0 || fileName.indexOf('"') >= 0
                || fileName.codePoints().anyMatch(Character::isISOControl)) {
            throw new AppException("RAG_DOCUMENT_FILE_NAME_INVALID", "文档文件名不能包含路径或 multipart 控制字符");
        }
    }

    private String readUtf8Bounded(Path path, long maxBytes) {
        int maxChars = safeReadLimit(maxBytes);
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        StringBuilder content = new StringBuilder();
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), decoder)) {
            char[] buffer = new char[TEXT_BUFFER_CHARS];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (content.length() > maxChars - count) {
                    throw new AppException("RAG_DOCUMENT_TEXT_TOO_LARGE", "Markdown 文档超过本地解析上限");
                }
                content.append(buffer, 0, count);
            }
        } catch (AppException e) {
            throw e;
        } catch (IOException e) {
            throw new AppException("RAG_DOCUMENT_TEXT_INVALID", "Markdown 必须是可读的 UTF-8 文本", e);
        }
        String markdown = stripBom(content.toString()).strip();
        if (markdown.isBlank()) {
            throw new AppException("RAG_DOCUMENT_TEXT_INVALID", "Markdown 解析结果不能为空");
        }
        return markdown;
    }

    private byte[] readBounded(InputStream input, long maxBytes) throws IOException {
        int limit = safeReadLimit(maxBytes);
        try (input) {
            byte[] body = input.readNBytes(limit + 1);
            if (body.length > limit) {
                throw new AppException("RAG_DOCLING_RESPONSE_TOO_LARGE", "Docling 响应超过安全上限");
            }
            return body;
        }
    }

    private int safeReadLimit(long maxBytes) {
        if (maxBytes < 1 || maxBytes >= Integer.MAX_VALUE) {
            throw new AppException("RAG_DOCLING_LIMIT_INVALID", "Docling 有界读取配置不合法");
        }
        return (int) maxBytes;
    }

    private ConvertDocumentResponse readResponse(byte[] body) {
        try {
            return objectMapper.readValue(body, ConvertDocumentResponse.class);
        } catch (IOException e) {
            // 不保留 Jackson 的原始异常，避免非法响应片段进入日志。
            throw new AppException("RAG_DOCLING_RESPONSE_INVALID", "Docling 返回的 JSON 结构不合法");
        }
    }

    private ParsedDocument parsedDocument(String markdown, String parserVersion, ParseCommand command,
                                          Map<String, String> metadata) {
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isBlank()) {
            throw new AppException("RAG_DOCUMENT_TEXT_INVALID", "文档解析结果不能为空");
        }
        Map<String, String> resultMetadata = new LinkedHashMap<>(metadata);
        resultMetadata.put("tenantId", command.tenantId());
        resultMetadata.put("jobId", command.jobId());
        resultMetadata.put("versionId", command.versionId());
        return new ParsedDocument(normalized, sections(normalized), 0, parserVersion, resultMetadata);
    }

    private List<ParsedSection> sections(String markdown) {
        List<ParsedSection> result = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        String currentPath = "";
        StringBuilder content = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            Heading heading = heading(line);
            if (heading == null) {
                if (!content.isEmpty()) {
                    content.append('\n');
                }
                content.append(line);
                continue;
            }
            addSection(result, currentPath, content);
            while (headingPath.size() >= heading.level()) {
                headingPath.remove(headingPath.size() - 1);
            }
            headingPath.add(heading.text());
            currentPath = String.join(" / ", headingPath);
        }
        addSection(result, currentPath, content);
        if (result.isEmpty()) {
            result.add(new ParsedSection("", markdown, null, 0));
        }
        return List.copyOf(result);
    }

    private void addSection(List<ParsedSection> sections, String headingPath, StringBuilder content) {
        String value = content.toString().strip();
        content.setLength(0);
        if (!value.isBlank()) {
            sections.add(new ParsedSection(headingPath, value, null, sections.size()));
        }
    }

    private Heading heading(String line) {
        int level = 0;
        while (level < line.length() && level < 6 && line.charAt(level) == '#') {
            level++;
        }
        if (level == 0 || level >= line.length() || line.charAt(level) != ' ') {
            return null;
        }
        String text = line.substring(level + 1).strip();
        return text.isBlank() ? null : new Heading(level, text);
    }

    private String stripBom(String value) {
        return !value.isEmpty() && value.charAt(0) == '\ufeff' ? value.substring(1) : value;
    }

    private record Heading(int level, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ConvertDocumentResponse(DoclingDocument document, String status,
                                           @JsonProperty("processing_time") Double processingTime) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DoclingDocument(@JsonProperty("filename") String fileName,
                                   @JsonProperty("md_content") String markdown) {
    }
}
