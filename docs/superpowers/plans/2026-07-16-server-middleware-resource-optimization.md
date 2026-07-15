# 服务器中间件资源优化与回滚计划

## 优化前核心证据

- 4 vCPU / 7.5 GiB 内存，无 Swap，容器中仅 Kafka 有内存上限。
- Kafka Broker `Xms=Xmx=1GiB`，容器 hard limit 也是 1 GiB；容器内只读 Kafka CLI 已因 heap OOM 退出。
- Nacos 754 MiB/282 PIDs，XXL Admin 439 MiB，XXL MySQL 429 MiB，MinIO 294 MiB，均无容器限制。
- Loki Docker json log 110.4 MB，而 Loki 时序数据仅 31 MB；除 XXL Admin 外的容器未见 json-file 轮转上限。
- 30 GB 根盘承载 Docker/中间件，70 GB ext4 数据盘未挂载。

## 变更分组

### A 组：在线容错

1. 确认根盘可用空间后创建 2 GiB swapfile，权限 600，`mkswap/swapon`，验证后再幂等写入 `/etc/fstab`。
2. 设置低 `vm.swappiness`，Swap 只作突发内存的 OOM 缓冲，不作常态扩容。
3. 变更前保存 `free/swapon/vmstat`，变更后确认服务和容器健康无变化。

### B 组：Kafka 单节点窗口

1. 查明 Kafka compose/.env/systemd 真实启动源，备份并记录 SHA-256，不显示账号密码。
2. 将 Broker 堆收敛为 Xms256m/Xmx512m，保留 1 GiB 容器上限给直接内存、线程栈、page cache 和运维 CLI。
3. 仅重启 Kafka；验证 KRaft/Broker、9094 端口、三个压缩 Topic、容器 OOM/restart 和实际 RSS，再运行有限堆的只读 CLI。
4. 若 Broker 无法在限时内健康，立即恢复备份配置并重启；单节点窗口内上下文压缩事件会短暂不可用。

### C 组：容器上限与日志轮转

1. 先盘点每个 compose 真实源文件和挂载，备份后为 Nacos/Kafka/MinIO/Loki/Grafana/XXL 设置符合当前峰值的 memory/PID 上限。
2. 为每个容器设置 `json-file max-size/max-file`，先处理日志增长最快的 Loki。
3. 逐个重建，每个服务健康后再继续，不同时重建整套中间件。

#### C 组执行前精确矩阵（2026-07-16 复核后落盘）

- 已找到可持久化的 Compose 源：XXL-JOB `/opt/ai-agent-scheduler/xxl-job/docker-compose.yml`、MinIO `/root/middleware/minio/docker-compose.yml`、Nacos `/opt/middleware/nacos/docker-compose.yml`、Loki/Grafana `/root/observability/hz/docker-compose.yml`。
- 按当前实测内存与 PID 755MiB/282、298MiB/10、123MiB/14、119MiB/14、439MiB/58、434MiB/52，拟设置：Nacos `1280m/384`、MinIO `640m/128`、Loki `384m/128`、Grafana `384m/128`、XXL Admin `768m/128`、XXL MySQL `1024m/128`（格式为 memory/PIDs）。
- 上述容器的 Docker `json-file` 统一为 `max-size=20m`、`max-file=3`；XXL Admin 由原 5 个文件收紧为 3 个。本组仅限制未来增长，不删除现有日志。
- 每个源文件在服务器原目录生成带时间戳备份并校验 Compose config；按 Loki → Grafana → MinIO → Nacos → XXL MySQL/Admin 的最小影响窗口逐组重建。任一组健康/端口/OOM/restart 门禁失败即恢复对应备份并停止后续。
- Kafka 仅找到数据、配置与密钥目录，未找到 Compose/systemd/启动脚本创建源。在新建并验证服务器本地可回滚创建源前，不重建 Kafka，不把一次性 `docker update`/容器可写层当作持久化闭环。

#### Kafka 创建源补全计划（执行前落盘）

1. 以当前容器只读 inspect 为唯一运行事实，在服务器原目录 `/root/ai-agent-kafka` 新建 Compose：固定当前镜像 digest 对应版本、`appuser`、原 entrypoint/command、三个原挂载、9094 映射、`KAFKA_OPTS` 和 `unless-stopped`，不读取或复制密钥内容。
2. 将已验证的 `KAFKA_HEAP_OPTS=-Xms256m -Xmx512m`、`mem_limit=1g`、`pids_limit=192`、`json-file 20m*3` 固化到创建源；先用 `docker compose config -q` 和渲染结果核对。
3. 保存当前 inspect 摘要、镜像 ID/digest 和服务端配置 SHA-256 后，仅重建 Kafka；若 9094、KRaft quorum、三个业务 Topic、OOM/restart 任一失败，停止新容器并按 inspect 摘要用原镜像/挂载/参数恢复。
4. 本步骤只在服务器本地生成中间件创建配置，不上传本地项目、源码或构建产物。

### D 组：延后的高风险工作

- 70 GB 数据盘的挂载点、已有数据鉴别、Docker data-root/中间件迁移需单独停机、校验与回滚计划，不与 A/B/C 组混做。
- Loki 历史数据删除、Docker build cache/image 清理均是删除动作，本轮只配置未来轮转，不删除现有数据。

## 验收条件

- 服务器具备 2 GiB 低 swappiness 应急 Swap，所有中间件健康。
- Kafka Broker 堆不超过容器上限的 50%–60%，只读 Topic CLI 可成功，三个业务 Topic 仍存在。
- 已变更容器均有明确资源上限和 Docker 日志上限，无 OOMKilled，无新增异常重启。
- 所有配置有服务器本地备份和回滚路径，本地项目未被上传。

## 执行实录

### A 组：在线容错已完成

- 根盘变更前可用 21,466,157,056 bytes；创建 `/swapfile` 2,147,483,648 bytes，有效 Swap 2,147,479,552 bytes，权限 `600/root:root`。
- Swap 从 0 变为 2 GiB，变更后使用量仍为 0；`vm.swappiness` 从 60 降到 10，作为 OOM 应急缓冲而非常态内存。
- `/etc/fstab` 已幂等增加 swapfile，同时写入 `/etc/sysctl.d/99-ai-agent-swap.conf`。原 fstab 备份为 `/etc/fstab.pre-ai-agent-swap-20260715-132107`。
- 变更后 vmstat 三次 si/so 均为 0，CPU idle 97%/98%/98%；Nacos/MinIO/Grafana/Loki 健康检查为 200，XXL-JOB 为预期登录 302，容器 OOM 状态无变化。

### B 组：Kafka 堆收敛已完成

- 真实启动源为容器内 `/opt/kafka/bin/kafka-server-start.sh`，容器无 Compose label 且无 `KAFKA_HEAP_OPTS` 环境变量。脚本与 `server.properties` 已备份到 `/root/ai-agent-kafka/backups/20260715-132234`，备份目录权限 700。
- 仅将 Broker 默认堆从 `Xms1G/Xmx1G` 收敛为 `Xms256m/Xmx512m`，`server.properties` 未改动；仅执行一次 Kafka 容器 restart，其他服务未重启或重建。
- Kafka 同口径内存从 678 MiB/66.21% 降到稳定 427.6–431.2 MiB/41.76%–42.11%，减少约 247–250 MiB（36.4%–36.9%）；PIDs 从 105 降到 103。
- 9094 在重启后 1 秒内恢复监听，实际 Broker `/proc` 参数确认为 256m/512m；重启后日志 ERROR/FATAL/OOM 均为 0，容器 `OOM=false`。
- 使用已挂载的 admin client 配置并给 CLI 单独限堆 64m/128m 后，Broker API 与 KRaft quorum 只读验证成功，Leader=1、MaxFollowerLag=0。
- `context.compaction.request.v1`、`context.compaction.request.v1-retry-1000`、`context.compaction.request.v1-dlt` 三个 Topic 全部存在，均为 6 分区/副本 1。
- 全局回归后内存 available 3,952,197,632 bytes，Swap used=0；Nacos/MinIO/Grafana/Loki/XXL-JOB 仍健康。所有门禁通过，未触发回滚。

### C 组：容器上限与日志轮转已完成

- 真实 Compose 源复核与 `docker compose config -q` 均通过。服务器原目录备份时间戳为 `20260715-134429`；XXL 机械修改首次区间表达式在修改前失败，额外保留重试前备份 `docker-compose.yml.pre-resource-retry-20260715-134447`。失败发生在任何重建前，无服务中断。
- 已固化并生效的 memory/PIDs：Loki `384MiB/128`、Grafana `384MiB/128`、MinIO `640MiB/128`、Nacos `1.25GiB/384`、XXL MySQL `1GiB/128`、XXL Admin `768MiB/128`。
- 上述六个容器均使用 Docker `json-file max-size=20m,max-file=3`。未删除现有日志或数据；XXL Admin 的文件数上限由 5 收紧到 3。
- 按 Loki、Grafana、MinIO、Nacos、XXL MySQL、XXL Admin 顺序逐个/逐依赖组重建。Grafana 由于启动耗时超过首轮 30 秒探测窗口曾暂时未返回宿主端健康响应，但容器无重启/OOM、内部 API 已为 200；延长观察后宿主端连续 5 次 200，因此未回滚。
- 最终健康门禁：Loki 200、Grafana 200、MinIO 200、Nacos 200、XXL-JOB 登录跳转 302；XXL 公网地址从本地复核为 302、耗时 0.151s。六个已变更容器全部 `OOMKilled=false`、`RestartCount=0`。
- 同口径稳定采样：XXL Admin 439→265MiB、XXL MySQL 429→374MiB、Nacos 754→592MiB、MinIO 294→255MiB、Grafana 119→66MiB、Loki 135→48MiB；连同 Kafka 678→448MiB，七个中间件合计约 2848→2048MiB，减少约 800MiB（约 28.1%）。
- 主机最终 available memory 4,356,726,784 bytes，Swap used=0，swappiness=10。Kafka 9094 仍可连接，近 15 分钟无 ERROR/FATAL/OOM，业务未随 C 组重建。
- Kafka 创建源已在六个容器完成后按独立计划补全，见下方闭环记录。

#### Kafka 创建源与资源边界闭环

- 基于当前容器 inspect 在服务器本地新建 `/root/ai-agent-kafka/docker-compose.yml`，镜像固定到原 `3.9.2` digest；保留 `appuser`、原 command、三个绝对路径挂载、9094 映射、JAAS 参数和重启策略，没有读取、复制或输出密钥内容。
- 在创建源中固化 `KAFKA_HEAP_OPTS=-Xms256m -Xmx512m`、`mem_limit=1g`、`pids_limit=192` 和 `json-file 20m*3`；`docker compose config -q` 与渲染字段核对通过。
- 重建前将容器/image inspect 与敏感配置 SHA-256 保存到权限受限备份 `/root/ai-agent-kafka/backups/20260715-135123`；原容器保留为停止态 `ai-agent-kafka-pre-compose-20260715-135146`，作为短期快速回滚点。
- 新容器由 Compose 正式管理，实测进程参数为 `-Xms256m/-Xmx512m`，memory/PIDs/logging 均与计划一致；`OOMKilled=false`、`RestartCount=0`，稳定内存约 427–430MiB/PIDs 103。
- KRaft 验证：LeaderId=1、MaxFollowerLag=0；三个上下文压缩 Topic 全部存在。服务器与公网 9094 TCP 均可连接，启动后日志无 ERROR/FATAL/OOM。
- Kafka 完成后全局复核仍为 Loki/Grafana/MinIO/Nacos 200、XXL-JOB 302；主机 available memory 4,381,487,104 bytes，Swap used=0。

### 回滚与持久性记录

- Kafka 回滚：短期可停止并移除新 Compose 容器，将 `ai-agent-kafka-pre-compose-20260715-135146` 改回原名后启动；长期可依据 `20260715-135123` inspect/校验备份恢复原镜像、挂载和参数。回滚后重复 9094/KRaft/Topic/OOM 门禁。
- Swap 回滚：恢复 fstab 备份，恢复或移除 sysctl 文件并设回 swappiness=60，`swapoff` 后再删除 swapfile。
- Kafka 堆、memory/PID 与日志边界现已固化到正式 Compose，删除/重建不会再回到镜像 1G/1G 默认堆。
- D 组尚未执行：未清理历史日志/镜像，未挂载或迁移 70 GB 数据盘，也未上传本地项目。C 组已完成。
