package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;

import java.util.List;

/** 需要在同一数据库事务中登记的文档删除墓碑、全部版本、任务与唤醒事件。 */
public record RagDocumentDeletionRegistration(RagDocumentEntity document,
                                              List<RagDocumentVersionEntity> versions,
                                              RagIngestJobEntity job,
                                              String eventId) {

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
