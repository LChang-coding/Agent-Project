package cn.bugstack.ai.types.observability;

import org.junit.Assert;
import org.junit.Test;

import java.util.regex.Pattern;

public class DomainLogTest {

    private static final Pattern LOG_TRACE_PREFIX = Pattern.compile("^logId=[0-9a-fA-F-]{36} traceId=[0-9a-fA-F-]{36} ");

    @Test
    public void shouldBuildCompatibleTokenUsageLog() {
        String actual = AiLog.model()
                .tokenUsage("u1", "s1", "onlyAgent", "testAgent03", "inv-1", "deepseek-v4-flash",
                        10, 20, 30, null, null, false, true)
                .toLogfmt();

        assertTraceIdAndLogBody("event=token_usage domain=model userId=u1 sessionId=s1 agentName=onlyAgent appName=testAgent03 invocationId=inv-1 modelVersion=deepseek-v4-flash promptTokens=10 candidateTokens=20 totalTokens=30 thoughtsTokens=null toolUsePromptTokens=null partial=false turnComplete=true", actual);
    }

    @Test
    public void shouldBuildDomainLogsForFutureMiddleware() {
        assertTraceIdAndLogBody("event=db_query domain=db database=mysql operation=select table=agent_session rows=1 costMs=8 success=true",
                AiLog.db().query("mysql", "select", "agent_session", 1, 8L, true).toLogfmt());
        assertTraceIdAndLogBody("event=auth_login_success domain=auth tenantId=t1 userId=u1 username=codeliu roleCode=owner success=true",
                AiLog.auth().loginSuccess("t1", "u1", "codeliu", "owner").toLogfmt());
        assertTraceIdAndLogBody("event=redis_command domain=redis command=GET key=session:s1 hit=true costMs=2 success=true",
                AiLog.redis().command("GET", "session:s1", true, 2L, true).toLogfmt());
        assertTraceIdAndLogBody("event=http_request domain=http method=POST uri=/api/v1/chat status=200 costMs=18 success=true",
                AiLog.http().request("POST", "/api/v1/chat", 200, 18L, true).toLogfmt());
        assertTraceIdAndLogBody("event=rag_retrieve domain=rag userId=u1 sessionId=s1 knowledgeBase=kb-agent queryId=q1 topK=5 hits=3 costMs=21 success=true",
                AiLog.rag().retrieve("u1", "s1", "kb-agent", "q1", 5, 3, 21L, true).toLogfmt());
        assertTraceIdAndLogBody("event=oss_upload domain=oss bucket=agent-files objectKey=demo.txt bytes=128 costMs=13 success=true",
                AiLog.oss().upload("agent-files", "demo.txt", 128L, 13L, true).toLogfmt());
        assertTraceIdAndLogBody("event=scheduler_done domain=scheduler jobName=daily-summary triggerType=cron runId=run-1 costMs=44 success=true",
                AiLog.scheduler().done("daily-summary", "cron", "run-1", 44L, true).toLogfmt());
    }

    @Test
    public void shouldBuildErrorLogWithTypeAndMessage() {
        String actual = AiLog.db()
                .error("mysql", "insert", "agent_message", 19L, new IllegalStateException("db timeout"))
                .toLogfmt();

        assertTraceIdAndLogBody("event=db_error domain=db database=mysql operation=insert table=agent_message costMs=19 success=false errorType=IllegalStateException errorMessage=\"db timeout\"", actual);
    }

    @Test
    public void shouldBuildAuthFailureLogWithoutSecrets() {
        String actual = AiLog.auth()
                .loginFailed("codeliu", "AUTH_LOGIN_FAILED", "用户名或密码错误")
                .toLogfmt();

        assertTraceIdAndLogBody("event=auth_login_failed domain=auth username=codeliu errorCode=AUTH_LOGIN_FAILED errorMessage=\"用户名或密码错误\" success=false", actual);
        Assert.assertFalse(actual.contains("password"));
        Assert.assertFalse(actual.contains("token"));
    }

    /**
     * 校验续期日志；无参数；验证日志里不包含刷新令牌明文。
     */
    @Test
    public void shouldBuildRefreshLogWithoutTokenValue() {
        String actual = AiLog.auth()
                .refreshSuccess("t1", "u1", "codeliu", "owner")
                .toLogfmt();

        assertTraceIdAndLogBody("event=auth_refresh_success domain=auth tenantId=t1 userId=u1 username=codeliu roleCode=owner success=true", actual);
        Assert.assertFalse(actual.contains("refreshToken"));
    }

    @Test
    public void shouldReuseTraceIdAndCreateDifferentLogIdsInSameContext() {
        TraceContext.setTraceId("11111111-1111-1111-1111-111111111111");
        try {
            String first = AiLog.db().query("mysql", "select", "agent_session", 1, 8L, true).toLogfmt();
            String second = AiLog.redis().command("GET", "session:s1", true, 2L, true).toLogfmt();

            Assert.assertTrue(first.contains(" traceId=11111111-1111-1111-1111-111111111111 "));
            Assert.assertTrue(second.contains(" traceId=11111111-1111-1111-1111-111111111111 "));
            Assert.assertNotEquals(first.substring(0, first.indexOf(' ')), second.substring(0, second.indexOf(' ')));
        } finally {
            TraceContext.clear();
        }
    }

    private void assertTraceIdAndLogBody(String expectedBody, String actual) {
        Assert.assertTrue("logId and traceId should be the first fields: " + actual, LOG_TRACE_PREFIX.matcher(actual).find());
        Assert.assertEquals(expectedBody, LOG_TRACE_PREFIX.matcher(actual).replaceFirst(""));
    }
}
