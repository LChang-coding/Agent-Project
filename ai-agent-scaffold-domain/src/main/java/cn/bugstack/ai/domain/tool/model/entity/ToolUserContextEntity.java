package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 「谁在管理工具」的身份三元组，用于工具的上架、发版、测试、停用和列表查询。
 *
 * <p>所属层次：工具领域的实体（入参上下文对象），不落库。</p>
 *
 * <p>谁构造它：触发器层从认证上下文（登录态）取出租户、用户、角色后组装，绝不接受前端请求体直接指定，
 * 否则任何人都能把 userId 填成别人去改别人的工具。</p>
 *
 * <p>谁消费它：{@code ToolPublishService} 的每个方法第一步都要校验它非空，然后用它做租户隔离与权限判断；
 * {@code ToolResolver} 用它查询当前用户本轮可调用的工具目录。</p>
 *
 * <p>它不负责什么：不含运行、会话、追踪信息（那些在 {@code ToolInvokeContextEntity} 里），
 * 也不表示「能不能调用某个具体工具」——那要结合工具的所有者和可见范围一起判断。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolUserContextEntity {

    /**
     * 当前租户编号；所有工具的查询和写入都靠它隔离不同公司的数据，缺失或写错会导致读到、甚至改到别的租户的工具，
  * 因此发布服务在入口就会直接拒绝空值。
     */
    private String tenantId;

    /**
  * 当前操作人编号；决定他能管理哪些私有工具（只有所有者本人或管理员能改），也是发布日志里的责任人。
     * 必须来自登录态，不能由请求参数指定。
     */
    private String userId;

    /**
  * 当前用户在本租户内的角色编码；只有 owner 或 admin 才被视为租户管理员，
     * 才能创建租户公开工具、配置能启动本机进程的 stdio/local MCP，以及管理别人的工具。空值一律按普通成员处理。
     */
    private String roleCode;
}
