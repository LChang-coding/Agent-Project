package cn.bugstack.ai.domain.schedule.service;

import cn.bugstack.ai.types.exception.AppException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一使用 UTC 入库并按配置时区计算 Spring 六段式 Cron。
 */
@Component
public class CronScheduleSupport {

    /** 压缩空白并验证 Spring 六段式 Cron，返回可稳定计算摘要的形式。 */
    public String normalize(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new AppException("SCHEDULE_CRON_INVALID", "Cron 表达式不能为空");
        }
        String normalized = expression.trim().replaceAll("\\s+", " ");
        try {
            CronExpression.parse(normalized);
        } catch (IllegalArgumentException e) {
            throw new AppException("SCHEDULE_CRON_INVALID", "Cron 表达式不合法：" + e.getMessage(), e);
        }
        return normalized;
    }

    /** 校验 IANA 时区；未配置时采用产品默认时区。 */
    public String normalizeTimezone(String timezone) {
        String normalized = timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone.trim();
        try {
            ZoneId.of(normalized);
        } catch (Exception e) {
            throw new AppException("SCHEDULE_TIMEZONE_INVALID", "时区不合法：" + normalized, e);
        }
        return normalized;
    }

    /** 在配置时区计算下一个触发点，再转换为 UTC 无时区值入库。 */
    public LocalDateTime next(String expression, String timezone, LocalDateTime afterUtc) {
        CronExpression cron = CronExpression.parse(normalize(expression));
        ZoneId zone = ZoneId.of(normalizeTimezone(timezone));
        Instant cursor = afterUtc.toInstant(ZoneOffset.UTC);
        ZonedDateTime next = cron.next(cursor.atZone(zone));
        if (next == null) {
            throw new AppException("SCHEDULE_CRON_EXHAUSTED", "Cron 无法计算下一次执行时间");
        }
        return LocalDateTime.ofInstant(next.toInstant(), ZoneOffset.UTC);
    }

    /** 最多预览二十个未来触发点，防止接口被用于无界计算。 */
    public List<LocalDateTime> preview(String expression, String timezone, int count, LocalDateTime nowUtc) {
        int safeCount = Math.max(1, Math.min(count, 20));
        List<LocalDateTime> result = new ArrayList<>(safeCount);
        LocalDateTime cursor = nowUtc;
        for (int i = 0; i < safeCount; i++) {
            cursor = next(expression, timezone, cursor);
            result.add(cursor);
        }
        return result;
    }
}
