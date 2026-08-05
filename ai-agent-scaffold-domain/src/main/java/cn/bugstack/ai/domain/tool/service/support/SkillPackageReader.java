package cn.bugstack.ai.domain.tool.service.support;

import cn.bugstack.ai.domain.tool.model.SkillPackageProperties;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 从用户上传的 Skill 压缩包里安全地取出那份 SKILL.md 文本。
 *
 * <p>所属层次：领域层的支撑组件。</p>
 *
 * <p>谁调用它：{@code ToolPublishService}（上传校验、建 Skill、加版本时各解析一次）
 * 和 {@code ToolGateway}（模型每次调用 Skill 时读出指令文本返回给模型）。</p>
 *
 * <p>为什么这个类通篇都在防守：它处理的是完全不可信的外部文件。三类攻击必须挡住——
 * 一是压缩炸弹（小 ZIP 解出巨量数据把内存吃光）；二是畸形 ZIP（解压过程抛出各种底层异常）；
 * 三是非 UTF-8 字节（解码出乱码后被当成指令喂给大模型）。所以这里只做「读」，
 * 而且是在条目数、单条字节数、字符编码三重边界内读。</p>
 *
 * <p>它明确不做的事：不执行包里的任何代码或脚本（Skill 的语义就是「给模型看的说明书」，不是可执行程序）；
 * 不解释 SKILL.md 的正文含义；不落库；不做权限判断。</p>
 */
@Component
public class SkillPackageReader {

    /**
     * 对外统一的错误码。包坏在哪一步（不是 ZIP、缺文件、超限、编码非法）都用这一个码，
     * 只在文案里区分原因，避免把 ZIP 底层异常类型暴露成对外错误码，也方便调用方统一处理。
     */
    private static final String INVALID_CODE = "TOOL_SKILL_PACKAGE_INVALID";
    /** 流式解压时每次从 ZIP 流读取的字节数；只影响读取效率，不构成安全边界，真正的上限来自配置。 */
    private static final int BUFFER_SIZE = 8192;

    /**
     * 解压资源上限的来源（最多几个条目、单个条目最多多少字节）。
     *
     * <p>它是这个类唯一的安全参数来源。配置错了（比如被设成 0 或负数）就等于没有防线，
     * 所以每次读包之前都会先检查它是否合法。</p>
     */
    private final SkillPackageProperties properties;

    /**
     * 注入解压边界配置完成构造。
     *
     * <p>这里故意不校验配置：构造期抛异常会让整个应用启动失败且难定位，
     * 改成在真正读包前检查，既能拦住问题，也能给出明确的报错时机。</p>
     */
    public SkillPackageReader(SkillPackageProperties properties) {
        // 只保存配置引用，真正的合法性检查推迟到每次读包之前做。
        this.properties = properties;
    }

    /**
     * 读出压缩包里第一个 SKILL.md 的文本内容。
     *
     * <p>各层职责：
     * 第一层：先确认解压边界配置本身可用，配置坏了就不能开始处理不可信文件。
     * 第二层：用文件头的 PK 签名判断这到底是不是 ZIP，避免把随便一个文件丢进解压器。
     * 第三层：逐条遍历压缩包，一边数条目数一边跳过不需要的条目，只对第一个 SKILL.md 做展开。
     * 第四层：展开时按字节上限流式读取，超限立刻中止。
     * 第五层：严格按 UTF-8 解码，非法字节直接拒绝而不是替换成问号。</p>
     *
     * <p>数据流：
     * 上传的 ZIP 字节
     * → 校验解压边界配置
     * → 校验 PK 文件头
     * → 遍历条目并计数
     * → 跳过目录、非 SKILL.md、以及第二个 SKILL.md
     * → 在字节上限内展开目标条目
     * → 严格 UTF-8 解码
   * → 返回 Markdown 文本</p>
     *
     * <p>不写库、不产生外部副作用。任何一步不满足都抛业务异常，绝不返回半截内容——
     * 因为这段文本会被直接送进大模型的提示词，残缺内容会让模型按错误的说明书办事。</p>
     */
    public String readSkillMd(byte[] bytes) {
      // 第一层：先确认防线本身有效。上限配置为空或非正数意味着没有任何保护，此时绝不能开始解压外部文件。
        validateLimits();
        // 第二层：只看前四个字节的 PK 签名就能判断这是不是 ZIP，比直接交给解压器试错更快也更安全。
        if (!hasZipSignature(bytes)) {
   // 不是 ZIP 就没有继续的必要，直接告诉用户包的格式不对。
            throw invalid("Skill 包不是有效的 ZIP 文件");
        }
        // 已遍历的条目计数，用于对照条目数上限，挡住「文件数量极多」这类压缩炸弹。
        int entryCount = 0;
// 目标文件的字节内容；保持为空表示还没找到 SKILL.md，同时也充当「已经找到过」的标记。
        byte[] skillBytes = null;
    // 用 try-with-resources 包住解压流，保证无论中途怎么失败都会释放底层资源。
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
     // 当前正在检查的压缩包条目。
            ZipEntry entry;
        // 第三层：逐条向前推进，直到条目读完；不一次性把整包展开到内存。
            while ((entry = input.getNextEntry()) != null) {
     // 每见到一个条目就计数，无论它是否被展开，这样纯靠数量做的攻击也会被拦下。
                entryCount++;
          // 条目数一旦越界立刻判整包非法，不再继续读剩下的条目。
                if (entryCount > properties.getMaxEntries()) {
        // 报错文案里带上上限值，方便上传者知道该怎么改包。
                    throw invalid("Skill 包文件数超过上限 " + properties.getMaxEntries());
                }
// 三种条目都不展开：目录本身没有内容；不叫 SKILL.md 的文件我们不关心；第二个及以后的 SKILL.md 一律忽略，
              // 只认第一个，避免有人在包里塞两份说明书来制造歧义。
                if (entry.isDirectory() || !entry.getName().endsWith("SKILL.md") || skillBytes != null) {
            // 目录、非目标文件和第二个 SKILL.md 均不展开到内存。
                    continue;
                }
                // ZIP 头里声明的原始大小如果已经超限，连读都不必读，直接失败，省掉一次无意义的解压。
                if (entry.getSize() > properties.getMaxEntryBytes()) {
            // 抛出统一的「展开字节超上限」异常。
                    throw entryTooLarge();
                }
 // 第四层：在字节上限内把这个条目真正读出来；头里声明的大小可能撒谎，所以读的时候还要再累计校验一次。
                skillBytes = readEntry(input);
            }
        } catch (AppException e) {
         // 这是上面几处主动抛出的业务异常，错误码和文案都是设计好的，原样向上传递。
            throw e;
        } catch (Exception e) {
            // 走到这里说明是 ZIP 解压器自己报的错（结构损坏、意外截断等），统一收敛成对外错误码，
    // 只带一句可读的原因，不把底层异常类型暴露给调用方。
            throw new AppException(INVALID_CODE, "Skill 包 ZIP 结构损坏：" + readableMessage(e), e);
        }
   // 整包都翻完了还是没有 SKILL.md，说明这不是一个合法的 Skill 包。
        if (skillBytes == null) {
            // 明确告诉上传者缺了什么文件，而不是返回一段空文本让模型无所适从。
            throw invalid("Skill 包必须包含 SKILL.md");
        }
        // 第五层：严格解码成文本再返回；这段文本会直接进入模型提示词，必须保证编码干净。
        return decodeUtf8(skillBytes);
    }

    /**
     * 把当前这个条目的内容读进内存，边读边卡字节上限。
   *
     * <p>为什么不用 ZIP 头里的大小一次性分配：那个数字来自压缩包本身，是攻击者可以伪造的。
     * 真正可靠的做法是边读边累加实际字节数，一超上限立刻中断，这样即使头里写着 1KB、
     * 实际能解出 10GB，也只会多读一个缓冲区就被截住。</p>
     *
     * <p>数据流：ZIP 条目流 → 按 8KB 分块读 → 每块先做上限预判 → 写入内存缓冲 → 返回完整字节。</p>
     *
     * <p>超限时抛业务异常，已读的部分随缓冲一起被丢弃，不会返回半截内容。</p>
     */
    private byte[] readEntry(ZipInputStream input) throws Exception {
        // 初始容量取「缓冲区大小」和「允许的最大字节数」中较小的那个：上限很小时不必预先占用大块内存。
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                Math.min(BUFFER_SIZE, properties.getMaxEntryBytes()));
        // 复用同一个读取缓冲区，避免循环里反复分配数组。
        byte[] buffer = new byte[BUFFER_SIZE];
     // 已经写进内存的实际字节总数，是判断是否超限的唯一依据。
        int total = 0;
        // 本次从流里读到的字节数，-1 表示条目已读完。
        int read;
        // 分块读取直到条目结束；全程不把整个条目一次性映射进内存。
        while ((read = input.read(buffer)) != -1) {
            // 先判断「再写这一块会不会超限」再写，用减法而不是加法比较，避免相加时整数溢出导致判断失效。
            if (total > properties.getMaxEntryBytes() - read) {
  // 立刻中止，绝不把超限内容写进内存。
                throw entryTooLarge();
            }
            // 确认安全后才把这一块追加进缓冲。
            output.write(buffer, 0, read);
            // 累加已写字节，供下一轮判断使用。
            total += read;
        }
        // 条目正常读完，返回完整内容给调用方去解码。
        return output.toByteArray();
    }

    /**
  * 用严格模式把字节解码成 UTF-8 文本。
     *
     * <p>为什么用严格模式而不是默认的替换模式：默认模式会把非法字节替换成问号并「解码成功」，
     * 结果是一段夹着乱码的说明书被送进大模型，模型可能照着乱码乱办事，而且这种问题极难排查。
   * 宁可在这里明确报错，让上传者去修文件编码。</p>
     */
    private String decodeUtf8(byte[] bytes) {
        // 解码失败会抛受检异常，必须接住并翻译成业务异常。
        try {
            // 显式创建解码器并把「畸形输入」和「无法映射的字符」都设为报错，而不是静默替换。
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
    // 明确告诉上传者是编码问题，引导他把文件另存为 UTF-8，而不是让他去猜。
            throw new AppException(INVALID_CODE, "SKILL.md 必须是有效的 UTF-8 文本", e);
        }
    }

    /**
     * 在动手处理用户文件之前，先确认解压上限配置本身是可用的。
     *
     * <p>上限被配成 0 或负数等于把防线彻底关掉，这属于部署配置错误而不是用户输入错误，
     * 所以抛的是运行时状态异常而不是业务异常——它需要被运维发现并修配置，不该显示成「你的包有问题」。</p>
     */
    private void validateLimits() {
        // 配置对象缺失或两个上限任一不是正数，都说明防线不成立，直接中止。
        if (properties == null || properties.getMaxEntries() <= 0 || properties.getMaxEntryBytes() <= 0) {
// 用状态异常明确指向配置问题，避免把部署错误误报成用户上传错误。
            throw new IllegalStateException("Skill 包解压边界配置不合法");
        }
    }

    /**
     * 只看开头四个字节判断这是不是一个 ZIP 文件。
     *
     * <p>为什么接受两种签名：PK\3\4 是普通 ZIP 的本地文件头，PK\5\6 是「空 ZIP」的结束记录。
 * 空包也算格式合法（后面会因为找不到 SKILL.md 而被拒），这样报错原因才准确——
     * 用户得到的是「缺少 SKILL.md」而不是含糊的「不是 ZIP」。</p>
     *
     * <p>不依赖前端声明的 contentType：那是用户可随意填写的，判断文件真实类型只能看内容本身。</p>
     */
    private boolean hasZipSignature(byte[] bytes) {
        // 先确保有至少四个字节可读，再逐字节比对两种合法签名之一。
        return bytes != null && bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && ((bytes[2] == 3 && bytes[3] == 4) || (bytes[2] == 5 && bytes[3] == 6));
    }

    /**
   * 造一个「单个文件展开字节超上限」的业务异常。
     *
 * <p>抽出来是因为两个地方都会用到（读之前按声明大小预判、读的过程中按实际字节判），
  * 统一在这里生成能保证两处的错误码和文案完全一致。</p>
     */
    private AppException entryTooLarge() {
 // 文案里带上具体上限值，上传者据此就知道该把文件压到多小。
        return invalid("SKILL.md 展开字节超过上限 " + properties.getMaxEntryBytes());
    }

    /**
     * 造一个统一错误码的「包非法」业务异常，只有文案不同。
  *
     * <p>好处是调用方处理错误时只需匹配一个错误码，而具体原因仍然对用户可见。</p>
     */
    private AppException invalid(String message) {
  // 固定错误码 + 传入文案，避免各处随手写不同的码导致上层无法统一处理。
        return new AppException(INVALID_CODE, message);
    }

    /**
     * 从异常里取一句能给人看的原因。
     *
     * <p>有些底层异常的 message 是空的，直接拼进文案会得到「结构损坏：null」这种没有信息量的提示，
     * 所以这种情况退化成用异常类名，至少能看出是哪类故障。</p>
     */
    private String readableMessage(Exception error) {
        // 消息为空或全是空白时退回类名，保证错误提示里永远有可用信息。
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }
}
