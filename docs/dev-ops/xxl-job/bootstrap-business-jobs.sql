-- 删除官方初始化脚本中的示例任务，避免生产环境保留无关执行入口。
DELETE info FROM xxl_job_info info
INNER JOIN xxl_job_group job_group ON job_group.id = info.job_group
WHERE job_group.app_name IN ('xxl-job-executor-sample', 'xxl-job-executor-sample-ai');

DELETE FROM xxl_job_group
WHERE app_name IN ('xxl-job-executor-sample', 'xxl-job-executor-sample-ai');

-- 执行器组以 appName 自动注册地址。
INSERT INTO xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'ai-agent-scheduler', 'AI Agent 业务调度器', 0, NULL, NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_group WHERE app_name = 'ai-agent-scheduler');

-- 长间隔配置对账：每五分钟唤醒一次。
INSERT INTO xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email,
                          schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
                          executor_handler, executor_param, executor_block_strategy, executor_timeout,
                          executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
                          child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT job_group.id, '业务定时配置对账', NOW(), NOW(), 'system', '',
       'CRON', '0 */5 * * * ?', 'DO_NOTHING', 'ROUND',
       'scheduleReconcileJobHandler', '', 'SERIAL_EXECUTION', 120,
       0, 'BEAN', '', '系统初始化', NOW(), '', 0, 0, 0
FROM xxl_job_group job_group
WHERE job_group.app_name = 'ai-agent-scheduler'
  AND NOT EXISTS (
    SELECT 1 FROM xxl_job_info existing
    WHERE existing.job_group = job_group.id AND existing.executor_handler = 'scheduleReconcileJobHandler'
  );

-- 短间隔到期派发：每五秒唤醒一次。
INSERT INTO xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email,
                          schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
                          executor_handler, executor_param, executor_block_strategy, executor_timeout,
                          executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
                          child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT job_group.id, '业务到期任务派发', NOW(), NOW(), 'system', '',
       'CRON', '*/5 * * * * ?', 'DO_NOTHING', 'ROUND',
       'scheduleDispatchJobHandler', '', 'SERIAL_EXECUTION', 120,
       0, 'BEAN', '', '系统初始化', NOW(), '', 0, 0, 0
FROM xxl_job_group job_group
WHERE job_group.app_name = 'ai-agent-scheduler'
  AND NOT EXISTS (
    SELECT 1 FROM xxl_job_info existing
    WHERE existing.job_group = job_group.id AND existing.executor_handler = 'scheduleDispatchJobHandler'
  );

UPDATE xxl_job_info info
INNER JOIN xxl_job_group job_group ON job_group.id = info.job_group
SET info.schedule_type = 'CRON',
    info.schedule_conf = IF(info.executor_handler = 'scheduleReconcileJobHandler', '0 */5 * * * ?', '*/5 * * * * ?'),
    info.misfire_strategy = 'DO_NOTHING',
    info.executor_route_strategy = 'ROUND',
    info.executor_block_strategy = 'SERIAL_EXECUTION',
    info.executor_timeout = 120,
    info.executor_fail_retry_count = 0,
    info.update_time = NOW()
WHERE job_group.app_name = 'ai-agent-scheduler'
  AND info.executor_handler IN ('scheduleReconcileJobHandler', 'scheduleDispatchJobHandler');
