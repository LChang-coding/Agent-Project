package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.service.armory.BoundedInMemorySessionService;
import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** OpenAI 兼容流的非 partial 累计快照不得无界占用 ADK Session。 */
public class BoundedInMemorySessionServiceTest {

    @Test
    public void shouldKeepInitialInputAndOnlyRecentEvents() {
        BoundedInMemorySessionService service = new BoundedInMemorySessionService();
        Session session = service.createSession("app", "user", new ConcurrentHashMap<>(), "session")
                .blockingGet();
        List<Event> appended = new ArrayList<>();
        for (int index = 0; index < 400; index++) {
            Event event = mock(Event.class);
            when(event.partial()).thenReturn(Optional.empty());
            when(event.timestamp()).thenReturn((long) index);
            appended.add(event);
            service.appendEvent(session, event).blockingGet();
        }

        assertEquals(256, session.events().size());
        assertSame(appended.get(0), session.events().get(0));
        assertSame(appended.get(399), session.events().get(255));
    }
}
