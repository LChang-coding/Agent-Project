package cn.bugstack.ai.api.dto.session;

import lombok.Data;

import java.util.List;

/**
 * 会话RAG设置请求。
 */
@Data
public class SessionRagSettingRequestDTO {
    /** 兼容旧客户端的开关；未传mode时映射为AUTO或OFF。 */
    private Boolean enabled;

    /** 会话RAG模式：OFF/AUTO/MANUAL。 */
    private String mode;

    /** MANUAL模式选择的绑定ID。 */
    private List<String> selectedBindingIds;

    /** 可选的策略乐观锁版本。 */
    private Long expectedRevision;
}
