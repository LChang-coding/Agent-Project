# 应用资源与 MinIO 连接优化计划

## 优化前证据

- 通用线程池 core/max/queue 为 20/50/5000，keep-alive 配置 5000 且以秒为单位。
- Hikari 参数写在 `spring.hikari`，Spring Boot 标准层级应为 `spring.datasource.hikari`；目标值 min/max 为 15/25，对当前低负载与紧张服务器偏大。
- Logback 每 10 秒扫描配置，INFO 异步队列 8192，观测日志同步写盘，三类日志理论总上限 17 GB，WARN/ERROR 同时进入 INFO 文件与错误文件。
- 启动观测样例默认开启，每次启动会制造非业务日志。
- `MinioObjectStorageService` 每次操作创建 `MinioClient`，上传前每次调用 `bucketExists`。

## 执行计划（执行前落盘）

1. 将应用线程池改为可通过环境变量覆盖的低资源默认值，收敛核心/最大线程、队列和 keep-alive，保留 CallerRuns 背压。
2. 将 Hikari 配置移到正确的 `spring.datasource.hikari` 层级，设置小型默认池、更短的等待/验证超时，移除额外 `SELECT 1`。
3. 关闭 Logback 热扫描，将 INFO 与 WARN+ 分流，为观测文件增加小型异步队列，按服务器磁盘预算收紧大小/保留上限。
4. 将观测样例改为默认关闭。
5. `MinioObjectStorageService` 在配置维度复用 Client，对已确认存在的 bucket 做线程安全成功缓存；创建失败不污染缓存。
6. 补充/调整 MinIO 单测以证明 Client 复用和桶检查只发生一次；执行配置加载、对象存储及关联模块测试。
7. 将实际改动、指标差值、测试和回滚方式追加到本文档，通过后做中文提交。

## 验收条件

- 默认通用线程池和数据库池显著小于优化前，且可通过环境变量扩容。
- Logback 不定期轮询配置，WARN/ERROR 不在 INFO 文件重复保留，观测写盘不阻塞业务调用方。
- 同一配置的 MinIO 操作复用 Client，同一已存在 bucket 不在每次上传前重复远程检查。
- 相关 Java 测试通过，不改变对象存储接口、资产去重和异常语义。

## 执行实录

### 应用资源默认值

- dev/prod/test 三个 profile 的通用线程池都改为环境变量可覆盖，默认 core/max/queue 从 20/50/5000 收敛到 4/8/256；keep-alive 从 5000 秒修正为 60 秒。
- 新增 `allow-core-thread-timeout=true`，使低流量时通用池核心线程也可在 60 秒后回收；保留 `CallerRunsPolicy` 作为有界队列满载时的背压。
- `ThreadPoolConfigProperties` 的 Java 默认值与 YAML 保持一致，避免绑定缺失时回退到 max=200。
- Hikari 从无法按 Spring Boot 标准绑定的 `spring.hikari` 移到 `spring.datasource.hikari`；默认 min/max 为 2/10，连接等待/验证超时为 10s/3s，并移除显式 `SELECT 1`，使用 JDBC4 验证。
- 开发启动观测样例改为显式开关，默认不再生成样例模型/DB/Redis/RAG/OSS/调度日志。

### 日志资源

- 关闭 Logback 10 秒配置扫描；INFO 文件增加精确 INFO 过滤，WARN/ERROR 只保留在错误文件，消除重复写盘。
- INFO 异步队列从 8192 降到 1024，WARN/ERROR 队列从 1024 降到 256；观测日志新增 512 的非阻塞异步队列。
- 日志理论总大小上限从 17 GB 收敛到 2 GB：INFO 1 GB，WARN/ERROR 512 MB，观测 512 MB；单文件分别降为 50/50/25 MB。

### MinIO 连接与桶检查

- `MinioObjectStorageService` 改为惰性、volatile + 双重检查的单 Client 复用；local 模式不创建 Client，MinIO 配置错误仍在首次 MinIO 操作时失败。
- 对已确认存在或创建成功的 bucket 做并发安全成功缓存；常规已就绪路径使用无锁 `contains` 快速返回，首次检查/创建才进入临界区。
- 只有 `bucketExists/makeBucket` 全部成功后才写缓存；失败后下一请求仍会重试，不会被错误的就绪状态污染。
- MinIO 测试保留本地路径越界拒绝，新增 8 路并发上传、建桶成功缓存和首次失败后重试三个场景。

### 验证结果

- Java 17 联合定向测试：`MinioObjectStorageServiceTest`、`ThreadPoolConfigTest`、`AssetServiceTest`、`MyBatisMapperLoadTest` 共 9 项，0 失败、0 错误。
- `MinioObjectStorageServiceTest` 中 8 路并发上传验证 Client factory 1 次、`bucketExists` 1 次、`putObject` 8 次。
- Maven 关联模块 `compile` 通过；`xmllint --noout logback-spring.xml` 通过；变更范围 `git diff --check` 通过。
- 本闭环仅修改本地代码/配置与测试，未上传项目，未改动或重启远程中间件。

### 回滚方式

- 线程与连接池不需回滚代码即可通过环境变量扩容；如需回到旧默认，分别将 APP executor 设为 20/50/5000/5000s，MySQL pool 设为旧期望 15/25。
- MinIO 缓存为进程内状态，应用重启即清空；不写数据库或 MinIO 元数据，无数据迁移回滚要求。
