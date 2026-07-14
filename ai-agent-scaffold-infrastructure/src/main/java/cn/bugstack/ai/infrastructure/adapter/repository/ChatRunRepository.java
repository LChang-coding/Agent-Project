package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.run.adapter.repository.IChatRunRepository;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.infrastructure.dao.IChatRunDao;
import cn.bugstack.ai.infrastructure.dao.po.ChatRunPO;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 会话运行仓储实现。
 */
@Repository
public class ChatRunRepository implements IChatRunRepository {

    private final IChatRunDao dao;

    /**
     * 创建仓储；参数是运行 DAO；返回仓储实例。
     */
    public ChatRunRepository(IChatRunDao dao) {
        this.dao = dao;
    }

    @Override
    public int insert(ChatRunEntity run) {
        return dao.insert(toPO(run));
    }

    @Override
    public ChatRunEntity query(String tenantId, String userId, String runId) {
        return toEntity(dao.query(blankToNull(tenantId), userId, runId));
    }

    @Override
    public ChatRunEntity lock(String tenantId, String userId, String runId) {
        return toEntity(dao.lock(blankToNull(tenantId), userId, runId));
    }

    @Override
    public int transition(String tenantId, String userId, String runId, RunStatus expectedStatus, RunStatus targetStatus,
                          int expectedVersion, String reason, LocalDateTime cancelRequestedAt, LocalDateTime finishedAt) {
        return dao.transition(blankToNull(tenantId), userId, runId, value(expectedStatus), value(targetStatus),
                expectedVersion, reason, cancelRequestedAt, finishedAt);
    }

    @Override
    public int bindUserMessage(String tenantId, String userId, String runId, String messageId, int expectedVersion) {
        return dao.bindUserMessage(blankToNull(tenantId), userId, runId, messageId, expectedVersion);
    }

    @Override
    public int bindSuccessor(String tenantId, String userId, String runId, String successorRunId,
                             String steerInstruction, int expectedVersion) {
        return dao.bindSuccessor(blankToNull(tenantId), userId, runId, successorRunId, steerInstruction, expectedVersion);
    }

    @Override
    public int updateContextRevision(String tenantId, String userId, String runId, long contextRevision, int expectedVersion) {
        return dao.updateContextRevision(blankToNull(tenantId), userId, runId, contextRevision, expectedVersion);
    }

    private ChatRunPO toPO(ChatRunEntity run) {
        return ChatRunPO.builder()
                .runId(run.getRunId()).turnId(run.getTurnId()).tenantId(blankToNull(run.getTenantId()))
                .userId(run.getUserId()).sessionId(run.getSessionId()).sourceType(run.getSourceType()).sourceId(run.getSourceId())
                .status(value(run.getStatus())).version(run.getVersion()).baseContextRevision(run.getBaseContextRevision())
                .currentContextRevision(run.getCurrentContextRevision()).predecessorRunId(run.getPredecessorRunId())
                .successorRunId(run.getSuccessorRunId()).userMessageId(run.getUserMessageId())
                .steerInstruction(run.getSteerInstruction()).terminalReason(run.getTerminalReason())
                .cancelRequestedAt(run.getCancelRequestedAt()).startedAt(run.getStartedAt()).finishedAt(run.getFinishedAt())
                .build();
    }

    private ChatRunEntity toEntity(ChatRunPO run) {
        if (run == null) {
            return null;
        }
        return ChatRunEntity.builder()
                .runId(run.getRunId()).turnId(run.getTurnId()).tenantId(run.getTenantId()).userId(run.getUserId())
                .sessionId(run.getSessionId()).sourceType(run.getSourceType()).sourceId(run.getSourceId())
                .status(RunStatus.valueOf(run.getStatus().toUpperCase(Locale.ROOT))).version(run.getVersion())
                .baseContextRevision(run.getBaseContextRevision()).currentContextRevision(run.getCurrentContextRevision())
                .predecessorRunId(run.getPredecessorRunId()).successorRunId(run.getSuccessorRunId())
                .userMessageId(run.getUserMessageId()).steerInstruction(run.getSteerInstruction())
                .terminalReason(run.getTerminalReason()).cancelRequestedAt(run.getCancelRequestedAt())
                .startedAt(run.getStartedAt()).finishedAt(run.getFinishedAt()).build();
    }

    private String value(RunStatus status) {
        return status == null ? null : status.name().toLowerCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
