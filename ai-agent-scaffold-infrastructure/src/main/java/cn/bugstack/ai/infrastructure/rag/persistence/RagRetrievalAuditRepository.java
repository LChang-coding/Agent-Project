package cn.bugstack.ai.infrastructure.rag.persistence;

import cn.bugstack.ai.domain.rag.adapter.port.RagRetrievalAuditPort;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalAuditCommand;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalResult;
import cn.bugstack.ai.infrastructure.dao.IRagRetrievalCitationDao;
import cn.bugstack.ai.infrastructure.dao.IRagRetrievalRecordDao;
import cn.bugstack.ai.infrastructure.dao.po.RagRetrievalCitationPO;
import cn.bugstack.ai.infrastructure.dao.po.RagRetrievalRecordPO;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** MySQL 检索主记录与引用原子留痕实现。 */
@Repository
public class RagRetrievalAuditRepository implements RagRetrievalAuditPort {

    /** 检索摘要与最终引用分别入库。 */
    private final IRagRetrievalRecordDao recordDao;
    private final IRagRetrievalCitationDao citationDao;
    private final RagProperties properties;
    private final ObjectMapper objectMapper;

    public RagRetrievalAuditRepository(IRagRetrievalRecordDao recordDao,
                                       IRagRetrievalCitationDao citationDao,
                                       RagProperties properties,
                                       ObjectMapper objectMapper) {
        this.recordDao = recordDao;
        this.citationDao = citationDao;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    /** 审计失败回滚本次摘要和引用，但不得改变已生成在线结果。 */
    public void record(RagRetrievalAuditCommand command) {
        RagRetrievalResult result = command.result();
        RagRetrievalResult.Metrics metrics = result == null ? null : result.metrics();
        RagRetrievalRecordPO record = RagRetrievalRecordPO.builder()
                .retrievalId(command.retrievalId()).tenantId(command.tenantId()).userId(command.userId())
                .sessionId(command.sessionId()).runId(command.runId()).agentId(command.targetId())
                .profileId(command.profileId()).profileRevision(command.profileRevision())
                .queryHash(sha256(command.normalizedQuery()))
                .queryText(properties.getAudit().isStoreQueryText() ? command.normalizedQuery() : null)
                .denseEnabled(flag(command.denseEnabled())).sparseEnabled(flag(command.sparseEnabled()))
                .rerankEnabled(flag(command.rerankEnabled()))
                .denseCandidateCount(metrics == null ? 0 : metrics.denseCandidateCount())
                .sparseCandidateCount(metrics == null ? 0 : metrics.sparseCandidateCount())
                .fusionCandidateCount(metrics == null ? 0 : metrics.fusionCandidateCount())
                .finalCount(result == null ? 0 : result.citations().size())
                .embeddingMs(metrics == null ? null : metrics.embeddingMs())
                .denseMs(metrics == null ? null : metrics.denseMs())
                .sparseMs(metrics == null ? null : metrics.sparseMs())
                .fusionMs(metrics == null ? null : metrics.fusionMs())
                .rerankMs(metrics == null ? null : metrics.rerankMs())
                .assembleMs(metrics == null ? null : metrics.assemblyMs())
                .totalMs(metrics == null ? 0L : metrics.totalMs())
                .status(command.status()).errorCode(command.errorCode())
                .errorMessage(errorSummary(command.errorCode(), command.errorType()))
                .traceId(command.traceId()).requestSnapshot(json(command.requestSnapshot()))
                .stageMetrics(json(stageMetrics(result))).build();
        if (recordDao.insert(record) != 1) {
            throw new IllegalStateException("RAG检索主记录写入失败");
        }
        if (result == null || result.citations().isEmpty()) return;
        List<RagRetrievalCitationPO> citations = result.citations().stream()
                .map(value -> citation(command, value)).toList();
        if (citationDao.insertBatch(command.tenantId(), command.retrievalId(), citations) < 1) {
            throw new IllegalStateException("RAG引用记录写入失败");
        }
    }

    /** 将最终引用快照映射为可独立追查的证据行。 */
    private RagRetrievalCitationPO citation(RagRetrievalAuditCommand command,
                                            RagRetrievalResult.Citation value) {
        return RagRetrievalCitationPO.builder().tenantId(command.tenantId())
                .retrievalId(command.retrievalId()).citationId(value.citationId()).rankNo(value.rank())
                .knowledgeBaseId(value.knowledgeBaseId()).documentId(value.documentId())
                .documentVersion(value.documentVersion()).generation(value.generation())
                .chunkId(value.chunkId()).vectorPointId(null)
                .denseScore(decimal(value.denseScore())).sparseScore(decimal(value.sparseScore()))
                .fusionScore(decimal(value.fusionScore())).rerankScore(decimal(value.rerankScore()))
                .pageFrom(value.pageNumber()).pageTo(value.pageNumber()).sectionPath(value.headingPath())
                .contentHash(value.contentHash())
                .contentSnapshot(properties.getAudit().isStoreCitationContent() ? value.context() : null)
                .metadata(json(value.metadata())).build();
    }

    /** 保存各阶段耗时与候选数，不把指标混入业务正文。 */
    private Map<String, Object> stageMetrics(RagRetrievalResult result) {
        if (result == null) return Map.of();
        return Map.of("degraded", result.degraded(), "degradationReasons", result.degradationReasons(),
                "estimatedTokenCount", result.estimatedTokenCount(),
                "configurationMs", result.metrics().configurationMs(),
                "hydrationMs", result.metrics().hydrationMs());
    }

    private String errorSummary(String code, String type) {
        if (code == null && type == null) return null;
        String value = (code == null ? "RAG_RETRIEVAL_FAILED" : code) + ":"
                + (type == null ? "RuntimeException" : type);
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private int flag(boolean value) {
        return value ? 1 : 0;
    }

    /** JSON 编码失败时抛出，禁止写入不可解析审计记录。 */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("RAG审计JSON序列化失败", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前JDK不支持SHA-256", exception);
        }
    }
}
