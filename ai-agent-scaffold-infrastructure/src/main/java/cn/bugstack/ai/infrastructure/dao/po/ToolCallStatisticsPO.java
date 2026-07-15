package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

/**
 * 会话工具调用聚合持久化对象。
 */
@Data
public class ToolCallStatisticsPO {
    private Long callCount;
    private Long toolCount;
}
