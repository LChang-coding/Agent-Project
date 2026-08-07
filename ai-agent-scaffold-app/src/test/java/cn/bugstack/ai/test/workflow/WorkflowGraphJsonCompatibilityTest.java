package cn.bugstack.ai.test.workflow;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowGraphEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class WorkflowGraphJsonCompatibilityTest {

    @Test
    public void readsPublishedGraphWithRoutingProtocolAndFutureFields() throws Exception {
        String json = """
                {
                  "routingProtocolVersion":"MARKER_V1",
                  "workflowKind":"INTELLIGENT",
                  "futureGraphField":"ignored",
                  "nodes":[{
                    "nodeId":"classify",
                    "nodeType":"llm",
                    "mcpIds":[],
                    "futureNodeField":"ignored"
                  }],
                  "edges":[{
                    "edgeId":"edge-1",
                    "sourceNodeId":"classify",
                    "targetNodeId":"END",
                    "routeType":"DEFAULT",
                    "futureEdgeField":"ignored"
                  }]
                }
                """;

        WorkflowGraphEntity graph = new ObjectMapper().readValue(json, WorkflowGraphEntity.class);

        assertEquals("MARKER_V1", graph.getRoutingProtocolVersion());
        assertEquals("INTELLIGENT", graph.getWorkflowKind());
        assertNotNull(graph.getNodes());
        assertEquals("classify", graph.getNodes().get(0).getNodeId());
        assertEquals("edge-1", graph.getEdges().get(0).getEdgeId());
    }
}
