package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 给已有 Skill 追加一个新版本的请求参数。
 *
 * <p>所属层次：工具领域的实体（入参命令对象），不落库。</p>
 *
 * <p>谁消费它：{@code ToolPublishService#createSkillVersion}。它会校验操作权限、确认版本号没被占用、
 * 重新解析新包，然后插入一条草稿版本并把定义上的「最近编辑版本」推到新版本。</p>
 *
 * <p>关键点：这一步不会改动已发布版本。也就是说线上对话仍然跑旧版本，直到有人显式调用发布接口，
 * 这样才能先建好新版本再挑时间切换。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillVersionCreateCommandEntity {

    /**
     * 操作人身份；用于判断他是不是这个 Skill 的所有者或租户管理员，不是就直接拒绝，
     * 防止别人往你的工具里塞一个包。
     */
    private ToolUserContextEntity context;

    /**
     * 要加版本的目标 Skill 业务编号；查不到会报「Skill 不存在」。
     */
    private String skillId;

    /**
     * 新版本号；为空时按当前版本的补丁位自动加一。
     * 如果这个版本号已经存在会直接报错，因为版本必须不可变，不允许悄悄覆盖已有版本的内容。
     */
    private String version;

    /**
     * 新版本要用的包资产编号；服务端会凭它把包读回来重新解析 SKILL.md，解析不过就不建版本。
   */
    private String assetId;
}
