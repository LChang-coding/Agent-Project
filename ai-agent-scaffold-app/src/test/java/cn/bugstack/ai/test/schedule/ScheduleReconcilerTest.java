package cn.bugstack.ai.test.schedule;

import cn.bugstack.ai.domain.schedule.adapter.IScheduleRepository;
import cn.bugstack.ai.domain.schedule.model.ScheduleConfigEntity;
import cn.bugstack.ai.domain.schedule.model.ScheduleTaskEntity;
import cn.bugstack.ai.domain.schedule.service.CronScheduleSupport;
import cn.bugstack.ai.domain.schedule.service.ScheduleReconciler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 配置摘要对账测试。
 */
public class ScheduleReconcilerTest {

    @Test
    public void shouldKeepStableTaskIdentityAndChangeHashWhenCronChanges() {
        IScheduleRepository repository = mock(IScheduleRepository.class);
        AtomicReference<ScheduleTaskEntity> stored = new AtomicReference<>();
        when(repository.upsertTask(any())).thenAnswer(invocation -> {
            ScheduleTaskEntity input = invocation.getArgument(0);
            ScheduleTaskEntity previous = stored.get();
            input.setConfigVersion(previous == null ? 1
                    : previous.getConfigHash().equals(input.getConfigHash()) ? previous.getConfigVersion()
                    : previous.getConfigVersion() + 1);
            stored.set(input);
            return input;
        });
        ScheduleReconciler reconciler = new ScheduleReconciler(repository, new CronScheduleSupport(),
                new ObjectMapper());
        ScheduleConfigEntity config = config("0 0 9 * * *");

        ScheduleTaskEntity first = reconciler.reconcile(config);
        ScheduleTaskEntity same = reconciler.reconcile(config);
        config.setCronExpr("0 30 9 * * *");
        ScheduleTaskEntity changed = reconciler.reconcile(config);

        assertEquals(first.getTaskId(), same.getTaskId());
        assertEquals(first.getTaskId(), changed.getTaskId());
        assertEquals(1, same.getConfigVersion());
        assertEquals(2, changed.getConfigVersion());
        assertNotEquals(first.getConfigHash(), changed.getConfigHash());
    }

    private ScheduleConfigEntity config(String cron) {
        return ScheduleConfigEntity.builder().tenantId("tenant-1").ownerUserId("user-1")
                .runAsUserId("user-1").runAsRoleCode("member").configId("config-1")
                .agentId("agent-1").taskType("agent_prompt").taskPayload("{\"message\":\"hello\"}")
                .cronExpr(cron).timezone("Asia/Shanghai").enabled(true).status("active")
                .misfirePolicy("fire_once_now").maxRetries(3).build();
    }
}
