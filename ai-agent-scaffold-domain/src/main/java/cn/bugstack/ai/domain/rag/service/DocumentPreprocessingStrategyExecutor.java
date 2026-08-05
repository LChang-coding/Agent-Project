package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.port.RagDocumentParserPort;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr;
import cn.bugstack.ai.domain.rag.model.valobj.RagPreprocessingStrategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 按指定的「预处理策略」把已解析出来的文档中间表示（IR）改造成对应形态，供消融实验对比效果。
 *
 * <p>解决什么问题：一份文档从原始文件到可检索的分块，中间要经过格式解析、清洗、结构感知分块几步。
 * 要证明每一步到底有没有用，就得能一键把某一步关掉再跑一遍评测，这叫消融（ablation）。
 * 本类就是这个开关执行器：给它一个策略，它就产出「关掉某一步之后的 IR」，
 * 并把清洗过程的审计记录一起带出来，让结果可复核而不是黑盒。</p>
 *
 * <p>属于哪一层：领域层（domain）的纯计算服务。手动 new 出来使用，不是 Spring Bean。</p>
 *
 * <p>谁会调用它：摄取 Worker（RagIngestWorker）在解析完文档、准备分块之前调用一次。生产流量固定走完整策略，
 * 只有压测和评测场景才会传入被削弱的策略。</p>
 *
 * <p>它向下调用什么：只调用 IR 清洗器（DocumentIrCleaner）；其余都是本类内部的文本改写。</p>
 *
 * <p>它不负责什么：不下载文件、不做格式解析、不做分块、不生成向量、不写库、不产生任何索引副作用。
 * 它只把「输入的解析结果」换成「另一种形态的解析结果」，纯函数式，重复执行结果一致。</p>
 */
public final class DocumentPreprocessingStrategyExecutor {

    /**
     * 用来剥掉 Markdown 装饰符号的正则，只在「纯文本基线」策略里使用。
     *
     * <p>覆盖行首的标题号、列表符号、引用号，以及行内的代码反引号、加粗斜体标记和下划线，
     * 还有链接写法。链接部分用了捕获组，替换成 $1 后只留下链接文字、丢掉地址，
     * 这样纯文本基线看到的就是人眼读到的内容，不含格式噪声。</p>
     *
     * <p>常量、线程安全、被所有调用共用，不涉及租户数据。</p>
     */
    private static final Pattern MARKDOWN_DECORATION = Pattern.compile(
            "(?m)^(?:#{1,6}\\s+|[-*+]\\s+|>\\s+)|`{1,3}|\\*{1,2}|_{1,2}|\\[(.+?)]\\(.+?\\)");
    /**
     * IR 清洗器，负责去掉页眉页脚、水印、页码这类干扰内容。
     *
     * <p>只有策略声明启用清洗时才会调用它；清洗器还会返回每一步的审计记录，
     * 这些记录最终会写进摄取任务的阶段日志，让「清洗到底改了什么」可查。</p>
     */
    private final DocumentIrCleaner cleaner;

    /**
     * 构造执行器，必须传入可用的清洗器。
     *
     * <p>清洗器为空时直接拒绝构造，而不是等到执行阶段才空指针；因为一旦执行到一半失败，
     * 摄取任务已经产生了阶段日志，排查成本高得多。</p>
     */
    public DocumentPreprocessingStrategyExecutor(DocumentIrCleaner cleaner) {
        // 清洗器是必需依赖：启用清洗的策略离了它没法跑，宁可在装配阶段就失败。
        if (cleaner == null) throw new IllegalArgumentException("Cleaner不能为空");
        // 保存清洗器引用，后续按策略决定用不用它。
        this.cleaner = cleaner;
    }

    /**
     * 按策略产出改造后的文档 IR 与清洗审计记录。
     *
     * <p>各层职责：
     * 第一层：入参兜底，解析结果和策略都必须给全，否则后面每一步都无从判断；
     * 第二层：按策略决定是否跑清洗链，跑了就同时留下审计记录；
     * 第三层：按策略决定是否保留结构。要保留就只打一个策略标记；不保留就先按策略取出扁平文本，
     * 再把整篇文档压成单页单块；
     * 第四层：把策略、改造后的 IR、清洗审计打包返回。</p>
     *
     * <p>数据流：
     * 解析结果（IR + 归一化 Markdown） + 策略
     * → 非空校验
     * → 启用清洗则跑清洗链，得到清洗后 IR 与审计记录
     * → 保留结构：在 IR 上打策略标记，页面与块结构原样保留
     * → 不保留结构：按策略取扁平文本（旧链路 Markdown / 去标记纯文本 / 可检索正文拼接）→ 压成单块 IR
     * → 返回策略 + 最终 IR + 清洗审计</p>
     *
     * <p>关键输入：parsed 里既有结构化 IR 也有归一化 Markdown，不同策略取的是不同字段，这正是消融的对比点。</p>
     *
     * <p>不写库、不调外部服务、不产生索引副作用。失败条件：入参为空、扁平化结果为空文本、
     * 或者遇到一个声明「不保留结构」但没有对应扁平化实现的新策略。</p>
     */
    public Result execute(RagDocumentParserPort.ParsedDocument parsed, RagPreprocessingStrategy strategy) {
        // 解析结果和策略缺一个都没法继续：没有解析结果无内容可改造，没有策略不知道该关掉哪一步。
        if (parsed == null || strategy == null) throw new IllegalArgumentException("解析结果和策略不能为空");
        // 取出格式解析产出的结构化中间表示，它保留了页面、块类型、标题层级等全部结构信息。
        DocumentIr source = parsed.documentIr();
        // 审计记录默认空：策略若不启用清洗，就没有任何清洗步骤可审计。
        List<DocumentIrCleaner.CleaningAudit> audits = List.of();
        // candidate 是「当前正在加工的 IR」，先指向原始解析结果，后面每一步在它基础上继续改造。
        DocumentIr candidate = source;
        // 第二层：策略决定要不要清洗。关掉清洗正是为了验证「页眉页脚水印到底会不会污染召回」。
        if (strategy.cleanerEnabled()) {
            // 跑带审计的清洗，一次拿到清洗后文档和每一步的改动记录。
            DocumentIrCleaner.CleaningResult cleaned = cleaner.cleanWithAudit(source);
            // 后续加工基于清洗后的 IR 继续，原始 IR 不再使用。
            candidate = cleaned.document();
            // 留下清洗审计，调用方会把它逐条写进摄取任务的阶段日志，事后可核对清洗改了什么。
            audits = cleaned.audits();
        }
        // 第三层：策略声明不保留结构时，就要把文档压平，模拟「没有结构感知分块」的旧做法。
        if (!strategy.structurePreserved()) {
            // 按策略选出用来压平的文本来源；三种来源代表三条不同的退化链路，这是消融的核心对比点。
            String text = switch (strategy) {
                // 旧链路：直接用格式解析产出的归一化 Markdown，等于连 IR 都不用，格式标记全部留在正文里。
                case LEGACY_MARKDOWN_FLATTEN -> parsed.normalizedMarkdown();
                // 纯文本基线：在归一化 Markdown 基础上再剥掉全部 Markdown 标记，只剩人眼读到的文字。
                case RAW_TEXT_CHUNK -> plainText(parsed.normalizedMarkdown());
                // 走了清洗但不做结构感知分块：从 IR 里挑出可检索的正文块拼成一整段，标题和表格结构全部丢弃。
                case IR_NO_STRUCTURED_CHUNKING -> retrievableText(candidate);
                // 兜底：某个策略声明了「不保留结构」却没在这里实现对应的扁平化方式。
                // 直接抛错而不是随便挑一种，否则评测结果会归错到某条链路上，得出完全错误的结论。
                default -> throw new IllegalStateException("不支持的扁平化策略: " + strategy);
            };
            // 把选出来的文本压成「单页单块」的 IR，彻底抹掉页面和块级结构。
            candidate = flatten(candidate, text, strategy);
        } else {
            // 保留结构的策略（完整链路、或只关清洗）不改内容，只在 IR 上打一个策略标记，便于事后区分数据来源。
            candidate = stamp(candidate, strategy);
        }
        // 第四层：打包策略、最终 IR 和清洗审计一起返回，调用方据此继续分块和写阶段日志。
        return new Result(strategy, candidate, audits);
    }

    /**
     * 把一段文本压成「一页一块」的文档 IR，用来模拟没有结构感知能力的旧链路。
     *
     * <p>各层职责：
     * 第一层：文本归一并校验非空，空内容不允许进入索引；
     * 第二层：造一个覆盖全文的段落块，span 起止就是整段文本的首尾；
     * 第三层：继承原文档的标记与告警，并额外追加一条「本次用了哪个消融策略」的告警，让数据来源不会被误认成生产数据；
     * 第四层：保留文档身份信息（编号、来源名、媒体类型、语言、解析器版本），只替换页面结构和元数据。</p>
     *
     * <p>数据流：
     * 待压平文本 → 去首尾空白 → 非空校验 → 构造单个段落块 → 单页包一块
     * → 继承并追加告警与元数据 → 生成新的 IR</p>
     *
     * <p>为什么要保留文档身份：压平只改内容组织方式，不改「这是哪份文档」。若身份也换掉，
     * 评测时就无法把结果和原始文档对上。</p>
     *
     * <p>纯构造，不写库。文本为空时抛异常，因为空文档进索引会产生永远召不回的空分块。</p>
     */
    private DocumentIr flatten(DocumentIr source, String text, RagPreprocessingStrategy strategy) {
        // 先去掉首尾空白；null 当空串处理，避免空指针。
        String normalized = text == null ? "" : text.strip();
        // 压平后一个字都没有，说明这条退化链路把内容全丢了；直接失败，不允许生成空文档进入索引。
        if (normalized.isBlank()) throw new IllegalArgumentException("扁平化预处理结果不能为空");
        // 造唯一的段落块：块编号固定写死（这是评测数据，不需要真实块编号），原文与归一化文本都用这段文本，
        // span 从 0 到全文长度表示「这一块就是整篇」，并把策略版本号记在 span 上，便于追溯是哪个策略产出的；
        // 页码、标题层级、表格结构等结构化信息全部留空或置零——这正是「丢弃结构」要模拟的效果；
        // 最后两个布尔位表示这块不是装饰内容、且参与检索。
        DocumentIr.Block block = new DocumentIr.Block("benchmark-flat-0", DocumentIr.BlockType.PARAGRAPH,
                normalized, normalized, new DocumentIr.SourceSpan(0, normalized.length(),
                strategy.revision()), null, null, 0, "", 0, List.of(), source.detectedLanguage(),
                1.0, Set.of(), false, true, "", List.of());
        // 继承原文档的标记位（例如扫描件、加密等判定结果），用保序集合保证输出稳定可比。
        Set<DocumentIr.Flag> flags = new LinkedHashSet<>(source.flags());
        // 复制原有告警，准备在后面追加策略标记；不能直接改原列表，原 IR 可能还被别处引用。
        List<String> warnings = new ArrayList<>(source.warnings());
        // 追加一条显式告警，标明这份 IR 是消融策略产出的。有了它，压测数据永远不会被误当成生产数据。
        warnings.add("BENCHMARK_PREPROCESSING_STRATEGY=" + strategy.name());
        // 生成带策略信息的元数据，四个键完整描述了「这次关掉了什么」。
        Map<String, String> metadata = metadata(source, strategy);
        // 组装新 IR：文档身份（schema 版本、文档号、来源名、媒体类型、语言、解析器名与版本）全部原样继承，
        // 只把页面结构换成「第 1 页装着那唯一一块」，并替换元数据和告警。
        return new DocumentIr(source.schemaVersion(), source.documentId(), source.sourceName(),
                source.mediaType(), source.detectedLanguage(), source.parserName(), source.parserRevision(),
                List.of(new DocumentIr.Page(1, 0, 0, List.of(block))), metadata, warnings, flags);
    }

    /**
     * 只在 IR 上盖一个策略戳，内容与结构完全不动。
     *
     * <p>用于保留结构的策略（完整链路，或只关掉清洗）。作用是让产出的数据自带来源标记，
     * 评测时能分清哪批数据来自哪个策略，也避免消融数据混进生产判断。</p>
     *
     * <p>纯构造，不写库、不改原对象。</p>
     */
    private DocumentIr stamp(DocumentIr source, RagPreprocessingStrategy strategy) {
        // 复制原告警列表再追加，避免直接修改传入的 IR。
        List<String> warnings = new ArrayList<>(source.warnings());
        // 追加策略标记告警，作用同压平路径：让这份数据一眼能看出是消融产物。
        warnings.add("BENCHMARK_PREPROCESSING_STRATEGY=" + strategy.name());
        // 重新组装 IR：文档身份、页面结构、标记位全部原样保留，只换掉元数据和告警。
        return new DocumentIr(source.schemaVersion(), source.documentId(), source.sourceName(),
                source.mediaType(), source.detectedLanguage(), source.parserName(), source.parserRevision(),
                source.pages(), metadata(source, strategy), warnings, source.flags());
    }

    /**
     * 生成带策略信息的元数据，把「这次开了什么、关了什么」写进文档自身。
     *
     * <p>四个键分别记录策略名、策略版本号、是否启用清洗、是否保留结构。
     * 有了它们，后续分块、索引、评测都能凭元数据反查数据是怎么产生的，不用翻日志。</p>
     *
     * <p>返回新的映射，不修改原文档元数据；用保序映射保证输出顺序稳定、可逐字节比对。</p>
     */
    private Map<String, String> metadata(DocumentIr source, RagPreprocessingStrategy strategy) {
        // 先拷一份原有元数据，保留解析阶段写入的信息，再往上叠加策略信息。
        Map<String, String> metadata = new LinkedHashMap<>(source.metadata());
        // 记策略名，是区分不同消融分组最直接的标识。
        metadata.put("preprocessing_strategy", strategy.name());
        // 记策略版本号；同一个策略若实现改过，版本号会变，避免把新旧实现的结果混着比。
        metadata.put("preprocessing_strategy_revision", strategy.revision());
        // 记是否启用了清洗，方便评测时按这一维度分组统计。
        metadata.put("preprocessing_cleaner_enabled", Boolean.toString(strategy.cleanerEnabled()));
        // 记是否保留了结构，和上一项一起构成消融的两个正交开关。
        metadata.put("preprocessing_structure_preserved", Boolean.toString(strategy.structurePreserved()));
        // 返回叠加好的元数据，交给调用方装进新 IR。
        return metadata;
    }

    /**
     * 从 IR 里挑出真正参与检索的正文，拼成一整段纯文本。
     *
     * <p>用于「走了清洗但不做结构感知分块」这条策略：内容还是清洗过的干净正文，
     * 但标题层级、表格结构、页面边界全部被拉平，用来验证结构感知分块到底贡献了多少效果。</p>
     *
     * <p>只读 IR，不修改任何对象。</p>
     */
    private String retrievableText(DocumentIr document) {
        // 流式提取：文档全部块 → 只留标记为可检索的块 → 再排掉页眉、页脚、页码、水印这四类装饰块
        // （它们即使被判可检索也没有问答价值，留着只会污染召回）→ 取归一化后的文本 → 丢掉空白块
        // → 用空行拼成一整段。空行分隔是为了让后续按固定长度切分时不会把两块内容黏成一句话。
        return document.blocks().stream().filter(DocumentIr.Block::retrievable)
                .filter(block -> block.type() != DocumentIr.BlockType.HEADER
                        && block.type() != DocumentIr.BlockType.FOOTER
                        && block.type() != DocumentIr.BlockType.PAGE_NUMBER
                        && block.type() != DocumentIr.BlockType.WATERMARK)
                .map(DocumentIr.Block::normalizedText).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    /**
     * 把 Markdown 文本还原成人眼读到的纯文字，作为「无格式感知」的对照基线。
     *
     * <p>各层职责：
     * 第一层：剥掉标题号、列表符、引用号、代码反引号、加粗斜体标记，链接只留文字；
     * 第二层：删掉表格分隔行（那种全是横线和竖线的行），再把竖线换成空格，让表格退化成普通文字；
     * 第三层：压缩连续空格与过多空行，去掉首尾空白，避免格式残渣影响后续按长度切分。</p>
     *
     * <p>数据流：Markdown → 去装饰标记 → 删表格分隔行 → 竖线换空格 → 压缩空白 → 去首尾空白 → 纯文本</p>
     *
     * <p>纯字符串处理，不写库。null 输入按空串处理。</p>
     */
    private String plainText(String markdown) {
        // 第一层：一次正则替换剥掉所有 Markdown 装饰。替换成 $1 是为了让链接只保留显示文字、丢掉地址。
        String value = markdown == null ? "" : MARKDOWN_DECORATION.matcher(markdown).replaceAll("$1");
        // 第二层与第三层串在一条链上：先删掉表格的分隔行（只有横线和竖线，读出来毫无意义），
        // 再把剩下的竖线换成空格让表格退化成普通句子，接着把连续空格和制表符压成一个空格，
        // 把三行以上的空行压成一个空行，最后去掉首尾空白，得到干净的纯文本基线。
        return value.replaceAll("(?m)^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$", "")
                .replace('|', ' ').replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").strip();
    }

    /**
     * 一次预处理的完整产物：用了哪个策略、产出了什么 IR、清洗过程留下了哪些审计记录。
     *
     * <p>三者必须一起传递：光有 IR 不知道它是怎么来的，评测就无法归因；
     * 清洗审计则用于写摄取阶段日志，让「清洗改了什么」可复核。</p>
     *
     * <p>不可变值对象，不涉及持久化。</p>
     */
    public record Result(RagPreprocessingStrategy strategy, DocumentIr document,
                         List<DocumentIrCleaner.CleaningAudit> cleaningAudits) {
        /**
         * 构造时做最小完整性校验，保证拿到的产物一定能用于后续分块与归因。
         */
        public Result {
            // 策略和 IR 缺一个这份产物就没有意义：没策略无法归因，没 IR 没有内容可分块。
            if (strategy == null || document == null) throw new IllegalArgumentException("策略结果不能为空");
            // 审计记录允许为空（策略可能没开清洗），统一冻结成不可变列表，防止调用方事后篡改审计。
            cleaningAudits = List.copyOf(cleaningAudits == null ? List.of() : cleaningAudits);
        }
    }
}
