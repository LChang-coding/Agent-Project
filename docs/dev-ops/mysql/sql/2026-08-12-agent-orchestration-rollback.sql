-- 回滚前必须先关闭 AI_AGENT_ORCHESTRATION_ENABLED，确认无 RUNNING / PUBLISHING 数据并完成备份。
DROP TABLE IF EXISTS `agent_orchestration_outbox`;
DROP TABLE IF EXISTS `agent_tool_permission`;
DROP TABLE IF EXISTS `agent_tool_approval_request`;
DROP TABLE IF EXISTS `agent_parent_resume_request`;
DROP TABLE IF EXISTS `agent_parent_inbox`;
DROP TABLE IF EXISTS `agent_subagent_task`;
