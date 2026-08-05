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
 * 把技能目录构造成可调用的技能工具，兼容「磁盘目录」和「打进 Jar 的工程资源」两种来源。
 *
 * <p>解决什么问题：技能工具只认真实的文件系统目录，但项目打成 Jar 后资源在压缩包里，
 * 根本没有对应的目录路径。这里在遇到 Jar 场景时把资源整棵子树复制到临时目录，再交给技能工具。</p>
 *
 * <p>所属层次：领域层的装配辅料实现。</p>
 *
 * <p>谁会调用它：装配 Agent 或模型工具时，对配置里每条 Skill 项调用一次。</p>
 *
 * <p>它不负责什么：不清理释放出来的临时目录（依赖操作系统回收），
 * 也不做技能内容校验，只保证「有一个可读的目录交给技能工具」。</p>
 */
@Slf4j
@Service
public class DefaultToolSkillsCreateService implements ToolSkillsCreateService {

    /**
     * 资源扫描器，能同时扫普通目录和 Jar 内部资源。
     *
     * <p>做成静态常量是因为它无状态且构造有一定开销，全进程复用一份即可。</p>
     */
    private static final ResourcePatternResolver RESOURCE_PATTERN_RESOLVER = new PathMatchingResourcePatternResolver();

    /**
     * 按配置的来源类型解析技能目录，构造技能工具。
     *
     * <p>各层职责：
     * 第一层：取出来源类型和路径这两个决定性参数。
     * 第二层：类型是 directory 时，直接把路径当磁盘目录交给技能工具。
     * 第三层：类型是 resource 时，走 classpath 解析，必要时先把资源释放成真实目录。</p>
     *
     * <p>数据流：Skill 配置（type + path）→ 按类型选择解析方式 → 得到可读目录 → 构造技能工具 → 返回工具数组。</p>
     *
     * <p>注意：type 写成别的值（比如拼错成 dir）会两个分支都不命中，返回空工具数组。
     * 这时 Agent 会安静地少掉这批技能而不报错，配置时要留意。</p>
     */
    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) throws Exception {

        // 第一层：取出来源类型，它决定按磁盘还是按 classpath 解析。
        String type = toolSkills.getType();
        // 取出技能根路径。
        String path = toolSkills.getPath();

        // 结果容器；一条配置最多产出一个技能工具。
        List<ToolCallback> toolCallbackList = new ArrayList<>();

        // 第二层：磁盘目录来源。
        if ("directory".equals(type)){
            // directory 路径必须在运行机器文件系统中可遍历。
            ToolCallback toolCallback = SkillsTool.builder()
                    .addSkillsDirectory(path)
                    .build();
            // 放进结果容器。
            toolCallbackList.add(toolCallback);
        }

        // 第三层：工程资源来源。
        if ("resource".equals(type)){
            // classpath 资源在 Jar 场景需先释放为真实目录。
            ToolCallback toolCallback = buildClasspathResourceSkills(path);
            // 放进结果容器。
            toolCallbackList.add(toolCallback);
        }

        // 转成数组返回；两个分支都没命中时这里是空数组。
        return toolCallbackList.toArray(new ToolCallback[0]);
    }

    /**
     * 从 classpath 解析技能目录：能当普通目录用就直接用，否则先释放成临时目录。
     *
     * <p>为什么用「先试再退」而不是先判断是否在 Jar 里：判断打包形态既啰嗦又容易漏情况，
     * 直接尝试取文件、失败再走复制路径更可靠。</p>
     *
     * <p>数据流：classpath 路径 → 尝试取真实文件路径 → 成功则直接构造技能工具；
     * 失败（说明在 Jar 内）→ 把资源复制到临时目录 → 用临时目录构造技能工具。</p>
     */
    private ToolCallback buildClasspathResourceSkills(String path) throws IOException {
        // 把路径包装成 classpath 资源，后面尝试从它取真实文件。
        ClassPathResource resource = new ClassPathResource(path);
        // 先按「资源就在磁盘上」这条最简单的路径尝试。
        try {
            // 开发环境或解压部署时能直接拿到真实目录。
            File file = resource.getFile();
            // 直接用这个目录构造技能工具，不产生任何复制开销。
            return SkillsTool.builder()
                    .addSkillsDirectory(file.toPath().toAbsolutePath().toString())
                    .build();
        } catch (IOException ignored) {
            // ClassPathResource#getFile 在 Jar 内必然失败，此处转入资源复制路径。
            Path extractedDirectory = extractClasspathDirectory(path);
            // 用释放出来的临时目录构造技能工具。
            return SkillsTool.builder()
                    .addSkillsDirectory(extractedDirectory.toAbsolutePath().toString())
                    .build();
        }
    }

    /**
     * 把 classpath 下的整棵技能目录复制到一个独立临时目录。
     *
     * <p>各层职责：
     * 第一层：规范化路径，去掉首尾斜杠，保证扫描表达式稳定。
     * 第二层：扫出该目录下所有资源，并创建一个专属临时目录作为复制目标。
     * 第三层：逐个资源计算它在根目录内的相对路径，跳过目录占位和不可读项。
     * 第四层：做路径穿越校验——资源名里如果带 ../，规范化后可能跑到临时目录外面，必须拒绝。
     * 第五层：创建父目录并复制文件内容，统计成功复制的数量。
     * 第六层：一个文件都没复制成功时报错，避免发布一个永远没有能力的技能工具。</p>
     *
     * <p>数据流：
     * classpath 路径
     * → 规范化
     * → 扫描出全部资源
     * → 创建临时目录
     * → 逐个资源：算相对路径 → 校验未越界 → 建父目录 → 复制内容
     * → 统计数量
     * → 数量为零则报错，否则返回临时目录</p>
     *
     * <p>临时目录不会被主动删除，依赖操作系统清理；每次装配都会新建一个，
     * 因此频繁重装配会在临时区留下多份副本。</p>
     */
    private Path extractClasspathDirectory(String path) throws IOException {
        // 第一层：统一路径格式，避免 //skills/ 这类写法导致扫描表达式失效。
        String normalizedPath = normalizeResourcePath(path);
        // 第二层：扫出该目录下所有层级的资源（含 Jar 内部）。
        Resource[] resources = RESOURCE_PATTERN_RESOLVER.getResources(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + normalizedPath + "/**");
        // 建一个专属临时目录做复制目标，和其它技能包互不干扰。
        Path targetDirectory = Files.createTempDirectory("ai-agent-skills-");
        // 统计真正复制成功的文件数，用于最后判断技能包是否为空。
        int copiedCount = 0;
        // 第三层：逐个资源处理。
        for (Resource resource : resources) {
            // 目录占位和不可读资源不参与复制。
            if (!resource.isReadable() || resource.getFilename() == null) {
                // 跳过这一项，继续处理下一个资源。
                continue;
            }
            // 算出它相对于技能根目录的路径，用来在临时目录里还原层级结构。
            String relativePath = resolveRelativePath(normalizedPath, resource);
            // 相对路径为空或以斜杠结尾的都是目录占位，没有内容可复制。
            if (!StringUtils.hasText(relativePath) || relativePath.endsWith("/")) {
                // 跳过目录项。
                continue;
            }
            // 拼出目标文件路径并规范化，消解其中的 . 和 .. 片段。
            Path targetPath = targetDirectory.resolve(relativePath).normalize();
            // 第四层：规范化后必须仍位于临时根目录，阻止资源名路径穿越。
            if (!targetPath.startsWith(targetDirectory)) {
                // 一旦越界立即报错，绝不把文件写到临时目录之外。
                throw new IOException("非法技能资源路径: " + relativePath);
            }
            // 第五层：先建齐父级目录，否则复制会因目录不存在而失败。
            Files.createDirectories(targetPath.getParent());
            // 用 try-with-resources 打开资源流，保证复制后一定关闭，不泄漏文件句柄。
            try (InputStream inputStream = resource.getInputStream()) {
                // 复制内容，已存在则覆盖，使重复装配可以幂等执行。
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            // 成功复制一个文件，计数加一。
            copiedCount++;
        }
        // 第六层：一个文件都没有，说明路径写错或技能包是空的。
        if (copiedCount == 0) {
            // 空技能目录视为配置错误，避免发布一个永远无能力的工具。
            throw new IOException("未找到 classpath 技能资源目录: " + normalizedPath);
        }
        // 记录释放结果，便于确认技能文件数量和落地位置。
        log.info("classpath 技能资源已释放 path:{} files:{} target:{}", normalizedPath, copiedCount, targetDirectory);
        // 返回临时目录，交给技能工具当作技能根目录使用。
        return targetDirectory;
    }

    /**
     * 去掉 classpath 路径的首尾斜杠，让扫描表达式的拼接结果稳定。
     *
     * <p>为什么必须做：扫描表达式是「路径 + /**」拼出来的。路径带首斜杠会变成
     * classpath*://skills/**，带尾斜杠会变成 skills//**，两者都可能扫不到东西。</p>
     *
     * <p>入参为空时返回空串，最终会扫描 classpath 根，配合调用方的空目录检查报错。</p>
     */
    private String normalizeResourcePath(String path) {
        // 空值按空串处理，同时去掉两端空白，避免配置里多打空格。
        String normalizedPath = path == null ? "" : path.trim();
        // 逐个去掉开头的斜杠；用循环是为了处理连续多个斜杠的写法。
        while (normalizedPath.startsWith("/")) {
            // 去掉一个开头斜杠后继续判断。
            normalizedPath = normalizedPath.substring(1);
        }
        // 同样逐个去掉结尾的斜杠。
        while (normalizedPath.endsWith("/")) {
            // 去掉一个结尾斜杠后继续判断。
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }
        // 返回干净的相对路径。
        return normalizedPath;
    }

    /**
     * 从资源的完整 URL 里截出它在技能根目录内的相对路径。
     *
     * <p>为什么用字符串定位而不是路径 API：Jar 内资源的 URL 形如
     * jar:file:/app.jar!/skills/foo/bar.md，没有可用的文件系统路径，只能按根目录名做定位。</p>
     *
     * <p>用 lastIndexOf 而不是 indexOf：路径里可能重复出现同名片段（比如 skills/skills），
     * 取最后一次出现的位置更接近真实的根目录边界。</p>
     *
     * <p>定位失败时退化成只用文件名，会丢掉层级结构；但调用方还有目录边界校验兜底，不会写出目标目录。
     * 另外 URL 里的中文和空格是被编码过的，必须解码后才能作为真实文件名使用。</p>
     */
    private String resolveRelativePath(String rootPath, Resource resource) throws IOException {
        // 取资源的完整 URL 字符串，它是唯一能同时覆盖目录和 Jar 两种场景的定位依据。
        String url = resource.getURL().toString();
        // 构造根目录标记，带上斜杠避免匹配到同名前缀的其它片段。
        String marker = rootPath + "/";
        // 取最后一次出现的位置，尽量贴近真实的根目录边界。
        int index = url.lastIndexOf(marker);
        // 定位失败，说明 URL 结构不符合预期。
        if (index < 0) {
            // 无法定位根标记时只能退化为文件名，仍会接受目标目录边界校验。
            return resource.getFilename();
        }
        // 截取根标记之后的部分作为相对路径。
        String relativePath = url.substring(index + marker.length());
        // URL 里的中文和空格是百分号编码的，必须解码才能得到真实文件名。
        return URLDecoder.decode(relativePath, StandardCharsets.UTF_8);
    }

}
