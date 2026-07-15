package cn.bugstack.ai.config;

import cn.bugstack.ai.types.observability.AiLog;
import cn.bugstack.ai.types.observability.AiLogFields;
import cn.bugstack.ai.types.observability.AiLogRecord;
import cn.bugstack.ai.types.observability.TraceContext;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.StringJoiner;

@Component
@Profile("dev")
@ConditionalOnProperty(prefix = "ai.observability.sample", name = "enabled", havingValue = "true")
public class ObservabilitySampleRunner implements ApplicationRunner {

    private final Environment environment;

    public ObservabilitySampleRunner(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String env = activeProfiles();
        String version = getClass().getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = "local";
        }

        TraceContext.setTraceId(TraceContext.newTraceId());
        try {
            AiLog.info(sample(AiLog.app().start("ai-agent-scaffold", env, version)));
            AiLog.info(sample(AiLog.model().call("sample-user", "sample-session", "sampleAgent", "sampleApp",
                    "sample-invocation", "sample-model", 120L, true)));
            AiLog.info(sample(AiLog.model().tokenUsage("sample-user", "sample-session", "sampleAgent", "sampleApp",
                    "sample-invocation", "sample-model", 100, 20, 120, null, null, false, true)));
            AiLog.error(sample(AiLog.model().error("sample-user", "sample-session", "sampleAgent", "sampleApp",
                    "sample-invocation", "sample-model", new IllegalStateException("sample model error"))));

            AiLog.info(sample(AiLog.db().query("mysql", "select", "agent_session", 1, 8L, true)));
            AiLog.error(sample(AiLog.db().error("mysql", "insert", "agent_message", 19L,
                    new IllegalStateException("sample db timeout"))));

            AiLog.info(sample(AiLog.redis().command("GET", "session:sample-session", true, 2L, true)));
            AiLog.error(sample(AiLog.redis().error("SET", "session:sample-session", 4L,
                    new IllegalStateException("sample redis timeout"))));

            AiLog.info(sample(AiLog.rag().retrieve("sample-user", "sample-session", "agent-kb", "sample-query",
                    5, 3, 21L, true)));
            AiLog.error(sample(AiLog.rag().error("sample-user", "sample-session", "agent-kb", "sample-query",
                    32L, new IllegalStateException("sample rag retrieval failed"))));

            AiLog.info(sample(AiLog.oss().upload("agent-files", "sample/demo.txt", 128L, 13L, true)));
            AiLog.info(sample(AiLog.oss().download("agent-files", "sample/demo.txt", 128L, 11L, true)));
            AiLog.error(sample(AiLog.oss().error("upload", "agent-files", "sample/error.txt", 17L,
                    new IllegalStateException("sample oss upload failed"))));

            AiLog.info(sample(AiLog.scheduler().done("sample-daily-summary", "startup", "sample-run", 44L, true)));
            AiLog.error(sample(AiLog.scheduler().error("sample-daily-summary", "startup", "sample-run", 45L,
                    new IllegalStateException("sample scheduler failed"))));
        } finally {
            TraceContext.clear();
        }
    }

    private AiLogRecord sample(AiLogRecord record) {
        return record.field(AiLogFields.SAMPLE, true);
    }

    private String activeProfiles() {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) {
            return "default";
        }

        StringJoiner joiner = new StringJoiner(",");
        for (String profile : profiles) {
            joiner.add(profile);
        }
        return joiner.toString();
    }
}
