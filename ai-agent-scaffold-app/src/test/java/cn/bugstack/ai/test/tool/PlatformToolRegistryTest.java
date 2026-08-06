package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.service.PlatformToolHandler;
import cn.bugstack.ai.domain.tool.service.PlatformToolRegistry;
import cn.bugstack.ai.domain.tool.service.PlatformToolResult;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

public class PlatformToolRegistryTest {

    @Test
    public void dispatchesRegisteredHandlerAndReturnsStructuredResult() {
        PlatformToolRegistry registry = new PlatformToolRegistry();
        registry.register("rag_retrieve", (tool, input, context) ->
                PlatformToolResult.success(Map.of("retrievalId", "ret-1", "hits", 2)));

        PlatformToolResult result = registry.dispatch(
                ToolCatalogEntity.builder().toolType("platform").functionName("rag_retrieve").build(),
                Map.of("query", "q"), ToolInvokeContextEntity.builder().tenantId("tenant").build());

        Assert.assertEquals("ret-1", result.modelResult().get("retrievalId"));
        Assert.assertEquals(2, result.modelResult().get("hits"));
    }

    @Test
    public void failsClosedWhenHandlerIsMissingOrNameIsDuplicated() {
        PlatformToolRegistry registry = new PlatformToolRegistry();
        PlatformToolHandler handler = (tool, input, context) -> PlatformToolResult.success(Map.of());
        registry.register("select_workflow_route", handler);

        try {
            registry.register("select_workflow_route", handler);
            Assert.fail("duplicate platform handler must fail closed");
        } catch (RuntimeException expected) {
            // expected
        }

        try {
            registry.dispatch(ToolCatalogEntity.builder().functionName("unknown").build(), Map.of(),
                    ToolInvokeContextEntity.builder().tenantId("tenant").build());
            Assert.fail("missing platform handler must fail closed");
        } catch (RuntimeException expected) {
            // expected
        }
    }
}
