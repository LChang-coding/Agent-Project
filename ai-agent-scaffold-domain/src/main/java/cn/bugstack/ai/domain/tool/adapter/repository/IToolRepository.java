package cn.bugstack.ai.domain.tool.adapter.repository;

import cn.bugstack.ai.domain.tool.model.entity.McpDefinitionEntity;
import cn.bugstack.ai.domain.tool.model.entity.McpVersionEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillDefinitionEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillPackageUploadResultEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillVersionEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCallLogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCallStatisticsEntity;
import cn.bugstack.ai.domain.share.model.SessionToolDependencyEntity;

import java.util.List;

/**
 * 工具领域向外部存储要数据的唯一出口，由基础设施层用 MyBatis 实现。
 *
 * <p>所属层次：领域层的仓储接口（依赖倒置）。领域这边只声明「我需要什么」，
 * 具体是几张表、怎么关联、SQL 怎么写，全部关领域层什么事都没有。</p>
 *
 * <p>谁调用它：{@code ToolResolver}（查当前用户能用哪些工具）、{@code ToolPublishService}（工具的增删改查与发布）、
 * {@code ToolDispatchAuthorizationService}（抢执行权与回填调用结果）、
 * 以及会话洞察和会话分享这两个跨领域场景（读工具调用统计与依赖）。</p>
 *
 * <p>租户隔离约定：凡是带 tenantId 参数的方法，实现必须把它作为查询条件，不能只当日志字段。
 * 少一个条件就意味着别的公司的工具配置、连接地址会被读出来。</p>
 *
 * <p>它不负责什么：不做权限判断（谁能改哪个工具由发布服务判定）、不做业务校验、不发起任何外部调用、
 * 不管事务边界（事务由调用方的服务方法声明）。</p>
 */
public interface IToolRepository {

    /**
     * 把「某个 Skill 压缩包已经存进对象存储」这件事登记成一条资产记录。
     *
* <p>输入是租户、上传人和存储结果（桶、对象键、指纹、大小）；返回资产业务编号，前端后续建工具只需拿这个编号。</p>
     *
     * <p>会写库。这一步只登记文件，不创建任何工具定义，所以登记成功不代表有工具可用。</p>
     */
    String saveSkillAsset(String tenantId, String userId, SkillPackageUploadResultEntity result);

    /**
     * 按资产编号回查这个包到底存在哪、指纹是什么。
     *
     * <p>这是一道安全边界：创建工具时只信任服务端查出来的桶和对象键，绝不用前端传的路径，
   * 否则调用方可以拼一个路径去读别人上传的文件。查不到返回空，调用方会报「上传包不存在」。</p>
     */
    SkillPackageUploadResultEntity querySkillAsset(String assetId);

  /**
     * 新增一条 Skill 定义（主记录）。
     *
     * <p>写库，返回影响行数。新建出来的定义是草稿状态，模型看不到也调不到。</p>
  */
    int saveSkillDefinition(SkillDefinitionEntity entity);

    /**
     * 更新 Skill 定义。
     *
     * <p>写库，返回影响行数。加新版本、发布、停用最终都落到这个方法上——
     * 其中把已发布版本指针改掉就等于切换了所有正在进行的新对话实际加载的内容。</p>
     */
    int updateSkillDefinition(SkillDefinitionEntity entity);

    /**
     * 按业务编号查一条 Skill 定义；查不到返回空，调用方据此报「Skill 不存在」。
   *
     * <p>发布服务的每个写操作都会先用它把最新记录读出来，避免基于前端传来的过期字段做更新。</p>
     */
    SkillDefinitionEntity querySkillDefinition(String skillId);

  /**
     * 新增一条 Skill 版本记录（不可变快照）。
     *
     * <p>写库，返回影响行数。版本里冻结了包的位置和指纹，之后不再修改内容，改内容只能再加一版。</p>
     */
    int saveSkillVersion(SkillVersionEntity entity);

    /**
     * 更新 Skill 版本记录。
     *
     * <p>写库，返回影响行数。当前只有发布动作会用它把版本状态改成已激活。</p>
     */
    int updateSkillVersion(SkillVersionEntity entity);

    /**
     * 按 Skill 编号加版本号精确查一条版本记录。
   *
     * <p>两个用途：发布前确认这一版真的存在；创建新版本前确认这个版本号还没被占用——
     * 版本必须不可变，所以撞号只能报错而不能覆盖。</p>
     */
    SkillVersionEntity querySkillVersion(String skillId, String version);

    /**
     * 查询一个 Skill 的全部历史版本，供管理界面展示版本列表和回滚选项。
     *
     * <p>只读，不做租户过滤，因此调用方必须先确认这个 Skill 属于当前租户。</p>
     */
    List<SkillVersionEntity> querySkillVersions(String skillId);

    /**
     * 新增一条 MCP 定义（主记录）。
     *
     * <p>写库，返回影响行数。新建的 MCP 是「草稿 + 未测试」，必须先测试通过才允许发布。</p>
     */
    int saveMcpDefinition(McpDefinitionEntity entity);

 /**
     * 更新 MCP 定义。
     *
     * <p>写库，返回影响行数。测试结论回填、发布切换、停用都走这里。
     * 注意它会写入连接地址、命令和环境变量等敏感配置，实现里不应把这些内容打进日志。</p>
     */
    int updateMcpDefinition(McpDefinitionEntity entity);

    /**
     * 按业务编号查一条 MCP 定义；查不到返回空，调用方据此报「MCP 不存在」。
     */
    McpDefinitionEntity queryMcpDefinition(String mcpId);

    /**
     * 新增一条 MCP 版本记录（不可变快照）。
     *
     * <p>写库，返回影响行数。这一版的传输方式、地址、命令、环境变量创建后即冻结，
 * 保证正在进行的对话不会因为有人改草稿而突然连到别的服务器。</p>
     */
    int saveMcpVersion(McpVersionEntity entity);

    /**
     * 更新 MCP 版本记录。
  *
     * <p>写库，返回影响行数。测试后回填远程工具清单和测试结论、发布时置为激活，都用它。</p>
     */
    int updateMcpVersion(McpVersionEntity entity);

  /**
     * 按 MCP 编号加版本号精确查一条版本记录。
     *
     * <p>测试要用它拿到冻结的连接参数，发布要用它确认测试状态是否为成功。</p>
     */
    McpVersionEntity queryMcpVersion(String mcpId, String version);

    /**
     * 查询一个 MCP 的全部历史版本，供管理界面展示。
   *
     * <p>只读，不做租户过滤，调用方需自行保证归属正确。</p>
     */
    List<McpVersionEntity> queryMcpVersions(String mcpId);

    /**
     * 查询这个用户在管理界面上能看到的 Skill 列表。
     *
     * <p>scope 决定范围：mine 只看自己的，tenant 只看租户公开的，其余（默认 available）看两者合集并去重。
     * 无论哪种都限定在传入的租户内。返回的是定义记录，包含草稿，所以这里出现的工具不一定能被模型调用。</p>
  */
    List<SkillDefinitionEntity> querySkillDefinitions(String tenantId, String userId, String scope);

    /**
   * 查询这个用户在管理界面上能看到的 MCP 列表，scope 语义与 Skill 查询一致。
     */
    List<McpDefinitionEntity> queryMcpDefinitions(String tenantId, String userId, String scope);

    /**
     * 查询这个用户此刻真正能被大模型调用的工具目录，是整个工具注册链路的查找入口。
     *
     * <p>实现要做三件事：按租户和可见范围筛出用户有权使用的 Skill 与 MCP、只保留已激活的定义、
     * 再关联出各自的激活版本；关联不到激活版本的直接丢弃，因为没有版本就没有可执行内容。</p>
     *
     * <p>返回结果直接决定模型这一轮能看到哪些函数：这里多返回一个，模型就多一种产生外部副作用的能力；
     * 少返回一个，模型就会说自己没有这个能力。每轮对话都会重新查一次，所以发布、停用、权限变更能立刻生效。</p>
  */
    List<ToolCatalogEntity> queryAvailableTools(String tenantId, String userId);

    /**
     * 直接插入一条工具调用日志（非幂等写入）。
     *
     * <p>写库，返回影响行数。当前调用链走的是「先抢执行权再回填」的两段式写法，这个方法保留给不需要幂等保护的场景。</p>
     */
    int saveToolCallLog(ToolCallLogEntity entity);

    /**
     * 用幂等键去抢一次外部工具的执行权：以 started 状态尝试插入，返回 1 表示抢到，返回 0 表示别人已经抢过。
     *
     * <p>实现依赖数据库对幂等键的唯一索引做「插入即加锁」，而不是先查后插——先查后插在并发下两个线程会同时查不到，
     * 结果双双执行，用户就被重复下单或重复扣费。</p>
     *
     * <p>实现还需要把生成的主键回写进入参实体，调用方后续要凭它回填执行结果。</p>
   */
    int claimToolCallLog(ToolCallLogEntity entity);

    /**
 * 按幂等键取出已存在的那条调用日志，用于重复调用时决定重放什么结果。
     *
     * <p>抢执行权失败后必须能查到它；查不到说明抢锁和查询之间出现了异常情况，调用方会直接报错让模型重新推理，
     * 而不是冒险再执行一次。</p>
     */
    ToolCallLogEntity queryToolCallLogByIdempotencyKey(String idempotencyKey);

    /**
     * 把一条 started 日志推进到终态（成功或失败），并写入输出、错误信息和耗时。
*
     * <p>写库，返回影响行数；不等于 1 说明这条 started 记录没找到或已被别人改过，调用方会当成审计失败抛异常。</p>
     *
     * <p>这一步很关键：只有它执行成功，后续重试才能读到确定的终态并安全重放；
  * 一直停留在 started 的记录会让重试永远拿到「结果未知」，宁可不执行也不重复消耗。</p>
     */
    int finishToolCallLog(String idempotencyKey, String outputJson, String status,
                          String errorType, String errorMessage, Long costMs);

    /**
     * 查询一个会话里的全部工具调用明细，供界面展示「这段对话调用了什么、成功没有、花了多久」。
     *
  * <p>只读。必须同时按租户、用户、会话过滤，否则会把别人的调用记录连同其输入参数一起暴露出去。</p>
     */
    List<ToolCallLogEntity> queryToolCallLogs(String tenantId, String userId, String sessionId);

    /**
     * 汇总一个会话的工具使用情况，返回成功调用次数和去重后的工具个数。
     *
     * <p>只读，由 SQL 聚合完成，不把明细拉到内存。会话洞察用它给用户一句「本次对话用了 N 次工具」的概览。</p>
     */
    ToolCallStatisticsEntity summarizeToolCalls(String tenantId, String userId, String sessionId);

    /**
     * 查询一个会话里真正成功用过哪些工具，作为对外分享时要一并声明的依赖清单。
     *
   * <p>只读，按工具去重。分享出去的会话如果不声明依赖，接收方复现时会因为缺工具而得到完全不同的结果。
     * 同样必须按租户、用户、会话三者过滤，防止越权读取别人的会话依赖。</p>
     */
    List<SessionToolDependencyEntity> queryShareToolDependencies(String tenantId, String userId, String sessionId);
}
