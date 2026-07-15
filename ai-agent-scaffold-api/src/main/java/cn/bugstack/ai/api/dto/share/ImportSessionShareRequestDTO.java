package cn.bugstack.ai.api.dto.share;

import lombok.Data;

/** 会话分享导入确认请求。 */
@Data
public class ImportSessionShareRequestDTO {
    private Boolean confirmToolAccessRisk;
}
