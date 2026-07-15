package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.domain.tool.model.SkillPackageProperties;
import cn.bugstack.ai.domain.tool.service.support.SkillPackageReader;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Skill ZIP 有界读取测试。
 */
public class SkillPackageReaderTest {

    @Test
    public void shouldReadNestedSkillMarkdownAsUtf8() throws Exception {
        SkillPackageReader reader = reader(8, 1024);
        byte[] zip = zip(Map.of("demo/SKILL.md", "---\nname: 测试\n---\n请执行任务".getBytes(StandardCharsets.UTF_8)));

        String result = reader.readSkillMd(zip);

        Assert.assertTrue(result.contains("name: 测试"));
        Assert.assertTrue(result.contains("请执行任务"));
    }

    @Test
    public void shouldRejectPackageWithoutSkillMarkdown() throws Exception {
        AppException error = expectInvalid(reader(8, 1024), zip(Map.of("README.md", bytes("readme"))));

        Assert.assertTrue(error.getInfo().contains("必须包含 SKILL.md"));
    }

    @Test
    public void shouldRejectWhenTrailingEntriesExceedLimitAfterSkillMarkdown() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("SKILL.md", bytes("skill"));
        entries.put("a.txt", bytes("a"));
        entries.put("b.txt", bytes("b"));

        AppException error = expectInvalid(reader(2, 1024), zip(entries));

        Assert.assertTrue(error.getInfo().contains("文件数超过上限 2"));
    }

    @Test
    public void shouldRejectHighlyCompressedSkillMarkdownBeyondExpandedByteLimit() throws Exception {
        byte[] expanded = new byte[64 * 1024];
        java.util.Arrays.fill(expanded, (byte) 'A');
        byte[] zip = zip(Map.of("SKILL.md", expanded));
        Assert.assertTrue(zip.length < expanded.length / 4);

        AppException error = expectInvalid(reader(8, 1024), zip);

        Assert.assertTrue(error.getInfo().contains("展开字节超过上限 1024"));
    }

    @Test
    public void shouldRejectInvalidZipAndMalformedUtf8() throws Exception {
        AppException invalidZip = expectInvalid(reader(8, 1024), bytes("not-a-zip"));
        Assert.assertTrue(invalidZip.getInfo().contains("不是有效的 ZIP"));

        AppException invalidUtf8 = expectInvalid(reader(8, 1024),
                zip(Map.of("SKILL.md", new byte[]{(byte) 0xC3, (byte) 0x28})));
        Assert.assertTrue(invalidUtf8.getInfo().contains("有效的 UTF-8"));
    }

    private static SkillPackageReader reader(int maxEntries, int maxEntryBytes) {
        SkillPackageProperties properties = new SkillPackageProperties();
        properties.setMaxEntries(maxEntries);
        properties.setMaxEntryBytes(maxEntryBytes);
        return new SkillPackageReader(properties);
    }

    private static AppException expectInvalid(SkillPackageReader reader, byte[] zip) {
        try {
            reader.readSkillMd(zip);
            Assert.fail("异常 Skill 包必须被拒绝");
            return null;
        } catch (AppException error) {
            Assert.assertEquals("TOOL_SKILL_PACKAGE_INVALID", error.getCode());
            return error;
        }
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
