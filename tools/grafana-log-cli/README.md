# grafana-log

`grafana-log` 是项目内独立的只读 Grafana/Loki 日志查询与链路诊断工具。它通过 Grafana datasource proxy 查询 Loki，不直接暴露 Loki，也不提供删除、修改数据源或看板等写操作。

## 安全配置

真实地址和认证信息只允许放在仓库根目录本机 `codex.md` 的
`grafana-log-cli-config-v1` 有界 JSON 配置块。该文件必须保持 Git 未跟踪。

认证信息不支持命令行或环境变量覆盖。以下非敏感项可临时覆盖：

- `GRAFANA_LOG_CODEX_FILE`
- `GRAFANA_LOG_GRAFANA_URL`
- `GRAFANA_LOG_DATASOURCE_UID`
- `GRAFANA_LOG_SELECTOR`

不要在 shell 历史、Skill、README、日志或提交中复制密码和 Token。

## 常用命令

```bash
grafana-log doctor --output json
grafana-log trace <trace-id> --since 30m --output timeline
grafana-log retrieval <retrieval-id> --since 2h --output jsonl
grafana-log ingest <task-id> --since 24h --output jsonl
grafana-log search --event rag_stage --since 30m --limit 200
grafana-log query '{job="ai-agent-scaffold"} |= "ERROR"' --since 30m
```

`trace` 在默认开启 `--expand` 时会在无结果后依次扩大到 2、12、24、72
小时。所有命令都有 31 天范围、10 万行总量和 5000 行单页上限。

输出格式：

- `timeline`：面向人工阅读的中文时间线和异常摘要。
- `table`：紧凑表格。
- `jsonl`：推荐 Agent 使用，包含 meta、逐条 entry、analysis。
- `json`：完整稳定结构。
- `raw`：仅输出经过脱敏的原始日志行。

## 开发与发布

```bash
make verify
make release
make skill-validate
```

`make release` 为 darwin/linux 的 amd64/arm64 构建可复现二进制，同时把
压缩后的二进制和 SHA-256 清单写入 Skill 的 `assets/bin`。Skill 入口会验证
哈希后再解压到用户缓存目录。
