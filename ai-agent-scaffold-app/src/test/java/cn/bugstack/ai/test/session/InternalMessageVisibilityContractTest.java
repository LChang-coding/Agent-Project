package cn.bugstack.ai.test.session;

import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/** 平台恢复输入保留审计，但不进入用户历史、分享和后续上下文。 */
public class InternalMessageVisibilityContractTest {
    @Test
    public void publicAndContextQueriesMustExcludePlatformRole() throws Exception {
        Path mapper = Path.of("../ai-agent-scaffold-infrastructure/src/main/resources/mybatis/mapper/chat_message_mapper.xml");
        if (!Files.exists(mapper)) mapper = Path.of("ai-agent-scaffold-infrastructure/src/main/resources/mybatis/mapper/chat_message_mapper.xml");
        String xml = Files.readString(mapper);
        for (String query : new String[]{"queryContextRange", "sumContextTokens", "queryValidMessages", "queryValidMessagesBefore"}) {
            int start = xml.indexOf("id=\"" + query + "\"");
            int end = xml.indexOf(query.startsWith("sum") ? "</select>" : "</select>", start);
            Assert.assertTrue("missing query " + query, start >= 0 && end > start);
            Assert.assertTrue("platform role leaked from " + query,
                    xml.substring(start, end).contains("role &lt;&gt; 'platform'"));
        }
    }
}
