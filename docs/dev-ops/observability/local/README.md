# Local Alloy to Middleware Server Loki

## 1. 启动应用

应用继续本地运行，例如：

```bash
mvn spring-boot:run -pl ai-agent-scaffold-app
```

日志默认会写入：

```text
./data/log
```

如需改为绝对路径，请在启动前设置：

```bash
export OBS_LOG_DIR=/absolute/path/to/data/log
```

## 2. 默认：启动类前置自动拉起观测链路

`Application.main(...)` 会在 `SpringApplication.run(...)` 之前尝试执行：

```bash
./docs/dev-ops/observability/local/ensure-observability.sh
```

本地脚本存在时，会自动确保：

- `127.0.0.1:13100 -> 69.165.65.123:3100` 的 Loki SSH 隧道
- Grafana Alloy 日志采集进程

如果不想自动启动，例如只想单独调试 Java 应用，可以加环境变量：

```bash
OBS_AUTO_START=false
```

也可以指定自定义脚本路径：

```bash
OBS_BOOTSTRAP_SCRIPT=/absolute/path/to/ensure-observability.sh
```

脚本失败不会阻塞 Java 应用启动，只会在控制台输出告警。

## 3. 可选：IDEA Before launch 自动拉起观测链路

本地提供了幂等启动脚本，适合挂到 IDEA 的 `Before launch`：

```bash
./docs/dev-ops/observability/local/ensure-observability.sh
```

它会自动检查并启动：

- `127.0.0.1:13100 -> 69.165.65.123:3100` 的 Loki SSH 隧道
- Grafana Alloy 日志采集进程

脚本可重复执行，已经启动时会自动跳过，不会重复创建进程。

默认会同时监听两个日志目录，避免 IDEA 工作目录不同导致日志断采：

```text
Agent-Project/data/log/*.log
../data/log/*.log
```

停止本地观测链路：

```bash
./docs/dev-ops/observability/local/stop-observability.sh
```

## 4. 手动：建立到中间件服务器 Loki 的 SSH 隧道

```bash
chmod +x start-loki-tunnel.sh start-alloy.sh
./start-loki-tunnel.sh
```

默认会把：

- 本机 `127.0.0.1:13100`

转发到：

- `69.165.65.123:127.0.0.1:3100`

## 5. 手动：启动 Alloy

```bash
./start-alloy.sh
```

默认推送地址：

```text
http://127.0.0.1:13100/loki/api/v1/push
```

## 6. Grafana 查询建议

Explore 中常用查询：

```logql
{job="ai-agent-scaffold"} |= "event=token_usage" | logfmt
```

```logql
sum by (userId) (
  sum_over_time({job="ai-agent-scaffold"} |= "event=token_usage" | logfmt | unwrap totalTokens [5m])
)
```

```logql
sum by (modelVersion) (
  sum_over_time({job="ai-agent-scaffold"} |= "event=token_usage" | logfmt | unwrap totalTokens [5m])
)
```

```logql
{job="ai-agent-scaffold"} |= "event=model_error" | logfmt
```
