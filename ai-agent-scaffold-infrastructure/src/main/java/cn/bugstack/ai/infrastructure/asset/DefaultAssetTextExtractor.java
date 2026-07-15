package cn.bugstack.ai.infrastructure.asset;

import cn.bugstack.ai.domain.asset.adapter.AssetTextExtractor;
import cn.bugstack.ai.domain.asset.model.AssetParseResultEntity;
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

    static final int MAX_EXTRACTED_CHARS = 60_000;
    static final int MAX_PDF_PAGES = 200;
    static final int MAX_ERROR_CHARS = 240;

    /** 解析附件；参数是名称、MIME 和字节；返回受限文本结果。 */
    @Override
    public AssetParseResultEntity extract(String fileName, String mimeType, byte[] bytes) {
        String extension = extension(fileName);
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        try {
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
            return AssetParseResultEntity.builder().parseStatus("unsupported")
                    .errorSummary("当前格式不支持文本提取").build();
        } catch (Exception e) {
            return AssetParseResultEntity.builder().parseStatus("failed")
                    .errorSummary(safeError(e)).build();
        }
    }

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

    private String extractDocx(byte[] bytes) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = document.getParagraphs().stream().map(XWPFParagraph::getText)
                    .collect(Collectors.joining("\n"));
            return truncate(text);
        }
    }

    private AssetParseResultEntity ready(String text) {
        if (text == null || text.isBlank()) {
            return AssetParseResultEntity.builder().parseStatus("unsupported")
                    .errorSummary("未提取到可注入文本，文件已作为资产保存").build();
        }
        return AssetParseResultEntity.builder().parseStatus("ready").extractedText(text).build();
    }

    private String truncate(String value) {
        if (value == null) return null;
        String normalized = value.replace('\0', ' ').trim();
        return normalized.length() <= MAX_EXTRACTED_CHARS ? normalized : normalized.substring(0, MAX_EXTRACTED_CHARS);
    }

    private String safeError(Exception error) {
        String message = error.getMessage();
        String value = message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
        value = value.replace('\n', ' ').replace('\r', ' ');
        return value.length() <= MAX_ERROR_CHARS ? value : value.substring(0, MAX_ERROR_CHARS);
    }

    private boolean isImageExtension(String extension) {
        return extension.equals("png") || extension.equals("jpg") || extension.equals("jpeg")
                || extension.equals("gif") || extension.equals("webp") || extension.equals("bmp");
    }

    private String extension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
