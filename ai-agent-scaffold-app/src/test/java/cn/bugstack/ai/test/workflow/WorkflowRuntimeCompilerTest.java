package cn.bugstack.ai.test.workflow;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowGraphEntity;
import cn.bugstack.ai.domain.workflow.service.WorkflowRuntimeCompiler;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

/**
 * 工作流运行时编译器测试。
 */
public class WorkflowRuntimeCompilerTest {

    private static final String ADK_AGENT_NAME_REGEX = "^_?[a-zA-Z0-9]*([. _-][a-zA-Z0-9]+)*$";

    /**
     * 校验中文展示名不进入运行时名称；无参数；验证节点 Agent 名称符合 ADK 规则。
     */
    @Test
    public void shouldUseNodeIdAsRuntimeNameWhenDisplayNameIsChinese() throws Exception {
        WorkflowRuntimeCompiler compiler = new WorkflowRuntimeCompiler();
        WorkflowGraphEntity.Node node = WorkflowGraphEntity.Node.builder()
                .nodeId("llm-node-1")
                .nodeType("llm")
                .name("智能体节点")
                .build();

        String nodeAgentName = invokeNodeAgentName(compiler, node);

        Assert.assertEquals("node_llm_node_1", nodeAgentName);
        Assert.assertTrue(nodeAgentName.matches(ADK_AGENT_NAME_REGEX));
    }

    /**
     * 校验运行时 Agent ID 安全化；无参数；验证模型编码里的分隔符被转成稳定名称。
     */
    @Test
    public void shouldBuildSafeRuntimeAgentId() {
        WorkflowRuntimeCompiler compiler = new WorkflowRuntimeCompiler();

        String runtimeAgentId = compiler.runtimeAgentId("workflow-abc-001", 2, "deepseek-v4-flash");

        Assert.assertEquals("wf_workflow_abc_001_v2_deepseek_v4_flash", runtimeAgentId);
        Assert.assertTrue(runtimeAgentId.matches(ADK_AGENT_NAME_REGEX));
    }

    /**
     * 反射读取节点运行时名称；参数是编译器和节点；返回内部生成的 Agent 名称。
     */
    private String invokeNodeAgentName(WorkflowRuntimeCompiler compiler, WorkflowGraphEntity.Node node) throws Exception {
        Method method = WorkflowRuntimeCompiler.class.getDeclaredMethod("nodeAgentName", WorkflowGraphEntity.Node.class);
        method.setAccessible(true);
        return (String) method.invoke(compiler, node);
    }
}
