package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

/**
 * 会话工具调用聚合持久化对象。
 */
@Data
public class ToolCallStatisticsPO {
    /** 会话内有效成功调用总数。 */
    private Long callCount;
    /** 会话内去重工具数量。 */
    private Long toolCount;
}
