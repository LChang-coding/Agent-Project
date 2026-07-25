package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.run.adapter.repository.IChatRunRepository;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.infrastructure.dao.IChatRunDao;
import cn.bugstack.ai.infrastructure.dao.po.ChatRunPO;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话运行仓储实现。
 */
@Repository
public class ChatRunRepository implements IChatRunRepository {

    /** 运行状态持久化入口。 */
    private final IChatRunDao dao;
    /** 只用于绑定 ID 列表 JSON，不承载业务序列化。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建仓储；参数是运行 DAO；返回仓储实例。
     */
    public ChatRunRepository(IChatRunDao dao, ObjectMapper objectMapper) {
        this.dao = dao;
        this.objectMapper = objectMapper;
    }

    @Override
    /** 创建运行；数据库唯一键阻止 runId 重复。 */
    public int insert(ChatRunEntity run) {
        return dao.insert(toPO(run));
    }

    @Override
    /** 在可信租户和用户范围内查询运行。 */
    public ChatRunEntity query(String tenantId, String userId, String runId) {
        return toEntity(dao.query(blankToNull(tenantId), userId, runId));
    }

    @Override
    /** 悲观锁定运行，串行化取消、续接和终态推进。 */
    public ChatRunEntity lock(String tenantId, String userId, String runId) {
        return toEntity(dao.lock(blankToNull(tenantId), userId, runId));
    }

    @Override
    /** 查询会话内仍可能执行或被取消的运行。 */
    public List<ChatRunEntity> queryExecutableBySession(String tenantId, String userId, String sessionId) {
        return dao.queryExecutableBySession(blankToNull(tenantId), userId, sessionId).stream()
                .map(this::toEntity).collect(Collectors.toList());
    }

    @Override
    /** 删除 Agent/Workflow 前查询其全部未完成运行。 */
    public List<ChatRunEntity> queryExecutableBySource(String tenantId, String sourceType, String sourceId) {
        return dao.queryExecutableBySource(blankToNull(tenantId), sourceType, sourceId).stream()
                .map(this::toEntity).collect(Collectors.toList());
    }

    @Override
    /** 以期望状态和版本做 CAS 状态迁移。 */
    public int transition(String tenantId, String userId, String runId, RunStatus expectedStatus, RunStatus targetStatus,
                          int expectedVersion, String reason, LocalDateTime cancelRequestedAt, LocalDateTime finishedAt) {
        return dao.transition(blankToNull(tenantId), userId, runId, value(expectedStatus), value(targetStatus),
                expectedVersion, reason, cancelRequestedAt, finishedAt);
    }

    @Override
    /** 只在期望版本下绑定本轮用户消息。 */
    public int bindUserMessage(String tenantId, String userId, String runId, String messageId, int expectedVersion) {
        return dao.bindUserMessage(blankToNull(tenantId), userId, runId, messageId, expectedVersion);
    }

    @Override
    /** 原子连接 steer 前后运行并记录引导指令。 */
    public int bindSuccessor(String tenantId, String userId, String runId, String successorRunId,
                             String steerInstruction, int expectedVersion) {
        return dao.bindSuccessor(blankToNull(tenantId), userId, runId, successorRunId, steerInstruction, expectedVersion);
    }

    @Override
    /** 以运行版本 CAS 推进已消费上下文修订。 */
    public int updateContextRevision(String tenantId, String userId, String runId, long contextRevision, int expectedVersion) {
        return dao.updateContextRevision(blankToNull(tenantId), userId, runId, contextRevision, expectedVersion);
    }

    /** 将领域快照编码为数据库行。 */
    private ChatRunPO toPO(ChatRunEntity run) {
        return ChatRunPO.builder()
                .runId(run.getRunId()).turnId(run.getTurnId()).tenantId(blankToNull(run.getTenantId()))
                .userId(run.getUserId()).sessionId(run.getSessionId()).sourceType(run.getSourceType()).sourceId(run.getSourceId())
                .ragEnabled(Boolean.TRUE.equals(run.getRagEnabled())).ragMode(run.getRagMode())
                .ragPolicyRevision(run.getRagPolicyRevision()).ragBindingIdsJson(writeBindingIds(run.getRagBindingIds()))
                .traceId(run.getTraceId())
                .status(value(run.getStatus())).version(run.getVersion()).baseContextRevision(run.getBaseContextRevision())
                .currentContextRevision(run.getCurrentContextRevision()).predecessorRunId(run.getPredecessorRunId())
                .successorRunId(run.getSuccessorRunId()).userMessageId(run.getUserMessageId())
                .steerInstruction(run.getSteerInstruction()).terminalReason(run.getTerminalReason())
                .cancelRequestedAt(run.getCancelRequestedAt()).startedAt(run.getStartedAt()).finishedAt(run.getFinishedAt())
                .build();
    }

    /** 将数据库行恢复为运行状态机实体。 */
    private ChatRunEntity toEntity(ChatRunPO run) {
        if (run == null) {
            return null;
        }
        return ChatRunEntity.builder()
                .runId(run.getRunId()).turnId(run.getTurnId()).tenantId(run.getTenantId()).userId(run.getUserId())
                .sessionId(run.getSessionId()).sourceType(run.getSourceType()).sourceId(run.getSourceId())
                .ragEnabled(Boolean.TRUE.equals(run.getRagEnabled())).ragMode(run.getRagMode())
                .ragPolicyRevision(run.getRagPolicyRevision()).ragBindingIds(readBindingIds(run.getRagBindingIdsJson()))
                .traceId(run.getTraceId())
                .status(RunStatus.valueOf(run.getStatus().toUpperCase(Locale.ROOT))).version(run.getVersion())
                .baseContextRevision(run.getBaseContextRevision()).currentContextRevision(run.getCurrentContextRevision())
                .predecessorRunId(run.getPredecessorRunId()).successorRunId(run.getSuccessorRunId())
                .userMessageId(run.getUserMessageId()).steerInstruction(run.getSteerInstruction())
                .terminalReason(run.getTerminalReason()).cancelRequestedAt(run.getCancelRequestedAt())
                .startedAt(run.getStartedAt()).finishedAt(run.getFinishedAt()).build();
    }

    /** 稳定编码运行冻结的 RAG 绑定列表。 */
    private String writeBindingIds(List<String> bindingIds) {
        try {
            return objectMapper.writeValueAsString(bindingIds == null ? List.of() : bindingIds);
        } catch (Exception exception) {
            throw new AppException("CHAT_RUN_RAG_SNAPSHOT_SERIALIZE_FAILED", "RAG运行快照序列化失败");
        }
    }

    /** 兼容空值并严格解码 RAG 绑定列表。 */
    private List<String> readBindingIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<>() { });
            return values == null ? List.of() : List.copyOf(values);
        } catch (Exception exception) {
            throw new AppException("CHAT_RUN_RAG_SNAPSHOT_DESERIALIZE_FAILED", "RAG运行快照反序列化失败");
        }
    }

    private String value(RunStatus status) {
        return status == null ? null : status.name().toLowerCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
