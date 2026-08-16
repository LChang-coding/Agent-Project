package cn.bugstack.ai.infrastructure.document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * DOCX 包的局部兼容性修复器。
 * <p>只修复部分旧编辑器产生的 core.xml 时间属性缺少 xsi:type 问题，
 * 不修改正文、关系或媒体内容。</p>
 */
public final class DocxPackageCompatibility {

    /** 防止构造过多 ZIP 条目耗尽 CPU。 */
    private static final int MAX_ENTRY_COUNT = 4_096;
    /** 防止压缩炸弹在 POI 校验前被无界展开。 */
    private static final long MAX_INFLATED_BYTES = 100L * 1024 * 1024;
    /** 精确匹配 dcterms 时间属性的开始标签。 */
    private static final Pattern CORE_DATE = Pattern.compile(
            "(<dcterms:(?:created|modified)\\b)(?![^>]*\\bxsi:type\\s*=)([^>]*>)");
    /** coreProperties 根标签，用于补全 xsi 命名空间。 */
    private static final Pattern CORE_ROOT = Pattern.compile("<cp:coreProperties\\b([^>]*)>");

    private DocxPackageCompatibility() {
    }

    /**
     * 返回 POI 可正常打开的 DOCX 字节。
     * <p>无需修复时仍会校验 ZIP 边界，修复时只改写 docProps/core.xml。</p>
     */
    public static byte[] repairCoreDateTypes(byte[] source) throws IOException {
        if (source == null || source.length == 0) return source;
        ByteArrayOutputStream repairedPackage = new ByteArrayOutputStream(source.length);
        Set<String> entryNames = new HashSet<>();
        long inflatedBytes = 0;
        int entryCount = 0;
        boolean repaired = false;

        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(source));
             ZipOutputStream output = new ZipOutputStream(repairedPackage)) {
            ZipEntry entry;
            byte[] buffer = new byte[8_192];
            while ((entry = input.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRY_COUNT) {
                    throw new IOException("DOCX ZIP条目数超过限制");
                }
                if (!entryNames.add(entry.getName())) {
                    throw new IOException("DOCX包含有重复ZIP条目");
                }
                output.putNextEntry(new ZipEntry(entry.getName()));
                ByteArrayOutputStream core = "docProps/core.xml".equals(entry.getName())
                        ? new ByteArrayOutputStream() : null;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    inflatedBytes += read;
                    if (inflatedBytes > MAX_INFLATED_BYTES) {
                        throw new IOException("DOCX解压后大小超过限制");
                    }
                    if (core == null) output.write(buffer, 0, read);
                    else core.write(buffer, 0, read);
                }
                if (core != null) {
                    byte[] original = core.toByteArray();
                    byte[] compatible = repairCoreXml(original);
                    repaired |= compatible != original;
                    output.write(compatible);
                }
                output.closeEntry();
                input.closeEntry();
            }
        }
        return repaired ? repairedPackage.toByteArray() : source;
    }

    /** 仅为缺少类型的 created/modified 补充 OOXML 规范要求的 W3CDTF。 */
    private static byte[] repairCoreXml(byte[] original) {
        String xml = new String(original, StandardCharsets.UTF_8);
        Matcher dateMatcher = CORE_DATE.matcher(xml);
        if (!dateMatcher.find()) return original;
        String repaired = dateMatcher.replaceAll("$1 xsi:type=\"dcterms:W3CDTF\"$2");
        if (!repaired.contains("xmlns:xsi=")) {
            Matcher rootMatcher = CORE_ROOT.matcher(repaired);
            if (rootMatcher.find()) {
                repaired = rootMatcher.replaceFirst("<cp:coreProperties xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"$1>");
            }
        }
        return repaired.getBytes(StandardCharsets.UTF_8);
    }
}
