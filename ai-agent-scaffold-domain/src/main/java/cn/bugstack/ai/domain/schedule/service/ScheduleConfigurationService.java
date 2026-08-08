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

    /** 调度配置允许的错过执行处理策略。 */
    private static final Set<String> MISFIRE_POLICIES = Set.of("fire_once_now", "skip", "catch_up");

    /** 在用户所有权范围内保存和查询调度配置。 */
    private final IScheduleRepository repository;
    /** 配置变化后立即更新对应的运行态任务。 */
    private final ScheduleReconciler reconciler;
    /** 规范化并校验 Cron 表达式和时区。 */
    private final CronScheduleSupport cronSupport;
    /** 校验并规范化持久化任务载荷。 */
    private final ObjectMapper objectMapper;
    /** 生成调度配置业务时间的 UTC 时钟。 */
    private final Clock clock = Clock.systemUTC();

    /** 校验并保存当前用户的配置，随后立即收敛运行态。 */
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
            // 服务端生成标识，避免调用方伪造其他用户的已有配置。
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

    /** 查询当前用户拥有的调度配置。 */
    public List<ScheduleConfigEntity> list(String tenantId, String userId) {
        requireIdentity(userId);
        return repository.listOwnedConfigs(tenantId, userId);
    }

    /** 在所有权边界内启停配置，并同步刷新运行态。 */
    public ScheduleConfigEntity setEnabled(String tenantId, String userId, String configId, boolean enabled) {
        requireIdentity(userId);
        if (!repository.updateEnabled(tenantId, userId, configId, enabled)) {
            throw new AppException("SCHEDULE_NOT_FOUND", "定时任务不存在或无权操作");
        }
        reconciler.reconcile(configId);
        return repository.findOwnedConfig(tenantId, userId, configId);
    }

    /** 将指定任务推进为立即到期；运行态缺失时先补做一次收敛。 */
    public void triggerNow(String tenantId, String userId, String configId) {
        requireIdentity(userId);
        if (repository.findOwnedConfig(tenantId, userId, configId) == null) {
            throw new AppException("SCHEDULE_NOT_FOUND", "定时任务不存在或无权操作");
        }
        if (!repository.triggerNow(tenantId, userId, configId, LocalDateTime.now(clock))) {
            // 配置可能刚创建但扫描器尚未落运行态，先收敛再重试一次。
            reconciler.reconcile(configId);
            if (!repository.triggerNow(tenantId, userId, configId, LocalDateTime.now(clock))) {
                throw new AppException("SCHEDULE_TRIGGER_CONFLICT", "任务正在执行或尚未启用");
            }
        }
    }

    /** 在所有权校验后查询有限条执行审计记录。 */
    public List<ScheduleExecutionEntity> executions(String tenantId, String userId, String configId,
                                                    int limit) {
        requireIdentity(userId);
        if (repository.findOwnedConfig(tenantId, userId, configId) == null) {
            throw new AppException("SCHEDULE_NOT_FOUND", "定时任务不存在或无权查看");
        }
        return repository.listExecutions(tenantId, userId, configId, Math.max(1, Math.min(limit, 200)));
    }

    /** 预览规范化 Cron 的未来触发时间。 */
    public List<LocalDateTime> preview(String cron, String timezone, int count) {
        return cronSupport.preview(cron, timezone, count, LocalDateTime.now(clock));
    }

    /** 保证执行身份不可越过当前配置所有者。 */
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

    /** 拒绝脱离认证上下文创建或操作任务。 */
    private void requireIdentity(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new AppException("SCHEDULE_CONTEXT_INVALID", "当前登录身份不完整");
        }
    }

    /** 只接受单一 message 字段，阻断载荷借道注入未授权执行参数。 */
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
            // 重建 JSON 以丢弃原始格式差异，保证摘要和审计结果稳定。
            return objectMapper.createObjectNode().put("message", message).toString();
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException("SCHEDULE_PAYLOAD_INVALID", "任务载荷不是合法 JSON", e);
        }
    }

    /** 统一缺省错过策略并拒绝未知策略。 */
    private String normalizeMisfire(String policy) {
        String value = policy == null || policy.isBlank() ? "fire_once_now" : policy;
        if (!MISFIRE_POLICIES.contains(value)) {
            throw new AppException("SCHEDULE_MISFIRE_INVALID", "不支持的错过执行策略");
        }
        return value;
    }
}
