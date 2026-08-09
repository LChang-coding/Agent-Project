package cn.bugstack.ai.infrastructure.rag.client;

import cn.bugstack.ai.domain.rag.adapter.port.RagDocumentParserPort;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Docling 文档解析 HTTP 适配器。
 * <p>Markdown 在本地有界流式读取，PDF 和 DOCX 以 multipart 文件发布器流式上传。</p>
 */
@Component
@Slf4j
public class DoclingDocumentParserAdapter implements RagDocumentParserPort {

    /** 单个待解析文档最大允许 50 MiB。 */
    private static final long MAX_DOCUMENT_BYTES = 50L * 1024 * 1024;
    /** 本地 Markdown 字符读取缓冲区大小。 */
    private static final int TEXT_BUFFER_CHARS = 8 * 1024;
    /** PDF 的标准 MIME 类型。 */
    private static final String PDF_MIME = "application/pdf";
    /** DOCX 的标准 MIME 类型。 */
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    /** Markdown 的标准 MIME 类型。 */
    private static final String MARKDOWN_MIME = "text/markdown";
    /** 本地 Markdown 兼容解析器标识。 */
    private static final String LOCAL_MARKDOWN_PARSER = "local-markdown-java17";

    /** Docling 地址、认证、超时和文档上限配置。 */
    private final RagProperties properties;
    /** 解析 Docling 响应并序列化 Document IR。 */
    private final ObjectMapper objectMapper;
    /** 复用连接执行 Docling HTTP 请求。 */
    private final HttpClient httpClient;
    /** 限制当前进程同时发送的 Docling 请求数量。 */
    private final Semaphore concurrency;
    /** 在本地保留 Markdown AST 结构。 */
    private final MarkdownAstDocumentParser markdownParser;
    /** 使用 Apache POI 在本地保留 DOCX 结构。 */
    private final DocxDocumentParser docxParser;
    /** 将 Docling JSON 转换为平台统一 Document IR。 */
    private final DoclingJsonDocumentIrMapper doclingIrMapper;

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

    /** 注入可替换 HTTP 客户端，供远端协议和失败分支测试使用。 */
    DoclingDocumentParserAdapter(RagProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.concurrency = new Semaphore(properties.getDocling().getMaxConcurrency(), true);
        this.markdownParser = new MarkdownAstDocumentParser(objectMapper);
        this.docxParser = new DocxDocumentParser(objectMapper);
        this.doclingIrMapper = new DoclingJsonDocumentIrMapper(objectMapper);
    }

    /**
     * 解析受控目录中的 Markdown、PDF 或 DOCX 文档。
     *
     * @param command 文档解析命令
     * @return 规范化 Markdown 和章节信息
     */
    @Override
    /** 按 MIME 路由本地 Markdown/DOCX 或 Docling，并统一输出 Canonical IR。 */
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
        if (DOCX_MIME.equals(mimeType)) {
            try {
                return docxParser.parse(command);
            } catch (AppException localFailure) {
                log.warn("event=rag_docx_parser_fallback outcome=docling errorCode={}",
                        localFailure.getCode());
                ParsedDocument fallback = parseWithDocling(command, path, mimeType);
                List<String> warnings = new ArrayList<>(fallback.warnings());
                warnings.add("DOCX_PRIMARY_PARSER_FAILED_DOCLING_FALLBACK");
                return new ParsedDocument(fallback.normalizedMarkdown(), fallback.sections(),
                        fallback.pageCount(), fallback.parserVersion(), fallback.metadata(),
                        fallback.documentIr(), fallback.parserOutputJson(), warnings,
                        fallback.ocrApplied());
            }
        }
        return parseWithDocling(command, path, mimeType);
    }

    /** 在配置的内容上限内读取 UTF-8 Markdown，并交给本地 AST 解析器。 */
    private ParsedDocument parseMarkdown(ParseCommand command, Path path) {
        RagProperties.Docling config = properties.getDocling();
        if (command.contentLength() > config.getMaxResponseBytes()) {
            throw new AppException("RAG_DOCUMENT_TEXT_TOO_LARGE", "Markdown 文档超过本地解析上限");
        }
        String markdown = readUtf8Bounded(path, config.getMaxResponseBytes());
        return markdownParser.parse(command, markdown);
    }

    /** 以受限 multipart 调用 Docling，优先映射结构化 JSON，Markdown 仅作展示/兜底。 */
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
                    || (payload.document().markdown() == null || payload.document().markdown().isBlank())
                    && (payload.document().jsonContent() == null
                    || payload.document().jsonContent().isNull())) {
                throw new AppException("RAG_DOCLING_RESPONSE_INVALID", "Docling 未返回可用的结构化文档");
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
            PageMetadata pageMetadata = pageMetadata(payload.document().jsonContent(), config.getMaxPages());
            DoclingJsonDocumentIrMapper.MappedDocument mapped = doclingIrMapper.map(command,
                    payload.document().jsonContent(), payload.document().markdown(), config.getParserRevision());
            metadata.put("pageCount", Integer.toString(pageMetadata.pageCount()));
            // ParsedSection继续作为兼容读模型由Markdown展示产物派生；Document IR才是结构化主事实源。
            return new ParsedDocument(mapped.normalizedMarkdown(),
                    sections(mapped.normalizedMarkdown(), pageMetadata.headingPages()),
                    pageMetadata.pageCount(), config.getParserRevision(), metadata,
                    mapped.ir(), mapped.parserOutputJson(), mapped.warnings(), mapped.ocrApplied());
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

    /** 构造受限 multipart 请求，关闭图片回传并传递页数、OCR 和文档超时。 */
    private HttpRequest buildRequest(ParseCommand command, Path path, String mimeType,
                                     RagProperties.Docling config) throws IOException {
        String boundary = "ai-agent-rag-" + UUID.randomUUID();
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
                textPart(boundary, "target_type", "inbody"),
                textPart(boundary, "from_formats", PDF_MIME.equals(mimeType) ? "pdf" : "docx"),
                textPart(boundary, "to_formats", "md"),
                textPart(boundary, "to_formats", "json"),
                textPart(boundary, "do_ocr", Boolean.toString(command.ocrEnabled())),
                textPart(boundary, "force_ocr",
                        Boolean.toString(command.ocrMode() == RagDocumentParserPort.OcrMode.FORCED)),
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

    /** 对网络错误和 5xx 再请求一次；完整请求超时、4xx 和响应格式错误不重试。 */
    private TransportResponse sendWithOneRetry(HttpRequest request)
            throws IOException, InterruptedException {
        long started = System.nanoTime();
        final HttpResponse<InputStream> firstResponse;
        try {
            firstResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (firstResponse.statusCode() < 500 || firstResponse.statusCode() > 599) {
                long wallMs = elapsedMillis(started);
                log.info("event=rag_docling_http attempt=1 outcome=response status={} wallMs={}",
                        firstResponse.statusCode(), wallMs);
                return new TransportResponse(firstResponse, 1, wallMs);
            }
        } catch (HttpTimeoutException timeout) {
            log.warn("event=rag_docling_http attempt=1 outcome=timeout wallMs={}", elapsedMillis(started));
            throw timeout;
        } catch (IOException firstFailure) {
            log.warn("event=rag_docling_http attempt=1 outcome=io_failure errorType={} wallMs={}",
                    firstFailure.getClass().getSimpleName(), elapsedMillis(started));
            return sendSecondAttempt(request, started, firstFailure);
        }

        // 第一次收到服务器错误时先关闭响应体，再重新发送同一个可重复读取的文件请求。
        firstResponse.body().close();
        log.warn("event=rag_docling_http attempt=1 outcome=server_error status={} wallMs={}",
                firstResponse.statusCode(), elapsedMillis(started));
        return sendSecondAttempt(request, started, null);
    }

    /** 执行唯一一次补充请求；第二次无论怎样失败都直接返回，不会再发第三次。 */
    private TransportResponse sendSecondAttempt(HttpRequest request, long started, IOException firstFailure)
            throws IOException, InterruptedException {
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            long wallMs = elapsedMillis(started);
            log.info("event=rag_docling_http attempt=2 outcome=response status={} wallMs={}",
                    response.statusCode(), wallMs);
            return new TransportResponse(response, 2, wallMs);
        } catch (IOException retryFailure) {
            log.warn("event=rag_docling_http attempt=2 outcome={} errorType={} wallMs={}",
                    retryFailure instanceof HttpTimeoutException ? "timeout" : "io_failure",
                    retryFailure.getClass().getSimpleName(), elapsedMillis(started));
            if (firstFailure != null) retryFailure.addSuppressed(firstFailure);
            throw retryFailure;
        }
    }

    /** 连接超时不超过完整请求超时和十秒上限。 */
    private static Duration connectTimeout(Duration requestTimeout) {
        Duration upperBound = Duration.ofSeconds(10);
        return requestTimeout.compareTo(upperBound) < 0 ? requestTimeout : upperBound;
    }

    /** 将单调时钟耗时转换为非负毫秒数。 */
    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS
                .toMillis(System.nanoTime() - startedNanos));
    }

    /** 构造一个 UTF-8 multipart 文本字段。 */
    private HttpRequest.BodyPublisher textPart(String boundary, String name, String value) {
        return bytes("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n");
    }

    /** 构造文档文件的 multipart 头，文件内容由后续 BodyPublisher 提供。 */
    private HttpRequest.BodyPublisher fileHeader(String boundary, String fileName, String mimeType) {
        return bytes("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + mimeType + "\r\n\r\n");
    }

    /** 将 multipart 控制文本转换为 UTF-8 请求体片段。 */
    private HttpRequest.BodyPublisher bytes(String value) {
        return HttpRequest.BodyPublishers.ofByteArray(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 将请求超时转换为 Docling 接受的至少一秒文档超时。 */
    private long documentTimeoutSeconds(Duration timeout) {
        return Math.max(1L, timeout.toSeconds());
    }

    /** 保留 HTTP 响应、实际尝试次数和总传输耗时。 */
    private record TransportResponse(HttpResponse<InputStream> response, int attempts, long wallMs) {
    }

    /** 去除端点末尾斜线后拼接固定 API 路径。 */
    private URI endpoint(URI base, String path) {
        return URI.create(base.toString().replaceAll("/+$", "") + "/" + path);
    }

    /** 只读取 Worker 控制工作区内的普通文件，并限制大小和符号链接。 */
    private Path validateControlledFile(ParseCommand command) {
        Path path = command.contentPath().toAbsolutePath().normalize();
        try {
            Path workspace = command.workspaceRoot().toAbsolutePath().normalize();
            if (Files.isSymbolicLink(workspace)
                    || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
                throw new AppException("RAG_DOCUMENT_WORKSPACE_INVALID", "文档工作目录不受信任");
            }
            Path realWorkspace = workspace.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realPath = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!realPath.startsWith(realWorkspace) || Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)) {
                throw new AppException("RAG_DOCUMENT_PATH_INVALID", "待解析文档不在受控目录或不可读");
            }
            Path cursor = workspace;
            for (Path component : workspace.relativize(path)) {
                cursor = cursor.resolve(component);
                if (Files.isSymbolicLink(cursor)) {
                    throw new AppException("RAG_DOCUMENT_PATH_INVALID", "待解析文档路径包含符号链接");
                }
            }
            if (command.contentLength() > MAX_DOCUMENT_BYTES) {
                throw new AppException("RAG_DOCUMENT_TOO_LARGE", "单个解析文档不能超过 50 MiB");
            }
            if (Files.size(realPath) != command.contentLength()) {
                throw new AppException("RAG_DOCUMENT_SIZE_MISMATCH", "待解析文档长度与声明值不一致");
            }
        } catch (IOException e) {
            throw new AppException("RAG_DOCUMENT_READ_FAILED", "无法读取待解析文档长度", e);
        }
        return path;
    }

    /** 去除 MIME 参数并统一转为小写，供解析器路由判断。 */
    private String normalizeMimeType(String mimeType) {
        return mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    /** 拒绝会改变 multipart 结构或携带路径信息的远端文件名。 */
    private void validateRemoteFileName(String fileName) {
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0 || fileName.indexOf('"') >= 0
                || fileName.codePoints().anyMatch(Character::isISOControl)) {
            throw new AppException("RAG_DOCUMENT_FILE_NAME_INVALID", "文档文件名不能包含路径或 multipart 控制字符");
        }
    }

    /** 严格 UTF-8 有界读取 Markdown，拒绝替换字符掩盖坏编码。 */
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
        String markdown = stripBom(content.toString())
                .replace("\r\n", "\n").replace('\r', '\n').strip();
        if (markdown.isBlank()) {
            throw new AppException("RAG_DOCUMENT_TEXT_INVALID", "Markdown 解析结果不能为空");
        }
        return markdown;
    }

    /** 有界读取 Docling 响应，超过配置上限时拒绝整个响应。 */
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

    /** 将配置的字节上限安全转换为 JDK 读取 API 使用的 int。 */
    private int safeReadLimit(long maxBytes) {
        if (maxBytes < 1 || maxBytes >= Integer.MAX_VALUE) {
            throw new AppException("RAG_DOCLING_LIMIT_INVALID", "Docling 有界读取配置不合法");
        }
        return (int) maxBytes;
    }

    /** 解析 Docling JSON；不把可能包含文档内容的原始异常写入错误链。 */
    private ConvertDocumentResponse readResponse(byte[] body) {
        try {
            return objectMapper.readValue(body, ConvertDocumentResponse.class);
        } catch (IOException e) {
            // 不保留 Jackson 的原始异常，避免非法响应片段进入日志。
            throw new AppException("RAG_DOCLING_RESPONSE_INVALID", "Docling 返回的 JSON 结构不合法");
        }
    }

    /** 从规范化 Markdown、页码映射和解析元数据构造兼容结果。 */
    private ParsedDocument parsedDocument(String markdown, String parserVersion, ParseCommand command,
                                          Map<String, String> metadata, PageMetadata pageMetadata) {
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isBlank()) {
            throw new AppException("RAG_DOCUMENT_TEXT_INVALID", "文档解析结果不能为空");
        }
        Map<String, String> resultMetadata = new LinkedHashMap<>(metadata);
        resultMetadata.put("tenantId", command.tenantId());
        resultMetadata.put("jobId", command.jobId());
        resultMetadata.put("versionId", command.versionId());
        return new ParsedDocument(normalized, sections(normalized, pageMetadata.headingPages()),
                pageMetadata.pageCount(), parserVersion, resultMetadata);
    }

    /** 按 Markdown 标题切分段落，并按标题路径消耗 Docling 页码。 */
    private List<ParsedSection> sections(String markdown, HeadingPageResolver headingPages) {
        List<ParsedSection> result = new ArrayList<>();
        List<Heading> headingPath = new ArrayList<>();
        String currentPath = "";
        Integer currentPage = null;
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
            addSection(result, currentPath, currentPage, content);
            currentPath = pushHeading(headingPath, heading);
            currentPage = headingPages.nextPage(currentPath);
        }
        addSection(result, currentPath, currentPage, content);
        if (result.isEmpty()) {
            result.add(new ParsedSection("", markdown, null, 0));
        }
        return List.copyOf(result);
    }

    /** 提交一个非空段落并清空当前内容缓冲区。 */
    private void addSection(List<ParsedSection> sections, String headingPath, Integer pageNumber,
                            StringBuilder content) {
        String value = content.toString().strip();
        content.setLength(0);
        if (!value.isBlank()) {
            sections.add(new ParsedSection(headingPath, value, pageNumber, sections.size()));
        }
    }

    /** 从 Docling provenance 恢复页码与标题路径；不猜测缺失页码。 */
    private PageMetadata pageMetadata(JsonNode jsonContent, int maxPages) {
        if (jsonContent == null || jsonContent.isNull() || jsonContent.isMissingNode()) {
            return PageMetadata.empty();
        }
        if (!jsonContent.isObject()) {
            throw invalidPageMetadata();
        }
        JsonNode pages = jsonContent.get("pages");
        if (pages == null || pages.isNull()) {
            return PageMetadata.empty();
        }
        if (!pages.isObject()) {
            throw invalidPageMetadata();
        }
        if (pages.isEmpty()) {
            return PageMetadata.empty();
        }
        Set<Integer> pageNumbers = new HashSet<>();
        pages.fields().forEachRemaining(entry -> {
            JsonNode page = entry.getValue();
            JsonNode pageNumber = page == null ? null : page.get("page_no");
            int keyPageNumber;
            try {
                keyPageNumber = Integer.parseInt(entry.getKey());
            } catch (NumberFormatException ignored) {
                throw invalidPageMetadata();
            }
            if (pageNumber == null || !pageNumber.isIntegralNumber() || !pageNumber.canConvertToInt()) {
                throw invalidPageMetadata();
            }
            int value = pageNumber.intValue();
            if (value != keyPageNumber || value < 1 || value > maxPages || !pageNumbers.add(value)) {
                throw invalidPageMetadata();
            }
        });
        for (int expected = 1; expected <= pageNumbers.size(); expected++) {
            if (!pageNumbers.contains(expected)) {
                throw invalidPageMetadata();
            }
        }

        List<HeadingPage> headings = new ArrayList<>();
        List<Heading> headingPath = new ArrayList<>();
        JsonNode texts = jsonContent.get("texts");
        if (texts != null && !texts.isNull()) {
            if (!texts.isArray()) {
                throw invalidPageMetadata();
            }
            for (JsonNode text : texts) {
                if (!"section_header".equals(text.path("label").asText())) {
                    continue;
                }
                String headingText = text.path("text").asText("").strip();
                int level = text.path("level").asInt(0);
                Integer pageNumber = provenancePage(text.get("prov"), pageNumbers);
                if (headingText.isBlank() || level < 1 || level > 6 || pageNumber == null) {
                    continue;
                }
                String path = pushHeading(headingPath, new Heading(level, headingText));
                headings.add(new HeadingPage(path, pageNumber));
            }
        }
        return new PageMetadata(pageNumbers.size(), new HeadingPageResolver(headings));
    }

    /** 按标题级别更新当前路径，并返回供段落和页码匹配的完整路径。 */
    private String pushHeading(List<Heading> path, Heading heading) {
        while (!path.isEmpty() && path.get(path.size() - 1).level() >= heading.level()) {
            path.remove(path.size() - 1);
        }
        path.add(heading);
        StringBuilder value = new StringBuilder();
        for (Heading node : path) {
            if (!value.isEmpty()) {
                value.append(" / ");
            }
            value.append(node.text());
        }
        return value.toString();
    }

    /** 从首条 provenance 读取已验证页码，缺失或越界时返回空值。 */
    private Integer provenancePage(JsonNode provenance, Set<Integer> pageNumbers) {
        if (provenance == null || !provenance.isArray() || provenance.isEmpty()) {
            return null;
        }
        JsonNode first = provenance.get(0);
        JsonNode pageNumber = first == null ? null : first.get("page_no");
        if (pageNumber == null || !pageNumber.isIntegralNumber() || !pageNumber.canConvertToInt()) {
            return null;
        }
        int value = pageNumber.intValue();
        return pageNumbers.contains(value) ? value : null;
    }

    /** 创建统一的 Docling 页级元数据协议错误。 */
    private AppException invalidPageMetadata() {
        return new AppException("RAG_DOCLING_PAGE_METADATA_INVALID", "Docling 返回的页级元数据不合法");
    }

    /** 识别一到六级 ATX 标题，普通文本返回空值。 */
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

    /** 删除 UTF-8 文本开头的单个 BOM。 */
    private String stripBom(String value) {
        return !value.isEmpty() && value.charAt(0) == '\ufeff' ? value.substring(1) : value;
    }

    /** Markdown 标题级别和正文。 */
    private record Heading(int level, String text) {
    }

    /** 一个标题路径在 Docling 原文中出现的页码。 */
    private record HeadingPage(String path, int pageNumber) {
    }

    /** 已验证的总页数和标题页码解析器。 */
    private record PageMetadata(int pageCount, HeadingPageResolver headingPages) {

        /** 返回没有可靠页码信息的结果。 */
        private static PageMetadata empty() {
            return new PageMetadata(0, new HeadingPageResolver(List.of()));
        }
    }

    /** 按标题路径保存出现顺序，处理同名标题位于不同页面的情况。 */
    private static final class HeadingPageResolver {

        /** 每个标题路径尚未消费的页码队列。 */
        private final Map<String, Deque<Integer>> pagesByPath = new LinkedHashMap<>();

        /** 按 Docling 文本顺序建立标题路径到页码的队列。 */
        private HeadingPageResolver(List<HeadingPage> headings) {
            headings.forEach(heading -> pagesByPath.computeIfAbsent(heading.path(), ignored -> new ArrayDeque<>())
                    .addLast(heading.pageNumber()));
        }

        /** 消费标题路径下一次出现的页码，无法匹配时返回空值。 */
        private Integer nextPage(String path) {
            Deque<Integer> pages = pagesByPath.get(path);
            return pages == null || pages.isEmpty() ? null : pages.removeFirst();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    /** Docling convert/file 接口的顶层响应字段。 */
    private record ConvertDocumentResponse(DoclingDocument document, String status,
                                           @JsonProperty("processing_time") Double processingTime) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    /** Docling 返回的文件名、展示 Markdown 和结构化 JSON。 */
    private record DoclingDocument(@JsonProperty("filename") String fileName,
                                   @JsonProperty("md_content") String markdown,
                                   @JsonProperty("json_content") JsonNode jsonContent) {
    }
}
