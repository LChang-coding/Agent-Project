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

    private final IContextCompactionTaskDao dao;

    /**
     * 创建任务仓储；参数是任务 DAO；返回仓储实例。
     */
    public ContextCompactionTaskRepository(IContextCompactionTaskDao dao) {
        this.dao = dao;
    }

    /**
     * 创建幂等压缩任务；参数是创建命令；返回新建或已存在任务。
     */
    @Override
    public ContextCompactionTaskEntity createIfAbsent(ContextTaskCreateCommand command) {
        String taskKey = taskKey(command);
        ContextCompactionTaskPO task = new ContextCompactionTaskPO();
        task.setTaskId("ctx_task_" + UUID.nameUUIDFromBytes(taskKey.getBytes(StandardCharsets.UTF_8)));
        task.setTaskKey(taskKey);
        task.setTenantId(blankToNull(command.getTenantId()));
        task.setUserId(command.getUserId());
        task.setSessionId(command.getSessionId());
        task.setFromSequence(command.getFromSequence());
        task.setToSequence(command.getToSequence());
        task.setExpectedMemoryVersion(command.getExpectedMemoryVersion());
        task.setPolicyVersion(command.getPolicyVersion());
        task.setStatus("pending");
        task.setAttemptCount(0);
        task.setTraceId(command.getTraceId());
        dao.insertIgnore(task);
        return toEntity(dao.queryByTaskKey(taskKey));
    }

    /**
     * 查询压缩任务；参数是任务ID；返回任务或空。
     */
    @Override
    public ContextCompactionTaskEntity queryByTaskId(String taskId) {
        return toEntity(dao.queryByTaskId(taskId));
    }

    /**
     * 查询会话未完成任务；参数是会话身份；返回待重投任务。
     */
    @Override
    public List<ContextCompactionTaskEntity> queryUnfinished(String tenantId, String userId, String sessionId) {
        return dao.queryUnfinished(blankToNull(tenantId), userId, sessionId).stream()
                .map(this::toEntity)
                .toList();
    }

    /**
     * 领取任务；参数是任务ID；成功返回 true。
     */
    @Override
    public boolean claim(String taskId) {
        return dao.claim(taskId) == 1;
    }

    /**
     * 完成任务；参数是任务ID；返回影响行数。
     */
    @Override
    public int complete(String taskId) {
        return dao.complete(taskId);
    }

    /**
     * 标记任务重试；参数是任务ID和错误摘要；返回影响行数。
     */
    @Override
    public int retry(String taskId, String errorMessage) {
        return dao.retry(taskId, truncate(errorMessage));
    }

    /**
     * 标记任务进入死信；参数是任务ID和错误摘要；返回影响行数。
     */
    @Override
    public int dead(String taskId, String errorMessage) {
        return dao.dead(taskId, truncate(errorMessage));
    }

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
                .fromSequence(po.getFromSequence())
                .toSequence(po.getToSequence())
                .expectedMemoryVersion(po.getExpectedMemoryVersion())
                .policyVersion(po.getPolicyVersion())
                .status(toStatus(po.getStatus()))
                .attemptCount(po.getAttemptCount())
                .errorMessage(po.getErrorMessage())
                .traceId(po.getTraceId())
                .build();
    }

    private ContextCompactionTaskStatus toStatus(String status) {
        if (status == null || status.isBlank()) {
            return ContextCompactionTaskStatus.PENDING;
        }
        return ContextCompactionTaskStatus.valueOf(status.toUpperCase(Locale.ROOT));
    }

    private String taskKey(ContextTaskCreateCommand command) {
        String raw = String.join("|",
                blank(command.getTenantId()),
                blank(command.getUserId()),
                blank(command.getSessionId()),
                String.valueOf(command.getFromSequence()),
                String.valueOf(command.getToSequence()),
                String.valueOf(command.getExpectedMemoryVersion()),
                blank(command.getPolicyVersion()));
        return sha256(raw);
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    private String blank(String value) {
        return value == null ? "" : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
