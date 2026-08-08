package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.rag.adapter.repository.RagDocumentDeletionRegistrationPort;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentDeletionRegistration;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentVersionDao;
import cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao;
import cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDao;
import cn.bugstack.ai.infrastructure.dao.IRagOutboxDao;
import cn.bugstack.ai.infrastructure.dao.po.RagDocumentPO;
import cn.bugstack.ai.infrastructure.dao.po.RagIngestTaskPO;
import cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBasePO;
import cn.bugstack.ai.infrastructure.dao.po.RagOutboxPO;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.infrastructure.rag.persistence.RagPersistenceMapper;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** MySQL文档删除登记事务：聚合锁、墓碑、任务和Outbox原子落库。 */
@Repository
@RequiredArgsConstructor
public class RagDocumentDeletionRegistrationRepository implements RagDocumentDeletionRegistrationPort {

    /** 删除与摄取共享 Worker Topic，通过 operation 区分。 */
    private static final String EVENT_TYPE = "rag.ingest.requested.v1";

    /** 删除登记所需 DAO；锁定、建账和 outbox 必须同事务。 */
    private final IRagIngestTaskDao ingestTaskDao;
    /** 锁定知识库并确认删除目标仍属于可操作聚合。 */
    private final IRagKnowledgeBaseDao knowledgeBaseDao;
    /** 锁定逻辑文档并写入删除中墓碑状态。 */
    private final IRagDocumentDao documentDao;
    /** 读取删除范围内的不可变版本，防止遗漏源文件或解析产物。 */
    private final IRagDocumentVersionDao documentVersionDao;
    /** 在同一事务中登记待发布 Kafka 删除唤醒事件。 */
    private final IRagOutboxDao outboxDao;
    /** 将领域文档和删除任务转换为数据库记录。 */
    private final RagPersistenceMapper mapper;
    /** 提供删除任务复用的摄取 Kafka Topic。 */
    private final RagProperties properties;
    /** 将稳定事件字段编码为 Outbox JSON 负载。 */
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    /** 锁定文档后登记删除 generation、任务和 outbox，拒绝与活动摄取并发。 */
    public boolean register(String tenantId, RagDocumentDeletionRegistration registration) {
        requireScope(tenantId, registration);
        RagKnowledgeBasePO lockedKnowledgeBase = knowledgeBaseDao.queryByTenantAndKnowledgeBaseIdForUpdate(
                tenantId, registration.document().knowledgeBaseId());
        if (lockedKnowledgeBase == null || !"active".equalsIgnoreCase(lockedKnowledgeBase.getStatus())
                && !"deleting".equalsIgnoreCase(lockedKnowledgeBase.getStatus())) {
            throw new AppException("RAG_KNOWLEDGE_BASE_UNAVAILABLE", "知识库当前不能登记文档删除");
        }
        RagDocumentPO locked = documentDao.queryByTenantKnowledgeBaseAndDocumentIdForUpdate(tenantId,
                registration.document().knowledgeBaseId(), registration.document().documentId());
        if (locked == null || "deleting".equalsIgnoreCase(locked.getStatus())
                || "deleted".equalsIgnoreCase(locked.getStatus())) return false;
        long expectedDocumentRevision = registration.document().revision() - 1;
        if (locked.getRevision() == null || locked.getRevision() != expectedDocumentRevision
                || !"ready".equalsIgnoreCase(locked.getStatus()) && !"failed".equalsIgnoreCase(locked.getStatus())) {
            throw new AppException("RAG_DOCUMENT_REVISION_CONFLICT", "文档已变化，请刷新后重试");
        }
        if (ingestTaskDao.queryActiveByTenantAndDocumentId(tenantId,
                registration.document().documentId()) != null) {
            throw new AppException("RAG_DOCUMENT_TASK_CONFLICT", "文档仍有未完成任务，请先等待或取消后重试");
        }

        RagIngestTaskPO task = mapper.toIngestTaskPo(registration.job());
        task.setDocumentVersion(registration.versions().stream()
                .filter(value -> value.versionId().equals(registration.job().versionId()))
                .findFirst().orElseThrow().versionNumber());
        try {
            ingestTaskDao.insert(task);
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
        requireChanged(documentDao.updateByTenantAndRevision(tenantId,
                mapper.toDocumentPo(registration.document()), expectedDocumentRevision));
        registration.versions().forEach(version -> requireChanged(
                documentVersionDao.updateByTenantAndRevision(tenantId,
                        mapper.toDocumentVersionPo(version), version.revision() - 1)));
        requireChanged(outboxDao.insert(outbox(registration)));
        return true;
    }

    /** 构造删除请求 outbox。 */
    private RagOutboxPO outbox(RagDocumentDeletionRegistration registration) {
        return RagOutboxPO.builder().eventId(registration.eventId()).tenantId(registration.job().tenantId())
                .taskId(registration.job().jobId()).aggregateType("rag_ingest_task")
                .aggregateId(registration.job().jobId()).eventType(EVENT_TYPE)
                .topicName(properties.getKafka().getTopic()).partitionKey(registration.job().jobId())
                .payload(payload(registration)).status("pending").attemptCount(0).maxAttempts(10)
                .fencingToken(0L).rowVersion(0L).build();
    }

    /** 编码 Worker 执行删除所需稳定身份与 generation。 */
    private String payload(RagDocumentDeletionRegistration registration) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("eventId", registration.eventId());
        payload.put("tenantId", registration.job().tenantId());
        payload.put("taskId", registration.job().jobId());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("RAG删除Outbox事件序列化失败", e);
        }
    }

    /** 校验可信租户与登记范围完全一致。 */
    private void requireScope(String tenantId, RagDocumentDeletionRegistration registration) {
        if (tenantId == null || tenantId.isBlank() || registration == null
                || !tenantId.equals(registration.document().tenantId())
                || !tenantId.equals(registration.job().tenantId())) {
            throw new IllegalArgumentException("RAG删除登记租户范围不一致");
        }
    }

    /** 将影响行数 0 映射为并发冲突，阻止部分登记提交。 */
    private void requireChanged(int changed) {
        if (changed != 1) {
            throw new AppException("RAG_DELETE_CONCURRENT_UPDATE", "文档删除登记发生并发变化");
        }
    }
}
