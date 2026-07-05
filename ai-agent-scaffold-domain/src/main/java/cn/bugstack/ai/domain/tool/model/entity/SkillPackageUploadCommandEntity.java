package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Skill 包上传命令。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillPackageUploadCommandEntity {

    /**
     * 操作用户上下文
     */
    private ToolUserContextEntity context;

    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * 内容类型
     */
    private String contentType;

    /**
     * 文件字节内容
     */
    private byte[] bytes;
}
