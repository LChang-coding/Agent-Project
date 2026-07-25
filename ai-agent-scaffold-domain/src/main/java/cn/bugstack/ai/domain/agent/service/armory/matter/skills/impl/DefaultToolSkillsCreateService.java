package cn.bugstack.ai.domain.agent.service.armory.matter.skills.impl;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** 从文件系统或 classpath 构造 Spring AI Community SkillsTool。 */
@Slf4j
@Service
public class DefaultToolSkillsCreateService implements ToolSkillsCreateService {

    /** 支持同时扫描目录与 Jar 内资源。 */
    private static final ResourcePatternResolver RESOURCE_PATTERN_RESOLVER = new PathMatchingResourcePatternResolver();

    /** 每条配置最多构造一个 SkillsTool；未知类型返回空工具集。 */
    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) throws Exception {

        String type = toolSkills.getType();
        String path = toolSkills.getPath();

        List<ToolCallback> toolCallbackList = new ArrayList<>();

        if ("directory".equals(type)){
            // directory 路径必须在运行机器文件系统中可遍历。
            ToolCallback toolCallback = SkillsTool.builder()
                    .addSkillsDirectory(path)
                    .build();
            toolCallbackList.add(toolCallback);
        }

        if ("resource".equals(type)){
            // classpath 资源在 Jar 场景需先释放为真实目录。
            ToolCallback toolCallback = buildClasspathResourceSkills(path);
            toolCallbackList.add(toolCallback);
        }

        return toolCallbackList.toArray(new ToolCallback[0]);
    }

    /** classpath 是普通目录时直接使用；Jar 资源则释放到临时目录。 */
    private ToolCallback buildClasspathResourceSkills(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        try {
            File file = resource.getFile();
            return SkillsTool.builder()
                    .addSkillsDirectory(file.toPath().toAbsolutePath().toString())
                    .build();
        } catch (IOException ignored) {
            // ClassPathResource#getFile 在 Jar 内必然失败，此处转入资源复制路径。
            Path extractedDirectory = extractClasspathDirectory(path);
            return SkillsTool.builder()
                    .addSkillsDirectory(extractedDirectory.toAbsolutePath().toString())
                    .build();
        }
    }

    /** 将 classpath 目录完整复制到隔离临时目录，供只支持文件系统的 SkillsTool 使用。 */
    private Path extractClasspathDirectory(String path) throws IOException {
        String normalizedPath = normalizeResourcePath(path);
        Resource[] resources = RESOURCE_PATTERN_RESOLVER.getResources(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + normalizedPath + "/**");
        Path targetDirectory = Files.createTempDirectory("ai-agent-skills-");
        int copiedCount = 0;
        for (Resource resource : resources) {
            if (!resource.isReadable() || resource.getFilename() == null) {
                // 目录占位和不可读资源不参与复制。
                continue;
            }
            String relativePath = resolveRelativePath(normalizedPath, resource);
            if (!StringUtils.hasText(relativePath) || relativePath.endsWith("/")) {
                continue;
            }
            Path targetPath = targetDirectory.resolve(relativePath).normalize();
            // 规范化后必须仍位于临时根目录，阻止资源名路径穿越。
            if (!targetPath.startsWith(targetDirectory)) {
                throw new IOException("非法技能资源路径: " + relativePath);
            }
            Files.createDirectories(targetPath.getParent());
            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            copiedCount++;
        }
        if (copiedCount == 0) {
            // 空技能目录视为配置错误，避免发布一个永远无能力的工具。
            throw new IOException("未找到 classpath 技能资源目录: " + normalizedPath);
        }
        log.info("classpath 技能资源已释放 path:{} files:{} target:{}", normalizedPath, copiedCount, targetDirectory);
        return targetDirectory;
    }

    /** 统一去除 classpath 根路径首尾斜杠，便于生成稳定扫描表达式。 */
    private String normalizeResourcePath(String path) {
        String normalizedPath = path == null ? "" : path.trim();
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        while (normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }
        return normalizedPath;
    }

    /** 从资源 URL 截取并解码根目录内相对路径。 */
    private String resolveRelativePath(String rootPath, Resource resource) throws IOException {
        String url = resource.getURL().toString();
        String marker = rootPath + "/";
        int index = url.lastIndexOf(marker);
        if (index < 0) {
            // 无法定位根标记时只能退化为文件名，仍会接受目标目录边界校验。
            return resource.getFilename();
        }
        String relativePath = url.substring(index + marker.length());
        return URLDecoder.decode(relativePath, StandardCharsets.UTF_8);
    }

}
