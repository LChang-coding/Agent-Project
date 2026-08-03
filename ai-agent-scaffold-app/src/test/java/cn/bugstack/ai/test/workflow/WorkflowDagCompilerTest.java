package cn.bugstack.ai.test.workflow;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowDagCompileResultEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowDagPlanEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowGraphEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowMcpToolEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowOptionEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowSkillToolEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowVersionEntity;
import cn.bugstack.ai.domain.workflow.service.ModelRouter;
import cn.bugstack.ai.domain.workflow.service.WorkflowRuntimeCompiler;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作流 DAG 编译器测试。
 */
public class WorkflowDagCompilerTest {

    /**
     * 校验混合 DAG 编译；无参数；验证自循环、并行和汇聚边会保留在执行计划中。
     */
    @Test
    public void shouldCompileLoopParallelJoinGraphToDagPlan() throws Exception {
        WorkflowRuntimeCompiler compiler = compiler();

        WorkflowDagCompileResultEntity result = compiler.compileDag(workflow(), version(mixedDagGraph()), "deepseek-v4-flash");

        Assert.assertEquals(4, result.getTables().size());
        Assert.assertEquals("node_start", result.getDagPlan().getRootNodeId());
        Assert.assertEquals(4, result.getDagPlan().getNodes().size());
        Assert.assertEquals(5, result.getDagPlan().getEdges().size());
        Set<String> runtimeAgentIds = result.getDagPlan().getNodes().stream()
                .map(WorkflowDagPlanEntity.Node::getRuntimeAgentId)
                .collect(Collectors.toSet());
        Assert.assertEquals("长工作流ID不能导致节点运行时 Bean 名称撞车", 4, runtimeAgentIds.size());
        Assert.assertTrue(result.getDagPlan().getEdges().stream()
                .anyMatch(edge -> "node_start".equals(edge.getSourceNodeId()) && "node_start".equals(edge.getTargetNodeId())));
    }

    /**
     * 校验非法环路；无参数；验证非自循环环会被拒绝。
     */
    @Test
    public void shouldRejectNonSelfCycleGraph() throws Exception {
        WorkflowRuntimeCompiler compiler = compiler();

        try {
            compiler.compileDag(workflow(), version(cycleGraph()), "deepseek-v4-flash");
            Assert.fail("非自循环环路不应该通过 DAG 编译");
        } catch (AppException e) {
            Assert.assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), e.getCode());
        }
    }

    @Test
    public void shouldCompileBoundedIntelligentCycle() throws Exception {
        WorkflowRuntimeCompiler compiler = compiler();
        WorkflowGraphEntity graph = cycleGraph();
        graph.setWorkflowKind("INTELLIGENT");
        graph.setMaxSteps(12);
        graph.setTokenBudget(20_000L);
        graph.getNodes().forEach(node -> {
            node.setMaxVisits(4);
            node.setAllowedTargetNodeIds(List.of("node_a", "node_b", "END"));
            node.setEnabledStrategies(List.of("DEFAULT"));
        });
        graph.getEdges().forEach(edge -> edge.setRouteType("DEFAULT"));

        WorkflowDagCompileResultEntity result = compiler.compileDag(workflow(), version(graph), "deepseek-v4-flash");

        Assert.assertEquals("INTELLIGENT", result.getDagPlan().getWorkflowKind());
        Assert.assertEquals(Integer.valueOf(12), result.getDagPlan().getMaxSteps());
        Assert.assertEquals(2, result.getDagPlan().getEdges().size());
    }

    /**
     * 创建测试编译器；无参数；返回注入假依赖的编译器。
     */
    private WorkflowRuntimeCompiler compiler() throws Exception {
        WorkflowRuntimeCompiler compiler = new WorkflowRuntimeCompiler();
        setField(compiler, "aiAgentAutoConfigProperties", properties());
        setField(compiler, "workflowRepository", new FakeWorkflowRepository());
        setField(compiler, "modelRouter", new ModelRouter());
        return compiler;
    }

    /**
     * 创建工作流；无参数；返回固定工作流实体。
     */
    private WorkflowEntity workflow() {
        return WorkflowEntity.builder()
                .tenantId("tenant_1")
                .workflowId("wf_123e4567-e89b-12d3-a456-426614174000")
                .workflowName("测试 DAG 工作流")
                .description("测试")
                .build();
    }

    /**
     * 创建版本；参数是画布；返回版本实体。
     */
    private WorkflowVersionEntity version(WorkflowGraphEntity graph) {
        return WorkflowVersionEntity.builder()
                .version(1)
                .defaultModelCode("deepseek-v4-flash")
                .graph(graph)
                .build();
    }

    /**
     * 创建混合 DAG 图；无参数；返回自循环、并行和汇聚图。
     */
    private WorkflowGraphEntity mixedDagGraph() {
        return WorkflowGraphEntity.builder()
                .mode("loop")
                .rootNodeId("node_start")
                .nodes(List.of(
                        node("node_start", "智能体节点", 3),
                        node("node_1783238083777_2", "并行1", 3),
                        node("node_1783238099691_3", "并行2", 3),
                        node("node_1783238145482_4", "结束", 3)
                ))
                .edges(List.of(
                        edge("node_start", "node_start"),
                        edge("node_start", "node_1783238083777_2"),
                        edge("node_start", "node_1783238099691_3"),
                        edge("node_1783238083777_2", "node_1783238145482_4"),
                        edge("node_1783238099691_3", "node_1783238145482_4")
                ))
                .build();
    }

    /**
     * 创建非法环图；无参数；返回 A->B->A 图。
     */
    private WorkflowGraphEntity cycleGraph() {
        return WorkflowGraphEntity.builder()
                .mode("sequential")
                .rootNodeId("node_a")
                .nodes(List.of(node("node_a", "A", 3), node("node_b", "B", 3)))
                .edges(List.of(edge("node_a", "node_b"), edge("node_b", "node_a")))
                .build();
    }

    /**
     * 创建节点；参数是节点ID、名称和循环次数；返回节点。
     */
    private WorkflowGraphEntity.Node node(String nodeId, String name, Integer maxIterations) {
        return WorkflowGraphEntity.Node.builder()
                .nodeId(nodeId)
                .nodeType("llm")
                .name(name)
                .instruction("测试")
                .modelCode("deepseek-v4-flash")
                .mcpIds(Collections.emptyList())
                .skillIds(Collections.emptyList())
                .maxIterations(maxIterations)
                .build();
    }

    /**
     * 创建边；参数是起点和终点；返回边。
     */
    private WorkflowGraphEntity.Edge edge(String sourceNodeId, String targetNodeId) {
        return WorkflowGraphEntity.Edge.builder()
                .edgeId("edge_" + sourceNodeId + "_" + targetNodeId)
                .sourceNodeId(sourceNodeId)
                .targetNodeId(targetNodeId)
                .build();
    }

    /**
     * 创建模型通道配置；无参数；返回最小可编译配置。
     */
    private AiAgentAutoConfigProperties properties() {
        AiAgentConfigTableVO table = new AiAgentConfigTableVO();
        AiAgentConfigTableVO.Module module = new AiAgentConfigTableVO.Module();
        AiAgentConfigTableVO.Module.AiApi aiApi = new AiAgentConfigTableVO.Module.AiApi();
        aiApi.setBaseUrl("http://127.0.0.1");
        aiApi.setApiKey("test");
        module.setAiApi(aiApi);
        table.setModule(module);

        AiAgentAutoConfigProperties properties = new AiAgentAutoConfigProperties();
        properties.setTables(Map.of("test", table));
        return properties;
    }

    /**
     * 注入字段；参数是目标、字段名和值；无返回值。
     */
    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class FakeWorkflowRepository implements IWorkflowRepository {

        /**
         * 新增工作流；参数是工作流实体；返回影响行数。
         */
        @Override
        public int insertWorkflow(WorkflowEntity workflow) {
            return 0;
        }

        /**
         * 更新工作流；参数是工作流实体；返回影响行数。
         */
        @Override
        public int updateWorkflow(WorkflowEntity workflow) {
            return 0;
        }

        /**
         * 查询工作流；参数是租户和工作流ID；返回工作流实体。
         */
        @Override
        public WorkflowEntity queryWorkflow(String tenantId, String workflowId) {
            return null;
        }

        /**
         * 查询租户工作流列表；参数是租户ID；返回工作流实体列表。
         */
        @Override
        public List<WorkflowEntity> queryWorkflowList(String tenantId) {
            return Collections.emptyList();
        }

        @Override
        public int softDeleteWorkflow(String tenantId, String workflowId, String deletedBy) {
            return 0;
        }

        /**
         * 新增版本；参数是版本实体；返回影响行数。
         */
        @Override
        public int insertVersion(WorkflowVersionEntity version) {
            return 0;
        }

        /**
         * 更新版本；参数是版本实体；返回影响行数。
         */
        @Override
        public int updateVersion(WorkflowVersionEntity version) {
            return 0;
        }

        /**
         * 查询指定版本；参数是租户、工作流ID和版本；返回版本实体。
         */
        @Override
        public WorkflowVersionEntity queryVersion(String tenantId, String workflowId, Integer version) {
            return null;
        }

        /**
         * 查询最新草稿版本；参数是租户和工作流ID；返回版本实体。
         */
        @Override
        public WorkflowVersionEntity queryLatestDraft(String tenantId, String workflowId) {
            return null;
        }

        /**
         * 查询最新发布版本；参数是租户和工作流ID；返回版本实体。
         */
        @Override
        public WorkflowVersionEntity queryLatestPublished(String tenantId, String workflowId) {
            return null;
        }

        /**
         * 查询最大版本号；参数是租户和工作流ID；返回版本号。
         */
        @Override
        public Integer queryMaxVersion(String tenantId, String workflowId) {
            return 0;
        }

        /**
         * 查询可用 MCP 选项；参数是租户ID；返回选项列表。
         */
        @Override
        public List<WorkflowOptionEntity> queryMcpOptions(String tenantId) {
            return Collections.emptyList();
        }

        /**
         * 查询可用 Skill 选项；参数是租户ID；返回选项列表。
         */
        @Override
        public List<WorkflowOptionEntity> querySkillOptions(String tenantId) {
            return Collections.emptyList();
        }

        /**
         * 查询 MCP 工具配置；参数是租户ID和 MCP ID；返回工具配置列表。
         */
        @Override
        public List<WorkflowMcpToolEntity> queryMcpTools(String tenantId, List<String> mcpIds) {
            return Collections.emptyList();
        }

        /**
         * 查询 Skill 工具配置；参数是租户ID和 Skill ID；返回工具配置列表。
         */
        @Override
        public List<WorkflowSkillToolEntity> querySkillTools(String tenantId, List<String> skillIds) {
            return Collections.emptyList();
        }
    }
}
