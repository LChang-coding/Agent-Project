package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.model.valobj.RagUploadFileCandidate;
import cn.bugstack.ai.domain.rag.model.valobj.RagValidatedUploadFile;
import cn.bugstack.ai.domain.rag.service.RagUploadFilePolicy;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * RAG 上传文件格式、长度、MIME 与 magic 安全测试。
 */
public class RagUploadFilePolicyTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final RagUploadFilePolicy policy = new RagUploadFilePolicy();

    @Test
    public void shouldAcceptPdfAndReturnCanonicalMetadata() throws Exception {
        Path path = write("upload-pdf.bin", "%PDF-1.7\nbody".getBytes(StandardCharsets.US_ASCII));

        RagValidatedUploadFile result = validate(path, "产品 手册.PDF", "application/pdf");

        Assert.assertEquals("产品 手册.pdf", result.safeFileName());
        Assert.assertEquals("pdf", result.extension());
        Assert.assertEquals("application/pdf", result.mimeType());
        Assert.assertTrue(result.path().isAbsolute());
    }

    @Test
    public void shouldAcceptMinimalOpenableDocxContainer() throws Exception {
        Path path = write("upload-docx.bin", minimalDocxEntries());

        RagValidatedUploadFile result = validate(path, "企业制度.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        Assert.assertEquals("企业制度.docx", result.safeFileName());
        Assert.assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                result.mimeType());
    }

    @Test
    public void shouldRejectPseudoZipWithoutCentralDirectory() throws Exception {
        Path path = write("pseudo-docx.bin", new byte[]{'P', 'K', 3, 4, 1, 2, 3});

        assertAppException("RAG_FILE_DOCX_INVALID", () -> validate(path, "manual.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    @Test
    public void shouldRejectDocxMissingRequiredOoxmlEntries() throws Exception {
        Path missingContentTypes = write("missing-content-types.bin", zip(Map.of(
                "word/document.xml", "<document/>".getBytes(StandardCharsets.UTF_8))));
        Path missingDocument = write("missing-document.bin", zip(Map.of(
                "[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8))));

        assertAppException("RAG_FILE_DOCX_STRUCTURE_INVALID", () -> validate(missingContentTypes,
                "missing.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertAppException("RAG_FILE_DOCX_STRUCTURE_INVALID", () -> validate(missingDocument,
                "missing.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    @Test
    public void shouldRejectDocxZipPathTraversalEntry() throws Exception {
        Map<String, byte[]> entries = minimalDocxEntryMap();
        entries.put("../outside.xml", "evil".getBytes(StandardCharsets.UTF_8));
        Path path = write("path-traversal.bin", zip(entries));

        assertAppException("RAG_FILE_ZIP_ENTRY_INVALID", () -> validate(path, "manual.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    @Test
    public void shouldRejectDocxSingleEntryDeclaredUncompressedSizeBomb() throws Exception {
        Map<String, byte[]> entries = minimalDocxEntryMap();
        entries.put("word/media/filler.bin", new byte[]{1});
        byte[] zip = zip(entries);
        patchCentralDirectoryUncompressedSize(zip, "word/media/filler.bin", 33L * 1024 * 1024);
        Path path = write("single-entry-bomb.bin", zip);

        assertAppException("RAG_FILE_ZIP_BOMB", () -> validate(path, "manual.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    @Test
    public void shouldRejectDocxTotalDeclaredUncompressedSizeBomb() throws Exception {
        Map<String, byte[]> entries = minimalDocxEntryMap();
        for (int index = 0; index < 4; index++) {
            entries.put("word/media/filler-" + index + ".bin", new byte[]{1});
        }
        byte[] zip = zip(entries);
        for (int index = 0; index < 4; index++) {
            patchCentralDirectoryUncompressedSize(zip, "word/media/filler-" + index + ".bin",
                    30L * 1024 * 1024);
        }
        Path path = write("total-size-bomb.bin", zip);

        assertAppException("RAG_FILE_ZIP_BOMB", () -> validate(path, "manual.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    @Test
    public void shouldRejectDocxExceedingZipEntryCountLimit() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int index = 0; index < 4_097; index++) {
            entries.put("custom/item-" + index + ".xml", new byte[]{1});
        }
        Path path = write("too-many-entries.bin", zip(entries));

        assertAppException("RAG_FILE_ZIP_BOMB", () -> validate(path, "manual.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    @Test
    public void shouldAcceptUtf8MarkdownAndNormalizeExtension() throws Exception {
        Path path = write("upload-markdown.bin", "# 标题\n正文".getBytes(StandardCharsets.UTF_8));

        RagValidatedUploadFile result = validate(path, "  知识   说明.MARKDOWN  ", "text/plain; charset=UTF-8");

        Assert.assertEquals("知识 说明.md", result.safeFileName());
        Assert.assertEquals("md", result.extension());
        Assert.assertEquals("text/markdown", result.mimeType());
    }

    @Test
    public void shouldRejectUnsafeFileNamesAndUnsupportedExtensions() throws Exception {
        Path path = write("upload-name.bin", "text".getBytes(StandardCharsets.UTF_8));

        assertAppException("RAG_FILE_NAME_INVALID",
                () -> validate(path, "../secret.md", "text/markdown"));
        assertAppException("RAG_FILE_NAME_INVALID",
                () -> validate(path, "C:\\fakepath\\secret.md", "text/markdown"));
        assertAppException("RAG_FILE_EXTENSION_UNSUPPORTED",
                () -> validate(path, "secret.txt", "text/plain"));
    }

    @Test
    public void shouldRejectDeclaredMimeMismatch() throws Exception {
        Path path = write("upload-mime.bin", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));

        assertAppException("RAG_FILE_MIME_MISMATCH",
                () -> validate(path, "manual.pdf", "text/plain"));
        assertAppException("RAG_FILE_MIME_INVALID",
                () -> validate(path, "manual.pdf", null));
    }

    @Test
    public void shouldRejectWrongPdfAndDocxMagic() throws Exception {
        Path fakePdf = write("fake-pdf.bin", "not-a-pdf".getBytes(StandardCharsets.UTF_8));
        Path fakeDocx = write("fake-docx.bin", "not-a-zip".getBytes(StandardCharsets.UTF_8));

        assertAppException("RAG_FILE_MAGIC_MISMATCH",
                () -> validate(fakePdf, "manual.pdf", "application/pdf"));
        assertAppException("RAG_FILE_MAGIC_MISMATCH", () -> validate(fakeDocx, "manual.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    @Test
    public void shouldRejectMalformedUtf8AndNulMarkdown() throws Exception {
        Path malformed = write("malformed.bin", new byte[]{(byte) 0xC3, 0x28});
        Path nul = write("nul.bin", new byte[]{'#', ' ', 'A', 0, 'B'});

        assertAppException("RAG_FILE_TEXT_ENCODING_INVALID",
                () -> validate(malformed, "bad.md", "text/markdown"));
        assertAppException("RAG_FILE_TEXT_NUL",
                () -> validate(nul, "bad.md", "text/markdown"));
    }

    @Test
    public void shouldRejectEmptyMismatchedAndOversizedDeclarations() throws Exception {
        Path empty = write("empty.bin", new byte[0]);
        Path markdown = write("size.bin", "body".getBytes(StandardCharsets.UTF_8));
        long actualSize = Files.size(markdown);

        assertAppException("RAG_FILE_EMPTY",
                () -> validate(empty, "empty.md", "text/markdown"));
        assertAppException("RAG_FILE_SIZE_MISMATCH", () -> policy.validate(new RagUploadFileCandidate(
                markdown, actualSize + 1, "size.md", "text/markdown")));
        assertAppException("RAG_FILE_TOO_LARGE", () -> policy.validate(new RagUploadFileCandidate(
                markdown, RagUploadFilePolicy.MAX_FILE_BYTES + 1, "size.md", "text/markdown")));
    }

    @Test
    public void shouldRejectMissingOrNonRegularControlledPath() throws Exception {
        Path missing = temporaryFolder.getRoot().toPath().resolve("missing.md");
        Path directory = temporaryFolder.newFolder("folder.md").toPath();

        assertAppException("RAG_FILE_PATH_INVALID", () -> policy.validate(new RagUploadFileCandidate(
                missing, 1L, "missing.md", "text/markdown")));
        assertAppException("RAG_FILE_PATH_INVALID", () -> policy.validate(new RagUploadFileCandidate(
                directory, 1L, "folder.md", "text/markdown")));
    }

    private RagValidatedUploadFile validate(Path path, String fileName, String mimeType) {
        try {
            return policy.validate(new RagUploadFileCandidate(path, Files.size(path), fileName, mimeType));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private Path write(String name, byte[] bytes) throws Exception {
        File file = temporaryFolder.newFile(name);
        Files.write(file.toPath(), bytes);
        return file.toPath();
    }

    private byte[] minimalDocxEntries() throws Exception {
        return zip(minimalDocxEntryMap());
    }

    private Map<String, byte[]> minimalDocxEntryMap() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>").getBytes(StandardCharsets.UTF_8));
        entries.put("_rels/.rels", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>").getBytes(StandardCharsets.UTF_8));
        entries.put("word/document.xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t>RAG test</w:t></w:r></w:p></w:body></w:document>")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("word/_rels/document.xml.rels", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>")
                .getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutput = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zipOutput.putNextEntry(new ZipEntry(entry.getKey()));
                zipOutput.write(entry.getValue());
                zipOutput.closeEntry();
            }
        }
        return output.toByteArray();
    }

    /**
     * 只改写 ZIP 中央目录的声明解压大小，用很小的测试文件触发 zip bomb 防护。
     */
    private void patchCentralDirectoryUncompressedSize(byte[] zip, String targetName, long declaredSize) {
        for (int offset = 0; offset <= zip.length - 46; offset++) {
            if (readIntLittleEndian(zip, offset) != 0x02014b50) {
                continue;
            }
            int nameLength = readUnsignedShortLittleEndian(zip, offset + 28);
            int extraLength = readUnsignedShortLittleEndian(zip, offset + 30);
            int commentLength = readUnsignedShortLittleEndian(zip, offset + 32);
            int nextOffset = offset + 46 + nameLength + extraLength + commentLength;
            if (nextOffset > zip.length) {
                throw new AssertionError("ZIP 中央目录越界");
            }
            String name = new String(zip, offset + 46, nameLength, StandardCharsets.UTF_8);
            if (targetName.equals(name)) {
                writeUnsignedIntLittleEndian(zip, offset + 24, declaredSize);
                return;
            }
            offset = nextOffset - 1;
        }
        throw new AssertionError("未找到 ZIP 中央目录条目：" + targetName);
    }

    private int readUnsignedShortLittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private int readIntLittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private void writeUnsignedIntLittleEndian(byte[] bytes, int offset, long value) {
        for (int index = 0; index < 4; index++) {
            bytes[offset + index] = (byte) (value >>> (index * 8));
        }
    }

    private void assertAppException(String code, Runnable action) {
        try {
            action.run();
            Assert.fail("预期抛出领域异常：" + code);
        } catch (AppException e) {
            Assert.assertEquals(code, e.getCode());
        }
    }
}
