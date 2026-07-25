# 故障处理

| 错误类别 | 含义 | 处理 |
|---|---|---|
| `GRAFANA_UNAUTHORIZED` | 本机 `codex.md` 认证失效 | 请维护者更新本机配置，不要回显凭据 |
| `GRAFANA_FORBIDDEN` | 账号无 datasource proxy 权限 | 检查 Grafana 角色和数据源权限 |
| `GRAFANA_NOT_FOUND` | 接口或 datasource UID 不存在 | 先运行 `doctor`，核对非敏感 UID |
| `GRAFANA_RATE_LIMITED` | Grafana 限流 | 缩小范围和 limit，稍后重试 |
| `GRAFANA_TIMEOUT` | 请求超过配置时限 | 缩小时间窗；不要无限提高超时 |
| `GRAFANA_UNAVAILABLE` | 网络、DNS 或服务不可达 | 检查服务器与 Grafana 健康状态 |
| `GRAFANA_DATASOURCE_INVALID` | 不是预期 Loki proxy 数据源 | 修正本机 datasource UID |
| `LOKI_QUERY_FAILED` | Loki 返回非成功业务状态 | 缩小并验证 LogQL |
| `LOKI_PAGINATION_STALLED` | 单一时间戳日志超过安全分页能力 | 缩小时间窗或增加更精确过滤 |

无结果时：

1. 确认 ID 类型与命令匹配。
2. `trace` 保持默认 `--expand`，最多扩大至 72 小时。
3. 检查日志是否使用相同字段名，以及 Alloy/Loki 是否在目标时段摄取。
4. 明确记录“指定时间窗内未发现日志”，不要宣称链路不存在。
