package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.tool.model.entity.McpCreateCommandEntity;
import cn.bugstack.ai.domain.tool.model.entity.McpDefinitionEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillCreateCommandEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillDefinitionEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillPackageUploadCommandEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillPackageUploadResultEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillVersionCreateCommandEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCallLogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolUserContextEntity;

import java.util.List;

/**
 * 工具「管理面」的领域入口：把 Skill/MCP 从上传、建草稿、加版本、测试，一路推到发布或停用。
 *
 * <p>所属层次：领域层的服务接口，实现是 {@code ToolPublishService}。</p>
 *
 * <p>谁调用它：触发器层的工具管理接口（Skill 中心、MCP 中心页面），身份一律来自登录态。</p>
 *
 * <p>它向下调用什么：工具仓储（读写定义、版本、资产、调用日志）、对象存储（存取 Skill 包）、
 * MCP 协议客户端（真的连上去拉远程工具清单）、Skill 包读取器（在限额内解析 ZIP）。</p>
 *
 * <p>它和「运行面」的分工：这里只管工具怎么上架，绝不执行任何工具。
 * 真正的调用、幂等与审计在 {@code ToolGateway}；本轮该加载哪些工具在 {@code ToolResolver}。</p>
 *
 * <p>共同的安全约定：每个方法第一步都要校验身份完整（租户和用户都在），
 * 每个写操作都要确认操作人是工具所有者或租户管理员，涉及租户公开工具和 stdio 传输时再额外收紧到管理员。</p>
 */
public interface IToolPublishService {

    /**
     * 上传一个 Skill 压缩包并登记成资产。
     *
     * <p>关键输入是原始字节；会先校验非空、不超过 20MB、并且真能从里面解析出 SKILL.md，再写进对象存储。</p>
     *
     * <p>返回资产编号等信息，前端拿它继续建工具。会写对象存储和资产表，但不创建任何工具定义，
     * 因此上传成功不等于工具可用。包非法（不是 ZIP、缺 SKILL.md、条目超限）会直接抛业务异常。</p>
     */
    SkillPackageUploadResultEntity uploadSkillPackage(SkillPackageUploadCommandEntity command);

    /**
     * 用一个已上传的资产建出 Skill 定义和它的首个草稿版本。
   *
     * <p>会重新把包从对象存储读回来解析一遍，防止只凭上传登记就建出一个解析不了的版本。</p>
     *
     * <p>会写库（两条记录）。结果是草稿：模型看不到、调不到，必须再发布。
   * 主要失败情形：身份不完整、资产不存在、把可见范围设成租户公开但操作人不是管理员。</p>
     */
    SkillDefinitionEntity createSkill(SkillCreateCommandEntity command);

    /**
     * 给已有 Skill 追加一个新的草稿版本。
*
     * <p>会写库，并把定义上的「最近编辑版本」推进到新版本，但不动已发布版本——
     * 所以线上对话仍然跑旧版本，可以先备好新版再择机切换。</p>
     *
     * <p>主要失败情形：无权操作该工具、版本号已存在（版本不可变，不允许覆盖）、新包解析不过。</p>
 */
    SkillDefinitionEntity createSkillVersion(SkillVersionCreateCommandEntity command);

  /**
     * 发布指定版本的 Skill，让它进入运行目录。
     *
     * <p>版本为空时发布当前最近编辑的版本。会写库：把版本置为激活，并把定义的已发布版本指针指过去。</p>
     *
     * <p>这是一个对线上立即生效的动作——下一轮对话的工具解析就会带上这个工具，模型随即能看到并调用它。
     * 主要失败情形：无权操作、指定版本不存在。</p>
 */
    SkillDefinitionEntity publishSkill(ToolUserContextEntity context, String skillId, String version);

    /**
     * 停用一个 Skill，让它从运行目录里消失。
     *
     * <p>会写库，只改定义状态。历史版本和全部调用审计都保留，方便事后追溯。
     * 停用同样立即生效：模型下一轮就看不到这个工具了。主要失败情形是无权操作。</p>
     */
    SkillDefinitionEntity disableSkill(ToolUserContextEntity context, String skillId);

    /**
     * 查询管理界面上该用户能看到的 Skill 列表。
     *
     * <p>scope 为空按 available 处理（自己的 + 租户公开的）。只读，不改状态。
     * 返回结果包含草稿，所以列表里出现的工具不一定能被模型调用。</p>
     */
    List<SkillDefinitionEntity> querySkills(ToolUserContextEntity context, String scope);

    /**
   * 创建一个 MCP 工具的定义和首个未测试版本。
     *
     * <p>创建前会依次校验：可见范围合法、传输类型该角色能不能用（stdio 只允许管理员，因为它会在服务器起子进程）、
     * args/env 是不是合法 JSON、stdio 命令能不能组装成进程参数。</p>
   *
     * <p>会写库（两条记录）。结果是「草稿 + 未测试」，必须先测试通过才允许发布，
     * 否则模型会拿到一个连不通的工具反复重试，失败文案还会污染后续提示词。</p>
     */
    McpDefinitionEntity createMcp(McpCreateCommandEntity command);

  /**
     * 对 MCP 的当前版本真的建一次连接，把远程工具清单拉回来存下。
     *
     * <p>这是「工具能力如何声明给大模型」的源头：拉回来的清单会冻结到版本里，
     * 之后模型看到的可用远程工具摘要就由它生成。</p>
     *
     * <p>会写库并发起外部连接。测试失败不算异常而是一种业务结果，同样落库为失败状态和原因文案，
     * 好让配置者看到到底哪里连不通。主要失败情形是无权操作或版本不存在。</p>
     */
    McpDefinitionEntity testMcp(ToolUserContextEntity context, String mcpId);

    /**
     * 发布指定版本的 MCP，让它进入运行目录。
   *
* <p>会写库：版本置为激活，定义的已发布版本指针指过去。硬性前提是该版本的测试状态必须是成功，
 * 否则直接抛「MCP 必须测试通过后才能发布」。</p>
     *
     * <p>对线上立即生效：下一轮对话模型就能调用它，也就意味着模型从此具备了触发这个外部系统的能力。</p>
  */
    McpDefinitionEntity publishMcp(ToolUserContextEntity context, String mcpId, String version);

    /**
     * 停用一个 MCP，让它从运行目录里消失。
     *
     * <p>会写库，只改定义状态；版本记录和调用审计保留。停用立即生效，是「出事了先断掉这个外部工具」的应急开关。</p>
     */
    McpDefinitionEntity disableMcp(ToolUserContextEntity context, String mcpId);

    /**
     * 查询管理界面上该用户能看到的 MCP 列表，scope 语义与 Skill 查询一致。只读。
   */
    List<McpDefinitionEntity> queryMcps(ToolUserContextEntity context, String scope);

    /**
     * 查询该用户当前真正可被调用的工具目录，供管理界面展示「我现在有哪些工具能用」。
  *
     * <p>只读。它和运行时解析走的是同一个仓储查询，因此界面显示的清单和模型实际看到的一致，
   * 不会出现「界面上有但模型说没有」的困惑。</p>
     */
    List<ToolCatalogEntity> queryCatalog(ToolUserContextEntity context);

    /**
     * 查询某个会话的工具调用明细，供界面展示这段对话调了什么、成功没有、耗时多少。
     *
     * <p>只读，按租户、用户、会话三重过滤，防止越权查看别人的调用记录及其输入参数。</p>
 */
    List<ToolCallLogEntity> queryCallLogs(ToolUserContextEntity context, String sessionId);
}
