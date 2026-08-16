package cn.bugstack.ai;

import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.server.MyTestMcpService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.mybatis.spring.annotation.MapperScan;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
@Configurable
@MapperScan("cn.bugstack.ai.infrastructure.dao")
public class Application {

    private static final String OBSERVABILITY_SCRIPT = "docs/dev-ops/observability/local/ensure-observability.sh";
    private static final String RXJAVA_BUFFER_SIZE_PROPERTY = "rx3.buffer-size";
    private static final String RXJAVA_BUFFER_SIZE_DEFAULT = "4";

    public static void main(String[] args) {
        // Google ADK 1.1 的模型流内部使用默认 flatMap 预取。默认 128 会在高频 SSE 下
        // 形成 128×128 的 Event 等待队列；必须在 Flowable 首次加载前收紧全局预取窗口。
        configureRxJavaPrefetch();
        startLocalObservability();
        SpringApplication.run(Application.class, args);
    }

    static void configureRxJavaPrefetch() {
        System.setProperty(RXJAVA_BUFFER_SIZE_PROPERTY,
                System.getProperty(RXJAVA_BUFFER_SIZE_PROPERTY, RXJAVA_BUFFER_SIZE_DEFAULT));
    }

    private static void startLocalObservability() {
        String enabled = System.getProperty("ai.observability.local.auto-start",
                System.getenv().getOrDefault("OBS_AUTO_START", "true"));
        if ("false".equalsIgnoreCase(enabled)) {
            System.out.println("Local observability auto-start is disabled.");
            return;
        }

        Path script = findObservabilityScript();
        if (script == null) {
            System.out.println("Local observability script not found, skip auto-start.");
            return;
        }

        try {
            Process process = new ProcessBuilder("/bin/bash", script.toString())
                    .directory(script.getParent().toFile())
                    .inheritIO()
                    .start();

            boolean completed = process.waitFor(15, TimeUnit.SECONDS);
            if (!completed) {
                process.destroy();
                System.err.println("Local observability auto-start timed out, application will continue.");
                return;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                System.err.println("Local observability auto-start failed, exitCode=" + exitCode + ", application will continue.");
            }
        } catch (Exception e) {
            System.err.println("Local observability auto-start failed, application will continue. reason=" + e.getMessage());
        }
    }

    private static Path findObservabilityScript() {
        String customScript = System.getProperty("ai.observability.local.script", System.getenv("OBS_BOOTSTRAP_SCRIPT"));
        if (customScript != null && !customScript.isBlank()) {
            Path script = Paths.get(customScript).toAbsolutePath().normalize();
            return isExecutableFile(script) ? script : null;
        }

        List<Path> candidates = new ArrayList<>();
        Path userDir = Paths.get("").toAbsolutePath().normalize();
        candidates.add(userDir.resolve(OBSERVABILITY_SCRIPT));
        candidates.add(userDir.resolve("Agent-Project").resolve(OBSERVABILITY_SCRIPT));
        candidates.add(userDir.resolve("..").resolve(OBSERVABILITY_SCRIPT).normalize());

        try {
            URI codeSource = Application.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path location = Paths.get(codeSource).toAbsolutePath().normalize();
            Path cursor = Files.isDirectory(location) ? location : location.getParent();
            for (int i = 0; cursor != null && i < 8; i++) {
                candidates.add(cursor.resolve(OBSERVABILITY_SCRIPT).normalize());
                cursor = cursor.getParent();
            }
        } catch (Exception ignore) {
            // Fall back to user.dir based candidates.
        }

        for (Path candidate : candidates) {
            if (isExecutableFile(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    static boolean isExecutableFile(Path path) {
        return path != null
                && FileSystems.getDefault().equals(path.getFileSystem())
                && Files.isRegularFile(path)
                && Files.isExecutable(path);
    }

    @Bean("myToolCallbackProvider")
    public ToolCallbackProvider testTools(MyTestMcpService testService) {
        return MethodToolCallbackProvider.builder().toolObjects(testService).build();
    }

}
