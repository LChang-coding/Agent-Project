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

/**
 * Spring AI Community 构建skills <a href="https://github.com/spring-ai-community/spring-ai-agent-utils">spring-ai-agent-utils</a>
 *
 * 2026/2/6 08:04
 */
@Slf4j
@Service
public class DefaultToolSkillsCreateService implements ToolSkillsCreateService {

    private static final ResourcePatternResolver RESOURCE_PATTERN_RESOLVER = new PathMatchingResourcePatternResolver();

    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) throws Exception {

        String type = toolSkills.getType();
        String path = toolSkills.getPath();

        List<ToolCallback> toolCallbackList = new ArrayList<>();

        if ("directory".equals(type)){
            ToolCallback toolCallback = SkillsTool.builder()
                    .addSkillsDirectory(path)
                    .build();
            toolCallbackList.add(toolCallback);
        }

        if ("resource".equals(type)){
            ToolCallback toolCallback = buildClasspathResourceSkills(path);
            toolCallbackList.add(toolCallback);
        }

        return toolCallbackList.toArray(new ToolCallback[0]);
    }

    /**
     * 构建 classpath 技能工具；jar 内目录会先释放到临时目录以兼容第三方工具。
     *
     * @param path classpath 下的技能目录
     * @return 技能工具回调
     * @throws IOException 资源读取失败时抛出
     */
    private ToolCallback buildClasspathResourceSkills(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        try {
            File file = resource.getFile();
            return SkillsTool.builder()
                    .addSkillsDirectory(file.toPath().toAbsolutePath().toString())
                    .build();
        } catch (IOException ignored) {
            Path extractedDirectory = extractClasspathDirectory(path);
            return SkillsTool.builder()
                    .addSkillsDirectory(extractedDirectory.toAbsolutePath().toString())
                    .build();
        }
    }

    /**
     * 释放 classpath 目录；参数是资源路径；返回可被文件系统遍历的目录。
     */
    private Path extractClasspathDirectory(String path) throws IOException {
        String normalizedPath = normalizeResourcePath(path);
        Resource[] resources = RESOURCE_PATTERN_RESOLVER.getResources(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + normalizedPath + "/**");
        Path targetDirectory = Files.createTempDirectory("ai-agent-skills-");
        int copiedCount = 0;
        for (Resource resource : resources) {
            if (!resource.isReadable() || resource.getFilename() == null) {
                continue;
            }
            String relativePath = resolveRelativePath(normalizedPath, resource);
            if (!StringUtils.hasText(relativePath) || relativePath.endsWith("/")) {
                continue;
            }
            Path targetPath = targetDirectory.resolve(relativePath).normalize();
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
            throw new IOException("未找到 classpath 技能资源目录: " + normalizedPath);
        }
        log.info("classpath 技能资源已释放 path:{} files:{} target:{}", normalizedPath, copiedCount, targetDirectory);
        return targetDirectory;
    }

    /**
     * 规范化 classpath 路径；参数是原始路径；返回无首尾斜杠路径。
     */
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

    /**
     * 解析资源相对路径；参数是根目录和资源；返回根目录内相对路径。
     */
    private String resolveRelativePath(String rootPath, Resource resource) throws IOException {
        String url = resource.getURL().toString();
        String marker = rootPath + "/";
        int index = url.lastIndexOf(marker);
        if (index < 0) {
            return resource.getFilename();
        }
        String relativePath = url.substring(index + marker.length());
        return URLDecoder.decode(relativePath, StandardCharsets.UTF_8);
    }

}
