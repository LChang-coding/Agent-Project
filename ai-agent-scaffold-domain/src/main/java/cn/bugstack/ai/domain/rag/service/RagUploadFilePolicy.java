package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.model.valobj.RagUploadFileCandidate;
import cn.bugstack.ai.domain.rag.model.valobj.RagValidatedUploadFile;
import cn.bugstack.ai.types.exception.AppException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 上传文件进入系统前的安全闸门：只放行真正是 PDF、DOCX、Markdown 的文件。
 *
 * <p>解决什么问题：上传口是外部输入进入系统最直接的通道。仅看扩展名或客户端声明的 MIME 完全不可信——
 * 任何人都能把一个可执行文件改名成 .pdf。更危险的是 DOCX 本质是个 ZIP，攻击者可以在里面塞：
 * 解压后膨胀几百倍的压缩炸弹（打爆内存和磁盘）、路径带 ../ 的条目（解压时写到系统目录去）、
 * 或者干脆是个假的 OOXML 结构让解析器崩溃。本类把这些都在落库和解析之前挡住。</p>
 *
 * <p>核心原则是「不信声明，只信内容」：大小以磁盘实际长度为准并要求与声明一致，
 * 格式以文件头魔数和内部结构为准，MIME 只用来做一致性交叉检查。</p>
 *
 * <p>为什么强调流式：单个文件允许到 50 MiB，把内容整体读进堆内存，几个并发上传就能把服务打挂。
 * 所以所有校验都基于受控的临时文件路径做流式读取——魔数只读前几个字节，
 * Markdown 按 8 KiB 缓冲逐段解码，ZIP 只读条目元信息不解压内容。</p>
 *
 * <p>属于哪一层：领域层（domain）的纯规则对象。无状态、线程安全，被上传服务直接 new 出来用。</p>
 *
 * <p>谁会调用它：文档上传服务，在把文件写进对象存储之前调用。</p>
 *
 * <p>它向下调用什么：只用 JDK 的文件、编码和 ZIP 能力，不调任何外部服务、不读库。</p>
 *
 * <p>它不负责什么：不做病毒扫描、不做内容审核、不解析文档正文、不写对象存储、不做权限校验，
 * 也不删除临时文件（临时文件的生命周期由调用方管理）。</p>
 */
public final class RagUploadFilePolicy {

    /**
     * 单个上传文件的大小上限，50 MiB。
     *
     * <p>公开常量，因为接口层也要用它做前置拦截，两边必须用同一个数字，否则会出现「前端放过、后端拒绝」的割裂体验。
     * 这个上限同时约束声明值和磁盘实际长度。</p>
     */
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    /**
     * 文件名长度上限，255 个字符。
     *
     * <p>对齐主流文件系统的单个文件名上限。超长文件名会在拼对象存储键、写日志、渲染界面时到处出问题。</p>
     */
    private static final int MAX_FILE_NAME_LENGTH = 255;
    /**
     * Markdown 编码校验时每次读取的字符数，8 KiB。
     *
     * <p>决定了校验一个 50 MiB 文本文件时的峰值内存只有几十 KB，而不是整个文件。
     * 缓冲太小会增加系统调用次数，太大则失去流式的意义，8 KiB 是常见的折中值。</p>
     */
    private static final int TEXT_BUFFER_CHARS = 8 * 1024;
    /**
     * DOCX 内部允许的最大 ZIP 条目数量，4096 个。
     *
     * <p>防「大量小文件」型压缩炸弹：单个条目都很小，但数量极多，光遍历元信息就能耗尽 CPU。
     * 正常的 Word 文档条目数在几十到几百之间，4096 已经非常宽松。</p>
     */
    private static final int MAX_ZIP_ENTRIES = 4_096;
    /**
     * DOCX 内部单个 ZIP 条目声明的解压大小上限，32 MiB。
     *
     * <p>防「单文件超高压缩比」型炸弹：一个几 KB 的条目可能声明解压后有几 GB。
     * 这里只看条目声明的大小就能提前拒绝，完全不需要真的去解压。</p>
     */
    private static final long MAX_ZIP_ENTRY_BYTES = 32L * 1024 * 1024;
    /**
     * DOCX 内部全部 ZIP 条目声明解压大小的总和上限，100 MiB。
     *
     * <p>单条目限制会被「很多个刚好不超限的条目」绕过，所以还要卡总量。
     * 累加时用溢出检查的加法，防止攻击者用极大值让求和回绕成小数字骗过检查。</p>
     */
    private static final long MAX_ZIP_TOTAL_BYTES = 100L * 1024 * 1024;
    /**
     * OOXML 规范要求每个 DOCX 根目录下必须有的内容类型清单条目名。
     *
     * <p>用它验证「这确实是个 Office 文档」而不只是一个随便打包的 ZIP。缺了它，后面的解析器必然失败。</p>
     */
    private static final String CONTENT_TYPES_ENTRY = "[Content_Types].xml";
    /**
     * DOCX 正文所在的条目路径。
     *
     * <p>与内容类型清单一起构成最小结构校验：两个条目都存在、都不是目录、都有内容，
     * 才认为这是一个能被解析出正文的 Word 文档。</p>
     */
    private static final String DOCUMENT_ENTRY = "word/document.xml";

    /**
     * 支持的扩展名到规范类型的映射表。
     *
     * <p>键是小写扩展名，值里包含三样东西：规范扩展名（用于统一命名，例如 markdown 一律归一成 md）、
     * 规范 MIME（写进对象存储元数据）、以及允许客户端声明的 MIME 集合。</p>
     *
     * <p>为什么允许声明的 MIME 是一个集合：不同浏览器和操作系统对同一种文件给出的 MIME 并不统一，
     * 例如 Markdown 可能被报成 text/markdown、text/x-markdown 或 text/plain。全部接受这三种，
     * 但真正决定用哪个校验器的始终是扩展名，MIME 只用于交叉验证声明是否自相矛盾。</p>
     *
     * <p>不可变常量映射，线程安全，被所有上传共用。要新增支持的格式，除了在这里加一项，
     * 还必须在内容校验里加上对应分支，否则会走到兜底拒绝。</p>
     */
    private static final Map<String, SupportedType> TYPES = Map.of(
            "pdf", new SupportedType("pdf", "application/pdf", Set.of("application/pdf")),
            "docx", new SupportedType("docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
            "md", new SupportedType("md", "text/markdown",
                    Set.of("text/markdown", "text/x-markdown", "text/plain")),
            "markdown", new SupportedType("md", "text/markdown",
                    Set.of("text/markdown", "text/x-markdown", "text/plain")));

    /**
     * 校验一个待上传文件，通过后返回归一化的可信元数据。
     *
     * <p>各层职责：
     * 第一层：入参兜底；
     * 第二层：先用客户端声明的大小做便宜的预筛，明显超限的直接拒绝，连磁盘都不碰；
     * 第三层：把路径规范化，并拒绝符号链接与非普通文件；
     * 第四层：读磁盘实际长度，要求非空、不超限、且与声明完全一致；
     * 第五层：规范化文件名并识别扩展名，拒绝一切带路径语义的名字；
     * 第六层：扩展名映射到规范类型，并交叉校验客户端声明的 MIME；
     * 第七层：按类型做内容级校验（PDF 魔数 / DOCX 容器与结构 / Markdown 编码）；
     * 第八层：校验结束后再复核一次长度，缩小「校验期间文件被替换」的竞态窗口。</p>
     *
     * <p>数据流：
     * 上传候选（路径 + 原始文件名 + 声明大小 + 声明 MIME）
     * → 声明大小预筛
     * → 路径规范化与类型检查
     * → 磁盘实长校验（与声明一致）
     * → 文件名规范化（得到主体名 + 扩展名）
     * → 扩展名映射规范类型
     * → 声明 MIME 一致性校验
     * → 内容级校验
     * → 长度复核
     * → 返回（受控路径 + 规范文件名 + 规范扩展名 + 规范 MIME + 实际大小）</p>
     *
     * <p>为什么校验顺序是「先便宜后昂贵」：声明大小检查是纯内存判断，文件名检查是字符串处理，
     * 而内容级校验要真的读磁盘。把便宜的放前面，一个明显非法的上传就不会消耗任何 IO。</p>
     *
     * <p>为什么最后要再查一次长度：校验过程读了好几次磁盘，中间存在时间差。
     * 如果有人在这期间把文件替换成另一个，前面所有校验就都白做了。再复核一次长度不能完全消除竞态
     * （长度相同的替换仍可能漏过），但能把窗口显著缩小，成本也几乎为零。</p>
     *
     * <p>只读磁盘，不写任何数据。任何一项不通过都抛带明确错误码的业务异常。</p>
     */
    public RagValidatedUploadFile validate(RagUploadFileCandidate candidate) {
        // 第一层：候选为空说明调用方用法有问题，直接拒绝。
        if (candidate == null) {
            // 用统一的错误码抛出，接口层据此翻译成用户可读的提示。
            throw error("RAG_FILE_INVALID", "上传文件不能为空");
        }
        // 第二层：先用客户端声明的大小做预筛。这是纯内存判断，明显超限的上传在这里就被挡掉，不碰磁盘。
        validateDeclaredSize(candidate.declaredSize());
        // 第三层：把路径转成绝对路径并规范化，同时拒绝符号链接和非普通文件。
        Path path = normalizeControlledPath(candidate.path());
        // 第四层：读磁盘实际长度，并要求它与客户端声明完全一致。以磁盘为准，声明只用来交叉验证。
        long actualSize = readAndValidateSize(path, candidate.declaredSize());
        // 第五层：规范化文件名，拆出主体名和扩展名；一切带路径语义或危险字符的名字都会在这里被拒。
        SafeName safeName = normalizeFileName(candidate.originalFileName());
        // 第六层：扩展名映射到规范类型；不支持的格式在这里被拒。扩展名（而不是 MIME）决定用哪个校验器。
        SupportedType type = requireSupportedType(safeName.extension());
        // 交叉校验客户端声明的 MIME 与扩展名是否自相矛盾，例如扩展名是 pdf 却声明成图片。
        validateDeclaredMime(candidate.declaredMimeType(), type);
        // 第七层：内容级校验，真正打开文件看它是不是名副其实。这是拦住「改后缀」攻击的关键一步。
        validateContent(path, type);
        // 第八层：复核长度，确认文件在整个校验过程中没有被替换过。
        ensureUnchangedSize(path, actualSize);
        // 全部通过，返回归一化元数据：受控路径、重新拼装的规范文件名（主体名 + 规范扩展名）、
        // 规范扩展名与规范 MIME（后续存储和解析都用它们，不再看客户端声明），以及磁盘实际大小。
        return new RagValidatedUploadFile(path, safeName.baseName() + "." + type.canonicalExtension(),
                type.canonicalExtension(), type.canonicalMime(), actualSize);
    }

    /**
     * 用客户端声明的大小做便宜的预筛。
     *
     * <p>把 0 和「负数或超限」分成两种错误码：0 通常是用户选错了空文件，超限是文件太大，
     * 两者的处理方式完全不同，提示也应该不一样。负数只可能来自伪造请求，归到超限一类拒绝。</p>
     *
     * <p>纯内存判断，不碰磁盘。</p>
     */
    private void validateDeclaredSize(long declaredSize) {
        // 声明为 0 说明用户选了个空文件，单独给一个错误码，提示更贴切。
        if (declaredSize == 0) {
            // 按「文件为空」拒绝。
            throw error("RAG_FILE_EMPTY", "上传文件不能为空");
        }
        // 负数只可能来自伪造或计算错误，和超限一起拒绝；两者都属于「这个大小不可接受」。
        if (declaredSize < 0 || declaredSize > MAX_FILE_BYTES) {
            // 按「文件过大」拒绝，并在提示里写明具体上限，让用户知道该怎么改。
            throw error("RAG_FILE_TOO_LARGE", "单个知识库文件不能超过 50 MiB");
        }
    }

    /**
     * 把路径规范化，并把校验对象收紧到「一个真实可读的普通文件」。
     *
     * <p>三道检查：不能是符号链接、必须是普通文件、必须可读。检查普通文件时明确不跟随链接，
     * 否则「链接指向的目标是普通文件」也会被判为通过。</p>
     *
     * <p>为什么必须排除符号链接：如果放行，攻击者可以上传一个指向 /etc/passwd 或服务私钥的链接，
     * 后面的内容校验和上传流程就会照着链接把系统文件读出来传到对象存储里去。</p>
     *
     * <p>为什么要先规范化：路径里可能含有 ../ 之类的片段。先转成绝对路径再消解，
     * 后续所有检查才作用在同一个确定的目标上，不会出现「检查的是一个路径、读取的是另一个」。</p>
     *
     * <p>只读磁盘元信息，不读内容。</p>
     */
    private Path normalizeControlledPath(Path input) {
        // 先转绝对路径再消解 ../ 之类的片段，保证后面所有检查和读取都作用在同一个确定目标上。
        Path path = input.toAbsolutePath().normalize();
        // 三个条件任一命中就拒绝——是符号链接（可能指向系统敏感文件）、
        // 不是普通文件（目录、设备文件、管道都不该出现在上传流程里），或者不可读。
        // 判断普通文件时明确不跟随链接，否则指向普通文件的链接会被误判为合法。
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(path)) {
            // 按「路径不受控」拒绝，不透露具体是哪一项不满足，避免帮攻击者试探文件系统结构。
            throw error("RAG_FILE_PATH_INVALID", "上传文件不在可读取的受控路径中");
        }
        // 返回规范化后的受控路径，后续所有读取都用它。
        return path;
    }

    /**
     * 以磁盘上的实际长度为准做校验，并要求它与客户端声明完全一致。
     *
     * <p>三项检查：不能为空、不能超限、必须与声明相等。前两项和声明预筛重复，
     * 但这一次是基于真实数据——预筛只是为了省 IO，真正算数的是这里。</p>
     *
     * <p>为什么要求与声明严格相等：不一致意味着上传过程中发生了截断、
     * 或者客户端在撒谎。无论哪种，后续算出的内容哈希和幂等键都会指向一份不确定的内容，
     * 所以宁可直接失败也不能放行。</p>
     *
     * <p>只读磁盘元信息。读取失败转成明确的读取错误码，而不是让 IO 异常直接冒出去。</p>
     */
    private long readAndValidateSize(Path path, long declaredSize) {
        // 读文件长度要访问磁盘，可能失败，整段包在 try 里以便转成明确的业务错误。
        try {
            // 读磁盘上的真实长度，这才是后续所有判断的依据。
            long actualSize = Files.size(path);
            // 实际长度为 0：客户端可能声明了非零值，但文件其实是空的。
            if (actualSize == 0) {
                // 按「文件为空」拒绝，空文件解析不出任何内容，进索引只会产生永远召不回的空分块。
                throw error("RAG_FILE_EMPTY", "上传文件不能为空");
            }
            // 实际长度超限：声明值可能被伪造成合法值，但真实文件超了。
            if (actualSize > MAX_FILE_BYTES) {
                // 按「文件过大」拒绝，保护后续的存储、解析和内存。
                throw error("RAG_FILE_TOO_LARGE", "单个知识库文件不能超过 50 MiB");
            }
            // 实际长度与声明不一致：要么上传被截断，要么客户端在撒谎。
            if (actualSize != declaredSize) {
                // 单独给一个错误码，便于在监控里把「上传截断」这类问题单独统计出来。
                throw error("RAG_FILE_SIZE_MISMATCH", "上传文件长度与声明值不一致");
            }
            // 返回磁盘实际长度，后续元数据一律用它，不再采信客户端声明。
            return actualSize;
        } catch (IOException e) {
            // 连文件长度都读不到（权限变化、文件被删、磁盘故障），转成明确的读取失败错误码并保留原始异常。
            throw new AppException("RAG_FILE_READ_FAILED", "无法读取上传文件长度", e);
        }
    }

    /**
     * 规范化文件名，并拆出主体名和扩展名。
     *
     * <p>各层职责：
     * 第一层：非空校验；
     * 第二层：Unicode 归一化、去首尾空白、把连续空白压成一个空格；
     * 第三层：一大串安全检查，任何带路径语义、隐藏文件语义或危险字符的名字全部拒绝；
     * 第四层：定位最后一个点，拆出主体名和小写扩展名；
     * 第五层：主体名不能为空。</p>
     *
     * <p>数据流：原始文件名 → NFKC 归一化 + 去空白压缩 → 安全检查 → 按最后一个点拆分
     * → 主体名非空校验 → 返回（主体名 + 小写扩展名）</p>
     *
     * <p>为什么要先做 NFKC 归一化：Unicode 里有很多视觉上一样、编码不同的字符。
     * 攻击者可以用一个「看起来像斜杠但编码不同」的字符绕过路径检查。先折叠成标准形式，检查才有意义。</p>
     *
     * <p>这个名字最终会被拼进对象存储的键，所以它必须既不能带路径语义，也不能带在任何操作系统上有特殊含义的字符。</p>
     *
     * <p>纯字符串处理，不碰磁盘。</p>
     */
    private SafeName normalizeFileName(String originalFileName) {
        // 第一层：文件名为空无法判断格式，也无法生成对象存储键。
        if (originalFileName == null || originalFileName.isBlank()) {
            // 按「文件名非法」拒绝。
            throw error("RAG_FILE_NAME_INVALID", "文件名不能为空");
        }
        // 第二层：NFKC 归一化把视觉等价但编码不同的字符折叠成标准形式（这是后面安全检查有效的前提），
        // 再去掉首尾空白，并把中间的连续空白压成一个空格，避免生成带大段空白的对象键。
        String normalized = Normalizer.normalize(originalFileName, Normalizer.Form.NFKC).trim()
                .replaceAll("\\s+", " ");
        // 第三层：安全检查，任一命中就拒绝——
        // 超长（撑坏文件系统、日志和界面）；
        // 名字就是 . 或 ..（这是目录语义，不是文件名）；
        // 以点开头（Unix 隐藏文件，也容易绕过扩展名判断）；
        // 以点结尾（Windows 会静默去掉结尾的点，导致实际落地的名字和校验的不一致）；
        // 含 ..（路径穿越的核心特征）；
        // 含正斜杠或反斜杠（直接就是路径分隔符，会让文件被写到别的目录去）；
        // 含冒号（Windows 盘符和 NTFS 备用数据流的语义）；
        // 含其他不安全字符（控制字符、引号、通配符等，见下面的字符白名单）。
        if (normalized.length() > MAX_FILE_NAME_LENGTH || normalized.equals(".") || normalized.equals("..")
                || normalized.startsWith(".") || normalized.endsWith(".") || normalized.contains("..")
                || normalized.indexOf('/') >= 0 || normalized.indexOf('\\') >= 0
                || normalized.indexOf(':') >= 0 || containsUnsafeCharacter(normalized)) {
            // 统一按「文件名包含不安全字符或路径片段」拒绝，不逐项说明是哪一条，避免帮攻击者逐步试探规则。
            throw error("RAG_FILE_NAME_INVALID", "文件名包含不安全字符或路径片段");
        }
        // 第四层：从最后一个点开始拆。用最后一个点而不是第一个，是因为「报告.2024.pdf」这种名字很常见。
        int dot = normalized.lastIndexOf('.');
        // 点必须存在、不能在第 0 位（那是隐藏文件，前面已拒但这里再兜一次）、也不能是最后一个字符（没有扩展名）。
        if (dot < 1 || dot == normalized.length() - 1) {
            // 没有可用扩展名就无法确定该用哪个校验器，按「扩展名不受支持」拒绝。
            throw error("RAG_FILE_EXTENSION_UNSUPPORTED", "文件扩展名不受支持");
        }
        // 点之前是主体名，再去一次首尾空白（例如「报告 .pdf」这种写法）。
        String baseName = normalized.substring(0, dot).trim();
        // 点之后是扩展名，统一转小写；用固定语区避免不同语区下大小写转换结果不一致。
        String extension = normalized.substring(dot + 1).toLowerCase(Locale.ROOT);
        // 主体名去空白后为空（例如名字就是「 .pdf」），这样的名字没有任何可辨识信息。
        if (baseName.isBlank()) {
            // 按「文件名主体为空」拒绝。
            throw error("RAG_FILE_NAME_INVALID", "文件名主体不能为空");
        }
        // 返回拆好的主体名和小写扩展名，供后续映射类型和重新拼装规范文件名。
        return new SafeName(baseName, extension);
    }

    /**
     * 判断文件名里是否含有不安全字符。
     *
     * <p>采用白名单而不是黑名单：只允许字母数字、空格、下划线、连字符、点、圆括号、方括号，
     * 其余一律视为不安全。黑名单永远列不全（各操作系统的特殊字符、各种 Unicode 控制字符），
     * 白名单则默认拒绝，安全性由「允许什么」而不是「禁止什么」来保证。</p>
     *
     * <p>控制字符单独判一次，因为它们既不属于字母数字也肉眼不可见，混在文件名里极易被忽略，
     * 却能在日志、终端和 HTTP 头里造成注入。</p>
     *
     * <p>按码点遍历而不是按 char，这样超出基本平面的字符不会被拆成两半误判。纯字符串判断，不碰磁盘。</p>
     */
    private boolean containsUnsafeCharacter(String value) {
        // 逐个码点判断：先直接拒绝控制字符，然后要求必须落在白名单内——
        // 字母数字（含中文等各国文字）、空格、下划线、连字符、点、圆括号、方括号。
        // 白名单之外的字符（引号、星号、竖线、分号、各种不可见字符）一律判为不安全。
        return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || !(Character.isLetterOrDigit(codePoint) || codePoint == ' ' || codePoint == '_'
                || codePoint == '-' || codePoint == '.' || codePoint == '(' || codePoint == ')'
                || codePoint == '[' || codePoint == ']'));
    }

    /**
     * 把扩展名映射到唯一的规范类型。
     *
     * <p>映射表里没有的扩展名一律拒绝，这是「只支持三种格式」这条规则的落脚点。
     * markdown 和 md 会映射到同一个规范类型，保证同一种格式在系统里只有一种表示。</p>
     */
    private SupportedType requireSupportedType(String extension) {
        // 按小写扩展名查表；查不到就意味着这是不支持的格式。
        SupportedType type = TYPES.get(extension);
        // 表里没有说明格式不支持。
        if (type == null) {
            // 明确告诉用户支持哪三种格式，比笼统的「不支持」更有用。
            throw error("RAG_FILE_EXTENSION_UNSUPPORTED", "仅支持 PDF、DOCX 和 Markdown 文件");
        }
        // 返回规范类型，后续的 MIME 校验、内容校验和元数据都基于它。
        return type;
    }

    /**
     * 交叉校验客户端声明的 MIME 与扩展名是否自相矛盾。
     *
     * <p>先剥掉 MIME 后面的参数（例如 charset=utf-8），再转小写，然后看它是否落在这个扩展名允许的集合里。</p>
     *
     * <p>为什么 MIME 只做交叉校验而不做格式判定：MIME 完全由客户端提供，可以随意伪造，
     * 真正决定格式的是扩展名和文件内容。但如果声明的 MIME 和扩展名明显矛盾，
     * 说明这个请求本身不正常，值得直接拒绝。</p>
     */
    private void validateDeclaredMime(String declaredMimeType, SupportedType type) {
        // MIME 为空说明客户端没有正常填写请求，无法做交叉校验。
        if (declaredMimeType == null || declaredMimeType.isBlank()) {
            // 按「MIME 非法」拒绝。
            throw error("RAG_FILE_MIME_INVALID", "文件 MIME 不能为空");
        }
        // 只取分号前的主类型，剥掉 charset 等参数；再去空白转小写，用固定语区保证结果稳定。
        String normalized = declaredMimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        // 声明的 MIME 不在这个扩展名允许的集合里，说明扩展名和声明互相矛盾。
        if (!type.allowedDeclaredMimes().contains(normalized)) {
            // 按「扩展名与声明 MIME 不一致」拒绝：这种自相矛盾的请求本身就不正常，不值得放行。
            throw error("RAG_FILE_MIME_MISMATCH", "文件扩展名与声明 MIME 不一致");
        }
    }

    /**
     * 按规范扩展名做内容级校验，这是拦住「只改后缀」攻击的关键一步。
     *
     * <p>各层职责：
     * 第一层：按规范扩展名分派到对应的校验器；
     * 第二层：没有对应校验器的类型直接拒绝，绝不默认放行；
     * 第三层：业务异常原样透出（它们带着精确的错误码），IO 异常统一转成读取失败。</p>
     *
     * <p>数据流：受控路径 + 规范类型 → 按扩展名分派 → PDF 魔数 / DOCX 容器与结构 / Markdown 编码
     * → 业务异常原样抛出；IO 异常转成读取失败</p>
     *
     * <p>为什么兜底分支要拒绝：将来在类型映射表里新增格式时，如果这里默认放行，
     * 那个新格式就等于完全没做内容校验，「改后缀」攻击立刻生效。宁可让新格式先失败，也不能默许放行。</p>
     *
     * <p>会读磁盘（只读必要部分，不整体加载）。</p>
     */
    private void validateContent(Path path, SupportedType type) {
        // 内容校验都要读磁盘，可能抛 IO 异常，需要统一转成业务错误码。
        try {
            // 按规范扩展名分派：注意用的是规范扩展名而不是用户写的原扩展名，所以 markdown 也会走 md 分支。
            switch (type.canonicalExtension()) {
                // PDF 只校验固定的文件头魔数，完整解析留给后面的解析器做。
                case "pdf" -> validatePdf(path);
                // DOCX 要校验 ZIP 容器合法、无压缩炸弹、无路径穿越、且具备最小 OOXML 结构。
                case "docx" -> validateDocx(path);
                // Markdown 是纯文本，校验它必须是严格的 UTF-8 且不含 NUL 字符。
                case "md" -> validateUtf8Markdown(path);
                // 兜底：类型映射表里加了新格式却没在这里加校验分支。直接拒绝，绝不默认放行，
                // 否则那个新格式就成了一个完全没有内容校验的通道。
                default -> throw error("RAG_FILE_EXTENSION_UNSUPPORTED", "文件扩展名不受支持");
            }
        // 业务异常已经带着精确的错误码和文案（例如魔数不匹配、压缩炸弹），原样透出即可。
        } catch (AppException e) {
            // 原样重抛，不做任何包装，避免丢失具体原因。
            throw e;
        // 只有真正的 IO 异常才走到这里（文件被删、磁盘错误、权限变化）。
        } catch (IOException e) {
            // 统一转成读取失败错误码并保留原始异常，便于排查是环境问题还是文件问题。
            throw new AppException("RAG_FILE_READ_FAILED", "读取上传文件失败", e);
        }
    }

    /**
     * 校验 PDF 的文件头魔数。
     *
     * <p>只读前 5 个字节并逐字节比对 %PDF- 这个固定标记。这一步的目的不是判断 PDF 是否完整可解析，
     * 而是拦住「把别的文件改名成 .pdf」这种最常见的绕过手法。完整性由后续的解析器负责。</p>
     *
     * <p>只读文件开头 5 个字节，不加载整个文件。</p>
     */
    private void validatePdf(Path path) throws IOException {
        // 只读开头 5 个字节，足够判断魔数，也不会因为文件很大而占内存。
        byte[] prefix = readPrefix(path, 5);
        // PDF 规范要求文件以 %PDF- 开头；用 ASCII 编码取字节，这个标记只含 ASCII 字符。
        byte[] expected = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        // 读到的字节比魔数还短，说明文件太小，压根不可能是 PDF。
        if (prefix.length < expected.length) {
            // 按魔数不匹配拒绝。
            throw error("RAG_FILE_MAGIC_MISMATCH", "PDF 文件头不合法");
        }
        // 逐字节比对；不用字符串比较是为了避免把二进制字节按某种编码解释时产生歧义。
        for (int index = 0; index < expected.length; index++) {
            // 任何一个字节不同就说明这不是 PDF。
            if (prefix[index] != expected[index]) {
                // 立刻拒绝，不继续比对剩下的字节。
                throw error("RAG_FILE_MAGIC_MISMATCH", "PDF 文件头不合法");
            }
        }
    }

    /**
     * 校验 DOCX：既要是合法 ZIP 容器，又不能是压缩炸弹或路径穿越，还必须具备最小 OOXML 结构。
     *
     * <p>各层职责：
     * 第一层：先看 ZIP 魔数，不是 ZIP 就没必要往下走；
     * 第二层：打开 ZIP 并检查条目总数，防「海量小条目」型炸弹；
     * 第三层：逐条目校验路径安全、拒绝重复条目；
     * 第四层：逐条目校验声明的解压大小，单条超限、总量超限、求和溢出都拒绝；
     * 第五层：顺路记录两个必需条目是否存在；
     * 第六层：必需条目缺失则拒绝；
     * 第七层：业务异常原样透出，ZIP 读取异常转成「不是可读的 OOXML」。</p>
     *
     * <p>数据流：
     * 受控路径
     * → ZIP 魔数校验
     * → 打开 ZIP → 条目数量校验
     * → 逐条目：路径安全校验 → 重复校验 → 大小校验（单条 / 累计 / 溢出）→ 标记必需条目
     * → 必需条目完整性校验
     * → 通过</p>
     *
     * <p>为什么只看声明的大小而不真的解压：解压才是危险动作——一个 10 KB 的条目可能膨胀成 10 GB。
     * ZIP 的条目头里就写着解压后的大小，读元信息就能提前拒绝，成本几乎为零，也完全不会被炸到。</p>
     *
     * <p>为什么要拒绝重复条目名：同名条目会让不同解析器读到不同的那一份（有的取第一个，有的取最后一个）。
     * 攻击者可以借此让安全检查看到无害的那份、解析器读到恶意的那份。</p>
     *
     * <p>只读 ZIP 元信息，不解压任何内容。</p>
     */
    private void validateDocx(Path path) throws IOException {
        // 第一层：先看魔数。不是 ZIP 就直接失败，省掉打开 ZIP 的开销，也避免把畸形文件喂给 ZIP 解析器。
        validateZipMagic(path);
        // 第二层：以 UTF-8 打开 ZIP（OOXML 的条目名是 UTF-8）；用 try-with-resources 保证句柄一定被关闭，
        // 否则大量畸形上传会把文件句柄耗尽。
        try (ZipFile zipFile = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            // 条目数为 0 说明是个空包（不可能是 Word 文档），超过上限则是「海量小条目」型压缩炸弹。
            if (zipFile.size() == 0 || zipFile.size() > MAX_ZIP_ENTRIES) {
                // 两种情况都按压缩炸弹拒绝。
                throw error("RAG_FILE_ZIP_BOMB", "DOCX ZIP 条目数量超过安全限制");
            }
            // 累计所有条目声明的解压大小，用于总量限制。
            long totalDeclaredBytes = 0L;
            // 标记是否找到内容类型清单条目，OOXML 必需。
            boolean contentTypesFound = false;
            // 标记是否找到正文条目，OOXML 必需。
            boolean documentFound = false;
            // 记录已见过的条目名，用来发现重复条目。
            Set<String> entryNames = new HashSet<>();
            // 取条目枚举器，逐个遍历。只读元信息，不打开任何条目的数据流。
            var entries = zipFile.entries();
            // 第三层到第五层：逐条目校验。
            while (entries.hasMoreElements()) {
                // 取出下一个条目。
                ZipEntry entry = entries.nextElement();
                // 先校验条目路径安全：绝对路径、反斜杠、盘符、空字节、重复分隔符、路径穿越片段全部拒绝。
                String entryName = validateZipEntryName(entry.getName());
                // 加入已见集合，返回 false 说明这个名字之前出现过。
                if (!entryNames.add(entryName)) {
                    // 重复条目名必须拒绝：不同解析器对重复名的取舍不一致，攻击者能借此让校验和解析看到不同内容。
                    throw error("RAG_FILE_ZIP_ENTRY_INVALID", "DOCX 包含重复 ZIP 条目");
                }
                // 取条目声明的解压后大小。
                long size = entry.getSize();
                // 取压缩后大小，主要用于确认元信息完整（为负说明 ZIP 头里没写）。
                long compressedSize = entry.getCompressedSize();
                // 任一为负说明 ZIP 头里没有声明大小，无法做压缩炸弹判断。
                if (size < 0 || compressedSize < 0) {
                    // 大小未知就拒绝，绝不「不知道就放行」——那等于给炸弹开了个后门。
                    throw error("RAG_FILE_ZIP_ENTRY_INVALID", "DOCX ZIP 条目大小未知");
                }
                // 第四层：单个条目声明的解压大小超限，典型的超高压缩比炸弹。
                if (size > MAX_ZIP_ENTRY_BYTES) {
                    // 按压缩炸弹拒绝，不需要真的去解压验证。
                    throw error("RAG_FILE_ZIP_BOMB", "DOCX 单个 ZIP 条目声明解压大小超过限制");
                }
                // 累加总量时可能溢出，需要单独捕获。
                try {
                    // 用带溢出检查的加法：普通加法遇到极大值会回绕成负数或小数字，反而骗过后面的总量检查。
                    totalDeclaredBytes = Math.addExact(totalDeclaredBytes, size);
                // 溢出说明条目声明的大小被刻意做得极大。
                } catch (ArithmeticException e) {
                    // 同样按压缩炸弹拒绝。
                    throw error("RAG_FILE_ZIP_BOMB", "DOCX ZIP 声明解压大小溢出");
                }
                // 累计总量超限：单条限制会被「很多个刚好不超限的条目」绕过，所以必须再卡总量。
                if (totalDeclaredBytes > MAX_ZIP_TOTAL_BYTES) {
                    // 按压缩炸弹拒绝。
                    throw error("RAG_FILE_ZIP_BOMB", "DOCX ZIP 总声明解压大小超过限制");
                }
                // 第五层：顺路确认内容类型清单条目存在。
                if (CONTENT_TYPES_ENTRY.equals(entryName)) {
                    // 必须是文件而不是目录，且要有实际内容；空的或目录形式的同名条目不算真的存在。
                    contentTypesFound = !entry.isDirectory() && size > 0;
                } else if (DOCUMENT_ENTRY.equals(entryName)) {
                    // 同样确认正文条目存在，判断口径与上面一致。
                    documentFound = !entry.isDirectory() && size > 0;
                }
            }
            // 第六层：两个必需条目缺一个都说明这不是能解析出正文的 Word 文档（可能只是个改名的普通 ZIP）。
            if (!contentTypesFound || !documentFound) {
                // 按「DOCX 结构非法」拒绝，避免把它交给解析器后得到一个含义不明的失败。
                throw error("RAG_FILE_DOCX_STRUCTURE_INVALID", "DOCX 缺少必要的 OOXML 条目");
            }
        // 前面各处抛出的业务异常带着精确错误码，原样透出。
        } catch (AppException e) {
            // 原样重抛，不做包装。
            throw e;
        // ZIP 打开或读取本身失败（结构损坏、加密、被截断）。
        } catch (IOException e) {
            // 转成「不是可读的 OOXML 文件」并保留原始异常，比暴露 ZIP 库的内部报错更有意义。
            throw new AppException("RAG_FILE_DOCX_INVALID", "DOCX 不是可读取的 OOXML 文件", e);
        }
    }

    /**
     * 校验 ZIP 魔数，接受三种合法的 PK 头。
     *
     * <p>三种分别是：普通的本地文件头（PK 03 04）、空归档的结束记录（PK 05 06）、
     * 分卷归档标记（PK 07 08）。只认第一种会误拒某些工具生成的合法包，所以三种都接受。</p>
     *
     * <p>只读文件开头 4 个字节。</p>
     */
    private void validateZipMagic(Path path) throws IOException {
        // 只读开头 4 个字节，足够判断 ZIP 魔数。
        byte[] prefix = readPrefix(path, 4);
        // 必须恰好读到 4 个字节，前两个是 P、K，后两个是三种合法组合之一：
        // 03 04 普通本地文件头、05 06 空归档结束记录、07 08 分卷归档标记。
        boolean zip = prefix.length == 4 && prefix[0] == 'P' && prefix[1] == 'K'
                && ((prefix[2] == 3 && prefix[3] == 4)
                || (prefix[2] == 5 && prefix[3] == 6)
                || (prefix[2] == 7 && prefix[3] == 8));
        // 三种都不匹配，说明这压根不是 ZIP 容器。
        if (!zip) {
            // 按魔数不匹配拒绝，不再尝试打开它。
            throw error("RAG_FILE_MAGIC_MISMATCH", "DOCX 不是合法的 ZIP 容器");
        }
    }

    /**
     * 校验单个 ZIP 条目的路径安全，这是防「解压穿越」的核心。
     *
     * <p>各层职责：
     * 第一层：整体特征检查，绝对路径、反斜杠、盘符、空字节、重复分隔符一律拒绝；
     * 第二层：按斜杠拆成片段，逐段拒绝空片段、单点和双点。</p>
     *
     * <p>数据流：原始条目名 → 整体特征检查 → 按 / 拆分 → 逐片段校验（允许末尾空片段表示目录）
     * → 返回原名</p>
     *
     * <p>为什么必须这么严：ZIP 条目名会被解压程序当成相对路径。一个名为 ../../etc/cron.d/x 的条目
     * 在解压时就能把文件写到系统目录去，等于任意文件写入。空字节则可能让底层 C 库把路径提前截断，
     * 让实际写入的位置和检查的位置不一致。</p>
     *
     * <p>纯字符串判断，不碰磁盘。校验通过后返回原名（不做改写），因为条目名要用于和必需条目做精确比对。</p>
     */
    private String validateZipEntryName(String rawName) {
        // 第一层：整体特征检查，任一命中就拒绝——
        // 空名或空白名（没有意义的条目）；
        // 以斜杠或反斜杠开头（绝对路径，解压会写到根目录去）；
        // 含反斜杠（Windows 路径分隔符，会绕过只按正斜杠做的片段检查）；
        // 含冒号（盘符或 NTFS 备用数据流语义）；
        // 含空字节（可能让底层库提前截断路径，使实际写入位置与检查位置不一致）；
        // 含连续两个斜杠（不同解压实现对它的处理不一致，容易产生歧义）。
        if (rawName == null || rawName.isBlank() || rawName.startsWith("/") || rawName.startsWith("\\")
                || rawName.indexOf('\\') >= 0 || rawName.indexOf(':') >= 0 || rawName.indexOf('\0') >= 0
                || rawName.contains("//")) {
            // 统一按「条目路径不安全」拒绝，不逐项说明，避免帮攻击者摸清规则。
            throw error("RAG_FILE_ZIP_ENTRY_INVALID", "DOCX ZIP 条目路径不安全");
        }
        // 第二层：按正斜杠拆成片段；limit 传 -1 是为了保留末尾的空片段，
        // 这样才能识别出「以斜杠结尾的目录条目」这种合法形式。
        String[] segments = rawName.split("/", -1);
        // 逐片段检查；用下标遍历是因为需要知道当前是不是最后一段。
        for (int index = 0; index < segments.length; index++) {
            // 取出这一段。
            String segment = segments[index];
            // 只有「最后一段且为空」才是合法的目录结尾标记（例如 word/ 这种条目名）。
            boolean trailingDirectoryMarker = index == segments.length - 1 && segment.isEmpty();
            // 除了那个合法的目录结尾标记，任何空片段、单点（当前目录）、双点（上级目录）都必须拒绝。
            // 双点是路径穿越的核心特征，单点和空片段则会让不同解压实现算出不同的最终路径。
            if (!trailingDirectoryMarker && (segment.isBlank() || segment.equals(".") || segment.equals(".."))) {
                // 按「包含路径穿越片段」拒绝。
                throw error("RAG_FILE_ZIP_ENTRY_INVALID", "DOCX ZIP 条目包含路径穿越片段");
            }
        }
        // 校验通过，返回原始名字（不做任何改写）；后面要用它和必需条目名做精确比对，改写会导致比对失败。
        return rawName;
    }

    /**
     * 只读取开头若干字节，用于魔数判断。
     *
     * <p>用 try-with-resources 保证流一定关闭，否则大量畸形上传会把文件句柄耗尽。
     * readNBytes 在文件比请求长度短时会返回实际读到的字节，所以调用方必须自己检查长度。</p>
     */
    private byte[] readPrefix(Path path, int length) throws IOException {
        // 打开输入流；try-with-resources 保证无论是否抛异常都会关闭，避免句柄泄漏。
        try (InputStream input = Files.newInputStream(path)) {
            // 只读请求的字节数就返回。文件不够长时会返回更短的数组，调用方需要自行判断长度。
            return input.readNBytes(length);
        }
    }

    /**
     * 流式校验 Markdown 必须是严格的 UTF-8，且不含 NUL 字符。
     *
     * <p>各层职责：
     * 第一层：构造一个「遇到非法字节就报错」的严格解码器；
     * 第二层：按固定大小的缓冲逐段读取，边读边扫 NUL；
     * 第三层：解码异常统一转成编码错误。</p>
     *
     * <p>数据流：文件流 → 严格 UTF-8 解码器 → 每次读 8 KiB 字符 → 逐字符查 NUL
     * → 读完则通过；解码失败则报编码错误</p>
     *
     * <p>为什么要用严格解码器：Java 默认的解码行为是把非法字节替换成问号菱形，静默继续。
     * 那样一个 GBK 编码的文件也能「成功」读完，只是内容全是乱码——最后进索引的就是一堆垃圾。
     * 显式要求遇到非法字节和不可映射字符时报错，才能真正把非 UTF-8 文件挡在外面。</p>
     *
     * <p>为什么要拒绝 NUL：文本文件里出现 NUL 基本可以断定它其实是二进制文件（只是改了后缀）。
     * 而且 NUL 在很多下游系统里会截断字符串，造成校验和实际处理不一致。</p>
     *
     * <p>流式读取，峰值内存只有一个缓冲区大小，与文件实际大小无关。</p>
     */
    private void validateUtf8Markdown(Path path) throws IOException {
        // 构造严格 UTF-8 解码器：遇到非法字节序列报错、遇到不可映射字符报错。
        // 必须显式设置，否则默认行为是静默替换成问号菱形，让非 UTF-8 文件也能「校验通过」。
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        // 用 try-with-resources 打开字符流；解码异常在这一层统一捕获。
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), decoder)) {
            // 固定大小的字符缓冲，保证峰值内存与文件大小无关。
            char[] buffer = new char[TEXT_BUFFER_CHARS];
            // 保存每次实际读到的字符数；循环里要用它作为扫描上界。
            int count;
            // 逐段读取直到流结束；解码错误会在读取过程中抛出，由外层捕获。
            while ((count = reader.read(buffer)) >= 0) {
                // 只扫描本次真实读到的那部分，缓冲区剩余位置可能是上一轮的残留数据，不能一起扫。
                for (int index = 0; index < count; index++) {
                    // 出现 NUL 字符：文本文件里不该有它，出现基本说明这是个改了后缀的二进制文件。
                    if (buffer[index] == '\0') {
                        // 按「文本包含 NUL」拒绝，防止它在下游系统里截断字符串导致处理与校验不一致。
                        throw error("RAG_FILE_TEXT_NUL", "Markdown 文件不能包含 NUL 字符");
                    }
                }
            }
        // 解码失败说明文件不是合法的 UTF-8（常见于 GBK、UTF-16 或二进制文件改后缀）。
        } catch (CharacterCodingException e) {
            // 转成明确的编码错误，提示用户必须用 UTF-8 保存，而不是抛一个难懂的解码异常。
            throw error("RAG_FILE_TEXT_ENCODING_INVALID", "Markdown 文件必须使用 UTF-8 编码");
        }
    }

    /**
     * 校验结束后再复核一次文件长度。
     *
     * <p>整个校验过程读了好几次磁盘，中间存在时间差。如果有人在这期间把文件替换掉，
     * 前面所有校验就都是针对旧文件做的（典型的检查与使用之间的竞态）。
     * 再比一次长度不能彻底消除竞态（长度相同的替换仍能漏过），但能把窗口显著缩小，成本几乎为零。</p>
     *
     * <p>只读磁盘元信息。</p>
     */
    private void ensureUnchangedSize(Path path, long expectedSize) {
        // 读长度要访问磁盘，可能失败，需要转成明确的业务错误。
        try {
            // 与校验开始时记录的长度比对；不一致说明文件在校验期间被换过。
            if (Files.size(path) != expectedSize) {
                // 按「校验期间文件发生变化」拒绝，让调用方重新走一遍上传流程。
                throw error("RAG_FILE_CHANGED_DURING_VALIDATION", "上传文件在校验过程中发生变化");
            }
        // 复核时连长度都读不到（文件被删、权限变化），同样视为不可信。
        } catch (IOException e) {
            // 转成读取失败错误码并保留原始异常。
            throw new AppException("RAG_FILE_READ_FAILED", "复核上传文件长度失败", e);
        }
    }

    /**
     * 统一构造业务异常。
     *
     * <p>本类所有拒绝路径都经它产出异常，保证错误码格式统一、接口层可以稳定识别并翻译成用户提示。</p>
     */
    private AppException error(String code, String message) {
        // 只构造异常并返回，由各校验点自行抛出，便于在条件表达式里直接使用。
        return new AppException(code, message);
    }

    /**
     * 已经排除全部路径语义的安全文件名，拆成主体名和小写扩展名两部分。
     *
     * <p>能构造出这个对象就意味着名字已经通过全部安全检查。之后拼对象存储键、生成展示名都基于它，
     * 不会再回头使用用户传来的原始文件名。</p>
     *
     * <p>不可变值对象，不涉及持久化。</p>
     */
    private record SafeName(String baseName, String extension) {
    }

    /**
     * 一种受支持格式的完整描述：规范扩展名、规范 MIME、以及允许客户端声明的 MIME 集合。
     *
     * <p>规范扩展名用于统一命名（markdown 归一成 md）并决定走哪个内容校验器；
     * 规范 MIME 写进对象存储元数据；允许声明集合只用于交叉校验客户端声明是否自相矛盾。</p>
     *
     * <p>不可变值对象，作为常量映射的值被所有上传共用。</p>
     */
    private record SupportedType(String canonicalExtension, String canonicalMime,
                                 Set<String> allowedDeclaredMimes) {
    }
}
