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
    private Boolean hasRisk;
    private Integer availableCount;
    private Integer missingCount;
    private Integer deniedCount;
    private List<SessionToolAccessEntity> items;
}
