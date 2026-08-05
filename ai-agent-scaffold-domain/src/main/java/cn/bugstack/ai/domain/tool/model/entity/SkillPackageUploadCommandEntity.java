package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上传一个 Skill 压缩包的请求参数。
 *
 * <p>所属层次：工具领域的实体（入参命令对象），不落库。</p>
 *
 * <p>谁构造它：触发器层收到前端的文件上传后组装，其中身份部分必须来自登录态而不是表单字段。</p>
 *
 * <p>谁消费它：{@code ToolPublishService#uploadSkillPackage}。它会先校验包非空、不超过 20MB、
 * 并且真的能从里面解析出 SKILL.md，然后才写进对象存储。</p>
 *
 * <p>这一步只上传和登记资产，不创建任何 Skill 定义，所以上传成功不等于工具可用；
 * 真正建工具要拿返回的资产编号再调一次创建接口。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillPackageUploadCommandEntity {

    /**
     * 上传者身份（租户、用户、角色）；对象存储路径里会带上租户编号做隔离，资产记录也按它归属，
     * 缺失会在方法入口直接被拒。
     */
    private ToolUserContextEntity context;

    /**
     * 前端传来的原始文件名；不可信，存储前会把除字母数字点划线以外的字符全部替换掉，
     * 防止有人用 ../ 之类的路径片段影响对象键结构。
     */
    private String fileName;

    /**
     * 前端声明的内容类型；只作为对象存储的元信息记录，不用来判断文件是否真的是 ZIP，
     * 真正的类型判断靠读文件头的 PK 签名。
     */
    private String contentType;

    /**
     * 压缩包的原始字节；会先在内存里做大小与结构校验再写入对象存储。
     * 它完全来自外部，是压缩炸弹和畸形 ZIP 的入口，所有解压都必须在条目数和单条大小的上限内进行。
     */
    private byte[] bytes;
}
