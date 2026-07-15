# 综合回归与优化前后对比交付计划

## 执行前状态

- 已完成应用资源/MinIO、Context Insight、压缩等待、运行态快照、工作流并发、会话流式 UI、控制台操作反馈以及服务器中间件资源治理等独立闭环。
- 调度吞吐、附件字节边界和长会话窗口仍在实施；必须收口并提交后才能进入最终回归。
- 受保护的用户日志与资料目录始终保持不暂存、不提交。

## 执行计划（执行前落盘）

1. 汇总所有子计划的改动、测试和量化指标，检查总计划是否存在声称完成但缺少证据或已知限制未记录的项目。
2. 执行 Java 17 clean 定向大回归；再尝试项目全量 unit test，若外部依赖型测试失败，区分源码回归与环境依赖并继续完成可运行测试。
3. 执行前端 `vue-tsc + Vite` 生产构建，统计主 chunk、总 JS/CSS、关键路由 chunk；静态验证长会话窗口、响应式断点、z-index 和写操作反馈。
4. 复采服务器同口径内存、PID、健康、Swap、磁盘、容器限制和日志配置；验证 Kafka KRaft/Topic 与公网端口。
5. 生成 `docs/performance/2026-07-16-optimization-before-after.md`，逐项写明优化前、优化后、收益、测试、风险、回滚和未实施项，不把不可比采样或未完成 E2E 包装为已完成。
6. 回填本计划和总计划，执行 secret/diff/status 检查后中文提交；最终仅保留用户原有脏文件。

## 验收条件

- 最终文档包含服务器、中间件、后端、前端四类量化对比与所有本阶段中文提交。
- Java/前端构建结果可复现，失败项有明确分类；服务器所有运行服务健康且无 OOM/异常重启。
- 明确记录未执行的真实浏览器 E2E、数据盘迁移、历史数据删除等限制与理由。

## 执行实录

### 2026-07-16：Java 17 全量回归与装配修复

- 首次执行 `mvn -pl ai-agent-scaffold-app -am clean test` 暴露两个由本阶段性能改造引入的 Spring 多构造器装配回归：`MinioObjectStorageService` 与 `RunControlService` 的生产构造器没有显式标记，Spring 转而寻找无参构造器。
- 已为两个生产构造器补充 `@Autowired`，保持包级测试构造器不进入生产装配；`MinioObjectStorageServiceTest` 4/4 通过，Java 17 clean `ApiTest` 1/1 通过，证明完整应用上下文可启动并连接当前 Nacos、MySQL、Kafka 后正常关闭。
- 修复后再次执行 Java 17 reactor clean 全量测试：Types 12 项全部通过，App 126 项中 112 项通过、14 项报错、0 failure、0 skip，合计 138 项中 124 项通过。14 项均已分类：9 项是仓库演示测试没有可执行测试方法而被 JUnit4 判为 `Invalid test class`；2 项旧 `ChatServiceTest` 未设置新增的可信租户上下文；3 项 `AiAgentAutoConfigTest` 依赖本地未装配的 `100001/100002` Agent Bean。完整应用上下文测试已通过，因此不把这些测试夹具/外部配置问题归类为源码启动回归，也不隐瞒全量命令最终为 `BUILD FAILURE`。
- Maven 仍报告 `ai-agent-scaffold-api` 的 parent `relativePath` 坐标告警，这是既有构建基线问题，本轮不扩大修改范围。

### 2026-07-16：前端、服务器与最终报告

- 前端最终执行 `npm run build` 通过：`vue-tsc --noEmit` 与 Vite 均成功，1914 modules，857ms；主入口 165.19kB/gzip 63.61kB，Chat route 21.48kB/gzip 7.78kB，dist 总量 352,564 bytes。
- 服务器只读复采确认 available memory 4,220,452,864 bytes、Swap used=0、根盘 40%；七个核心容器均在 memory/PID 上限内、RestartCount=0，Loki/Grafana/MinIO/Nacos 200、XXL-JOB 302。
- 已生成 `docs/performance/2026-07-16-optimization-before-after.md`，覆盖服务器、中间件、后端、前端的前后指标、未达目标、测试分类、回滚路径和后续建议。
- 未执行真实浏览器 E2E：本地没有可用登录态/JWT 且项目没有 Playwright；未迁移 70 GB 数据盘、未删除历史业务数据/镜像/Topic/对象，也没有向服务器上传本地项目。
