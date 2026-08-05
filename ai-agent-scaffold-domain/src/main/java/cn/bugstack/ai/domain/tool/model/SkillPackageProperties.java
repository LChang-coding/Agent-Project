package cn.bugstack.ai.domain.tool.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 规定一个用户上传的 Skill 压缩包最多能有多少个文件、单个文件最多能解出多少字节。
 *
 * <p>所属层次：工具领域的模型配置对象，由 Spring 从 {@code ai.skill-package} 前缀的配置项装配。</p>
 *
 * <p>谁会用它：{@code SkillPackageReader}。它在解压用户上传的 ZIP 之前先读这两个上限，
 * 解压过程中逐条比对，一旦超限立刻中断并抛业务异常。</p>
 *
 * <p>为什么必须有上限：Skill 包是外部上传的文件，攻击者可以构造「压缩炸弹」——几十 KB 的 ZIP 能解出几十 GB，
 * 把服务器内存吃光，导致同一台机器上所有租户的对话一起不可用。这两个数字就是那道防线。</p>
 *
 * <p>它不负责什么：不判断 ZIP 内容是否合法、不读取 SKILL.md、不做任何权限校验，只提供两个阈值数字。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.skill-package")
public class SkillPackageProperties {

    /** 一个 Skill 压缩包允许包含的最大条目数；解压时逐条累加计数，超过就判定整包非法，用来挡住「条目数极多」的压缩炸弹。 */
    private int maxEntries = 256;
    /** 单个条目解压后允许写进内存的最大字节数；边读边累计，一超就中断，避免一个文件把堆内存撑爆拖垮整个进程。 */
    private int maxEntryBytes = 1024 * 1024;
}
