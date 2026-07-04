package cn.bugstack.ai.types.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 4514559731481443915L;

    private String tenantId;

    private String userId;

    private String username;

    private String roleCode;
}
