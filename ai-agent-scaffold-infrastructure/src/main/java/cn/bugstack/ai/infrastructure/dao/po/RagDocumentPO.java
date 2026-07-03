package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RagDocumentPO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * 文档上传者用户ID
     */
    private String ownerUserId;

    /**
     * 可见范围：private/tenant_public
     */
    private String visibility;

    /**
     * 知识库业务ID
     */
    private String knowledgeBaseId;

    /**
     * 文档业务ID
     */
    private String documentId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 来源类型：upload/url/text/oss
     */
    private String sourceType;

    /**
     * 来源地址
     */
    private String sourceUri;

    /**
     * 内容哈希
     */
    private String contentHash;

    /**
     * 文档状态：active/indexing/indexed/failed/deleted
     */
    private String status;

    /**
     * 扩展信息
     */
    private String metadata;
}
