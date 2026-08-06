package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.service.GatewayToolset;
import cn.bugstack.ai.domain.tool.service.PlatformToolResolver;
import cn.bugstack.ai.domain.tool.service.ToolGateway;
import cn.bugstack.ai.domain.tool.service.ToolResolver;
import com.google.adk.agents.ReadonlyContext;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GatewayToolsetConflictTest {

    @Test(expected = IllegalStateException.class)
    public void rejectsNamesThatCollideAfterAdkLengthNormalization() {
        ToolResolver toolResolver = mock(ToolResolver.class);
        String commonPrefix = "a".repeat(70);
        when(toolResolver.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                ToolCatalogEntity.builder().toolType("skill").toolId("one").toolCode(commonPrefix + "1").build(),
                ToolCatalogEntity.builder().toolType("skill").toolId("two").toolCode(commonPrefix + "2").build()));
        ReadonlyContext readonlyContext = mock(ReadonlyContext.class);
        when(readonlyContext.state()).thenReturn(Map.of("tenantId", "tenant", "userId", "user"));
        when(readonlyContext.userId()).thenReturn("user");
        when(readonlyContext.sessionId()).thenReturn("session");
        when(readonlyContext.invocationId()).thenReturn("invocation");

        GatewayToolset toolset = new GatewayToolset(toolResolver, mock(ToolGateway.class),
                new PlatformToolResolver(false, false));

        toolset.getTools(readonlyContext).toList().blockingGet();
    }
}
