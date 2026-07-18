package cn.bugstack.ai.test.rag;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.Assert;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Outbox 全局扫描与租户 CAS SQL 契约测试。 */
public class RagOutboxMapperContractTest {

    @Test
    public void shouldExposeOnlyIdentifiersInGlobalScanAndScopeEveryMutation() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mybatis/mapper/rag_outbox_mapper.xml";
        try (var input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        String namespace = "cn.bugstack.ai.infrastructure.dao.IRagOutboxDao.";
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", "tenant-a");
        parameters.put("eventId", "event-1");
        parameters.put("leaseOwner", "worker-1");
        parameters.put("fencingToken", 5L);
        parameters.put("now", LocalDateTime.now());
        parameters.put("leaseUntil", LocalDateTime.now().plusSeconds(30));
        parameters.put("publishedAt", LocalDateTime.now());
        parameters.put("nextRetryAt", LocalDateTime.now().plusSeconds(1));
        parameters.put("errorMessage", "safe");
        parameters.put("limit", 20);

        String scan = sql(configuration, namespace + "queryDueCandidates", parameters);
        String projection = scan.substring(0, scan.indexOf(" from "));
        Assert.assertTrue(projection.contains("tenant_id as tenantid"));
        Assert.assertTrue(projection.contains("event_id as eventid"));
        Assert.assertFalse(projection.contains("payload"));
        Assert.assertFalse(projection.contains("topic_name"));
        Assert.assertFalse(projection.contains("task_id"));

        for (String method : new String[]{"claimDue", "markPublished", "markRetrying", "markDead"}) {
            String mutation = sql(configuration, namespace + method, parameters);
            Assert.assertTrue(method + " 必须限定租户", mutation.contains("tenant_id = ?"));
            Assert.assertTrue(method + " 必须限定事件", mutation.contains("event_id = ?"));
        }
        Assert.assertTrue(sql(configuration, namespace + "markPublished", parameters)
                .contains("fencing_token = ?"));
    }

    private String sql(Configuration configuration, String statementId, Map<String, Object> parameters) {
        return configuration.getMappedStatement(statementId).getBoundSql(parameters).getSql()
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
