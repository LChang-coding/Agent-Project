import assert from 'node:assert/strict';
import test from 'node:test';

import {
  createLatestRefresh,
  currentRunTasks,
  hasVisibleResumeFinalMessage,
  isAsyncResultCurrent,
  isConversationInteractionLocked,
  isCurrentSessionDeleteBlocked,
  isSessionSwitchBlocked,
  mergeAuthoritativeMessages,
  mergeSubagentTaskDetail,
  resolveLoadedSubagentTaskDetail,
  runWithRetry,
  shouldDisplayChatMessage,
  subagentTaskLabel,
  subagentTaskTone,
} from '../src/domain/chat-orchestration-state.ts';

const message = (overrides = {}) => ({
  id: 'message',
  runId: 'run-parent',
  role: 'assistant',
  content: '',
  createdAt: '2026-08-16T00:00:00Z',
  status: 'streaming',
  ...overrides,
});

test('WAIT_ALL hides empty parent placeholders but keeps real content visible', () => {
  assert.equal(shouldDisplayChatMessage(message(), true, 'run-parent'), false);
  assert.equal(shouldDisplayChatMessage(message({ status: 'sending' }), true, 'run-parent'), false);
  assert.equal(shouldDisplayChatMessage(message({ content: 'final answer', status: 'done' }), true, 'run-parent'), true);
  assert.equal(shouldDisplayChatMessage(message(), false, 'run-parent'), true);
});

test('authoritative reload replaces optimistic run messages without duplicating the user turn', () => {
  const existing = [
    message({ id: 'msg-old', runId: 'run-old', role: 'user', content: 'older', status: 'done' }),
    message({ id: 'local-user', role: 'user', content: 'question', status: 'done', localOnly: true }),
    message({ id: 'local-assistant', localOnly: true }),
  ];
  const authoritative = [
    message({ id: 'msg-user', role: 'user', content: 'question', status: 'done' }),
    message({ id: 'msg-final', runId: 'run-resume', content: 'final answer', status: 'done' }),
  ];

  const merged = mergeAuthoritativeMessages(existing, authoritative);

  assert.deepEqual(merged.map((item) => item.id), ['msg-old', 'msg-user', 'msg-final']);
  assert.equal(merged.filter((item) => item.role === 'user' && item.content === 'question').length, 1);
});

test('authoritative reload preserves an unrelated optimistic run still in flight', () => {
  const local = message({ id: 'local-other', runId: 'run-other', role: 'user', content: 'pending', localOnly: true });
  const merged = mergeAuthoritativeMessages([local], [
    message({ id: 'msg-user', role: 'user', content: 'question', status: 'done' }),
  ]);
  assert.deepEqual(merged.map((item) => item.id), ['local-other', 'msg-user']);
});

test('会话锁仅保护当前会话写入和删除，不阻断切换到其他会话', () => {
  assert.equal(isConversationInteractionLocked(true, false), true);
  assert.equal(isConversationInteractionLocked(false, true), true);
  assert.equal(isSessionSwitchBlocked('session-a', 'session-b', true), false);
  assert.equal(isSessionSwitchBlocked('session-a', 'session-a', true), false);
  assert.equal(isCurrentSessionDeleteBlocked('session-a', 'session-a', true), true);
  assert.equal(isCurrentSessionDeleteBlocked('session-b', 'session-a', true), false);
});

test('ACKED tasks retain a failed presentation when an execution error is present', () => {
  const failed = { taskId: 'task', childAgentId: 'agent', instruction: 'work', status: 'ACKED', errorCode: 'MODEL_FAILED' };
  assert.equal(subagentTaskTone(failed), 'error');
  assert.match(subagentTaskLabel(failed), /失败/);
  assert.equal(subagentTaskTone({ ...failed, errorCode: undefined }), 'done');
});

test('refresh coordinator coalesces bursts and never drops a version received in flight', async () => {
  const gates = [];
  let calls = 0;
  const refresh = createLatestRefresh(async () => {
    calls += 1;
    await new Promise((resolve) => gates.push(resolve));
  });

  const first = refresh.request();
  await Promise.resolve();
  assert.equal(calls, 1);

  const second = refresh.request();
  const third = refresh.request();
  gates.shift()();
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(calls, 2);

  gates.shift()();
  await Promise.all([first, second, third]);
  assert.equal(calls, 2);
});

test('current run tree does not mix child agents from historical orchestration batches', () => {
  const snapshot = {
    currentRunId: 'run-current',
    runs: [
      { parentRunId: 'run-history', tasks: [{ taskId: 'old' }] },
      { parentRunId: 'run-current', tasks: [{ taskId: 'current-a' }, { taskId: 'current-b' }] },
    ],
  };

  assert.deepEqual(currentRunTasks(snapshot).map((task) => task.taskId), ['current-a', 'current-b']);
});

test('current run tree stays empty while a new parent run has not created child agents', () => {
  const snapshot = {
    currentRunId: 'run-new-parent',
    runs: [{ parentRunId: 'run-history', tasks: [{ taskId: 'old' }] }],
  };

  assert.deepEqual(currentRunTasks(snapshot), []);
  assert.deepEqual(currentRunTasks({ currentRunId: undefined, runs: snapshot.runs }), []);
});

test('fresh orchestration snapshot updates cached task state without discarding full context', () => {
  const cached = {
    taskId: 'task-1', childAgentId: '100001', instruction: 'code', status: 'RUNNING',
    fullContext: 'streamed details', attempt: 1,
  };
  const snapshot = {
    taskId: 'task-1', childAgentId: '100001', instruction: 'code', status: 'SUCCEEDED',
    resultSummary: 'done', callbackStatus: 'REGISTERED', attempt: 2,
  };

  assert.deepEqual(mergeSubagentTaskDetail(cached, snapshot), {
    ...snapshot,
    fullContext: 'streamed details',
  });
});

test('an async detail response cannot overwrite a snapshot received while it was in flight', () => {
  const staleDetail = {
    taskId: 'task-1', childAgentId: '100001', instruction: 'code', status: 'RUNNING',
    fullContext: 'complete child output', attempt: 1,
  };
  const freshSnapshot = {
    taskId: 'task-1', childAgentId: '100001', instruction: 'code', status: 'SUCCEEDED',
    resultSummary: 'done', callbackStatus: 'REGISTERED', attempt: 2,
  };

  assert.equal(isAsyncResultCurrent(4, 5), false);
  assert.equal(isAsyncResultCurrent(5, 5), true);
  assert.deepEqual(resolveLoadedSubagentTaskDetail(staleDetail, freshSnapshot, true), {
    ...freshSnapshot,
    fullContext: 'complete child output',
  });
  assert.equal(resolveLoadedSubagentTaskDetail(staleDetail, freshSnapshot, false).status, 'RUNNING');
});

test('final message refresh retries a transient read failure before surfacing an error', async () => {
  let calls = 0;
  const waits = [];
  const result = await runWithRetry(async () => {
    calls += 1;
    if (calls < 3) throw new Error('temporary');
    return 'final';
  }, { maxAttempts: 4, delayMs: 10 }, async (delay) => { waits.push(delay); });

  assert.equal(result, 'final');
  assert.equal(calls, 3);
  assert.deepEqual(waits, [10, 20]);
});

test('a successful history request is retried until the current resume final message is visible', async () => {
  const runStartedAt = '2026-08-16T00:00:00Z';
  const previousFinal = message({
    id: 'previous-final',
    runId: 'run_resume_previous',
    content: 'previous result',
    status: 'done',
    createdAt: '2026-08-15T23:59:59Z',
  });
  const currentFinal = message({
    id: 'current-final',
    runId: 'run_resume_current',
    content: 'current result',
    status: 'done',
    createdAt: '2026-08-16T00:00:01Z',
  });

  assert.equal(hasVisibleResumeFinalMessage([previousFinal], runStartedAt), false);
  assert.equal(hasVisibleResumeFinalMessage([
    message({ id: 'empty', runId: 'run_resume_current', content: '', status: 'done', createdAt: currentFinal.createdAt }),
  ], runStartedAt), false);
  assert.equal(hasVisibleResumeFinalMessage([previousFinal, currentFinal], runStartedAt), true);

  let calls = 0;
  const waits = [];
  const result = await runWithRetry(async () => {
    calls += 1;
    const messages = calls < 3 ? [previousFinal] : [previousFinal, currentFinal];
    if (!hasVisibleResumeFinalMessage(messages, runStartedAt)) {
      throw new Error('最终汇总消息尚未出现');
    }
    return 'ready';
  }, { maxAttempts: 4, delayMs: 10 }, async (delay) => { waits.push(delay); });

  assert.equal(result, 'ready');
  assert.equal(calls, 3);
  assert.deepEqual(waits, [10, 20]);
});
