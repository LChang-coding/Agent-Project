package cn.bugstack.ai.domain.share.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 会话分享工具权限预检汇总。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionToolPrecheckEntity {
    /** 是否存在至少一个不可直接复现的依赖。 */
    private Boolean hasRisk;
    /** 接收方可用且版本匹配的工具数。 */
    private Integer availableCount;
    /** 无权限、未发布或版本不匹配的工具数。 */
    private Integer missingCount;
    /** 被策略明确拒绝的工具数；当前实现保留为零。 */
    private Integer deniedCount;
    /** 每项工具的权限判定明细。 */
    private List<SessionToolAccessEntity> items;
}
