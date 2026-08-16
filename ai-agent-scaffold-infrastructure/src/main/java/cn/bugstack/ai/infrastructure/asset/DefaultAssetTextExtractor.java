package cn.bugstack.ai.infrastructure.asset;

import cn.bugstack.ai.domain.asset.adapter.AssetTextExtractor;
import cn.bugstack.ai.domain.asset.model.AssetParseResultEntity;
import cn.bugstack.ai.infrastructure.document.DocxPackageCompatibility;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 默认附件文本提取器。
 * <p>只处理受支持格式并对页数、字符数和错误摘要进行限制。</p>
 */
@Service
public class DefaultAssetTextExtractor implements AssetTextExtractor {

    /** 限制可注入上下文长度，避免单附件挤占模型窗口。 */
    static final int MAX_EXTRACTED_CHARS = 60_000;
    /** 拒绝超大 PDF，控制解析内存和 CPU。 */
    static final int MAX_PDF_PAGES = 200;
    /** 错误摘要只保留诊断信息，不回传无限异常文本。 */
    static final int MAX_ERROR_CHARS = 240;

    /** 解析附件；参数是名称、MIME 和字节；返回受限文本结果。 */
    @Override
    public AssetParseResultEntity extract(String fileName, String mimeType, byte[] bytes) {
        String extension = extension(fileName);
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        try {
            // MIME 与扩展名任一命中白名单即可解析，兼容浏览器缺失或泛化 MIME。
            if (mime.startsWith("text/") || extension.equals("txt") || extension.equals("md")
                    || extension.equals("markdown") || extension.equals("csv") || extension.equals("json")) {
                return ready(truncate(new String(bytes, StandardCharsets.UTF_8)));
            }
            if (mime.equals("application/pdf") || extension.equals("pdf")) {
                return ready(extractPdf(bytes));
            }
            if (mime.contains("wordprocessingml") || extension.equals("docx")) {
                return ready(extractDocx(bytes));
            }
            if (mime.startsWith("image/") || isImageExtension(extension)) {
                return AssetParseResultEntity.builder().parseStatus("unsupported")
                        .errorSummary("图片已保存，本阶段不做 OCR 或模型注入").build();
            }
            // 未知二进制格式只保存资产，禁止猜测编码后污染模型上下文。
            return AssetParseResultEntity.builder().parseStatus("unsupported")
                    .errorSummary("当前格式不支持文本提取").build();
        } catch (Exception e) {
            // 解析失败降级为资产可用、文本不可用，不让附件阻断整轮对话。
            return AssetParseResultEntity.builder().parseStatus("failed")
                    .errorSummary(safeError(e)).build();
        }
    }

    /** 提取 PDF 可见文本；超过页数上限直接拒绝。 */
    private String extractPdf(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new IllegalArgumentException("PDF 页数超过 " + MAX_PDF_PAGES + " 页限制");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(MAX_PDF_PAGES);
            return truncate(stripper.getText(document));
        }
    }

    /** 提取 DOCX 正文段落；表格等复杂结构不在聊天附件轻解析范围内。 */
    private String extractDocx(byte[] bytes) throws Exception {
        byte[] compatibleBytes = DocxPackageCompatibility.repairCoreDateTypes(bytes);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(compatibleBytes))) {
            String text = document.getParagraphs().stream().map(XWPFParagraph::getText)
                    .collect(Collectors.joining("\n"));
            return truncate(text);
        }
    }

    /** 将非空文本标记为可注入；空内容仍保留原始资产。 */
    private AssetParseResultEntity ready(String text) {
        if (text == null || text.isBlank()) {
            return AssetParseResultEntity.builder().parseStatus("unsupported")
                    .errorSummary("未提取到可注入文本，文件已作为资产保存").build();
        }
        return AssetParseResultEntity.builder().parseStatus("ready").extractedText(text).build();
    }

    /** 清除 NUL 并硬截断，保证下游提示词输入有界。 */
    private String truncate(String value) {
        if (value == null) return null;
        String normalized = value.replace('\0', ' ').trim();
        return normalized.length() <= MAX_EXTRACTED_CHARS ? normalized : normalized.substring(0, MAX_EXTRACTED_CHARS);
    }

    /** 压平并截断异常消息，避免日志换行或超长内容进入接口响应。 */
    private String safeError(Exception error) {
        String message = error.getMessage();
        String value = message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
        value = value.replace('\n', ' ').replace('\r', ' ');
        return value.length() <= MAX_ERROR_CHARS ? value : value.substring(0, MAX_ERROR_CHARS);
    }

    /** 判断扩展名是否属于只保存、不注入的图片格式。 */
    private boolean isImageExtension(String extension) {
        return extension.equals("png") || extension.equals("jpg") || extension.equals("jpeg")
                || extension.equals("gif") || extension.equals("webp") || extension.equals("bmp");
    }

    /** 仅取最后一个点后的扩展名并统一为小写。 */
    private String extension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
