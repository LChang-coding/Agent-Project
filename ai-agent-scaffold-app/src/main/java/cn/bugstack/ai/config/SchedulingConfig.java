package cn.bugstack.ai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 全局定时任务基础设施。
 *
 * <p>调度能力不得由任一受业务开关控制的任务类启用，否则关闭该任务会连带关闭其他模块的定时扫描。</p>
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
