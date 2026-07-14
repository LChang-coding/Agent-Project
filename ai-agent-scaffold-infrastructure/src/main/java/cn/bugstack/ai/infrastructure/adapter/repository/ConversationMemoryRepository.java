package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.context.adapter.repository.IConversationMemoryRepository;
import cn.bugstack.ai.domain.context.model.ConversationMemorySnapshotEntity;
import cn.bugstack.ai.infrastructure.dao.IConversationMemorySnapshotDao;
import cn.bugstack.ai.infrastructure.dao.po.ConversationMemorySnapshotPO;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话长期记忆 MySQL 仓储。
 */
@Repository
public class ConversationMemoryRepository implements IConversationMemoryRepository {

    private final IConversationMemorySnapshotDao dao;

    /**
     * 创建长期记忆仓储；参数是摘要 DAO；返回仓储实例。
     */
    public ConversationMemoryRepository(IConversationMemorySnapshotDao dao) {
        this.dao = dao;
    }

    /**
     * 查询会话有效摘要；参数是会话身份；返回有效摘要或空。
     */
    @Override
    public ConversationMemorySnapshotEntity queryActive(String tenantId, String userId, String sessionId) {
        return toEntity(dao.queryActive(blankToNull(tenantId), userId, sessionId));
    }

    /**
     * 新增记忆摘要；参数是摘要；返回影响行数。
     */
    @Override
    public int insert(ConversationMemorySnapshotEntity snapshot) {
        return dao.insert(toPO(snapshot));
    }

    /**
     * 关闭当前摘要；参数是会话身份和版本；返回影响行数。
     */
    @Override
    public int supersede(String tenantId, String userId, String sessionId, Integer memoryVersion) {
        return dao.supersede(blankToNull(tenantId), userId, sessionId, memoryVersion);
    }

    /**
     * 激活新摘要；参数是旧摘要版本和新摘要；返回是否激活成功。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean activate(String tenantId, String userId, String sessionId, Integer expectedMemoryVersion, ConversationMemorySnapshotEntity snapshot) {
        int expected = expectedMemoryVersion == null ? 0 : expectedMemoryVersion;
        if (expected > 0) {
            Integer activeCount = dao.countActiveVersion(blankToNull(tenantId), userId, sessionId, expected);
            if (activeCount == null || activeCount != 1) {
                return false;
            }
            int affected = dao.supersede(blankToNull(tenantId), userId, sessionId, expected);
            if (affected != 1) {
                return false;
            }
        } else {
            ConversationMemorySnapshotEntity active = queryActive(tenantId, userId, sessionId);
            if (active != null) {
                return false;
            }
        }
        return dao.insert(toPO(snapshot)) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationMemorySnapshotEntity invalidateCoveringAndRestore(String tenantId, String userId, String sessionId,
                                                                          Integer minInvalidSequence) {
        String normalizedTenantId = blankToNull(tenantId);
        dao.staleCovering(normalizedTenantId, userId, sessionId, minInvalidSequence);
        ConversationMemorySnapshotPO safe = dao.queryLatestSafe(normalizedTenantId, userId, sessionId, minInvalidSequence);
        if (safe != null && dao.reactivate(safe.getId()) == 1) {
            safe.setStatus("active");
            return toEntity(safe);
        }
        return null;
    }

    private ConversationMemorySnapshotEntity toEntity(ConversationMemorySnapshotPO po) {
        if (po == null) {
            return null;
        }
        return ConversationMemorySnapshotEntity.builder()
                .tenantId(po.getTenantId())
                .userId(po.getUserId())
                .sessionId(po.getSessionId())
                .memoryVersion(po.getMemoryVersion())
                .baseContextRevision(po.getBaseContextRevision())
                .coveredToSequence(po.getCoveredToSequence())
                .coverageHash(po.getCoverageHash())
                .parentMemoryVersion(po.getParentMemoryVersion())
                .content(po.getContent())
                .estimatedTokenCount(po.getEstimatedTokenCount())
                .policyVersion(po.getPolicyVersion())
                .status(po.getStatus())
                .traceId(po.getTraceId())
                .build();
    }

    private ConversationMemorySnapshotPO toPO(ConversationMemorySnapshotEntity value) {
        return ConversationMemorySnapshotPO.builder()
                .tenantId(blankToNull(value.getTenantId()))
                .userId(value.getUserId())
                .sessionId(value.getSessionId())
                .memoryVersion(value.getMemoryVersion())
                .baseContextRevision(value.getBaseContextRevision())
                .coveredToSequence(value.getCoveredToSequence())
                .coverageHash(value.getCoverageHash())
                .parentMemoryVersion(value.getParentMemoryVersion())
                .content(value.getContent())
                .estimatedTokenCount(value.getEstimatedTokenCount())
                .policyVersion(value.getPolicyVersion())
                .status(value.getStatus())
                .traceId(value.getTraceId())
                .build();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
