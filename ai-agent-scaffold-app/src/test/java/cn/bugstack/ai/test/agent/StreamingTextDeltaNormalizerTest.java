package cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 兼容累计快照和标准 delta 两种 OpenAI 流式返回。 */
public class StreamingTextDeltaNormalizerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void shouldConvertCumulativeSnapshotsWithoutBreakingRealDeltas() throws Exception {
        StreamingTextDeltaNormalizer normalizer = new StreamingTextDeltaNormalizer();

        assertFrame(normalizer, "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"先\",\"content\":\"\"}}]}", "先", "");
        assertFrame(normalizer, "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"先思考\",\"content\":\"答\"}}]}", "思考", "答");
        assertFrame(normalizer, "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"更多\",\"content\":\"案\"}}]}", "更多", "案");
    }

    private void assertFrame(StreamingTextDeltaNormalizer normalizer, String json,
                             String reasoning, String answer) throws Exception {
        ObjectNode normalized = normalizer.normalize((ObjectNode) mapper.readTree(json));
        ObjectNode delta = (ObjectNode) normalized.path("choices").get(0).path("delta");
        assertEquals(reasoning, delta.path("reasoning_content").asText());
        assertEquals(answer, delta.path("content").asText());
    }
}
