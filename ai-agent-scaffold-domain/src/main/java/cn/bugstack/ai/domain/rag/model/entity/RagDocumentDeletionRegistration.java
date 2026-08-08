package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;

import java.util.List;

/**
 * 文档删除状态、全部版本、删除任务与 Outbox 事件的原子登记内容。
 *
 * @param document 已进入删除中状态的逻辑文档
 * @param versions 同一文档下已进入删除中状态的全部版本
 * @param job 执行外部存储清理的删除任务
 * @param eventId Outbox 唤醒事件的唯一标识
 */
public record RagDocumentDeletionRegistration(RagDocumentEntity document,
                                              List<RagDocumentVersionEntity> versions,
                                              RagIngestJobEntity job,
                                              String eventId) {

    /** 校验文档、版本和删除任务的状态与资源范围一致。 */
    public RagDocumentDeletionRegistration {
        versions = versions == null ? List.of() : List.copyOf(versions);
        if (document == null || versions.isEmpty() || job == null || eventId == null || eventId.isBlank()
                || document.status() != RagDocumentStatus.DELETING
                || job.operation() != RagIngestOperation.DELETE
                || !document.tenantId().equals(job.tenantId())
                || !document.knowledgeBaseId().equals(job.knowledgeBaseId())
                || !document.documentId().equals(job.documentId())
                || versions.stream().anyMatch(version -> version == null
                || !document.tenantId().equals(version.tenantId())
                || !document.documentId().equals(version.documentId())
                || !document.knowledgeBaseId().equals(version.knowledgeBaseId())
                || version.status() != RagDocumentVersionStatus.DELETING)
                || versions.stream().noneMatch(version -> version.versionId().equals(job.versionId()))) {
            throw new IllegalArgumentException("RAG 文档删除登记参数非法");
        }
    }
}
