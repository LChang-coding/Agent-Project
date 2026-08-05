package cn.bugstack.ai.domain.tool.model.entity;

import lombok.Builder;
import lombok.Data;

/**
 * 一个会话里工具用得多不多的两个汇总数字，供会话概览和分享页展示。
 *
 * <p>所属层次：工具领域的实体（只读聚合结果），由仓储的 SQL 聚合直接填充，不参与任何业务判断。</p>
 *
 * <p>谁会用它：会话汇总与分享相关的查询方，通过 {@code IToolRepository#summarizeToolCalls} 拿到它。</p>
 *
 * <p>统计范围只包含「成功」的调用：失败和只领了执行权却没有结果的调用不计入，
 * 否则界面会显示「调用了 5 次工具」但用户根本没看到任何工具产出。</p>
 *
 * <p>它不负责什么：不含任何明细（明细走调用日志查询）、不含耗时和错误信息，也不做租户过滤——
 * 租户和用户范围由调用仓储时传入的参数在 SQL 层限定。</p>
 */
@Data
@Builder
public class ToolCallStatisticsEntity {
    /** 这个会话里成功执行完的工具调用总次数；界面上「本次对话调用了几次工具」显示的就是它，重复调用会各算一次。 */
    private Long callCount;
    /** 这个会话里成功调用过的不同工具个数（按工具编号去重）；用来回答「这段对话到底依赖了哪几个工具」。 */
    private Long toolCount;
}
