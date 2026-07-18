package cn.bugstack.ai.infrastructure.rag.client;

import cn.bugstack.ai.domain.rag.adapter.port.RagDocumentParserPort.ParseCommand;
import cn.bugstack.ai.domain.rag.adapter.port.RagDocumentParserPort.ParsedDocument;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Docling 本地 Markdown 与 multipart HTTP 协议测试。 */
public class DoclingDocumentParserAdapterProtocolTest {

    private static final String API_KEY = "docling-protocol-api-key";
    private static final String REMOTE_SECRET = "remote-response-must-not-leak";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger closeWithoutResponse = new AtomicInteger();
    private volatile byte[] responseBody;
    private HttpServer server;
    private RagProperties properties;
    private DoclingDocumentParserAdapter adapter;

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/convert/file", this::handle);
        server.start();

        properties = new RagProperties();
        RagProperties.Docling config = properties.getDocling();
        config.setEndpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
        config.setApiKey(API_KEY);
        config.setTimeout(Duration.ofSeconds(120));
        config.setMaxConcurrency(1);
        config.setMaxResponseBytes(8 * 1024);
        config.setParserRevision("docling-serve-test-revision");
        adapter = new DoclingDocumentParserAdapter(properties, objectMapper);
        respondSuccess("# Parsed\nremote body", "parsed.pdf");
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void markdownShouldParseLocallyWithBoundedUtf8AndHeadingSections() throws Exception {
        Path path = write("knowledge.md", ("\ufeff前言\r\n# 产品\r\n产品正文\r\n## 限制\r\n限制正文")
                .getBytes(StandardCharsets.UTF_8));

        ParsedDocument result = adapter.parse(command(path, "knowledge.md", "text/markdown", false));

        Assert.assertEquals("前言\n# 产品\n产品正文\n## 限制\n限制正文", result.normalizedMarkdown());
        Assert.assertEquals("local-markdown-java17", result.parserVersion());
        Assert.assertEquals(3, result.sections().size());
        Assert.assertEquals("", result.sections().get(0).headingPath());
        Assert.assertEquals("产品", result.sections().get(1).headingPath());
        Assert.assertEquals("产品 / 限制", result.sections().get(2).headingPath());
        Assert.assertEquals("local", result.metadata().get("parser"));
        Assert.assertTrue(requests.isEmpty());
    }

    @Test
    public void markdownShouldRejectMalformedUtf8AndConfiguredBoundaryWithoutRemoteCall() throws Exception {
        Path malformed = write("bad.md", new byte[]{(byte) 0xC3, 0x28});
        Assert.assertEquals("RAG_DOCUMENT_TEXT_INVALID",
                expectFailure(() -> adapter.parse(command(malformed, "bad.md", "text/markdown", false))).getCode());

        Path large = write("large.md", "x".repeat(65).getBytes(StandardCharsets.UTF_8));
        properties.getDocling().setMaxResponseBytes(64);
        Assert.assertEquals("RAG_DOCUMENT_TEXT_TOO_LARGE",
                expectFailure(() -> adapter.parse(command(large, "large.md", "text/markdown", false))).getCode());
        Assert.assertTrue(requests.isEmpty());
    }

    @Test
    public void pdfShouldStreamMultipartWithAuthenticationAndExplicitSafeOptions() throws Exception {
        Path path = write("source.pdf", "%PDF-1.7\nPDF_STREAM_SENTINEL".getBytes(StandardCharsets.US_ASCII));

        ParsedDocument result = adapter.parse(command(path, "source.pdf", "application/pdf", false));

        Assert.assertEquals("# Parsed\nremote body", result.normalizedMarkdown());
        Assert.assertEquals("docling-serve-test-revision", result.parserVersion());
        Assert.assertEquals("0.125", result.metadata().get("processingTimeSeconds"));
        CapturedRequest request = requests.get(0);
        Assert.assertEquals("POST", request.method());
        Assert.assertEquals("/v1/convert/file", request.path());
        Assert.assertEquals(API_KEY, request.apiKey());
        Assert.assertEquals("application/json", request.accept());
        Assert.assertTrue(request.contentType().startsWith("multipart/form-data; boundary="));
        Assert.assertNotNull(request.contentLength());
        Assert.assertNull(request.transferEncoding());
        String multipart = new String(request.body(), StandardCharsets.ISO_8859_1);
        assertTextPart(multipart, "target_type", "inbody");
        assertTextPart(multipart, "from_formats", "pdf");
        assertTextPart(multipart, "to_formats", "md");
        assertTextPart(multipart, "do_ocr", "false");
        assertTextPart(multipart, "force_ocr", "false");
        assertTextPart(multipart, "include_images", "false");
        assertTextPart(multipart, "include_page_images", "false");
        Assert.assertEquals(2, textPartCount(multipart, "page_range"));
        assertTextPart(multipart, "page_range", "1");
        assertTextPart(multipart, "page_range", Integer.toString(properties.getDocling().getMaxPages()));
        assertTextPart(multipart, "document_timeout", "120");
        Assert.assertTrue(multipart.contains("name=\"files\"; filename=\"source.pdf\""));
        Assert.assertTrue(multipart.contains("Content-Type: application/pdf"));
        Assert.assertTrue(multipart.contains("PDF_STREAM_SENTINEL"));
    }

    @Test
    public void docxShouldSendItsMimeTypeAndExplicitOcrChoice() throws Exception {
        respondSuccess("DOCX parsed", "source.docx");
        Path path = write("source.docx", new byte[]{'P', 'K', 3, 4, 1, 2, 3});

        adapter.parse(command(path, "source.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", true));

        String multipart = new String(requests.get(0).body(), StandardCharsets.ISO_8859_1);
        assertTextPart(multipart, "from_formats", "docx");
        assertTextPart(multipart, "do_ocr", "true");
        Assert.assertTrue(multipart.contains("name=\"files\"; filename=\"source.docx\""));
        Assert.assertTrue(multipart.contains(
                "Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    @Test
    public void shouldRetryOneTransportIoFailureAndThenParseSuccessfulResponse() throws Exception {
        closeWithoutResponse.set(1);
        Path path = write("retry.pdf", "%PDF-retry".getBytes(StandardCharsets.US_ASCII));

        ParsedDocument result = adapter.parse(command(path, "retry.pdf", "application/pdf", false));

        Assert.assertEquals("# Parsed\nremote body", result.normalizedMarkdown());
        Assert.assertEquals(2, requests.size());
    }

    @Test
    public void shouldNotRetryHttpOrJsonFailuresAndMustHideRemoteBodyAndKey() throws Exception {
        Path path = write("failed.pdf", "%PDF-failed".getBytes(StandardCharsets.US_ASCII));
        status.set(503);
        responseBody = (REMOTE_SECRET + API_KEY).getBytes(StandardCharsets.UTF_8);

        AppException httpError = expectFailure(
                () -> adapter.parse(command(path, "failed.pdf", "application/pdf", false)));

        Assert.assertEquals("RAG_DOCLING_HTTP_ERROR", httpError.getCode());
        assertSensitiveValuesHidden(httpError);
        Assert.assertEquals(1, requests.size());

        requests.clear();
        status.set(200);
        responseBody = "not-json".getBytes(StandardCharsets.UTF_8);
        AppException jsonError = expectFailure(
                () -> adapter.parse(command(path, "failed.pdf", "application/pdf", false)));
        Assert.assertEquals("RAG_DOCLING_RESPONSE_INVALID", jsonError.getCode());
        Assert.assertEquals(1, requests.size());
    }

    @Test
    public void shouldFailClosedForNullBlankOrNonSuccessMarkdownResponse() throws Exception {
        Path path = write("invalid.pdf", "%PDF-invalid".getBytes(StandardCharsets.US_ASCII));

        respond(Map.of("document", Map.of("filename", "invalid.pdf"),
                "status", "success", "processing_time", 0.1));
        assertInvalidResponse(path);

        respond(Map.of("document", Map.of("filename", "invalid.pdf", "md_content", "  "),
                "status", "success", "processing_time", 0.1));
        assertInvalidResponse(path);

        respond(Map.of("document", Map.of("filename", "invalid.pdf", "md_content", "body"),
                "status", "failure", "processing_time", 0.1));
        assertInvalidResponse(path);
    }

    @Test
    public void shouldBoundResponseAndRejectUnsafeFileNameBeforeRemoteCall() throws Exception {
        Path path = write("bounded.pdf", "%PDF-bounded".getBytes(StandardCharsets.US_ASCII));
        properties.getDocling().setMaxResponseBytes(64);
        responseBody = new byte[65];
        Arrays.fill(responseBody, (byte) 'x');

        Assert.assertEquals("RAG_DOCLING_RESPONSE_TOO_LARGE",
                expectFailure(() -> adapter.parse(command(path, "bounded.pdf", "application/pdf", false))).getCode());

        requests.clear();
        Assert.assertEquals("RAG_DOCUMENT_FILE_NAME_INVALID", expectFailure(() -> adapter.parse(
                command(path, "../bounded.pdf", "application/pdf", false))).getCode());
        Assert.assertTrue(requests.isEmpty());
    }

    private ParseCommand command(Path path, String fileName, String mimeType, boolean ocrEnabled) {
        try {
            return new ParseCommand("tenant", "job", "version", fileName, mimeType,
                    path, Files.size(path), ocrEnabled);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private Path write(String name, byte[] bytes) throws Exception {
        File file = temporaryFolder.newFile(name);
        Files.write(file.toPath(), bytes);
        return file.toPath();
    }

    private void respondSuccess(String markdown, String fileName) throws Exception {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("filename", fileName);
        document.put("md_content", markdown);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("document", document);
        response.put("status", "success");
        response.put("errors", List.of());
        response.put("processing_time", 0.125D);
        response.put("timings", Map.of());
        response.put("confidence", Map.of("mean_grade", "good"));
        respond(response);
    }

    private void respond(Map<String, ?> response) throws Exception {
        responseBody = objectMapper.writeValueAsBytes(response);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("X-Api-Key"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                exchange.getRequestHeaders().getFirst("Accept"),
                exchange.getRequestHeaders().getFirst("Content-Length"),
                exchange.getRequestHeaders().getFirst("Transfer-Encoding"), body));
        if (closeWithoutResponse.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            exchange.close();
            return;
        }
        byte[] currentResponse = responseBody == null ? new byte[0] : responseBody;
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(status.get(), currentResponse.length);
            exchange.getResponseBody().write(currentResponse);
        } catch (IOException ignored) {
            // 客户端在响应超限后会主动关闭流。
        } finally {
            exchange.close();
        }
    }

    private void assertTextPart(String multipart, String name, String value) {
        Assert.assertTrue(multipart.contains("name=\"" + name + "\"\r\n\r\n" + value + "\r\n"));
    }

    private int textPartCount(String multipart, String name) {
        String marker = "name=\"" + name + "\"\r\n\r\n";
        int count = 0;
        int cursor = 0;
        while ((cursor = multipart.indexOf(marker, cursor)) >= 0) {
            count++;
            cursor += marker.length();
        }
        return count;
    }

    private void assertInvalidResponse(Path path) {
        AppException exception = expectFailure(
                () -> adapter.parse(command(path, "invalid.pdf", "application/pdf", false)));
        Assert.assertEquals("RAG_DOCLING_RESPONSE_INVALID", exception.getCode());
    }

    private AppException expectFailure(ThrowingRunnable runnable) {
        try {
            runnable.run();
            Assert.fail("预期 Docling 适配器拒绝非法请求或响应");
            return null;
        } catch (AppException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("应转换为 AppException", e);
        }
    }

    private void assertSensitiveValuesHidden(AppException exception) {
        String rendered = exception.toString() + " " + exception.getInfo();
        Assert.assertFalse(rendered.contains(REMOTE_SECRET));
        Assert.assertFalse(rendered.contains(API_KEY));
    }

    private record CapturedRequest(String method, String path, String apiKey, String contentType,
                                   String accept, String contentLength, String transferEncoding, byte[] body) {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
