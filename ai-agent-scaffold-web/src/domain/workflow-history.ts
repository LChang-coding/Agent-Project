export interface WorkflowHistoryMessage {
  role: string;
  runId?: string;
  traceId?: string;
}

export interface WorkflowHistoryRunTarget {
  runId: string;
  traceId: string;
}

/** 从最新消息向前提取可回放的智能工作流 Run；同一 Run 只建立一个事件流。 */
export function workflowHistoryRunTargets(messages: WorkflowHistoryMessage[]): WorkflowHistoryRunTarget[] {
  const seen = new Set<string>();
  const targets: WorkflowHistoryRunTarget[] = [];
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (message.role !== 'assistant' || !message.runId || !message.traceId || seen.has(message.runId)) continue;
    seen.add(message.runId);
    targets.push({ runId: message.runId, traceId: message.traceId });
  }
  return targets;
}
