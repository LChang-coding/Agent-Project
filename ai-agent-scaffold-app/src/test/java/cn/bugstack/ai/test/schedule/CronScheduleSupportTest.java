package cn.bugstack.ai.test.schedule;

import cn.bugstack.ai.domain.schedule.service.CronScheduleSupport;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Test;

import java.time.LocalDateTime;

import static org.junit.Assert.assertEquals;

/**
 * Cron 与时区统一计算测试。
 */
public class CronScheduleSupportTest {

    private final CronScheduleSupport support = new CronScheduleSupport();

    @Test
    public void shouldCalculateNextTimeInConfiguredTimezoneAndStoreUtc() {
        LocalDateTime next = support.next("0 0 9 * * *", "Asia/Shanghai",
                LocalDateTime.of(2026, 7, 15, 0, 30));

        assertEquals(LocalDateTime.of(2026, 7, 15, 1, 0), next);
    }

    @Test(expected = AppException.class)
    public void shouldRejectInvalidTimezone() {
        support.next("0 0 9 * * *", "Mars/Base", LocalDateTime.now());
    }
}
