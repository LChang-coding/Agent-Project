package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用一个已上传的包创建 Skill 工具的请求参数。
 *
 * <p>所属层次：工具领域的实体（入参命令对象），不落库。</p>
 *
 * <p>谁消费它：{@code ToolPublishService#createSkill}。它会一次性建出两条记录——Skill 定义和它的首个草稿版本，
 * 并且会真的把包从对象存储读回来重新解析一遍，防止只凭上传登记就建出一个解析不了的版本。</p>
 *
 * <p>创建结果一定是草稿：模型看不到，也调不到，必须再调发布接口才会进入运行目录。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillCreateCommandEntity {

    /**
     * 操作人身份（租户、用户、角色）；决定工具归属哪个租户和哪个所有者，
     * 也决定能否把可见范围设成租户公开，缺失会在入口直接被拒。
     */
    private ToolUserContextEntity context;

    /**
     * Skill 的展示名称；会进入给大模型看的函数描述，模型靠它理解这个工具是干什么的。
     */
    private String skillName;

    /**
     * Skill 的编码；用来生成模型可用的函数名，为空时退化成用名称去生成。
     * 落库前会把非法字符替换掉并截断，因为模型函数名有严格的字符和长度限制。
     */
    private String skillCode;

    /**
 * 用途说明；同样会拼进模型能看到的描述里，写得越准确模型越不容易乱调工具。
     */
    private String description;

    /**
     * 可见范围（private 或 tenant_public）；为空按私有处理。
     * 填租户公开但操作人不是 owner/admin 时会被直接拒绝，避免普通成员把带凭证的工具开放给全公司。
     */
    private String visibility;

    /**
     * 首个版本号；为空时使用默认的 1.0.0。版本一旦创建就不可变，改内容只能加新版本。
     */
    private String version;

    /**
     * 已上传包的资产编号；服务端凭它回查真实的桶和对象键，
  * 查不到就报「上传包不存在」，从而挡住直接伪造存储路径的尝试。
     */
    private String assetId;
}
