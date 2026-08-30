# LCodeWorkflowAgent

## 1. 总体架构

LCodeWorkflowAgent 采用模块化单体承载业务与 Agent 运行时，并通过统一工具网关、可恢复任务账本和事件链路连接 MCP、Skill、RAG 及多 Agent 编排能力。

<p align="center">
  <a href="docs/readme-assets/architecture/lcode-workflow-agent-architecture-overview.pdf">
    <img src="docs/readme-assets/architecture/lcode-workflow-agent-architecture-overview.webp" width="960" alt="LCodeWorkflowAgent 总体架构图">
  </a>
</p>

<p align="center"><sub>README 加载压缩预览图；点击图片可查看完整矢量 PDF。</sub></p>

架构图覆盖以下核心领域：

- **权限与能力治理**：租户、用户和管理员权限边界，以及 MCP、Skill、知识库的发布、授权与审批。
- **Agent 与工作流运行时**：单 Agent、DAG、受控智能路由和 Supervisor 多 Agent 委派/回调。
- **工具与 RAG**：动态工具感知、Tool Gateway、文档解析、父子切块、混合检索、重排及引用证据链。
- **上下文与可靠执行**：短期记忆、长期摘要、幂等账本、租约心跳、指数退避、CAS 状态推进及故障恢复。
- **数据与基础设施**：MySQL、Redis、Kafka、Qdrant、MinIO、Nacos、Docling、TEI、Grafana/Loki 和 XXL-JOB。

## 2. 核心链路

### 2.1 工作流发布与可执行工作流装配

![工作流发布与可执行工作流装配](docs/readme-assets/flows/01-工作流发布与可执行工作流装配.png)

### 2.2 智能工作流启动与 Agent 执行

![智能工作流启动与 Agent 执行](docs/readme-assets/flows/02-智能工作流启动与Agent执行.png)

### 2.3 Agent 提出路径、服务端确定路径并执行下一个 Agent

![Agent 提出路径、服务端确定路径并执行下一个 Agent](docs/readme-assets/flows/03-Agent提出路径服务端确定路径并执行下一个Agent.png)

### 2.4 Agent 每轮动态发现可用工具

![Agent 每轮动态发现可用工具](docs/readme-assets/flows/04-Agent每轮动态发现可用工具.png)

### 2.5 统一 Tool Gateway 调用

![统一 Tool Gateway 调用](docs/readme-assets/flows/05-统一ToolGateway调用.png)

### 2.6 Agent 主动 RAG、多次检索、预算与证据记录

![Agent 主动 RAG、多次检索、预算与证据记录](docs/readme-assets/flows/06-Agent主动RAG多次检索预算和证据记录.png)

### 2.7 Dense、Sparse、RRF、Rerank 与上下文补全

![Dense、Sparse、RRF、Rerank 与上下文补全](docs/readme-assets/flows/07-DenseSparseRRF-Rerank与上下文补全.png)

### 2.8 Redis 保存近期对话并组装模型上下文

![Redis 保存近期对话并组装模型上下文](docs/readme-assets/flows/08-Redis保存近期对话并组装模型上下文.png)

### 2.9 将较早对话整理成长期摘要

![将较早对话整理成长期摘要](docs/readme-assets/flows/09-将较早对话整理成长期摘要.png)

### 2.10 用同一个 Trace ID 串起同步请求与后台处理日志

![用同一个 Trace ID 串起同步请求与后台处理日志](docs/readme-assets/flows/10-用同一个TraceID串起同步请求与后台处理日志.png)

### 2.11 Cobra CLI 查询 Grafana/Loki 并交给 Agent 分析

![Cobra CLI 查询 Grafana Loki 并交给 Agent 分析](docs/readme-assets/flows/11-CobraCLI查询GrafanaLoki并交给Agent分析.png)

### 2.12 将 PDF、DOCX 和 Markdown 转成统一文档结构

![将 PDF、DOCX 和 Markdown 转成统一文档结构](docs/readme-assets/flows/12-将PDF-DOCX和Markdown转成统一文档结构.png)

### 2.13 从统一文档结构生成父子分块并启用新索引版本

![从统一文档结构生成父子分块并启用新索引版本](docs/readme-assets/flows/13-从统一文档结构生成父子分块并启用新索引版本.png)

### 2.14 用固定问题集比较检索策略并计算指标

![用固定问题集比较检索策略并计算指标](docs/readme-assets/flows/14-用固定问题集比较检索策略并计算指标.png)

### 2.15 Document IR 具体生成父块和子块

![Document IR 具体生成父块和子块](docs/readme-assets/flows/15-DocumentIR具体生成父块和子块.png)

### 2.16 Dense、Sparse、Hybrid RRF 与 Rerank 检索

![Dense、Sparse、Hybrid RRF 与 Rerank 检索](docs/readme-assets/flows/16-DenseSparseHybridRRF与Rerank检索.png)

### 2.17 命中子块后的上下文补全与证据装配

![命中子块后的上下文补全与证据装配](docs/readme-assets/flows/17-命中子块后的上下文补全与证据装配.png)

### 2.18 工具权限配置、运行时判断与审批触发

![工具权限配置、运行时判断与审批触发](docs/readme-assets/flows/18-工具权限配置运行时判断与审批触发.png)

### 2.19 SSE + HTTP 人机审批、同步等待与断线恢复

![SSE + HTTP 人机审批、同步等待与断线恢复](docs/readme-assets/flows/19-SSE-HTTP人机审批同步等待与断线恢复.png)

### 2.20 主 Agent 创建多个子 Agent 与 Kafka 可靠投递

![主 Agent 创建多个子 Agent 与 Kafka 可靠投递](docs/readme-assets/flows/20-主Agent创建多个子Agent与Kafka可靠投递.png)

### 2.21 子 Agent 执行、心跳租约与 Worker 故障接管

![子 Agent 执行、心跳租约与 Worker 故障接管](docs/readme-assets/flows/21-子Agent执行心跳租约与Worker故障接管.png)

### 2.22 子 Agent 结果回调、主 Agent 自动续跑与实例清理

![子 Agent 结果回调、主 Agent 自动续跑与实例清理](docs/readme-assets/flows/22-子Agent结果回调主Agent自动续跑与实例清理.png)

## 3. 数据库领域设计

### 3.1 数据库领域总览

![数据库领域总览](docs/readme-assets/database/01-数据库领域总览.png)

### 3.2 租户、用户与 Agent 工具配置

![租户、用户与 Agent 工具配置](docs/readme-assets/database/02-租户用户与Agent工具配置.png)

### 3.3 会话、运行、记忆、资产与用量

![会话、运行、记忆、资产与用量](docs/readme-assets/database/03-会话运行记忆资产与用量.png)

### 3.4 RAG 知识库、摄取、检索与评测

![RAG 知识库、摄取、检索与评测](docs/readme-assets/database/04-RAG知识库摄取检索与评测.png)

### 3.5 工作流定义、运行、路由与事件

![工作流定义、运行、路由与事件](docs/readme-assets/database/05-工作流定义运行路由与事件.png)

### 3.6 定时调度、执行与故障恢复

![定时调度、执行与故障恢复](docs/readme-assets/database/06-定时调度执行与故障恢复.png)

### 3.7 工具权限、审批与调用审计

![工具权限、审批与调用审计](docs/readme-assets/database/07-工具权限审批与调用审计.png)

### 3.8 多 Agent 编排、回调与清理

![多 Agent 编排、回调与清理](docs/readme-assets/database/08-多Agent编排回调与清理.png)

## 4. 测试与评测结果

### 4.1 PDF / DOCX 200 份全策略检索质量

每种格式包含 200 份同源文档和 800 条正式查询结果，两个正式运行均为 `0 错误 / 0 降级 / 0 空结果`。

![PDF DOCX 200 份全策略检索质量](docs/readme-assets/evaluation/pdf-docx-200-retrieval-quality.png)

| 格式 | 策略 | Recall@1 | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | mean / p95 |
|---|---|---:|---:|---:|---:|---:|---:|
| PDF | Dense | .825 | .935 | .960 | .877109 | .897501 | 2,462 / 3,920 ms |
| PDF | Sparse | .610 | .785 | .820 | .686562 | .719277 | 2,163 / 3,577 ms |
| PDF | Hybrid RRF | .775 | .885 | .925 | .827032 | .850781 | 2,668 / 4,361 ms |
| PDF | Hybrid RRF + Rerank | .825 | .920 | .925 | .862222 | .877817 | 11,075 / 19,442 ms |
| DOCX | Dense | .835 | .950 | .960 | .885429 | .904045 | 1,764 / 2,175 ms |
| DOCX | Sparse | .630 | .805 | .830 | .700964 | .732808 | 1,508 / 1,739 ms |
| DOCX | Hybrid RRF | .800 | .900 | .920 | .843865 | .862536 | 1,846 / 2,146 ms |
| DOCX | Hybrid RRF + Rerank | .840 | .915 | .920 | .868542 | .881311 | 6,956 / 9,796 ms |

### 4.2 SciFact Markdown 检索质量与时延

正式运行包含 300 个问题和 4 种检索策略，共得到 1,200 条唯一结果，最终为 `0 错误 / 0 降级 / 0 空结果`。

![SciFact Markdown 检索质量与时延](docs/readme-assets/evaluation/scifact-quality-latency.png)

| 策略 | Recall@1 | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | mean / p95 |
|---|---:|---:|---:|---:|---:|---:|
| Sparse | .243889 | .383611 | .487778 | .321922 | .355442 | 914 / 2,380 ms |
| Dense | .552611 | .728500 | .797944 | .655835 | .683385 | 1,694 / 4,016 ms |
| Hybrid RRF | .448611 | .669833 | .750667 | .566630 | .604539 | 2,159 / 4,704 ms |
| Hybrid RRF + Rerank | .556111 | .714000 | .750667 | .646028 | .663244 | 13,451 / 21,100 ms |

### 4.3 PDF / DOCX 50 份切块策略消融

四个正式运行共完成 200 次文档摄取和 800 条查询结果，均为 `0 最终错误 / 0 降级 / 0 空结果`，瞬态重试实际发生 0 次。

| 格式 / 策略 | 子块数 | 摄取 mean / p95 | Dense R@1/5/10 | Sparse R@1/5/10 | Hybrid R@1/5/10 | Rerank R@1/5/10 |
|---|---:|---:|---:|---:|---:|---:|
| PDF / 保留结构化切块 | 137 | 26,912 / 46,209 ms | .90 / 1 / 1 | .78 / .86 / .88 | .86 / .94 / .98 | .82 / .96 / .98 |
| DOCX / 保留结构化切块 | 167 | 15,928 / 28,205 ms | .90 / 1 / 1 | .78 / .84 / .90 | .86 / .96 / .98 | .86 / .96 / .98 |
| PDF / 关闭结构化切块 | 81 | 21,229 / 33,221 ms | .96 / 1 / 1 | .80 / .88 / .88 | .86 / .94 / 1 | .94 / 1 / 1 |
| DOCX / 关闭结构化切块 | 98 | 13,493 / 22,394 ms | .90 / 1 / 1 | .78 / .88 / .90 | .84 / .94 / .96 | .92 / .96 / .96 |

该结果只说明单页短科学摘要采用较粗分块时更省，并不能外推到多页长文、跨页表格或扫描 PDF。

### 4.4 功能、可靠性与容量闭环

![RAG 功能、可靠性与容量闭环](docs/readme-assets/evaluation/rag-functional-reliability-closure.png)

| 测试范围 | 正式结果 |
|---|---|
| Markdown / DOCX / PDF 三格式摄取与检索 | 15 / 15 个证据问题通过 |
| PDF 页码引用 | 30 条查询引用全部落在金标页 |
| Agent / Workflow | 可回答、无答案拒答、伪造引用拦截、SSE、取消和跨租户隔离全部通过 |
| 知识库删除 | MySQL、Qdrant、MinIO 等 13 项残留检查全部通过 |
| MinIO 断连恢复 | 故障恢复后达到相同残留门禁 |
| 并发 1 / 2 稳定负载 | 共 320 个正式样本，0 错误、0 降级、0 空结果 |
| 并发 4 容量边界 | 第 39 个正式样本出现 67.19 秒 fallback，按门禁判定失败 |

| 并发 | 吞吐 QPS | Dense mean / p95 | Sparse mean / p95 | Hybrid mean / p95 | Rerank mean / p95 |
|---:|---:|---:|---:|---:|---:|
| 1 | .2379 | 695 / 1,788 ms | 255 / 582 ms | 1,130 / 2,207 ms | 13,866 / 24,493 ms |
| 2 | .3442 | 630 / 1,526 ms | 299 / 570 ms | 986 / 2,006 ms | 20,691 / 28,701 ms |
