package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.model.valobj.RagInvocationMode;
import org.junit.Assert;
import org.junit.Test;

/** RAG 调用方式的兼容解析合同。 */
public class RagInvocationModeTest {

    @Test
    public void shouldResolveKnownValueIgnoringCaseAndWhitespace() {
        Assert.assertEquals(RagInvocationMode.AGENT_TOOL, RagInvocationMode.resolve(" agent_tool "));
    }

    @Test
    public void shouldFallBackToAutoContextForMissingOrUnknownValue() {
        Assert.assertEquals(RagInvocationMode.AUTO_CONTEXT, RagInvocationMode.resolve(null));
        Assert.assertEquals(RagInvocationMode.AUTO_CONTEXT, RagInvocationMode.resolve("future_mode"));
    }
}
