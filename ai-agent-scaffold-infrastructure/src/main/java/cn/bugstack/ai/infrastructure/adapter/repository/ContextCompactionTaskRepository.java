package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.context.adapter.repository.IContextCompactionTaskRepository;
import cn.bugstack.ai.domain.context.model.ContextCompactionTaskEntity;
import cn.bugstack.ai.domain.context.model.ContextCompactionTaskStatus;
import cn.bugstack.ai.domain.context.model.ContextTaskCreateCommand;
import cn.bugstack.ai.infrastructure.dao.IContextCompactionTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.ContextCompactionTaskPO;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 上下文压缩任务 MySQL 仓储。
 */
@Repository
public class ContextCompactionTaskRepository implements IContextCompactionTaskRepository {

    /** 压缩任务幂等登记、领取和状态更新入口。 */
    private final IContextCompactionTaskDao dao;

    /** 注入任务 DAO；仓储本身不保存跨请求任务状态。 */
    public ContextCompactionTaskRepository(IContextCompactionTaskDao dao) {
        this.dao = dao;
    }

    /** 按覆盖范围和版本生成稳定任务键；重复请求返回同一任务账本。 */
    @Override
    public ContextCompactionTaskEntity createIfAbsent(ContextTaskCreateCommand command) {
        String taskKey = taskKey(command);
        ContextCompactionTaskPO task = new ContextCompactionTaskPO();
        task.setTaskId("ctx_task_" + UUID.nameUUIDFromBytes(taskKey.getBytes(StandardCharsets.UTF_8)));
        task.setTaskKey(taskKey);
        task.setTenantId(blankToNull(command.getTenantId()));
        task.setUserId(command.getUserId());
        task.setSessionId(command.getSessionId());
        task.setRunId(command.getRunId());
        task.setFromSequence(command.getFromSequence());
        task.setToSequence(command.getToSequence());
        task.setExpectedMemoryVersion(command.getExpectedMemoryVersion());
        task.setBaseContextRevision(command.getBaseContextRevision());
        task.setCoverageHash(command.getCoverageHash());
        task.setPolicyVersion(command.getPolicyVersion());
        task.setStatus("pending");
        task.setAttemptCount(0);
        task.setFencingToken(0L);
        task.setTraceId(command.getTraceId());
        dao.insertIgnore(task);
        return toEntity(dao.queryByTaskKey(taskKey));
    }

    /** 按任务 ID 查询压缩任务及其租约、尝试次数和错误信息。 */
    @Override
    public ContextCompactionTaskEntity queryByTaskId(String taskId) {
        return toEntity(dao.queryByTaskId(taskId));
    }

    /** 查询会话尚未进入终态的压缩任务，供恢复时重新投递。 */
    @Override
    public List<ContextCompactionTaskEntity> queryUnfinished(String tenantId, String userId, String sessionId) {
        return dao.queryUnfinished(blankToNull(tenantId), userId, sessionId).stream()
                .map(this::toEntity)
                .toList();
    }

    /** 查询会话最近一次压缩任务，用于判断当前压缩进度和结果。 */
    @Override
    public ContextCompactionTaskEntity queryLatest(String tenantId, String userId, String sessionId) {
        return toEntity(dao.queryLatest(blankToNull(tenantId), userId, sessionId));
    }

    /** 通过条件更新领取待执行任务，防止重复消费同时生成摘要。 */
    @Override
    public boolean claim(String taskId) {
        return dao.claim(taskId) == 1;
    }

    /** 将当前领取的任务推进为完成状态。 */
    @Override
    public int complete(String taskId) {
        return dao.complete(taskId);
    }

    /** 记录受限长度的错误摘要并释放任务等待下一次重试。 */
    @Override
    public int retry(String taskId, String errorMessage) {
        return dao.retry(taskId, truncate(errorMessage));
    }

    /** 尝试耗尽后将任务写入不可自动重试的终态。 */
    @Override
    public int dead(String taskId, String errorMessage) {
        return dao.dead(taskId, truncate(errorMessage));
    }

    @Override
    /** 将与失效消息区间重叠的任务标记过期，阻止旧摘要覆盖新上下文。 */
    public int staleOverlapping(String tenantId, String userId, String sessionId, String runId,
                                Integer minSequence, Integer maxSequence, String reason) {
        return dao.staleOverlapping(blankToNull(tenantId), userId, sessionId, runId, minSequence, maxSequence,
                truncate(reason));
    }

    /** 从数据库记录恢复任务身份、覆盖范围、版本门禁、租约和执行状态。 */
    private ContextCompactionTaskEntity toEntity(ContextCompactionTaskPO po) {
        if (po == null) {
            return null;
        }
        return ContextCompactionTaskEntity.builder()
                .taskId(po.getTaskId())
                .taskKey(po.getTaskKey())
                .tenantId(po.getTenantId())
                .userId(po.getUserId())
                .sessionId(po.getSessionId())
                .runId(po.getRunId())
                .fromSequence(po.getFromSequence())
                .toSequence(po.getToSequence())
                .expectedMemoryVersion(po.getExpectedMemoryVersion())
                .baseContextRevision(po.getBaseContextRevision())
                .coverageHash(po.getCoverageHash())
                .policyVersion(po.getPolicyVersion())
                .status(toStatus(po.getStatus()))
                .attemptCount(po.getAttemptCount())
                .leaseOwner(po.getLeaseOwner())
                .leaseUntil(po.getLeaseUntil())
                .fencingToken(po.getFencingToken())
                .errorMessage(po.getErrorMessage())
                .traceId(po.getTraceId())
                .build();
    }

    /** 兼容旧记录的空状态，并将数据库小写状态恢复为领域枚举。 */
    private ContextCompactionTaskStatus toStatus(String status) {
        if (status == null || status.isBlank()) {
            return ContextCompactionTaskStatus.PENDING;
        }
        return ContextCompactionTaskStatus.valueOf(status.toUpperCase(Locale.ROOT));
    }

    /** 对会话范围、消息区间和上下文版本生成稳定幂等键。 */
    private String taskKey(ContextTaskCreateCommand command) {
        String raw = String.join("|",
                blank(command.getTenantId()),
                blank(command.getUserId()),
                blank(command.getSessionId()),
                String.valueOf(command.getFromSequence()),
                String.valueOf(command.getToSequence()),
                String.valueOf(command.getExpectedMemoryVersion()),
                String.valueOf(command.getBaseContextRevision()),
                blank(command.getCoverageHash()),
                blank(command.getPolicyVersion()));
        return sha256(raw);
    }

    /** 使用 JDK SHA-256 生成不会暴露原始任务字段的固定长度键。 */
    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    /** 将可选空值归一为空串，保证相同命令生成相同任务键。 */
    private String blank(String value) {
        return value == null ? "" : value;
    }

    /** 将空租户归一为数据库空值，兼容历史单租户任务。 */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 将持久化错误或过期原因限制在 500 字符以内。 */
    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
