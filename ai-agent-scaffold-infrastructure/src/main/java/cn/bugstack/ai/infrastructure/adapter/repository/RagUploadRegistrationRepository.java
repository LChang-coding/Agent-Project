package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.rag.adapter.repository.RagUploadRegistrationPort;
import cn.bugstack.ai.domain.rag.model.entity.RagUploadRegistration;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentVersionDao;
import cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao;
import cn.bugstack.ai.infrastructure.dao.IRagOutboxDao;
import cn.bugstack.ai.infrastructure.dao.po.RagDocumentPO;
import cn.bugstack.ai.infrastructure.dao.po.RagDocumentVersionPO;
import cn.bugstack.ai.infrastructure.dao.po.RagIngestTaskPO;
import cn.bugstack.ai.infrastructure.dao.po.RagOutboxPO;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.infrastructure.rag.persistence.RagPersistenceMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** MySQL 文档上传事务：任务幂等闸门、文档、版本和 Outbox 原子落库。 */
@Repository
@RequiredArgsConstructor
public class RagUploadRegistrationRepository implements RagUploadRegistrationPort {

    private static final String EVENT_TYPE = "rag.ingest.requested.v1";
    private final IRagIngestTaskDao ingestTaskDao;
    private final IRagDocumentDao documentDao;
    private final IRagDocumentVersionDao documentVersionDao;
    private final IRagOutboxDao outboxDao;
    private final RagPersistenceMapper mapper;
    private final RagProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean register(String tenantId, RagUploadRegistration registration) {
        requireScope(tenantId, registration);
        RagIngestTaskPO task = mapper.toIngestTaskPo(registration.job());
        task.setDocumentVersion(registration.version().versionNumber());
        try {
            ingestTaskDao.insert(task);
        } catch (DuplicateKeyException duplicate) {
            return false;
        }

        RagDocumentPO document = mapper.toDocumentPo(registration.document());
        document.setSourceType("upload");
        document.setSourceBucket(registration.version().objectBucket());
        document.setSourceObjectKey(registration.version().objectKey());
        document.setMimeType(registration.version().mimeType());
        document.setSizeBytes(registration.version().sizeBytes());
        document.setContentHash(registration.version().sha256());
        document.setDocumentVersion(registration.version().versionNumber());
        documentDao.insert(document);

        RagDocumentVersionPO version = mapper.toDocumentVersionPo(registration.version());
        documentVersionDao.insert(version);
        outboxDao.insert(outbox(registration));
        return true;
    }

    private RagOutboxPO outbox(RagUploadRegistration registration) {
        return RagOutboxPO.builder()
                .eventId(registration.eventId())
                .tenantId(registration.job().tenantId())
                .taskId(registration.job().jobId())
                .aggregateType("rag_ingest_task")
                .aggregateId(registration.job().jobId())
                .eventType(EVENT_TYPE)
                .topicName(properties.getKafka().getTopic())
                .partitionKey(registration.job().jobId())
                .payload(payload(registration))
                .status("pending")
                .attemptCount(0)
                .maxAttempts(10)
                .fencingToken(0L)
                .rowVersion(0L)
                .build();
    }

    private String payload(RagUploadRegistration registration) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("eventId", registration.eventId());
        payload.put("tenantId", registration.job().tenantId());
        payload.put("taskId", registration.job().jobId());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("RAG Outbox 事件序列化失败", e);
        }
    }

    private void requireScope(String tenantId, RagUploadRegistration registration) {
        if (tenantId == null || tenantId.isBlank() || registration == null
                || !tenantId.equals(registration.document().tenantId())
                || !tenantId.equals(registration.version().tenantId())
                || !tenantId.equals(registration.job().tenantId())) {
            throw new IllegalArgumentException("RAG 上传登记租户范围不一致");
        }
    }
}
