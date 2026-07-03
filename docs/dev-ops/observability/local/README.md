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

## 2. 建立到中间件服务器 Loki 的 SSH 隧道

```bash
chmod +x start-loki-tunnel.sh start-alloy.sh
./start-loki-tunnel.sh
```

默认会把：

- 本机 `127.0.0.1:13100`

转发到：

- `69.165.65.123:127.0.0.1:3100`

## 3. 启动 Alloy

```bash
./start-alloy.sh
```

默认推送地址：

```text
http://127.0.0.1:13100/loki/api/v1/push
```

## 4. Grafana 查询建议

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
