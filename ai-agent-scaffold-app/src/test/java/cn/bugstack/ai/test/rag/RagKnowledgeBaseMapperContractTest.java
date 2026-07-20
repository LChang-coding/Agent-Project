package cn.bugstack.ai.test.rag;

import org.junit.Assert;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 知识库审计墓碑不得重新出现在租户列表。 */
public class RagKnowledgeBaseMapperContractTest {

    @Test
    public void listQueriesMustExcludeDeletedStatusTombstones() throws Exception {
        String resource = "mybatis/mapper/rag_knowledge_base_mapper.xml";
        try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            Assert.assertNotNull(resource, stream);
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Assert.assertEquals("两个知识库列表查询都必须过滤状态墓碑", 2,
                    occurrences(xml, "status &lt;&gt; 'deleted'"));
        }
    }

    private int occurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
