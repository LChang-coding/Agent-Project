package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.tool.adapter.repository.IToolRepository;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolUserContextEntity;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 回答一个问题：这一轮对话，该把哪些工具摆到大模型面前？
 *
 * <p>所属层次：领域层服务，是工具「注册表查找」这一步的实现。这里没有静态注册表也没有 Map 缓存，
 * 工具清单每轮都从数据库现查——好处是发布、停用、改权限能立刻生效，不需要重启或重装配 Agent。</p>
 *
 * <p>谁调用它：{@code GatewayToolset}，在每次模型调用前调一次。</p>
 *
 * <p>它向下调用什么：只调工具仓储的可用工具查询。权限过滤和租户范围过滤都在那条 SQL 里一次完成，
 * 因此这里不需要再做二次筛选。</p>
 *
 * <p>找不到工具会怎样：返回空列表而不是抛异常。模型这一轮就看不到任何函数，只能用纯文本回答，
 * 这是安全的降级方式——宁可少一个能力，也不能凭空给模型一个未授权的工具。</p>
 *
 * <p>它不负责什么：不执行工具、不把工具包装成模型函数、不做幂等和审计，这些分别在
 * {@code ToolGateway}、{@code GatewayAdkTool} 里。</p>
 */
@Service
public class ToolResolver {

    /**
     * 工具仓储。可用工具查询会同时完成三件事：限定租户、按可见范围和所有者过滤、只保留有激活版本的已发布工具。
     *
     * <p>也就是说「能不能用这个工具」的判定被下沉到了 SQL 里，这里拿到的每一项都已经是授权过的。</p>
  */
    private final IToolRepository toolRepository;

    /**
     * 注入工具仓储，完成构造。
     *
     * <p>只做依赖装配，不预热也不缓存任何工具清单，保证每轮解析看到的都是数据库最新状态。</p>
     */
    public ToolResolver(IToolRepository toolRepository) {
        // 只保存仓储引用，不预热也不缓存，保证每轮解析读到的都是数据库最新状态。
this.toolRepository = toolRepository;
    }

    /**
     * 按可信身份解析出这一轮可以交给大模型的工具目录。
     *
     * <p>输入是租户和用户身份；返回的每一项都已授权，调用方可以直接包装给模型。</p>
     *
   * <p>身份不完整时选择「失败关闭」而不是返回空列表：空列表意味着静默降级，
     * 排查时很难发现是身份丢了；直接抛异常能让问题立刻暴露，也避免在缺少租户时误查到全量数据。</p>
     *
     * <p>只读，不写库、不改状态、不发事件。</p>
     */
    public List<ToolCatalogEntity> resolve(ToolUserContextEntity context) {
        // 租户或用户任一缺失就立刻中止：没有租户无法隔离数据，没有用户无法判断私有工具归属，继续查下去等于放开权限。
        if (context == null || blank(context.getTenantId()) || blank(context.getUserId())) {
            // 抛业务异常让上层明确失败，而不是悄悄给模型一个空工具列表。
            throw new AppException("TOOL_CONTEXT_INVALID", "工具运行身份不完整");
        }
// 现查数据库：只返回该用户有权使用、已发布且有激活版本的工具，因此发布和停用能在下一轮立刻生效。
        return toolRepository.queryAvailableTools(context.getTenantId(), context.getUserId());
    }

    /**
     * 判断一个身份字段是否缺失（空引用或全是空白字符）。
     *
     * <p>之所以连空白串也算缺失：上游传过来的可能是空字符串或空格，若只判 null，
     * 这类值会被当成合法租户带进 SQL，查出来的结果完全不可预期。</p>
     */
    private boolean blank(String value) {
        // 空引用和纯空白都算缺失，防止空串被当成合法租户带进查询条件。
        return value == null || value.isBlank();
    }
}
