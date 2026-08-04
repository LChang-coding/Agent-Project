import assert from 'node:assert/strict';
import test from 'node:test';

import {
  WORKFLOW_TEMPLATES,
  cloneWorkflowTemplateGraph,
  workflowTemplateById,
} from '../src/domain/workflow-templates.ts';

test('模板数量和分类覆盖达到交付门禁', () => {
  assert.ok(WORKFLOW_TEMPLATES.length >= 24);
  assert.ok(WORKFLOW_TEMPLATES.filter((item) => item.category === 'PRODUCTION').length >= 12);
  assert.ok(WORKFLOW_TEMPLATES.filter((item) => item.category === 'TEST').length >= 12);
  assert.ok(WORKFLOW_TEMPLATES.filter((item) => item.workflowKind === 'STATIC').length >= 10);
  assert.ok(WORKFLOW_TEMPLATES.filter((item) => item.workflowKind === 'INTELLIGENT').length >= 10);
  for (const category of ['PRODUCTION', 'TEST']) {
    for (const kind of ['STATIC', 'INTELLIGENT']) {
      assert.ok(WORKFLOW_TEMPLATES.some((item) => item.category === category && item.workflowKind === kind));
    }
  }
});

test('模板、节点和边使用稳定唯一 ID', () => {
  const templateIds = WORKFLOW_TEMPLATES.map((item) => item.id);
  assert.equal(new Set(templateIds).size, templateIds.length);
  templateIds.forEach((id) => assert.match(id, /^(prod|test)-(static|intelligent)-[a-z0-9-]+$/));

  for (const template of WORKFLOW_TEMPLATES) {
    assert.ok(template.name.trim().length > 0, template.id);
    assert.ok(template.description.trim().length > 0, template.id);
    assert.ok(template.tags.length > 0, template.id);
    const nodeIds = template.graph.nodes.map((node) => node.nodeId);
    const edgeIds = template.graph.edges.map((edge) => edge.edgeId);
    assert.equal(new Set(nodeIds).size, nodeIds.length, template.id);
    assert.equal(new Set(edgeIds).size, edgeIds.length, template.id);
    assert.ok(nodeIds.includes(template.graph.rootNodeId), template.id);
    template.graph.nodes.forEach((node) => assert.equal(node.nodeType, 'llm', `${template.id}: ${node.nodeId}`));
    for (const edge of template.graph.edges) {
      assert.ok(nodeIds.includes(edge.sourceNodeId), `${template.id}: ${edge.edgeId} 起点不存在`);
      assert.ok(edge.targetNodeId === 'END' || nodeIds.includes(edge.targetNodeId), `${template.id}: ${edge.edgeId} 终点不存在`);
    }
  }
});

test('静态模板仅包含 DAG 和有界自循环', () => {
  for (const template of WORKFLOW_TEMPLATES.filter((item) => item.workflowKind === 'STATIC')) {
    assert.equal(template.graph.workflowKind, 'STATIC');
    assertStaticAcyclicIgnoringSelfLoops(template.graph, template.id);
    for (const edge of template.graph.edges.filter((item) => item.sourceNodeId === item.targetNodeId)) {
      const node = template.graph.nodes.find((item) => item.nodeId === edge.sourceNodeId);
      assert.ok(node?.maxIterations >= 1 && node.maxIterations <= 20, template.id);
    }
  }
});

test('智能模板具有有界预算、允许目标和 DEFAULT 出口', () => {
  for (const template of WORKFLOW_TEMPLATES.filter((item) => item.workflowKind === 'INTELLIGENT')) {
    const graph = template.graph;
    assert.equal(graph.workflowKind, 'INTELLIGENT');
    assert.ok(graph.maxSteps >= 1 && graph.maxSteps <= 200, template.id);
    assert.ok(graph.tokenBudget >= 1 && graph.tokenBudget <= 10_000_000, template.id);
    for (const node of graph.nodes) {
      assert.ok(node.maxVisits >= 1 && node.maxVisits <= 50, `${template.id}: ${node.nodeId}`);
      const outgoing = graph.edges.filter((edge) => edge.sourceNodeId === node.nodeId);
      if (outgoing.length === 0) continue;
      const fallback = outgoing.find((edge) => edge.routeType === 'DEFAULT');
      assert.ok(fallback, `${template.id}: ${node.nodeId} 缺 DEFAULT`);
      assert.equal(node.defaultTargetNodeId, fallback.targetNodeId, `${template.id}: ${node.nodeId} 默认目标不一致`);
      outgoing.forEach((edge) => assert.ok(node.allowedTargetNodeIds.includes(edge.targetNodeId), `${template.id}: ${edge.edgeId}`));
      outgoing.filter((edge) => edge.routeType === 'EXPRESSION').forEach((edge) => {
        assert.match(edge.conditionExpression, /^(status|output|suggestion)\s+(equals|contains)\s+'[^']+'$/,
          `${template.id}: ${edge.edgeId} 表达式不受后端路由器支持`);
      });
    }
  }
});

test('智能模板自动写入精确路由协议并支持中文主键和受控别名', () => {
  for (const template of WORKFLOW_TEMPLATES.filter((item) => item.workflowKind === 'INTELLIGENT')) {
    for (const node of template.graph.nodes) {
      const outgoing = template.graph.edges.filter((edge) => edge.sourceNodeId === node.nodeId);
      for (const edge of outgoing.filter((item) => ['AI_ROUTER', 'NODE_SUGGESTION'].includes(item.routeType))) {
        assert.ok(node.routeInstruction.includes(`[route:${edge.routeKey}]`), `${template.id}: ${edge.edgeId} 未注入精确格式`);
        for (const alias of edge.routeAliases || []) {
          assert.ok(node.routeInstruction.includes(alias), `${template.id}: ${edge.edgeId} 未注入别名 ${alias}`);
        }
      }
    }
  }

  const customer = workflowTemplateById('prod-intelligent-customer-router').graph;
  const billing = customer.edges.find((edge) => edge.sourceNodeId === 'classify' && edge.targetNodeId === 'billing');
  assert.equal(billing.routeKey, '账务');
  assert.deepEqual(billing.routeAliases, ['billing']);
  assert.match(customer.nodes.find((node) => node.nodeId === 'classify').routeInstruction, /\[route:账务]/);
});

test('深拷贝载入不会污染模板清单或其他载入实例', () => {
  const templateId = 'prod-intelligent-audit-revision';
  const first = cloneWorkflowTemplateGraph(templateId);
  const second = cloneWorkflowTemplateGraph(templateId);
  first.nodes[0].name = '已修改';
  first.edges[0].targetNodeId = 'END';
  first.nodes[0].allowedTargetNodeIds.push('tampered');

  assert.notEqual(second.nodes[0].name, '已修改');
  assert.notEqual(second.edges[0].targetNodeId, 'END');
  assert.ok(!second.nodes[0].allowedTargetNodeIds.includes('tampered'));
  assert.notEqual(workflowTemplateById(templateId).graph.nodes[0].name, '已修改');
  assert.throws(() => cloneWorkflowTemplateGraph('missing-template'), /不存在/);
});

test('关键测试模板保留串行、扇出汇聚、自循环和智能回路结构', () => {
  const serial = workflowTemplateById('test-static-serial-three').graph;
  assert.deepEqual(serial.edges.map((edge) => `${edge.sourceNodeId}->${edge.targetNodeId}`), ['a->b', 'b->c']);

  const join = workflowTemplateById('test-static-fanout-join').graph;
  assert.equal(join.edges.filter((edge) => edge.sourceNodeId === 'start').length, 2);
  assert.equal(join.edges.filter((edge) => edge.targetNodeId === 'join').length, 2);

  const selfLoop = workflowTemplateById('test-static-bounded-self-loop').graph;
  assert.ok(selfLoop.edges.some((edge) => edge.sourceNodeId === 'iterate' && edge.targetNodeId === 'iterate'));
  assert.equal(selfLoop.nodes.find((node) => node.nodeId === 'iterate').maxIterations, 3);

  const intelligentCycle = workflowTemplateById('test-intelligent-bounded-cycle').graph;
  assert.ok(intelligentCycle.edges.some((edge) => edge.sourceNodeId === 'review' && edge.targetNodeId === 'generate'));
  assert.ok(intelligentCycle.edges.some((edge) => edge.sourceNodeId === 'review' && edge.targetNodeId === 'END'));
  assert.equal(intelligentCycle.maxSteps, 8);

  const binary = workflowTemplateById('test-intelligent-binary-router').graph;
  assert.ok(binary.edges.some((edge) => edge.sourceNodeId === 'route' && edge.routeType === 'AI_ROUTER'));
  assert.ok(binary.edges.some((edge) => edge.sourceNodeId === 'route' && edge.routeType === 'DEFAULT'));
});

function assertStaticAcyclicIgnoringSelfLoops(graph, templateId) {
  const outgoing = new Map(graph.nodes.map((node) => [node.nodeId, []]));
  const indegree = new Map(graph.nodes.map((node) => [node.nodeId, 0]));
  for (const edge of graph.edges) {
    if (edge.sourceNodeId === edge.targetNodeId) continue;
    outgoing.get(edge.sourceNodeId).push(edge.targetNodeId);
    indegree.set(edge.targetNodeId, indegree.get(edge.targetNodeId) + 1);
  }
  const queue = [...indegree.entries()].filter(([, value]) => value === 0).map(([nodeId]) => nodeId);
  let visited = 0;
  while (queue.length > 0) {
    const nodeId = queue.shift();
    visited += 1;
    for (const targetNodeId of outgoing.get(nodeId)) {
      indegree.set(targetNodeId, indegree.get(targetNodeId) - 1);
      if (indegree.get(targetNodeId) === 0) queue.push(targetNodeId);
    }
  }
  assert.equal(visited, graph.nodes.length, `${templateId}: 存在非自循环`);
}
