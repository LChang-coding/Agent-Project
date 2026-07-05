package cn.bugstack.ai.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Getter
public enum ResponseCode {

    SUCCESS("0000", "成功"),
    UN_ERROR("0001", "未知失败"),
    ILLEGAL_PARAMETER("0002", "非法参数"),
    NOT_FOUND_METHOD("0003", "不存在的方法"),

    E0001("E0001", "智能体ID不存在"),
    E0002("E0002", "智能体MCP配置不在可加载范围"),
    SESSION_NOT_FOUND("SESSION_NOT_FOUND", "会话不存在"),
    SESSION_ACCESS_DENIED("SESSION_ACCESS_DENIED", "无权访问会话"),
    AUTH_REGISTER_FAILED("AUTH_REGISTER_FAILED", "注册失败"),
    AUTH_LOGIN_FAILED("AUTH_LOGIN_FAILED", "登录失败"),
    AUTH_REFRESH_FAILED("AUTH_REFRESH_FAILED", "令牌续期失败"),
    AUTH_CHANGE_PASSWORD_FAILED("AUTH_CHANGE_PASSWORD_FAILED", "修改密码失败"),
    AUTH_UPDATE_PROFILE_FAILED("AUTH_UPDATE_PROFILE_FAILED", "修改资料失败"),
    AUTH_UNAUTHORIZED("AUTH_UNAUTHORIZED", "未登录或登录已过期"),

    ;

    private String code;
    private String info;

}
