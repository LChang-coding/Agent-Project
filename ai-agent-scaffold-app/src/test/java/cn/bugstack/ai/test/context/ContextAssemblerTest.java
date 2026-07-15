package cn.bugstack.ai.test.context;

import cn.bugstack.ai.domain.context.model.ContextBudget;
import cn.bugstack.ai.domain.context.model.ContextFragment;
import cn.bugstack.ai.domain.context.model.ContextFragmentType;
import cn.bugstack.ai.domain.context.service.CharacterTokenCounter;
import cn.bugstack.ai.domain.context.service.ContextAssembler;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * 上下文组装器测试。
 */
public class ContextAssemblerTest {

    /**
     * 校验高优先级片段优先保留；无参数；验证超出预算时低优先级片段被裁剪。
     */
    @Test
    public void shouldKeepMemoryAndRecentConversationBeforeRagWhenBudgetIsLimited() {
        ContextAssembler assembler = new ContextAssembler(new CharacterTokenCounter());
        ContextBudget budget = new ContextBudget(24, 0, 0, 0, 0);

        List<ContextFragment> result = assembler.assemble(budget, List.of(
                ContextFragment.of(ContextFragmentType.RAG, "检索资料检索资料检索资料检索资料", 10),
                ContextFragment.of(ContextFragmentType.RECENT_CONVERSATION, "最近对话最近对话最近对话", 20),
                ContextFragment.of(ContextFragmentType.LONG_TERM_MEMORY, "长期记忆长期记忆长期记忆", 30)
        ));

        Assert.assertEquals(2, result.size());
        Assert.assertEquals(ContextFragmentType.LONG_TERM_MEMORY, result.get(0).getType());
        Assert.assertEquals(ContextFragmentType.RECENT_CONVERSATION, result.get(1).getType());
    }

    /**
     * 校验片段上限按实际注入量执行；无参数；验证超出片段预算的完整内容不会穿透总预算。
     */
    @Test
    public void shouldDropOversizedFragmentInsteadOfChargingOnlyDeclaredMaximum() {
        CharacterTokenCounter counter = new CharacterTokenCounter();
        ContextAssembler assembler = new ContextAssembler(counter);
        String oversized = "超长上下文".repeat(100);

        List<ContextFragment> result = assembler.assemble(new ContextBudget(100, 0, 0, 0, 0), List.of(
                ContextFragment.of(ContextFragmentType.LONG_TERM_MEMORY, oversized, 20),
                ContextFragment.of(ContextFragmentType.RECENT_CONVERSATION, "短消息", 20)));

        Assert.assertEquals(1, result.size());
        Assert.assertEquals(ContextFragmentType.RECENT_CONVERSATION, result.get(0).getType());
        Assert.assertTrue(result.stream().mapToInt(fragment -> counter.estimate(fragment.getContent())).sum() <= 100);
    }
}
