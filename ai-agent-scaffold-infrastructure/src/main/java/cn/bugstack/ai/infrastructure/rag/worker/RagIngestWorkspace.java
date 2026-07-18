package cn.bugstack.ai.infrastructure.rag.worker;

import cn.bugstack.ai.types.exception.AppException;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** 单次摄取 attempt 的受控临时工作目录。 */
public final class RagIngestWorkspace implements AutoCloseable {

    private final Path root;

    private RagIngestWorkspace(Path root) {
        this.root = root;
    }

    /** 创建不含用户文件名的独立工作目录。 */
    public static RagIngestWorkspace create() {
        try {
            Path root = Files.createTempDirectory("ai-rag-ingest-").toAbsolutePath().normalize();
            try {
                Files.setPosixFilePermissions(root, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException ignored) {
                // Windows/非 POSIX 文件系统仍使用 JVM 独立临时目录。
            }
            return new RagIngestWorkspace(root);
        } catch (IOException e) {
            throw new AppException("RAG_WORKSPACE_CREATE_FAILED", "无法创建 RAG 摄取工作目录", e);
        }
    }

    public Path root() {
        return root;
    }

    /** 固定内部文件名，不使用外部文件名拼接路径。 */
    public Path sourceRelativePath() {
        return Path.of("source.bin");
    }

    @Override
    public void close() {
        try {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new AppException("RAG_WORKSPACE_CLEANUP_FAILED", "RAG 摄取工作目录清理失败", e);
        }
    }
}
