# 智能工作流中文路由键、显式别名与模板修复计划

## 背景与真实故障

- 故障 Trace ID：`ac0bc6cf-0e7a-459c-9ea4-696b10b43a04`。
- 客服意图路由模板的边使用英文 `routeKey=billing/technical`，分类节点实际输出 `[route:账务]`。
- 当前路由标记正则仅接受 ASCII，因此中文标记无法形成有效建议；随后 `DEFAULT` 路由把运行送入 `manual`，最终产生错误的人工升级摘要。
- 用户明确要求保留最终答案中的 `[route:*]` 控制标记，不做展示层清理。

## 目标

1. `routeKey` 原生支持中文及其他安全 Unicode 文本。
2. 每条 `NODE_SUGGESTION` / `AI_ROUTER` 边支持显式 `routeAliases`。
3. 别名只能匹配独立 `[route:键]` 标记，不对普通自然语言做关键词、包含或语义猜测。
4. 同一来源节点的主键和别名经过统一标准化后必须全局唯一，歧义配置在编译/发布阶段失败。
5. 节点提示词自动列出可用键、含义、目标节点和精确输出格式。
6. 修复全部 24 个模板中的智能路由提示与客服模板中文键；修复已发布客服工作流时创建并发布新版本，不篡改历史 v1。

## 路由协议设计

### 数据结构

- 在 Graph DTO、领域 Graph、冻结 DAG Plan 和前端 `WorkflowEdge` 增加 `routeAliases: string[]`。
- Graph 仍存放在 `agent_workflow_version.graph_json`，不新增业务表字段。
- 主 `routeKey` 是展示和提示词中的首选键；别名仅用于兼容旧调用方、历史英文键或业务同义键。

### 标准化与匹配

1. 只解析正文末尾独立行 `[route:键]`。
2. 键执行 Unicode NFKC、trim、英文小写化；不分词、不做 contains、不调用模型猜别名。
3. 先比较边的主键，再比较该边的别名；两者都是精确等值匹配。
4. 中文键可直接作为主键，例如 `routeKey=账务`，别名可配置 `billing`、`invoice`。
5. 同一来源节点下的所有标准化主键/别名若重复，编译器拒绝发布，避免同一个标记命中多条边。

### 自动提示词

- 编译或运行时根据当前节点的出边生成受控路由说明，不依赖用户手写完整协议。
- 示例：`账务 -> 账务处理；精确输出：[route:账务]；兼容别名：billing`。
- `DEFAULT` 只说明未命中时的兜底目标，不要求模型输出 DEFAULT 标记。
- 用户填写的 `routeInstruction` 作为业务判断补充，系统生成的键清单作为不可省略的协议段。

## 实施阶段

### 阶段 1：领域协议与编译门禁

1. 扩展 DTO、Graph、Plan 和映射字段。
2. 提供单一 `RouteKeyNormalizer`，供标记解析、路由匹配和编译冲突校验共用。
3. 中文安全边界：禁止空键、控制字符、换行和方括号，限制标准化后长度；允许中英文、数字及常用业务符号。
4. 增加主键/别名重复、Unicode 等价冲突和非法字符测试。

### 阶段 2：运行时确定性路由与提示词

1. 放宽 route marker 解析到安全 Unicode 键。
2. `NODE_SUGGESTION` 和 `AI_ROUTER` 精确匹配主键或显式别名。
3. 根据冻结 Plan 的当前出边生成精确键清单并注入节点提示词。
4. 保留节点和最终答案中的 `[route:*]` 原文。

### 阶段 3：前端编辑与模板修复

1. 边编辑器提供中文路由键和逗号/换行分隔别名输入，并显示冲突提示。
2. 模板工厂自动生成节点路由说明，避免模板作者遗漏键清单。
3. 客服模板改为主键 `账务`、`技术`，兼容别名 `billing`、`technical`。
4. 审计其余智能模板，保证说明、主键、别名和测试输入一致。

### 阶段 4：测试、真实版本修复与提交

1. 后端测试：中文 marker、英文别名、NFKC、冲突拒绝、未显式标记不路由、DEFAULT 兜底。
2. 前端测试：24 模板合法、自动提示完整、中文键与别名序列化、深拷贝不污染。
3. 构建并启动前后端，用“发票怎么开”验证 `classify -> billing -> END`，记录 Trace、事件和最终答案。
4. 为 `wf_309d4544-c8fc-4931-ac16-ff3fcad83315` 创建并发布新版本；历史 v1 和历史 Run 保持不变。
5. 追加真实执行详情，审计差异，只提交本任务文件，中文本地提交。

## 禁止事项

- 不删除或隐藏最终答案中的 `[route:*]`。
- 不从自然语言正文推断路由，不做模糊包含、Embedding 或额外 LLM 分类作为别名匹配。
- 不直接修改已发布 v1 的 `graph_json`，不破坏历史可重放性。
- 不提交日志、对象存储、RAG 数据或现有无关工作树改动。

## 执行记录

### 2026-08-05 领域协议与前端模板

- DTO、领域 Graph、冻结 DAG Plan、Controller 映射和前端 `WorkflowEdge` 已增加 `routeAliases`，字段随版本化 `graph_json` 保存，不新增表字段。
- 新增 `WorkflowRouteKey`：统一执行 Unicode NFKC、trim、英文小写化、安全字符和 64 code point 长度校验。
- marker 只读取回答最后一个非空行上的独立 `[route:键]`；正文中的标记、标记后的正文以及包含式自然语言都不参与裁决。
- `NODE_SUGGESTION` 与 `AI_ROUTER` 只精确匹配主键或显式别名；编译器会拒绝同一来源节点中标准化后冲突的主键/别名。
- 运行时依据冻结 Plan 的真实出边自动注入“主键、目标节点、精确输出格式、兼容别名、DEFAULT 目标”，用户填写的 `routeInstruction` 仅作为业务判断补充。
- 智能模板工厂改为从边自动生成路由提示；客服模板主键改为 `账务/技术`，受控别名为 `billing/technical`。边编辑器支持中文主键及逗号、中文逗号或换行分隔的别名。
- 按用户要求，节点输出和最终答案中的 `[route:*]` 均未删除或隐藏。

### 2026-08-05 自动化验证

- 后端编译：`mvn -pl ai-agent-scaffold-app -am -DskipTests compile`，成功。
- 后端定向测试：`mvn -pl ai-agent-scaffold-app -am -Dtest=IntelligentWorkflowRouterTest,WorkflowRouteKeyTest,WorkflowDagCompilerTest -Dsurefire.failIfNoSpecifiedTests=false test`，13 tests、0 failure、0 error。
- 前端单元测试：`npm run test:unit`，12 tests、0 failure。
- 前端生产构建：`npm run build`，成功。
- 首次本地端到端运行使用了 Maven 本地仓库中的旧 domain JAR，Trace `759b4ecf-5116-4ca8-8d5b-04af4d5c14ef` 仍错误走向 DEFAULT。完成 `mvn -DskipTests install` 并重启后端后消除该测试环境问题；这次失败没有被当作新逻辑成功数据。
- 新代码真实端到端：工作流 `wf_ed6430e8-0cb0-42ae-9801-de5b0ab17054` v1，输入“发票怎么开”，Trace `c7fac0ca-a5a2-4758-bf0f-3d0122652e9b`，Run `run_6a7c880e-3777-4e59-890f-9a42769ae7ab`。执行顺序为 `classify -> billing -> END`，节点事件明确记录 `strategy=AI_ROUTER`、`sourceNodeId=classify`、`targetNodeId=billing`；页面显示 2 次节点执行且最终答案保留 `[route:账务]`。
- 运行时同时观察到 Java 25 下 Kafka JAAS 认证兼容警告及一次 RAG Worker MySQL 读取超时；两者未参与本次工作流链路，也未影响上述端到端结果，本任务未改动这些无关模块。

### 2026-08-05 已发布客服流程修复

- 原工作流 `wf_309d4544-c8fc-4931-ac16-ff3fcad83315` 的 v1 保持不可变，修复前后 SHA-256 均为 `5b9754abd9464008b310991285c0d48524078a731f879d85e7ae17c5e4222edd`。
- 在事务中从 v1 创建并发布 v2；主路由为 `账务 -> billing`、`技术 -> technical`，受控别名分别为 `billing`、`technical`，DEFAULT 仍指向 `manual`。
- 主表 `current_version`、`published_version` 均推进为 2，状态为 `published`；v2 Graph SHA-256 为 `cf2f0d92703da268f630772e7d6f7a0646425fdd191782526028de887d9d019f`。
- 数据库事务结果为 `inserted_version=1`、`updated_workflow=1`；随后独立回读确认 v1/v2 均为 published、v1 未被覆盖、v2 键和别名与设计一致。

### 留痕边界

- 未提交或改写现有日志、对象存储、RAG 评测数据、设计目录及 `RunControlService` 的用户既有改动。
- 未上传本地项目，也未改动与本次路由修复无关的服务器或中间件配置。
