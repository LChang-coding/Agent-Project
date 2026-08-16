package cn.bugstack.ai.domain.agent.service.armory;

import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

/**
 * ADK 内存会话的有界适配层。部分 OpenAI 兼容模型会把每个 SSE 累计快照标记为
 * 非 partial 事件，ADK 默认会把整个快照永久追加到 Session，长思考时会形成平方级堆占用。
 */
public final class BoundedInMemorySessionService implements BaseSessionService {
    static final int MAX_EVENTS_PER_INVOCATION = 256;
    private final InMemorySessionService delegate = new InMemorySessionService();

    @Override
    public Single<Session> createSession(String appName, String userId,
                                         ConcurrentMap<String, Object> state, String sessionId) {
        return delegate.createSession(appName, userId, state, sessionId);
    }

    @Override
    public Maybe<Session> getSession(String appName, String userId, String sessionId,
                                     Optional<GetSessionConfig> config) {
        return delegate.getSession(appName, userId, sessionId, config);
    }

    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {
        return delegate.listSessions(appName, userId);
    }

    @Override
    public Completable deleteSession(String appName, String userId, String sessionId) {
        return delegate.deleteSession(appName, userId, sessionId);
    }

    @Override
    public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
        return delegate.listEvents(appName, userId, sessionId);
    }

    @Override
    public Single<Event> appendEvent(Session session, Event event) {
        return delegate.appendEvent(session, event).map(saved -> {
            trim(session.events());
            return saved;
        });
    }

    /** 保留首条用户输入与最新事件，工具多轮仍可回看原始任务。 */
    private void trim(List<Event> events) {
        if (events == null || events.size() <= MAX_EVENTS_PER_INVOCATION) return;
        synchronized (events) {
            int removeUntil = events.size() - (MAX_EVENTS_PER_INVOCATION - 1);
            if (removeUntil > 1) events.subList(1, removeUntil).clear();
        }
    }
}
