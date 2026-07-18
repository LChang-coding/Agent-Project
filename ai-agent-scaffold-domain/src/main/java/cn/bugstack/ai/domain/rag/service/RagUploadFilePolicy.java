package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.model.valobj.RagUploadFileCandidate;
import cn.bugstack.ai.domain.rag.model.valobj.RagValidatedUploadFile;
import cn.bugstack.ai.types.exception.AppException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * RAG 上传文件安全策略。
 * <p>以受控 Path 流式验证文件，不把最大 50 MiB 的内容整体载入堆内存。</p>
 */
public final class RagUploadFilePolicy {

    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final int TEXT_BUFFER_CHARS = 8 * 1024;
    private static final int MAX_ZIP_ENTRIES = 4_096;
    private static final long MAX_ZIP_ENTRY_BYTES = 32L * 1024 * 1024;
    private static final long MAX_ZIP_TOTAL_BYTES = 100L * 1024 * 1024;
    private static final String CONTENT_TYPES_ENTRY = "[Content_Types].xml";
    private static final String DOCUMENT_ENTRY = "word/document.xml";

    private static final Map<String, SupportedType> TYPES = Map.of(
            "pdf", new SupportedType("pdf", "application/pdf", Set.of("application/pdf")),
            "docx", new SupportedType("docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
            "md", new SupportedType("md", "text/markdown",
                    Set.of("text/markdown", "text/x-markdown", "text/plain")),
            "markdown", new SupportedType("md", "text/markdown",
                    Set.of("text/markdown", "text/x-markdown", "text/plain")));

    /**
     * 校验受控临时文件并返回可信元数据。
     *
     * @param candidate 待校验文件
     * @return 规范化文件信息
     */
    public RagValidatedUploadFile validate(RagUploadFileCandidate candidate) {
        if (candidate == null) {
            throw error("RAG_FILE_INVALID", "上传文件不能为空");
        }
        validateDeclaredSize(candidate.declaredSize());
        Path path = normalizeControlledPath(candidate.path());
        long actualSize = readAndValidateSize(path, candidate.declaredSize());
        SafeName safeName = normalizeFileName(candidate.originalFileName());
        SupportedType type = requireSupportedType(safeName.extension());
        validateDeclaredMime(candidate.declaredMimeType(), type);
        validateContent(path, type);
        ensureUnchangedSize(path, actualSize);
        return new RagValidatedUploadFile(path, safeName.baseName() + "." + type.canonicalExtension(),
                type.canonicalExtension(), type.canonicalMime(), actualSize);
    }

    private void validateDeclaredSize(long declaredSize) {
        if (declaredSize == 0) {
            throw error("RAG_FILE_EMPTY", "上传文件不能为空");
        }
        if (declaredSize < 0 || declaredSize > MAX_FILE_BYTES) {
            throw error("RAG_FILE_TOO_LARGE", "单个知识库文件不能超过 50 MiB");
        }
    }

    private Path normalizeControlledPath(Path input) {
        Path path = input.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(path)) {
            throw error("RAG_FILE_PATH_INVALID", "上传文件不在可读取的受控路径中");
        }
        return path;
    }

    private long readAndValidateSize(Path path, long declaredSize) {
        try {
            long actualSize = Files.size(path);
            if (actualSize == 0) {
                throw error("RAG_FILE_EMPTY", "上传文件不能为空");
            }
            if (actualSize > MAX_FILE_BYTES) {
                throw error("RAG_FILE_TOO_LARGE", "单个知识库文件不能超过 50 MiB");
            }
            if (actualSize != declaredSize) {
                throw error("RAG_FILE_SIZE_MISMATCH", "上传文件长度与声明值不一致");
            }
            return actualSize;
        } catch (IOException e) {
            throw new AppException("RAG_FILE_READ_FAILED", "无法读取上传文件长度", e);
        }
    }

    private SafeName normalizeFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            throw error("RAG_FILE_NAME_INVALID", "文件名不能为空");
        }
        String normalized = Normalizer.normalize(originalFileName, Normalizer.Form.NFKC).trim()
                .replaceAll("\\s+", " ");
        if (normalized.length() > MAX_FILE_NAME_LENGTH || normalized.equals(".") || normalized.equals("..")
                || normalized.startsWith(".") || normalized.endsWith(".") || normalized.contains("..")
                || normalized.indexOf('/') >= 0 || normalized.indexOf('\\') >= 0
                || normalized.indexOf(':') >= 0 || containsUnsafeCharacter(normalized)) {
            throw error("RAG_FILE_NAME_INVALID", "文件名包含不安全字符或路径片段");
        }
        int dot = normalized.lastIndexOf('.');
        if (dot < 1 || dot == normalized.length() - 1) {
            throw error("RAG_FILE_EXTENSION_UNSUPPORTED", "文件扩展名不受支持");
        }
        String baseName = normalized.substring(0, dot).trim();
        String extension = normalized.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (baseName.isBlank()) {
            throw error("RAG_FILE_NAME_INVALID", "文件名主体不能为空");
        }
        return new SafeName(baseName, extension);
    }

    private boolean containsUnsafeCharacter(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || !(Character.isLetterOrDigit(codePoint) || codePoint == ' ' || codePoint == '_'
                || codePoint == '-' || codePoint == '.' || codePoint == '(' || codePoint == ')'
                || codePoint == '[' || codePoint == ']'));
    }

    private SupportedType requireSupportedType(String extension) {
        SupportedType type = TYPES.get(extension);
        if (type == null) {
            throw error("RAG_FILE_EXTENSION_UNSUPPORTED", "仅支持 PDF、DOCX 和 Markdown 文件");
        }
        return type;
    }

    private void validateDeclaredMime(String declaredMimeType, SupportedType type) {
        if (declaredMimeType == null || declaredMimeType.isBlank()) {
            throw error("RAG_FILE_MIME_INVALID", "文件 MIME 不能为空");
        }
        String normalized = declaredMimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!type.allowedDeclaredMimes().contains(normalized)) {
            throw error("RAG_FILE_MIME_MISMATCH", "文件扩展名与声明 MIME 不一致");
        }
    }

    private void validateContent(Path path, SupportedType type) {
        try {
            switch (type.canonicalExtension()) {
                case "pdf" -> validatePdf(path);
                case "docx" -> validateDocx(path);
                case "md" -> validateUtf8Markdown(path);
                default -> throw error("RAG_FILE_EXTENSION_UNSUPPORTED", "文件扩展名不受支持");
            }
        } catch (AppException e) {
            throw e;
        } catch (IOException e) {
            throw new AppException("RAG_FILE_READ_FAILED", "读取上传文件失败", e);
        }
    }

    private void validatePdf(Path path) throws IOException {
        byte[] prefix = readPrefix(path, 5);
        byte[] expected = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        if (prefix.length < expected.length) {
            throw error("RAG_FILE_MAGIC_MISMATCH", "PDF 文件头不合法");
        }
        for (int index = 0; index < expected.length; index++) {
            if (prefix[index] != expected[index]) {
                throw error("RAG_FILE_MAGIC_MISMATCH", "PDF 文件头不合法");
            }
        }
    }

    private void validateDocx(Path path) throws IOException {
        validateZipMagic(path);
        try (ZipFile zipFile = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            if (zipFile.size() == 0 || zipFile.size() > MAX_ZIP_ENTRIES) {
                throw error("RAG_FILE_ZIP_BOMB", "DOCX ZIP 条目数量超过安全限制");
            }
            long totalDeclaredBytes = 0L;
            boolean contentTypesFound = false;
            boolean documentFound = false;
            Set<String> entryNames = new HashSet<>();
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = validateZipEntryName(entry.getName());
                if (!entryNames.add(entryName)) {
                    throw error("RAG_FILE_ZIP_ENTRY_INVALID", "DOCX 包含重复 ZIP 条目");
                }
                long size = entry.getSize();
                long compressedSize = entry.getCompressedSize();
                if (size < 0 || compressedSize < 0) {
                    throw error("RAG_FILE_ZIP_ENTRY_INVALID", "DOCX ZIP 条目大小未知");
                }
                if (size > MAX_ZIP_ENTRY_BYTES) {
                    throw error("RAG_FILE_ZIP_BOMB", "DOCX 单个 ZIP 条目声明解压大小超过限制");
                }
                try {
                    totalDeclaredBytes = Math.addExact(totalDeclaredBytes, size);
                } catch (ArithmeticException e) {
                    throw error("RAG_FILE_ZIP_BOMB", "DOCX ZIP 声明解压大小溢出");
                }
                if (totalDeclaredBytes > MAX_ZIP_TOTAL_BYTES) {
                    throw error("RAG_FILE_ZIP_BOMB", "DOCX ZIP 总声明解压大小超过限制");
                }
                if (CONTENT_TYPES_ENTRY.equals(entryName)) {
                    contentTypesFound = !entry.isDirectory() && size > 0;
                } else if (DOCUMENT_ENTRY.equals(entryName)) {
                    documentFound = !entry.isDirectory() && size > 0;
                }
            }
            if (!contentTypesFound || !documentFound) {
                throw error("RAG_FILE_DOCX_STRUCTURE_INVALID", "DOCX 缺少必要的 OOXML 条目");
            }
        } catch (AppException e) {
            throw e;
        } catch (IOException e) {
            throw new AppException("RAG_FILE_DOCX_INVALID", "DOCX 不是可读取的 OOXML 文件", e);
        }
    }

    private void validateZipMagic(Path path) throws IOException {
        byte[] prefix = readPrefix(path, 4);
        boolean zip = prefix.length == 4 && prefix[0] == 'P' && prefix[1] == 'K'
                && ((prefix[2] == 3 && prefix[3] == 4)
                || (prefix[2] == 5 && prefix[3] == 6)
                || (prefix[2] == 7 && prefix[3] == 8));
        if (!zip) {
            throw error("RAG_FILE_MAGIC_MISMATCH", "DOCX 不是合法的 ZIP 容器");
        }
    }

    private String validateZipEntryName(String rawName) {
        if (rawName == null || rawName.isBlank() || rawName.startsWith("/") || rawName.startsWith("\\")
                || rawName.indexOf('\\') >= 0 || rawName.indexOf(':') >= 0 || rawName.indexOf('\0') >= 0
                || rawName.contains("//")) {
            throw error("RAG_FILE_ZIP_ENTRY_INVALID", "DOCX ZIP 条目路径不安全");
        }
        String[] segments = rawName.split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            boolean trailingDirectoryMarker = index == segments.length - 1 && segment.isEmpty();
            if (!trailingDirectoryMarker && (segment.isBlank() || segment.equals(".") || segment.equals(".."))) {
                throw error("RAG_FILE_ZIP_ENTRY_INVALID", "DOCX ZIP 条目包含路径穿越片段");
            }
        }
        return rawName;
    }

    private byte[] readPrefix(Path path, int length) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(length);
        }
    }

    private void validateUtf8Markdown(Path path) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), decoder)) {
            char[] buffer = new char[TEXT_BUFFER_CHARS];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                for (int index = 0; index < count; index++) {
                    if (buffer[index] == '\0') {
                        throw error("RAG_FILE_TEXT_NUL", "Markdown 文件不能包含 NUL 字符");
                    }
                }
            }
        } catch (CharacterCodingException e) {
            throw error("RAG_FILE_TEXT_ENCODING_INVALID", "Markdown 文件必须使用 UTF-8 编码");
        }
    }

    private void ensureUnchangedSize(Path path, long expectedSize) {
        try {
            if (Files.size(path) != expectedSize) {
                throw error("RAG_FILE_CHANGED_DURING_VALIDATION", "上传文件在校验过程中发生变化");
            }
        } catch (IOException e) {
            throw new AppException("RAG_FILE_READ_FAILED", "复核上传文件长度失败", e);
        }
    }

    private AppException error(String code, String message) {
        return new AppException(code, message);
    }

    private record SafeName(String baseName, String extension) {
    }

    private record SupportedType(String canonicalExtension, String canonicalMime,
                                 Set<String> allowedDeclaredMimes) {
    }
}
