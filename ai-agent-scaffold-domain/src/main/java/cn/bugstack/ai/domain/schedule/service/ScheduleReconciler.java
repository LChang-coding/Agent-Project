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

    /** 查询配置并按稳定业务键更新唯一运行态任务。 */
    private final IScheduleRepository repository;
    /** 规范化 Cron 和时区，并计算下一次计划时间。 */
    private final CronScheduleSupport cronSupport;
    /** 解析任务载荷以生成稳定配置摘要。 */
    private final ObjectMapper objectMapper;
    /** 统一计算计划时间和记录更新时间的 UTC 时钟。 */
    private final Clock clock = Clock.systemUTC();

    /** 有界扫描待收敛配置，并停用失去有效配置的运行态。 */
    public int reconcileBatch(int limit) {
        int count = 0;
        for (ScheduleConfigEntity config : repository.listForReconcile(Math.max(1, Math.min(limit, 1000)))) {
            reconcile(config);
            count++;
        }
        repository.disableInactiveTasks();
        return count;
    }

    /** 按配置标识执行一次幂等收敛；配置已删除时返回空。 */
    public ScheduleTaskEntity reconcile(String configId) {
        ScheduleConfigEntity config = repository.findConfig(configId);
        return config == null ? null : reconcile(config);
    }

    /** 将配置规范化、计算摘要并按稳定业务键冲突更新运行态。 */
    public ScheduleTaskEntity reconcile(ScheduleConfigEntity config) {
        LocalDateTime now = LocalDateTime.now(clock);
        String cron = cronSupport.normalize(config.getCronExpr());
        String timezone = cronSupport.normalizeTimezone(config.getTimezone());
        String hash = configHash(config, cron, timezone);
        LocalDateTime next = cronSupport.next(cron, timezone, now);
        // 业务键只由租户和配置标识决定，Cron 修改不会生成重复任务。
        String businessKey = sha256(nullSafe(config.getTenantId()) + "|" + config.getConfigId());
        ScheduleTaskEntity task = repository.upsertTask(ScheduleTaskEntity.builder()
                .tenantId(config.getTenantId()).userId(config.getRunAsUserId()).configId(config.getConfigId())
                .taskId("scht_" + businessKey.substring(0, 32)).businessKey(businessKey).configHash(hash)
                .cronExpr(cron).timezone(timezone).misfirePolicy(config.getMisfirePolicy())
                .maxRetries(config.getMaxRetries()).plannedTime(next).nextFireTime(next)
                .status(config.isEnabled() && "active".equals(config.getStatus()) ? "ready" : "disabled")
                .build());
        repository.updateReconciled(config.getConfigId(), task.getConfigHash(), task.getConfigVersion(), now,
                config.getUpdateTime());
        return task;
    }

    /** 只纳入会改变执行语义的字段，避免展示字段触发无意义版本升级。 */
    private String configHash(ScheduleConfigEntity config, String cron, String timezone) {
        return sha256(String.join("|", nullSafe(config.getTaskType()), nullSafe(config.getAgentId()), cron,
                timezone, canonicalJson(config.getTaskPayload()), nullSafe(config.getRunAsUserId()),
                nullSafe(config.getRunAsRoleCode()), nullSafe(config.getMisfirePolicy()),
                Integer.toString(config.getMaxRetries()), Boolean.toString(config.isEnabled()),
                nullSafe(config.getStatus())));
    }

    /** 规范化 JSON 键顺序，使等价载荷得到相同摘要。 */
    private String canonicalJson(String json) {
        try {
            JsonNode node = objectMapper.readTree(json == null ? "{}" : json);
            return objectMapper.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("定时任务 payload 不是合法 JSON", e);
        }
    }

    /** 生成数据库业务键与配置摘要使用的 SHA-256。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算调度配置摘要", e);
        }
    }

    /** 将缺失字符串稳定映射为空串，避免摘要计算歧义。 */
    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
