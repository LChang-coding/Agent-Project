/** 解析单个 SSE 块；只处理传输语法，不依赖浏览器或 HTTP 客户端。 */
export function parseWorkflowSseBlock(block: string): { eventName: string; data: unknown } | null {
  let eventName = '';
  const dataLines: string[] = [];
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) eventName = line.slice(6).trim();
    if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart());
  }
  if (!eventName || dataLines.length === 0) return null;
  try {
    return { eventName, data: JSON.parse(dataLines.join('\n')) as unknown };
  } catch {
    throw new Error(`工作流 SSE 事件 ${eventName} 不是有效 JSON`);
  }
}
