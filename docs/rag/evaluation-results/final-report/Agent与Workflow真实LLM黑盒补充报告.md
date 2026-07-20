# Agent与Workflow真实LLM黑盒补充报告

> 只记录2026-07-20通过真实HTTP入口、DeepSeek、MySQL、Embedding和Qdrant获得的结果。判定器为固定词项、精确拒答和引用集合校验，未使用LLM-as-judge。

## 环境与证据

- 有效运行：`agent-workflow-fixed-20260720T014714Z`，UTC `01:47:14.315699`～`01:51:49.555891`，单线程，总墙钟275.240秒。
- Context开启，模型窗口128000 Token，RAG预算8192 Token。App JAR SHA-256=`aaaef787a632b5efd70497a06d5ecad1a21718b3958024f1a597bb12411b5982`。
- [星灰灯塔运维手册](../../evaluation-data/agent-workflow-e2e/citation-fixture.md) SHA-256=`f2c2325836058b5eb32df4a5edc8bcd5112f8ca45c7d977d0a55bba60dd5e80b`；[spec.json](../../evaluation-data/agent-workflow-e2e/spec.json) SHA-256=`882d888a6b971748e531404b0a77614ce1e00731fc3d5dae33df8aaed630976b`。
- [原始结果](../agent-workflow-fixed-20260720T014714Z/agent-workflow-results.json) SHA-256=`a0260224949b15a3fb4c370d4f157469fa9c39e26f6792718a3ecec535d1e882`；[manifest](../agent-workflow-fixed-20260720T014714Z/manifest.json) SHA-256=`8d632daf6927b5c3820b2d0e50c37886ed0cc8094d3a9cd6df1c386488933c15`。
- 取消修复回归运行：[manifest](../agent-workflow-cancel-fixed-20260720T020800Z/manifest.json)，UTC `02:07:39.005234`～`02:11:05.733388`，单线程，总墙钟206.728秒；JAR SHA-256=`39f4a76815e1a870ce848110300700792a7b5e0587091ab0502eee9e1297f8cc`。manifest中的`codeRevision=58fdfe1`是构建前基线提交，JAR同时包含本节待提交的取消修复，因此以JAR哈希和最终提交共同定位，不把基线提交冒充完整源码版本。
- [回归原始结果](../agent-workflow-cancel-fixed-20260720T020800Z/agent-workflow-results.json) SHA-256=`469ce47861dd0fb3e96d9b8d5a779b09ff994e4977cca6412227264236423358`；[取消原始证据](../agent-workflow-cancel-fixed-20260720T020800Z/cancel.json) SHA-256=`1d75f264fc009cea8d991c300ed67035251ab9aacc8d713e7aa48b5fe8a7613d`。

## 最终结果

| 入口/用例 | 墙钟ms | 结果 | 引用状态 |
|---|---:|---|---|
| Workflow非流式可回答 | 10331 | 命中`INDIGO-FALCON-7319` | `VALID` |
| Workflow非流式无答案 | 7401 | 精确`NOT_IN_DOCUMENT` | `RAG_AVAILABLE_UNUSED` |
| Workflow伪造引用 | 8986 | 伪造ID被模型输出 | `INVALID_CITATIONS` |
| Workflow SSE可回答 | 8245 | 命中事实 | session/run/citation_validation各1个 |
| Agent非流式可回答 | 54555 | 命中`INDIGO-FALCON-7319` | `VALID` |
| Agent非流式无答案 | 23279 | 精确`NOT_IN_DOCUMENT` | `RAG_AVAILABLE_UNUSED` |
| Agent伪造引用 | 47440 | 伪造ID被模型输出 | `INVALID_CITATIONS` |
| Agent SSE可回答 | 50447 | 命中事实 | session/run/citation_validation各1个 |

两入口的响应均与数据库历史中的assistant message及citation metadata一致；回源正文含`INDIGO-FALCON-7319`。新租户跨租户查询时60ms级返回业务码`SESSION_NOT_FOUND`，未泄露正文或对象存在性。

取消修复后的全量回归没有牺牲上述行为。Workflow可回答/无答案/伪引用/SSE为10893/9259/9186/7114ms；Agent为43852/22468/29268/41570ms。所有确定性检查再次通过，跨租户56ms返回`SESSION_NOT_FOUND`。这些是同配置单次样本，不能当p50/p95。

## 配置消融与失败因果

| 运行 | Context | RAG Token | 观测 | 首个失效阶段 |
|---|---|---:|---|---|
| `agent-workflow-20260720T012357Z` | 关 | 0 | 全部`NO_RAG` | Context Manager总开关 |
| `agent-workflow-context-enabled-20260720T013326Z` | 开 | 0 | `injectedTokens:0`，`NO_RAG` | RAG Token预算门禁 |
| `agent-workflow-fixed-20260720T014714Z` | 开 | 8192 | 可回答用例均`VALID` | 无该失效 |

失败问题是“星灰灯塔计划的英文发布标识是什么？”，应命中文档即上述[运维手册](../../evaluation-data/agent-workflow-e2e/citation-fixture.md)。因果链为：文档READY且binding已创建 → RAG Token=0 → contributor在检索前返回空 → 模型未见文档 → 回答失败。开启8192 Token后同类用例转为`VALID`。

## 代码缺陷、取消前后差异与未闭环项

1. Agent非流式修复前把`N`、`NOT`、`NOT_IN`等累计事件全部换行拼接，首个失效阶段是Controller聚合，不是检索或模型。改为只合并delta后，Java 17定向3/3通过，最终黑盒响应精确为`NOT_IN_DOCUMENT`。
2. 修复前取消API返回`cancelled`，历史无该run的assistant message，也无citation终态，但SSE在30秒内未结束并得到`ConnectionError`。确定性根因有两个：Controller在把`run`事件暴露给客户端后才注册本机中断器，立即取消可丢信号；即使命中注册表也只`dispose()` Rx订阅，没有显式完成`SseEmitter`。
3. 修复后Controller在发送`run`事件前注册幂等中断器，取消时同时释放订阅并完成SSE；Workflow流每250ms观察数据库取消状态，覆盖跨实例取消和本机信号竞态，连接断开也反向取消run。Java 17定向回归29/29通过，其中新增测试证明“取消先于真实Disposable挂载”仍会立即释放晚到订阅，以及数据库取消能完成下游并中断coordinator。
4. 真实取消回归中，取消API为HTTP 200/2883ms并落`cancelled`，整个SSE请求4484ms内结束（旧基线30秒仍未结束），事件只有`session`和`run`；历史0条，无assistant message，无citation终态。服务端在10:11:01.116收到流请求、10:11:05.174记录取消，此后至10:12:07停机没有该运行的模型响应或工具事件。由于该次取消发生在模型请求实际发出前，这只证明“取消后没有再发起模型调用”，不证明已经发往远端供应商的在途请求能够撤销或停止计费；并行DAG子节点的强制传播仍需专门故障桩验证。
5. Agent单次墙钟22.468～43.852秒，Workflow为7.114～10.893秒。Agent观测上仍慢约2～6倍，但本轮没有拆分工具决策、工具HTTP、模型TTFT/生成时间；“现有Agent的百度检索和Skill是唯一根因”只是待验证假设。

优先级是：先为并行DAG和已发出的模型/工具HTTP增加可观察取消句柄并用可控慢服务证明真实撤销；再增加Agent各阶段时间戳并用每组至少30次报p50/p95；最后在启动时联合检查Context总开关、模型窗口和RAG预算，避免静默`NO_RAG`。
