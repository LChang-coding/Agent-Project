package cn.bugstack.ai.domain.tool.service.support;

import cn.bugstack.ai.domain.tool.model.SkillPackageProperties;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Skill ZIP 有界读取器。
 * <p>统一限制 entry 数量和单个文件的展开字节，避免异常压缩包占用过多内存。</p>
 */
@Component
public class SkillPackageReader {

    private static final String INVALID_CODE = "TOOL_SKILL_PACKAGE_INVALID";
    private static final int BUFFER_SIZE = 8192;

    private final SkillPackageProperties properties;

    /** 创建 Skill 包读取器；参数是解压边界；返回读取器实例。 */
    public SkillPackageReader(SkillPackageProperties properties) {
        this.properties = properties;
    }

    /** 读取 Skill 包内的 SKILL.md；参数是 ZIP 字节；返回 UTF-8 文本。 */
    public String readSkillMd(byte[] bytes) {
        validateLimits();
        if (!hasZipSignature(bytes)) {
            throw invalid("Skill 包不是有效的 ZIP 文件");
        }
        int entryCount = 0;
        byte[] skillBytes = null;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > properties.getMaxEntries()) {
                    throw invalid("Skill 包文件数超过上限 " + properties.getMaxEntries());
                }
                if (entry.isDirectory() || !entry.getName().endsWith("SKILL.md") || skillBytes != null) {
                    continue;
                }
                if (entry.getSize() > properties.getMaxEntryBytes()) {
                    throw entryTooLarge();
                }
                skillBytes = readEntry(input);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(INVALID_CODE, "Skill 包 ZIP 结构损坏：" + readableMessage(e), e);
        }
        if (skillBytes == null) {
            throw invalid("Skill 包必须包含 SKILL.md");
        }
        return decodeUtf8(skillBytes);
    }

    private byte[] readEntry(ZipInputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                Math.min(BUFFER_SIZE, properties.getMaxEntryBytes()));
        byte[] buffer = new byte[BUFFER_SIZE];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total > properties.getMaxEntryBytes() - read) {
                throw entryTooLarge();
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new AppException(INVALID_CODE, "SKILL.md 必须是有效的 UTF-8 文本", e);
        }
    }

    private void validateLimits() {
        if (properties == null || properties.getMaxEntries() <= 0 || properties.getMaxEntryBytes() <= 0) {
            throw new IllegalStateException("Skill 包解压边界配置不合法");
        }
    }

    private boolean hasZipSignature(byte[] bytes) {
        return bytes != null && bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && ((bytes[2] == 3 && bytes[3] == 4) || (bytes[2] == 5 && bytes[3] == 6));
    }

    private AppException entryTooLarge() {
        return invalid("SKILL.md 展开字节超过上限 " + properties.getMaxEntryBytes());
    }

    private AppException invalid(String message) {
        return new AppException(INVALID_CODE, message);
    }

    private String readableMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }
}
