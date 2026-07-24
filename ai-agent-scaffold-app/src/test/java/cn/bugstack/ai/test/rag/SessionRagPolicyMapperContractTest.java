package cn.bugstack.ai.test.rag;

import org.junit.Assert;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 会话RAG策略Mapper的租户范围与并发更新契约测试。
 */
public class SessionRagPolicyMapperContractTest {

    @Test
    public void shouldKeepSessionPolicyUpdateRevisionScoped() throws Exception {
        String mapper = mapper("mybatis/mapper/chat_session_mapper.xml");
        String update = statement(mapper, "id=\"updateragpolicy\"", "</update>");

        Assert.assertTrue(update.contains("rag_revision = rag_revision + 1"));
        Assert.assertTrue(update.contains("rag_revision = #{expectedrevision}"));
        Assert.assertTrue(update.contains("user_id = #{userid}"));
        Assert.assertTrue(update.contains("session_id = #{sessionid}"));
        Assert.assertTrue(update.contains("tenant_id = #{tenantid}"));
    }

    @Test
    public void shouldKeepManualSelectionReadAndDeleteTrustedSessionScoped() throws Exception {
        String mapper = mapper("mybatis/mapper/session_rag_binding_selection_mapper.xml");
        String query = statement(mapper, "id=\"querybysession\"", "</select>");
        String delete = statement(mapper, "id=\"deletebysession\"", "</delete>");

        for (String sql : java.util.List.of(query, delete)) {
            Assert.assertTrue(sql.contains("user_id = #{userid}"));
            Assert.assertTrue(sql.contains("session_id = #{sessionid}"));
            Assert.assertTrue(sql.contains("tenant_id = #{tenantid}"));
        }
        Assert.assertTrue(query.contains("order by selection_order asc, id asc"));
    }

    @Test
    public void shouldPersistFrozenRagPolicyInEveryRunRow() throws Exception {
        String mapper = mapper("mybatis/mapper/chat_run_mapper.xml");
        String insert = statement(mapper, "id=\"insert\"", "</insert>");
        String columns = statement(mapper, "id=\"chatruncolumns\"", "</sql>");

        for (String column : java.util.List.of(
                "rag_enabled", "rag_mode", "rag_policy_revision", "rag_binding_ids_json")) {
            Assert.assertTrue("运行快照缺少字段: " + column, columns.contains(column));
            Assert.assertTrue("运行写入缺少字段: " + column, insert.contains(column));
        }
        for (String parameter : java.util.List.of(
                "#{ragenabled}", "#{ragmode}", "#{ragpolicyrevision}", "#{ragbindingidsjson}")) {
            Assert.assertTrue("运行写入缺少参数: " + parameter, insert.contains(parameter));
        }
    }

    private String mapper(String resource) throws Exception {
        try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            Assert.assertNotNull("找不到Mapper: " + resource, stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").toLowerCase();
        }
    }

    private String statement(String xml, String marker, String close) {
        int start = xml.indexOf(marker);
        Assert.assertTrue("缺少Mapper语句: " + marker, start >= 0);
        int end = xml.indexOf(close, start);
        Assert.assertTrue("Mapper语句未闭合: " + marker, end > start);
        return xml.substring(start, end);
    }
}
