package cn.bugstack.ai.test.rag;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** 会话和运行 RAG 调用方式的 MyBatis 全链合同。 */
public class RagInvocationPersistenceMapperContractTest {

    @Test
    public void shouldReadWriteAndUpdateSessionInvocationMode() throws Exception {
        String mapper = resource("mybatis/mapper/chat_session_mapper.xml");
        Assert.assertTrue(mapper.contains("column=\"rag_invocation_mode\" property=\"ragInvocationMode\""));
        Assert.assertTrue(mapper.contains("rag_mode, rag_invocation_mode, rag_revision"));
        Assert.assertTrue(mapper.contains("rag_invocation_mode = #{ragInvocationMode}"));
    }

    @Test
    public void shouldReadAndFreezeRunInvocationMode() throws Exception {
        String mapper = resource("mybatis/mapper/chat_run_mapper.xml");
        Assert.assertTrue(mapper.contains("column=\"rag_invocation_mode\" property=\"ragInvocationMode\""));
        Assert.assertTrue(mapper.contains("rag_mode, rag_invocation_mode, rag_policy_revision"));
        Assert.assertTrue(mapper.contains("#{ragMode}, #{ragInvocationMode}, #{ragPolicyRevision}"));
    }

    private String resource(String name) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
            Assert.assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
