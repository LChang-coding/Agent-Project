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

    /** 本次摄取独占且规范化后的临时目录根路径。 */
    private final Path root;

    /** 封装已经创建并限制权限的临时根目录。 */
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

    /** 返回解析器和下载命令允许访问的受控根目录。 */
    public Path root() {
        return root;
    }

    /** 固定内部文件名，不使用外部文件名拼接路径。 */
    public Path sourceRelativePath() {
        return Path.of("source.bin");
    }

    /** 旧版规范化解析产物的受控暂存路径。 */
    public Path parsedMarkdownPath() {
        return root.resolve("normalized.md").normalize();
    }

    /** 格式解析器原始结构响应的受控暂存路径。 */
    public Path parserOutputPath() {
        return root.resolve("parser-output.json").normalize();
    }

    /** Canonical Document IR的受控暂存路径。 */
    public Path documentIrPath() {
        return root.resolve("document-ir-v1.json").normalize();
    }

    /** 规范化Markdown展示产物的受控暂存路径。 */
    public Path normalizedMarkdownPath() {
        return root.resolve("normalized-display.md").normalize();
    }

    /** 解析质量报告的受控暂存路径。 */
    public Path qualityReportPath() {
        return root.resolve("quality-report.json").normalize();
    }

    /** 分块清单的受控暂存路径。 */
    public Path chunkManifestPath() {
        return root.resolve("chunk-manifest.json").normalize();
    }

    @Override
    /** 递归删除本次摄取产生的文件和目录，清理失败显式上报。 */
    public void close() {
        try {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                /** 删除遍历到的普通文件，不跟随目录外的符号链接。 */
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                /** 子项清理完成后删除目录；遍历错误会终止并上报。 */
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
