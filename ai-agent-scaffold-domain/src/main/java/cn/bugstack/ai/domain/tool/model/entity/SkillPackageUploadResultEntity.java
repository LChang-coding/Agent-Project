package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一个已经上传并登记好的 Skill 包资产，是「文件已在对象存储里」这件事的凭据。
 *
 * <p>所属层次：工具领域的实体，会落到资产表，也会作为上传接口的返回值给前端。</p>
 *
 * <p>它在流程中的位置：上传接口产出它 → 前端拿着资产编号调创建 Skill 或创建新版本 →
 * 发布服务凭资产编号回查它，再从对象存储把包读回来解析，然后把这些字段整段抄进 Skill 版本记录。</p>
 *
 * <p>为什么要「上传」和「建工具」分两步：用户可能上传完就放弃，或者用同一个包建多个版本。
 * 分开之后包只需上传一次，也不会留下一堆残缺的工具定义。</p>
 *
 * <p>它不负责什么：不含工具名称、可见范围、版本号等业务属性，也不代表这个包已经能被模型调用。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillPackageUploadResultEntity {

    /**
     * 资产业务编号；前端后续创建 Skill 或加新版本时只传这个编号，
* 不需要（也不允许）自己指定桶和对象键，避免有人拼一个路径去读别人的文件。
     */
    private String assetId;

    /**
     * 包所在的对象存储桶；与对象键一起唯一定位文件，调用 Skill 时凭它把包取回来。
     */
    private String bucket;

    /**
     * 包在桶里的对象键；路径中包含租户编号和一段随机目录，因此不同人上传同名文件不会互相覆盖。
   */
    private String objectKey;

    /**
     * 清洗后的安全文件名；只保留字母数字和点划线，用于界面展示和版本记录留档。
     */
    private String fileName;

    /**
     * 包内容的 SHA-256 摘要；用来确认「发布出去的包和当初上传的是同一个文件」，
* 存储层没返回时会在创建版本时现算补齐，保证每个版本都有可校验的指纹。
     */
    private String sha256;

    /**
     * 包字节数；用于界面展示和容量统计，上限校验发生在上传阶段而不是这里。
     */
    private Long sizeBytes;
}
