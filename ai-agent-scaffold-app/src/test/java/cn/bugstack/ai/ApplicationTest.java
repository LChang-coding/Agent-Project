package cn.bugstack.ai;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ApplicationTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void shouldAcceptExecutableLocalFileAndRejectJarFileSystemPath() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path local = root.resolve("ensure-observability.sh");
        Files.writeString(local, "#!/bin/sh\n");
        local.toFile().setExecutable(true);
        assertTrue(Application.isExecutableFile(local));

        Path archive = root.resolve("application.jar");
        URI uri = URI.create("jar:" + archive.toUri());
        try (FileSystem zip = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path nested = zip.getPath("/docs/dev-ops/observability/local/ensure-observability.sh");
            Files.createDirectories(nested.getParent());
            Files.writeString(nested, "#!/bin/sh\n");
            assertFalse(Application.isExecutableFile(nested));
        }
    }
}
