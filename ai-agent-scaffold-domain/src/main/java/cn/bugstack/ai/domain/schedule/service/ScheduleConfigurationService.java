package cn.bugstack.ai.domain.schedule.service;

import cn.bugstack.ai.domain.schedule.adapter.IScheduleRepository;
import cn.bugstack.ai.domain.schedule.model.ScheduleConfigEntity;
import cn.bugstack.ai.domain.schedule.model.ScheduleExecutionEntity;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 面向当前登录用户的定时任务配置用例。
 */
@Service
@RequiredArgsConstructor
public class ScheduleConfigurationService {

    private static final Set<String> MISFIRE_POLICIES = Set.of("fire_once_now", "skip", "catch_up");

    private final IScheduleRepository repository;
    private final ScheduleReconciler reconciler;
    private final CronScheduleSupport cronSupport;
    private final ObjectMapper objectMapper;
    private final Clock clock = Clock.systemUTC();

    @Transactional(rollbackFor = Exception.class)
    public ScheduleConfigEntity save(ScheduleConfigEntity candidate) {
        validateIdentity(candidate);
        candidate.setCronExpr(cronSupport.normalize(candidate.getCronExpr()));
        candidate.setTimezone(cronSupport.normalizeTimezone(candidate.getTimezone()));
        candidate.setTaskType("agent_prompt");
        candidate.setTaskPayload(validatePayload(candidate.getTaskPayload()));
        candidate.setMisfirePolicy(normalizeMisfire(candidate.getMisfirePolicy()));
        candidate.setMaxRetries(Math.max(0, Math.min(candidate.getMaxRetries(), 10)));
        if (candidate.getConfigId() == null || candidate.getConfigId().isBlank()) {
            candidate.setConfigId("schc_" + UUID.randomUUID().toString().replace("-", ""));
        } else if (repository.findOwnedConfig(candidate.getTenantId(), candidate.getOwnerUserId(),
                candidate.getConfigId()) == null) {
            throw new AppException("SCHEDULE_NOT_FOUND", "定时任务不存在或无权修改");
        }
        candidate.setStatus(candidate.isEnabled() ? "active" : "disabled");
        repository.saveConfig(candidate);
        reconciler.reconcile(candidate.getConfigId());
        return repository.findOwnedConfig(candidate.getTenantId(), candidate.getOwnerUserId(),
                candidate.getConfigId());
    }

    public List<ScheduleConfigEntity> list(String tenantId, String userId) {
        requireIdentity(userId);
        return repository.listOwnedConfigs(tenantId, userId);
    }

    public ScheduleConfigEntity setEnabled(String tenantId, String userId, String configId, boolean enabled) {
        requireIdentity(userId);
        if (!repository.updateEnabled(tenantId, userId, configId, enabled)) {
            throw new AppException("SCHEDULE_NOT_FOUND", "定时任务不存在或无权操作");
        }
        reconciler.reconcile(configId);
        return repository.findOwnedConfig(tenantId, userId, configId);
    }

    public void triggerNow(String tenantId, String userId, String configId) {
        requireIdentity(userId);
        if (repository.findOwnedConfig(tenantId, userId, configId) == null) {
            throw new AppException("SCHEDULE_NOT_FOUND", "定时任务不存在或无权操作");
        }
        if (!repository.triggerNow(tenantId, userId, configId, LocalDateTime.now(clock))) {
            reconciler.reconcile(configId);
            if (!repository.triggerNow(tenantId, userId, configId, LocalDateTime.now(clock))) {
                throw new AppException("SCHEDULE_TRIGGER_CONFLICT", "任务正在执行或尚未启用");
            }
        }
    }

    public List<ScheduleExecutionEntity> executions(String tenantId, String userId, String configId,
                                                    int limit) {
        requireIdentity(userId);
        if (repository.findOwnedConfig(tenantId, userId, configId) == null) {
            throw new AppException("SCHEDULE_NOT_FOUND", "定时任务不存在或无权查看");
        }
        return repository.listExecutions(tenantId, userId, configId, Math.max(1, Math.min(limit, 200)));
    }

    public List<LocalDateTime> preview(String cron, String timezone, int count) {
        return cronSupport.preview(cron, timezone, count, LocalDateTime.now(clock));
    }

    private void validateIdentity(ScheduleConfigEntity candidate) {
        requireIdentity(candidate.getOwnerUserId());
        if (!candidate.getOwnerUserId().equals(candidate.getRunAsUserId())) {
            throw new AppException("SCHEDULE_RUN_AS_INVALID", "执行用户必须是当前登录用户");
        }
        if (candidate.getRunAsRoleCode() == null || candidate.getRunAsRoleCode().isBlank()) {
            throw new AppException("SCHEDULE_RUN_AS_INVALID", "执行角色不能为空");
        }
        if (candidate.getAgentId() == null || candidate.getAgentId().isBlank()) {
            throw new AppException("SCHEDULE_AGENT_INVALID", "Agent ID 不能为空");
        }
    }

    private void requireIdentity(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new AppException("SCHEDULE_CONTEXT_INVALID", "当前登录身份不完整");
        }
    }

    private String validatePayload(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload == null ? "{}" : payload);
            if (!node.isObject() || node.size() != 1 || !node.hasNonNull("message")
                    || node.path("message").asText().isBlank()) {
                throw new AppException("SCHEDULE_PAYLOAD_INVALID", "任务载荷只允许包含非空 message");
            }
            String message = node.path("message").asText();
            if (message.length() > 20_000) {
                throw new AppException("SCHEDULE_PAYLOAD_INVALID", "定时消息不能超过 20000 字符");
            }
            return objectMapper.createObjectNode().put("message", message).toString();
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException("SCHEDULE_PAYLOAD_INVALID", "任务载荷不是合法 JSON", e);
        }
    }

    private String normalizeMisfire(String policy) {
        String value = policy == null || policy.isBlank() ? "fire_once_now" : policy;
        if (!MISFIRE_POLICIES.contains(value)) {
            throw new AppException("SCHEDULE_MISFIRE_INVALID", "不支持的错过执行策略");
        }
        return value;
    }
}
