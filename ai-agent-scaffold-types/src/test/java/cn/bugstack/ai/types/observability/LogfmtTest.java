package cn.bugstack.ai.types.observability;

import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class LogfmtTest {

    private static final Pattern LOG_TRACE_PREFIX = Pattern.compile("^logId=[0-9a-fA-F-]{36} traceId=[0-9a-fA-F-]{36} ");

    @Test
    public void shouldFormatValuesWithStableOrderAndEscaping() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event", "db_query");
        fields.put("message", "hello \"grafana\"\nline2");
        fields.put("costMs", 12L);
        fields.put("success", true);
        fields.put("empty", "");
        fields.put("none", null);

        String actual = Logfmt.format(fields);

        Assert.assertEquals("event=db_query message=\"hello \\\"grafana\\\"\\nline2\" costMs=12 success=true empty=\"\" none=null", actual);
    }

    @Test
    public void shouldSkipNullByDefaultAndKeepExplicitNullableField() {
        AiLogRecord record = AiLogRecord.event(AiLogEvent.TOKEN_USAGE)
                .field("totalTokens", 100)
                .field("promptTokens", null)
                .nullableField("thoughtsTokens", null);

        assertTraceIdAndLogBody("event=token_usage domain=model eventName=\"模型Token用量已记录\" "
                + "message=\"模型Token用量已记录\" totalTokens=100 thoughtsTokens=null", record.toLogfmt());
    }

    private void assertTraceIdAndLogBody(String expectedBody, String actual) {
        Assert.assertTrue("logId and traceId should be the first fields: " + actual, LOG_TRACE_PREFIX.matcher(actual).find());
        Assert.assertEquals(expectedBody, LOG_TRACE_PREFIX.matcher(actual).replaceFirst(""));
    }
}
