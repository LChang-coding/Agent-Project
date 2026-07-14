package cn.bugstack.ai.domain.schedule.service;

import cn.bugstack.ai.domain.schedule.adapter.IScheduleRepository;
import cn.bugstack.ai.domain.schedule.model.ScheduleConfigEntity;
import cn.bugstack.ai.domain.schedule.model.ScheduleTaskEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

/**
 * 将用户配置幂等收敛为每配置唯一的运行态。
 */
@Service
@RequiredArgsConstructor
public class ScheduleReconciler {

    private final IScheduleRepository repository;
    private final CronScheduleSupport cronSupport;
    private final ObjectMapper objectMapper;
    private final Clock clock = Clock.systemUTC();

    public int reconcileBatch(int limit) {
        int count = 0;
        for (ScheduleConfigEntity config : repository.listForReconcile(Math.max(1, Math.min(limit, 1000)))) {
            reconcile(config);
            count++;
        }
        repository.disableInactiveTasks();
        return count;
    }

    public ScheduleTaskEntity reconcile(String configId) {
        ScheduleConfigEntity config = repository.findConfig(configId);
        return config == null ? null : reconcile(config);
    }

    public ScheduleTaskEntity reconcile(ScheduleConfigEntity config) {
        LocalDateTime now = LocalDateTime.now(clock);
        String cron = cronSupport.normalize(config.getCronExpr());
        String timezone = cronSupport.normalizeTimezone(config.getTimezone());
        String hash = configHash(config, cron, timezone);
        LocalDateTime next = cronSupport.next(cron, timezone, now);
        String businessKey = sha256(nullSafe(config.getTenantId()) + "|" + config.getConfigId());
        ScheduleTaskEntity task = repository.upsertTask(ScheduleTaskEntity.builder()
                .tenantId(config.getTenantId()).userId(config.getRunAsUserId()).configId(config.getConfigId())
                .taskId("scht_" + businessKey.substring(0, 32)).businessKey(businessKey).configHash(hash)
                .cronExpr(cron).timezone(timezone).misfirePolicy(config.getMisfirePolicy())
                .maxRetries(config.getMaxRetries()).plannedTime(next).nextFireTime(next)
                .status(config.isEnabled() && "active".equals(config.getStatus()) ? "ready" : "disabled")
                .build());
        repository.updateReconciled(config.getConfigId(), task.getConfigHash(), task.getConfigVersion(), now);
        return task;
    }

    private String configHash(ScheduleConfigEntity config, String cron, String timezone) {
        return sha256(String.join("|", nullSafe(config.getTaskType()), nullSafe(config.getAgentId()), cron,
                timezone, canonicalJson(config.getTaskPayload()), nullSafe(config.getRunAsUserId()),
                nullSafe(config.getRunAsRoleCode()), nullSafe(config.getMisfirePolicy()),
                Integer.toString(config.getMaxRetries()), Boolean.toString(config.isEnabled()),
                nullSafe(config.getStatus())));
    }

    private String canonicalJson(String json) {
        try {
            JsonNode node = objectMapper.readTree(json == null ? "{}" : json);
            return objectMapper.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("定时任务 payload 不是合法 JSON", e);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算调度配置摘要", e);
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
