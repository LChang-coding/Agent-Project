package cn.bugstack.ai.domain.observability.adapter.port;

import java.time.Instant;
import java.util.List;

/**
 * 按本次运行的 Trace ID 读取应用日志的外部能力。
 *
 * <p>领域层只传递已经确认属于当前运行的 Trace ID 和租户编号；具体的 Grafana 地址、认证方式和
 * LogQL 查询由基础设施层控制，不能由模型提供。</p>
 */
@FunctionalInterface
public interface TraceLogQueryPort {

    /**
     * 查询一个受限时间窗口内的日志。
     *
     * @param command 当前运行身份、向前查询的分钟数和最大返回条数
     * @return 已按时间排序并删除敏感信息的日志
     */
    QueryResult query(QueryCommand command);

    /**
     * @param tenantId 当前运行所属租户，用于缩小日志查询范围
     * @param traceId 当前运行自己的 Trace ID
     * @param lookbackMinutes 从当前时间向前查询多少分钟
     * @param limit 最大返回条数
     */
    record QueryCommand(String tenantId, String traceId, int lookbackMinutes, int limit) {
    }

    /**
     * @param entries 按发生时间升序排列的日志
     * @param truncated 是否因为条数上限而停止返回更多内容
     */
    record QueryResult(List<LogEntry> entries, boolean truncated) {
        public QueryResult {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /**
     * @param timestamp 日志发生时间
     * @param line 已删除常见密码、令牌和密钥的日志正文
     */
    record LogEntry(Instant timestamp, String line) {
    }
}
