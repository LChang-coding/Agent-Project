package cn.bugstack.ai.trigger.job;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-JOB 执行器配置，只有显式启用时才注册到 Admin。
 */
@Configuration
@ConditionalOnProperty(prefix = "xxl.job.executor", name = "enabled", havingValue = "true")
public class XxlJobConfiguration {

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor(
            @Value("${xxl.job.admin.addresses}") String adminAddresses,
            @Value("${xxl.job.access-token:}") String accessToken,
            @Value("${xxl.job.executor.appname:ai-agent-scheduler}") String appName,
            @Value("${xxl.job.executor.address:}") String address,
            @Value("${xxl.job.executor.ip:}") String ip,
            @Value("${xxl.job.executor.port:9999}") int port,
            @Value("${xxl.job.executor.log-path:./data/xxl-job}") String logPath,
            @Value("${xxl.job.executor.log-retention-days:30}") int retentionDays) {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAccessToken(accessToken);
        executor.setAppname(appName);
        executor.setAddress(address);
        executor.setIp(ip);
        executor.setPort(port);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(retentionDays);
        return executor;
    }
}
