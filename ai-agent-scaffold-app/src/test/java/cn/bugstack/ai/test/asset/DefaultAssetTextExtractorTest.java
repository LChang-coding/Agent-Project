package cn.bugstack.ai.test.asset;

import cn.bugstack.ai.domain.asset.model.AssetParseResultEntity;
import cn.bugstack.ai.infrastructure.asset.DefaultAssetTextExtractor;
import org.junit.Assert;
import org.junit.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 默认附件解析器测试。
 */
public class DefaultAssetTextExtractorTest {

    @Test
    public void shouldExtractDocxWhenCoreCreatedPropertyMissesXsiType() throws Exception {
        byte[] malformedDocx = docxWithoutCorePropertyType("Spring Boot 实验正文");

        AssetParseResultEntity result = new DefaultAssetTextExtractor().extract(
                "实验5 Spring Boot基础_已完成.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                malformedDocx);

        Assert.assertEquals("ready", result.getParseStatus());
        Assert.assertEquals("Spring Boot 实验正文", result.getExtractedText());
        Assert.assertNull(result.getErrorSummary());
    }

    @Test
    public void shouldExtractTextAndKeepImageReadyWithoutPromptText() {
        DefaultAssetTextExtractor extractor = new DefaultAssetTextExtractor();
        AssetParseResultEntity text = extractor.extract("readme.md", "text/markdown",
                "hello".getBytes(StandardCharsets.UTF_8));
        AssetParseResultEntity image = extractor.extract("photo.png", "image/png", new byte[]{1, 2, 3});

        Assert.assertEquals("ready", text.getParseStatus());
        Assert.assertEquals("hello", text.getExtractedText());
        Assert.assertEquals("unsupported", image.getParseStatus());
        Assert.assertNull(image.getExtractedText());
    }

    @Test
    public void shouldRejectUnsupportedBinaryFromPrompt() {
        AssetParseResultEntity result = new DefaultAssetTextExtractor().extract("archive.zip",
                "application/zip", new byte[]{1, 2, 3});
        Assert.assertEquals("unsupported", result.getParseStatus());
        Assert.assertNull(result.getExtractedText());
    }

    /** 构造一份正文完好、但 core.xml 时间属性缺少 xsi:type 的真实兼容性样本。 */
    private byte[] docxWithoutCorePropertyType(String body) throws Exception {
        ByteArrayOutputStream validOutput = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(body);
            document.getProperties().getCoreProperties().setCreated("2026-08-16T09:00:00Z");
            document.write(validOutput);
        }

        ByteArrayOutputStream malformedOutput = new ByteArrayOutputStream();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(validOutput.toByteArray()));
             ZipOutputStream output = new ZipOutputStream(malformedOutput)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                byte[] content = input.readAllBytes();
                if ("docProps/core.xml".equals(entry.getName())) {
                    String xml = new String(content, StandardCharsets.UTF_8)
                            .replaceAll("\\s+xsi:type=\"dcterms:W3CDTF\"", "");
                    content = xml.getBytes(StandardCharsets.UTF_8);
                }
                output.write(content);
                output.closeEntry();
            }
        }
        return malformedOutput.toByteArray();
    }
}
