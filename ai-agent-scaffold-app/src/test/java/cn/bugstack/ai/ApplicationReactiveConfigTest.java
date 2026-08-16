package cn.bugstack.ai;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ApplicationReactiveConfigTest {
    private final String previous = System.getProperty("rx3.buffer-size");

    @After
    public void restore() {
        if (previous == null) System.clearProperty("rx3.buffer-size");
        else System.setProperty("rx3.buffer-size", previous);
    }

    @Test
    public void shouldUseBoundedDefaultAndRespectExplicitOverride() {
        System.clearProperty("rx3.buffer-size");
        Application.configureRxJavaPrefetch();
        assertEquals("16", System.getProperty("rx3.buffer-size"));

        System.setProperty("rx3.buffer-size", "8");
        Application.configureRxJavaPrefetch();
        assertEquals("8", System.getProperty("rx3.buffer-size"));
    }
}
