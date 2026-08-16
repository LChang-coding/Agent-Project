import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';

const chatStoreSource = readFileSync(resolve(import.meta.dirname, '../src/stores/chat.ts'), 'utf8');
const orchestrationStoreSource = readFileSync(resolve(import.meta.dirname, '../src/stores/orchestration.ts'), 'utf8');
const agentApiSource = readFileSync(resolve(import.meta.dirname, '../src/api/agent.ts'), 'utf8');
const chatWorkspaceSource = readFileSync(resolve(import.meta.dirname, '../src/views/chat/ChatWorkspaceView.vue'), 'utf8');
const subagentTaskDetailSource = readFileSync(resolve(import.meta.dirname, '../src/components/agent/SubagentTaskDetail.vue'), 'utf8');
const orchestrationRunCardSource = readFileSync(resolve(import.meta.dirname, '../src/components/agent/OrchestrationRunCard.vue'), 'utf8');
const dashboardSource = readFileSync(resolve(import.meta.dirname, '../src/views/DashboardView.vue'), 'utf8');
const desktopMultiAgentE2eSource = readFileSync(resolve(import.meta.dirname, './online-multi-agent.e2e.cjs'), 'utf8');
const mobileMultiAgentE2eSource = readFileSync(resolve(import.meta.dirname, './online-multi-agent-mobile.e2e.cjs'), 'utf8');
const packageJson = JSON.parse(readFileSync(resolve(import.meta.dirname, '../package.json'), 'utf8'));

test('runId 生成兼容 HTTP 非安全上下文', () => {
  assert.match(chatStoreSource, /function createRunId\(\)\s*{\s*return `run_\$\{createId\(\)\}`;/);
  assert.doesNotMatch(chatStoreSource, /function createRunId\(\)\s*{\s*return `run_\$\{crypto\.randomUUID\(\)\}`;/);
  assert.match(chatStoreSource, /Date\.now\(\)\.toString\(36\)/);
  assert.match(chatStoreSource, /Math\.random\(\)\.toString\(36\)\.slice\(2\)/);

  const fallbackRunId = `run_${Date.now().toString(36)}${Math.random().toString(36).slice(2)}`;
  assert.match(fallbackRunId, /^[A-Za-z0-9_-]+$/);
  assert.ok(fallbackRunId.length <= 64);
});

test('SSE heartbeat 不会被当成模型正文', () => {
  assert.match(agentApiSource, /if \(eventName === 'heartbeat'\) \{\s*return;\s*\}/);
  const heartbeatBranch = agentApiSource.indexOf("if (eventName === 'heartbeat')");
  const chunkBranch = agentApiSource.indexOf('handlers.onChunk?.(data)');
  assert.ok(heartbeatBranch >= 0 && heartbeatBranch < chunkBranch);
});

test('切换会话只失效旧 UI 回调，不取消服务端运行', () => {
  const detachStart = chatStoreSource.indexOf('    detachActiveRequest() {');
  const detachEnd = chatStoreSource.indexOf('\n    },', detachStart);
  const detachBody = chatStoreSource.slice(detachStart, detachEnd);
  assert.match(detachBody, /activeRequest = null/);
  assert.match(detachBody, /this\.sending = false/);
  assert.doesNotMatch(detachBody, /request\.controller\.abort\(\)/);
  assert.doesNotMatch(chatStoreSource, /cancelActiveRun\('切换会话'\)/);
});

test('用户与 Agent 消息均提供可访问的正文复制操作', () => {
  assert.match(chatWorkspaceSource, /v-if="message\.role === 'user' \|\| message\.role === 'assistant'"/);
  assert.match(chatWorkspaceSource, /:class="\['message-copy', \{ 'message-copy--copied': copiedMessageId === message\.id \}\]"/);
  assert.match(chatWorkspaceSource, /:aria-label="messageCopyLabel\(message\)"/);
  assert.match(chatWorkspaceSource, /:title="messageCopyLabel\(message\)"/);
  assert.match(chatWorkspaceSource, /@click="copyMessageContent\(message\)"/);
  assert.match(chatWorkspaceSource, /<Copy\s+v-else[^>]*aria-hidden="true"/);
  assert.match(chatWorkspaceSource, /<Check\s+v-if="copiedMessageId === message\.id"[^>]*aria-hidden="true"/);
});

test('消息复制使用原始正文并显示成功或失败反馈', () => {
  assert.match(chatWorkspaceSource, /await copyText\(message\.content\)/);
  assert.match(chatWorkspaceSource, /copiedMessageId\.value = message\.id/);
  assert.match(chatWorkspaceSource, /window\.setTimeout\([^]*1200\)/);
  assert.match(chatWorkspaceSource, /messageCopyError\.value = '复制失败，请手动选择消息文本'/);
  assert.match(chatWorkspaceSource, /class="message-copy-error"\s+role="status"\s+aria-live="polite"/);
  assert.match(chatWorkspaceSource, /class="sr-only message-copy-announcement"\s+role="status"\s+aria-live="polite"/);
});

test('复制按钮支持桌面端悬停与键盘焦点，并在触摸设备上常驻', () => {
  assert.match(chatWorkspaceSource, /\.message:hover \.message-copy/);
  assert.match(chatWorkspaceSource, /\.message:focus-within \.message-copy/);
  assert.match(chatWorkspaceSource, /\.message-copy:focus-visible/);
  assert.match(chatWorkspaceSource, /@media\s*\(hover:\s*none\),\s*\(pointer:\s*coarse\)[^]*\.message-copy\s*{[^}]*opacity:\s*1/);
});

test('WAIT_ALL 期间只展示编排状态且不留下空的主 Agent 草稿气泡', () => {
  assert.match(
    chatWorkspaceSource,
    /shouldDisplayChatMessage\(\s*message,\s*orchestrationLocked\.value,\s*currentOrchestration\.value\?\.currentRunId,?\s*\)/,
  );
  const orchestrationBranch = chatWorkspaceSource.indexOf('if (orchestrationLocked.value)');
  const ordinarySendingBranch = chatWorkspaceSource.indexOf('if (chatStore.sending) return {', orchestrationBranch);
  assert.ok(orchestrationBranch >= 0 && ordinarySendingBranch > orchestrationBranch);
  assert.match(chatWorkspaceSource, /label: chatStore\.sending \? '主 Agent 与子 Agent 并行执行' : '正在等待子 Agent'/);
  assert.match(chatWorkspaceSource, /最终答案只会生成一次/);
  assert.match(chatWorkspaceSource, /standaloneExecutionRuns\[message\.id\]/);
  assert.match(chatWorkspaceSource, /data-main-agent-run-id/);
  assert.match(chatWorkspaceSource, /主 Agent<\/span>\s*<span>运行过程/);
  assert.match(chatWorkspaceSource, /子任务已委派，主 Agent 正在等待全部回调/);
});

test('WAIT_ALL 锁定同时作用于按钮与 handler，不能通过引导或会话操作绕过', () => {
  assert.match(chatWorkspaceSource, /:disabled="[^\"]*orchestrationControlsLocked[^\"]*"[^>]*@click="steerRun"/);
  assert.match(chatWorkspaceSource, /async function steerRun\(\) \{\s*if \(orchestrationControlsLocked\.value\) return;/);
  assert.match(chatWorkspaceSource, /async function createSession\(\) \{\s*if \(conversationInteractionLocked\.value\) return;/);
  assert.match(chatWorkspaceSource, /function sessionSwitchBlocked\(sessionId: string\)/);
  assert.match(chatWorkspaceSource, /function currentSessionDeleteBlocked\(sessionId: string\)/);
});

test('WAIT_ALL 只锁定新发送，仍保留主运行取消和子任务停止入口', () => {
  assert.match(
    chatWorkspaceSource,
    /:disabled="chatStore\.cancelling \|\| chatStore\.steering \|\| \(!chatStore\.sending && \(orchestrationControlsLocked/,
  );
  assert.match(chatWorkspaceSource, /@stop="stopOrchestration"/);
  assert.match(chatWorkspaceSource, /async function stopOrchestration\(\)/);
  assert.match(orchestrationRunCardSource, /`\u505c\u6b62 \$\{cancellableCount\} \u4e2a\u5b50\u4efb\u52a1`/);
  assert.match(orchestrationRunCardSource, /\['READY', 'RUNNING'\]\.includes\(task\.status\)/);
});

test('编排版本刷新使用不丢更新的合并器，并在原 SSE 结束后补拉', () => {
  assert.match(chatWorkspaceSource, /createLatestRefresh/);
  assert.match(chatWorkspaceSource, /pendingOrchestrationMessageRefresh/);
  assert.match(chatWorkspaceSource, /if \(!sending && pendingOrchestrationMessageRefresh\)/);
});

test('同一会话的连接重启使旧引导请求和 SSE 回调失效', () => {
  assert.match(orchestrationStoreSource, /let connectionGeneration\s*=\s*0/);
  assert.match(orchestrationStoreSource, /const generation\s*=\s*\+\+connectionGeneration/);
  assert.match(orchestrationStoreSource, /generation\s*!==\s*connectionGeneration/);
  assert.match(orchestrationStoreSource, /disconnect\(\)\s*{[^}]*connectionGeneration\s*\+=\s*1/s);
});

test('静默刷新不吞异常，最终汇总消息使用有限重试', () => {
  assert.match(chatStoreSource, /if \(silent\) throw error;/);
  assert.match(chatWorkspaceSource, /runWithRetry\(async \(\) => \{\s*await chatStore\.reloadSessionMessages\(sessionId, true\)/);
  assert.match(chatWorkspaceSource, /hasVisibleResumeFinalMessage\(chatStore\.messages,\s*currentRunStartedAt\)/);
  assert.match(chatWorkspaceSource, /最终汇总消息尚未出现/);
});

test('刷新会话时先恢复编排快照再解锁发送', () => {
  assert.match(orchestrationStoreSource, /initializedSessionIds/);
  assert.match(orchestrationStoreSource, /await this\.load\(sessionId\)/);
  assert.match(chatWorkspaceSource, /orchestrationRecoveryPending/);
  assert.match(chatWorkspaceSource, /if \(!message \|\| chatStore\.sending \|\| orchestrationControlsLocked\.value\)/);
});

test('中央运行卡与左侧树都通过同一入口加载子 Agent 完整详情', () => {
  assert.match(chatWorkspaceSource, /@select-task="openCurrentSubagent"/);
  assert.match(chatWorkspaceSource, /async function openCurrentSubagent\(taskId: string\)/);
  assert.match(chatWorkspaceSource, /await openSubagent\(chatStore\.sessionId, taskId\)/);
  assert.match(chatWorkspaceSource, /await orchestrationStore\.loadTask\(sessionId, taskId\)/);
  assert.match(chatWorkspaceSource, /\['SUCCEEDED', 'FAILED', 'CANCELLED', 'ACKED'\]\.includes\(current\.status\)/);
});

test('子 Agent Trace ID 复制失败时提供可见反馈', () => {
  assert.match(subagentTaskDetailSource, /catch/);
  assert.match(subagentTaskDetailSource, /复制失败，请手动选择 Trace ID/);
  assert.match(subagentTaskDetailSource, /role="status"/);
});

test('子 Agent 详情展示真实运行阶段、独立事件面板并支持复制结果', () => {
  assert.match(subagentTaskDetailSource, /aria-label="子 Agent 执行进度"\s+aria-live="polite"/);
  assert.match(subagentTaskDetailSource, /任务已创建/);
  assert.match(subagentTaskDetailSource, /子 Agent 执行/);
  assert.match(subagentTaskDetailSource, /结果回调/);
  assert.match(subagentTaskDetailSource, /WorkflowNodeExecutionPanel/);
  assert.match(subagentTaskDetailSource, /childRunId/);
  assert.match(subagentTaskDetailSource, /await copyText\(outputText\.value\)/);
  assert.match(subagentTaskDetailSource, /aria-label="resultCopied \? '子任务结果已复制' : '复制子任务结果'"/);
});

test('编排 SSE 快照为多个子 Agent 独立更新状态并引导查看时间线', () => {
  assert.match(orchestrationStoreSource, /for \(const task of value\.runs\.flatMap\(\(run\) => run\.tasks\)\)/);
  assert.match(orchestrationStoreSource, /this\.applySnapshot\(sessionId, value\)/);
  assert.match(orchestrationRunCardSource, /各子任务状态与回调结果独立更新/);
  assert.match(orchestrationRunCardSource, /思考与工具时间线/);
});

test('切换子 Agent 详情时重建详情视图，避免复制反馈串到下一任务', () => {
  assert.match(chatWorkspaceSource, /<SubagentTaskDetail[^>]*:key="selectedTask\.taskId"/);
});

test('子 Agent 详情在打开和返回时管理焦点，并标记当前树节点', () => {
  assert.match(chatWorkspaceSource, /ref="subagentDetailRef"/);
  assert.match(chatWorkspaceSource, /@back="closeSubagentDetail"/);
  assert.match(chatWorkspaceSource, /:aria-current="[^"]*selectedTaskId[^"]*"/);
  assert.match(chatWorkspaceSource, /data-subagent-task-id/);
  assert.match(chatWorkspaceSource, /lastSubagentTrigger\.value\?\.isConnected/);
  assert.match(chatWorkspaceSource, /lastSubagentTrigger\.value\?\.focus\(\)/);
  assert.match(subagentTaskDetailSource, /defineExpose\(\{\s*focus/);
});

test('子 Agent 移动端操作区不挤入状态标记列，Trace 复制满足最小点击区', () => {
  assert.match(subagentTaskDetailSource, /\.facts button\{[^}]*min-height:24px/);
  assert.match(subagentTaskDetailSource, /@media\(max-width:760px\)[^]*\.task-actions\{[^}]*grid-column:2/);
});

test('会话批量管理只全选可删除项，并明确反馈被排除的运行中会话', () => {
  assert.match(chatWorkspaceSource, />全选可删除会话</);
  assert.match(chatWorkspaceSource, /lockedVisibleSessionCount[^]*个运行中会话已排除/);
  assert.match(chatWorkspaceSource, /:disabled="!sessionDeletable\(session\.sessionId\)"/);
  assert.match(chatWorkspaceSource, /new Set\(deletableVisibleSessions\.value\.map/);
  assert.match(chatWorkspaceSource, /运行中的会话不能删除，请先停止运行/);
});

test('总览与会话工作台使用兼容单 Agent 和 Multi-Agent 的统一入口文案', () => {
  assert.match(dashboardSource, />进入 Agent 编排</);
  assert.match(dashboardSource, /title: '单 \/ Multi-Agent 编排'/);
  assert.match(chatWorkspaceSource, /<h1>Agent 编排工作台<\/h1>/);
  assert.match(chatWorkspaceSource, /<span>运行 Agent<\/span>/);
  assert.doesNotMatch(`${dashboardSource}\n${chatWorkspaceSource}`, /单一智能体/);
});

test('会话工作台提供可访问的双节点引擎拨杆与每次进入导览', () => {
  const source = readFileSync(new URL('../src/views/chat/ChatWorkspaceView.vue', import.meta.url), 'utf8');
  assert.match(source, /role="radiogroup" aria-label="运行引擎"/);
  assert.match(source, /data-source-mode="agent"/);
  assert.match(source, /data-source-mode="workflow"/);
  assert.match(source, /onMounted\(async \(\) =>[^]*await openModeGuide\(\)/);
  assert.doesNotMatch(source, /MODE_GUIDE_SEEN_KEY|mode-guide-seen/);
  assert.match(source, /主 Agent 调度模式/);
  assert.match(source, /DAG \/ 智能工作流/);
  assert.match(source, /WAIT_ALL/);
  assert.match(source, /智能路由/);
  assert.match(source, /:disabled="sourceSwitching \|\| !workspaceReady"/);
  assert.match(source, /if \(!workspaceReady\.value \|\| sourceSwitching\.value/);
  assert.match(source, /topology--supervisor/);
  assert.match(source, /topology--dag/);
  assert.match(source, /prefers-reduced-motion: reduce/);
});

test('Multi-Agent 线上 E2E 使用现行会话选择器并等待锁定会话解锁后删除', () => {
  assert.doesNotMatch(mobileMultiAgentE2eSource, /\.session-main/);
  assert.match(mobileMultiAgentE2eSource, /\.session-open/);
  assert.match(desktopMultiAgentE2eSource, /session-delete[^]*disabled/);
  assert.doesNotMatch(desktopMultiAgentE2eSource, /document\.querySelectorAll\('\.session-item'\)\.length === 0/);
  assert.equal(packageJson.scripts['test:e2e:multi-agent'], 'node tests/online-multi-agent.e2e.cjs');
  assert.equal(packageJson.scripts['test:e2e:multi-agent:mobile'], 'node tests/online-multi-agent-mobile.e2e.cjs');
});
