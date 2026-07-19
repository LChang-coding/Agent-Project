package cn.bugstack.ai.test.rag;

import org.junit.Assert;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** RAG摄取任务Mapper的强租户与有界列表契约测试。 */
public class RagIngestTaskMapperContractTest {

    @Test
    public void shouldKeepKnowledgeBaseTaskListTenantScopedOrderedAndBounded() throws Exception {
        String resource = "mybatis/mapper/rag_ingest_task_mapper.xml";
        try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            Assert.assertNotNull("找不到RAG摄取任务Mapper", stream);
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").toLowerCase();
            String marker = "id=\"querylistbytenantandknowledgebaseid\"";
            int start = xml.indexOf(marker);
            Assert.assertTrue("缺少知识库任务列表查询", start >= 0);
            int end = xml.indexOf("</select>", start);
            String query = xml.substring(start, end);
            Assert.assertTrue(query.contains("tenant_id = #{tenantid}"));
            Assert.assertTrue(query.contains("kb_id = #{knowledgebaseid}"));
            Assert.assertTrue(query.contains("deleted = 0"));
            Assert.assertTrue(query.contains("order by id desc"));
            Assert.assertTrue(query.contains("limit #{limit}"));
        }
    }
}
