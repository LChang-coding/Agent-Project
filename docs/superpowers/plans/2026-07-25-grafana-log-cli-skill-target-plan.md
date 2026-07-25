# Grafana 远程日志 CLI 与 Codex Skill 闭环计划

## 目标

在当前项目内新增独立 Go 工具包 `tools/grafana-log-cli`，通过 Grafana Loki datasource proxy 从本机只读查询远端日志，并将可执行 CLI 封装进标准 Codex Skill。Agent 应能按 `traceId/runId/sessionId/retrievalId/taskId` 获取、排序、解释完整中文链路，识别失败、降级、缺失阶段和主要耗时节点。

## 硬门禁

1. 所有真实 Grafana 地址、账号、密码、Token、SSH 信息和其他敏感配置只能写入本机 `codex.md`；源码、测试、Skill、示例、计划、构建产物元数据、日志和 Git 提交不得复制真实凭据。
2. `codex.md` 必须保持未被 Git 跟踪；CLI 只读取带明确边界和版本号的结构化配置块，不扫描或输出文档其余敏感内容。
3. 默认通过 Grafana `/api/datasources/proxy/uid/{uid}/loki/api/v1/query_range` 查询，不让 Agent 绕过 Grafana直接访问远端 Loki；所有操作只读。
4. `traceId` 保持 LogQL 查询字段而非 Loki 高基数标签。用户输入必须安全转义；原始 LogQL 仅在显式 `query` 命令下启用。
5. stdout 只输出命令结果，诊断信息写 stderr；密码、Authorization、Cookie、JWT、API Key 等字段在任何输出格式中都必须脱敏。
6. HTTP 客户端必须设置连接/总超时、响应体上限、分页与最大行数，明确处理 401、403、404、429、5xx、非 JSON 和 Loki 业务错误；仅对安全的瞬态只读请求进行有界重试。
7. Skill 使用 `skill-creator` 标准结构、`agents/openai.yaml` 和 `quick_validate.py`；Skill 内必须包含可执行入口，离开源码目录后仍可调用。
8. 任何重大闭环先追加本计划的真实执行详情，再使用中文本地提交；既有运行日志、对象存储、Alloy 数据和无关未跟踪文件不进入提交。

## 实施步骤

### 1. 契约与安全配置

- 审计现有 Grafana/Loki 部署、datasource UID、日志 label 和 logfmt 字段。
- 在 `codex.md` 新增 `grafana-log-cli` 结构化敏感配置块，记录配置版本、Grafana URL、datasource UID、认证方式、用户名/密码、默认 selector、时区、超时和 TLS 策略。
- 定义安全配置优先级：显式参数中的非敏感项 > 环境变量中的非敏感项 > `codex.md`；认证信息默认只来自 `codex.md`。

### 2. 独立 Go CLI

- 初始化 Go module，采用 Cobra 管理命令，Viper只承载非敏感覆盖；核心 HTTP、JSON、时间、脱敏与输出使用标准库。
- 实现 `doctor`、`trace`、`search`、`run`、`session`、`retrieval`、`ingest` 和显式 `query`。
- 支持 `timeline/table/json/jsonl/raw`，时间范围、方向、limit、分页、level/event/stage/errorCode/tenantId过滤。
- 自动解析 Loki matrix/streams 响应、合并多日志流、去重、稳定排序并解析 logfmt。

### 3. 链路诊断

- `trace` 默认按升序重建链路，必要时按有界窗口逐级扩大。
- 汇总链路起止、总耗时、最慢阶段、失败/降级/取消、候选漏斗和关键业务ID。
- 识别阶段开始但无完成、摄取/检索/Run终态缺失等不完整链路；未知情况明确标记，不编造原因。

### 4. 测试与构建

- 使用 `httptest` 覆盖认证、proxy路径、LogQL转义、分页、重试、响应上限、错误分类、脱敏、多流排序和所有输出格式。
- 建立确定性 fixture 覆盖完整RAG链路、失败链路、缺失阶段和敏感字段。
- 执行 `go test -race ./...`、`go vet ./...`、构建当前平台，并为 darwin/linux amd64/arm64生成可复现二进制和 SHA-256 manifest。

### 5. Skill 封装

- 使用 `skill-creator/scripts/init_skill.py` 初始化 `grafana-log-inspector`。
- 编写精简 `SKILL.md`：先 doctor，再按业务键查询，优先 JSONL 给 Agent、timeline 给人类，禁止泄露凭据或无界查询。
- 增加字段契约和故障处理 references；Skill 内携带平台选择入口与打包二进制。
- 执行 `quick_validate.py`，并在隔离目录验证 Skill 不依赖源码相对路径。

### 6. 真实只读验收与收尾

- 对远端 Grafana执行 health/datasource 探测。
- 选择现有真实 `traceId` 验证 timeline、JSONL、阶段诊断与直接 Grafana 查询结果一致。
- 验证错误凭据、无结果、扩大窗口、limit和脱敏门禁。
- 把真实命令、结果、限制、二进制hash和Skill验证结果追加到本计划；精确暂存并中文本地提交。

## 不在本轮范围

- 不修改或部署远端 Grafana、Loki、Alloy。
- 不上传本地 Java/Vue 项目。
- 不提供日志删除、看板修改、datasource修改或其他写操作。
- 不把 `traceId`、租户或用户ID改成 Loki 静态标签。

## 执行记录

### 2026-07-25 配置与架构闭环

- 核验远端 Grafana 为 `11.0.0`，Loki datasource 的 UID 为 `loki`、类型为
  `loki`、访问方式为 `proxy`；现有日志 selector 保持
  `{job="ai-agent-scaffold"}`，`traceId` 等业务键继续作为 logfmt 字段查询。
- 在本机 `codex.md` 增加唯一的 `grafana-log-cli-config-v1` JSON 配置块，收纳
  地址、认证、datasource、selector、时区、超时和 TLS 策略。已用
  `git check-ignore -v codex.md` 确认由 `.git/info/exclude` 排除，并用
  `git ls-files --error-unmatch codex.md` 确认它未被跟踪。
- 发现 `codex.md` 原权限为 `0644`，已收紧为仅本机用户可读写的 `0600`。
- CLI 只解析上述有界块且启用 JSON 未知字段拒绝。认证值不能由命令行或环境
  变量覆盖；仅 URL、datasource UID、selector 和配置文件路径允许非敏感覆盖。
- 所有远端请求固定为 Grafana GET API；Loki 查询经
  `/api/datasources/proxy/uid/{uid}/loki/api/v1/query_range` 转发。HTTP 客户端
  已设置 TLS 下限、连接/响应/总超时、16 MiB 响应上限、三次瞬态重试、分页
  上限、去重和分页停滞保护。

### 2026-07-25 CLI 与诊断闭环

- 新增独立 Go module `tools/grafana-log-cli`，实现 `doctor`、`trace`、`run`、
  `session`、`retrieval`、`ingest`、`search`、显式 `query` 命令。
- 实现 `timeline`、`table`、`json`、`jsonl`、`raw` 五种输出；`jsonl` 固定为
  meta、逐条 entry、analysis，适合 Agent 稳定消费。
- 实现多流合并、UTC 稳定排序、边界去重、logfmt 解析和密码、Authorization、
  Cookie、API Key、Token、JWT 等常见凭据脱敏。
- analysis 只基于日志证据汇总失败、降级、取消、最慢阶段、未闭合阶段、候选
  漏斗和业务终态。真实验收时发现现有 `rag_retrieve`、`model_call` 完成事件
  不使用 `_completed` 后缀，已按当前日志契约补充闭合映射及回归测试，未改动
  服务端日志架构。
- 无结果验收发现初版 `expanded` 只在扩大后命中时置真，已改为只要实际执行
  扩大窗口即置真，避免把已经查询 72 小时的结果误报成未扩大。

### 2026-07-25 自动化与真实验收

- `go test ./...`：通过。
- `go test -race ./...`：通过，覆盖 CLI 时间窗、配置块隔离、非敏感覆盖、
  LogQL 转义、只读 proxy 路径、Basic Auth、HTTP 错误分类、三次重试、分页
  去重/停滞、多流解析、脱敏、诊断语义和五种输出。
- `go vet ./...`：通过。
- `quick_validate.py skill/grafana-log-inspector`：通过。
- 对工具目录执行真实敏感值特征扫描：未发现服务器地址、数据库密码、Grafana
  密码或已知 Token。
- 隔离复制 Skill 后运行 `--version` 返回 `v0.1.0`；运行 `doctor` 返回
  Grafana `11.0.0`、datasource `loki`、类型 `loki`、访问方式 `proxy`。
- 使用一条已有真实 trace（完整 ID 未写入提交）查询最近 24 小时，得到 40
  条日志、0 失败、0 降级、0 取消、0 未闭合阶段。整条 Run 为 `15545ms`，
  RAG retrieval 为 `8179ms`，rerank 为 `5549ms`；候选漏斗从 Dense/Sparse
  各 18 条，经融合 18、rerank 6、引用封装 2，均来自真实日志，未推造数据。
- 使用不存在的 trace 查询，从 30 分钟自动扩大到 72 小时，结果为 0 条且
  `expanded=true`；使用 `limit=5` 的真实查询输出 5 条 entry，加 meta 和
  analysis 共 7 行 JSONL，边界生效。
- 认证失败、错误响应、非 JSON、错误 result type、非法时间戳、数据源类型
  错误和分页停滞由 `httptest` 验证；未使用错误真实凭据冲击生产环境。

### 2026-07-25 发布与 Skill 封装

- 使用 `skill-creator/init_skill.py` 初始化标准 Skill，完成 `SKILL.md`、
  `agents/openai.yaml`、平台启动器、日志字段和故障处理 references。
- `scripts/build-release.sh` 使用 `CGO_ENABLED=0`、`-trimpath`、
  `-buildvcs=false` 和固定版本 ldflag 构建 darwin/linux 的 amd64/arm64。
  `gzip -n` 消除压缩时间戳，Skill 启动器在执行前验证 SHA-256，并以版本和
  资源哈希共同作为缓存键，避免同版本重构后复用旧二进制。
- 从仓库根目录复验时发现初版发布脚本依赖当前工作目录，并会在构建失败前清空
  既有 Skill 资源。已改为脚本主动切换 Go module 根目录、先在临时 assets
  目录完成全部构建和校验、成功后再替换正式目录。随后从仓库根目录连续构建
  两次，`cmp SHA256SUMS` 通过，证明本次四个平台压缩产物可复现。
- Skill 资源 SHA-256：
  - darwin/amd64：
    `75f3625e0facd5787538c0b949fc708cdb7f9ce86d820e096d6b0d46a4b01c37`
  - darwin/arm64：
    `b8a4720b5caeb4f5d351e5f3d12e6b3993a9b007d2b881625de3dd86e66169fa`
  - linux/amd64：
    `a804ea6174d1f04365f6f1b4c25b18b420217960b150f8b7d46107c0523cb770`
  - linux/arm64：
    `f9459406b5ee1eb1ffd36531ecf848d29c8557f4ddd4d29020f68476298cd01b`
- 已把相同 Skill 安装到本机 `~/.codex/skills/grafana-log-inspector`，从安装目录
  再次执行真实 `doctor` 成功。Skill 不携带任何真实地址或认证信息，运行时
  仍只读取目标项目本机 `codex.md`。

### 已知边界

- Loki 没有日志流 opaque cursor。若同一纳秒内的日志超过安全单页能力，CLI
  会返回 `LOKI_PAGINATION_STALLED`，不会静默漏数；此时应缩小时间窗或增加
  更精确的字面过滤。
- `incompleteStages` 表示当前查询窗中观察到开始但没有终态，只是调查线索，
  不能单独证明服务失败。
- 本工具不修改远端 Grafana、Loki、Alloy，不提供任何写操作，也不改变现有
  日志 label 设计。
