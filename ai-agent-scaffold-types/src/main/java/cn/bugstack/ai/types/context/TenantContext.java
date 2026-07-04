package cn.bugstack.ai.types.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantContext {

    private String tenantId;

    private String userId;

    private String username;

    private String roleCode;
}
