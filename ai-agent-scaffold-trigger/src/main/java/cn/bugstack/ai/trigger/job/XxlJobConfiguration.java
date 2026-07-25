package cn.bugstack.ai.trigger.job;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-JOB 执行器装配配置。
 * <p>只有显式启用时才创建执行器并向 Admin 注册，未启用环境不会建立额外端口和心跳连接。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "xxl.job.executor", name = "enabled", havingValue = "true")
public class XxlJobConfiguration {

    /**
     * 根据外部配置创建 XXL-JOB Spring 执行器。
     *
     * @param adminAddresses Admin 地址列表
     * @param accessToken Admin 通信令牌
     * @param appName 执行器注册名称
     * @param address 对外注册地址；为空时由框架推导
     * @param ip 执行器绑定 IP；为空时自动探测
     * @param port 执行器回调端口
     * @param logPath 任务日志落盘目录
     * @param retentionDays 日志保留天数
     * @return 由 Spring 管理生命周期的 XXL-JOB 执行器
     */
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
        // 逐项传入部署配置，避免 XXL-JOB 使用与项目环境不一致的隐式默认值。
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAccessToken(accessToken);
        executor.setAppname(appName);
        executor.setAddress(address);
        executor.setIp(ip);
        executor.setPort(port);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(retentionDays);
        // Bean 初始化阶段由框架启动注册线程，销毁阶段由 Spring 关闭执行器。
        return executor;
    }
}
