package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.model.document.DocumentIr;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr.Block;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr.BlockType;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr.Flag;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr.SourceSpan;
import cn.bugstack.ai.domain.rag.model.document.DocumentParseQualityReport;
import cn.bugstack.ai.domain.rag.model.document.DocumentParseQualityReport.Finding;
import cn.bugstack.ai.domain.rag.model.document.DocumentParseQualityReport.Severity;
import cn.bugstack.ai.domain.rag.model.document.DocumentQualityDisposition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 给「文档解析得好不好」打一个可复算的分，并裁决这份文档能不能进索引。
 *
 * <p>解决什么问题：一份 PDF 被解析成结构化中间表示（IR）后，可能存在各种坑——正文漏抓了一半、
 * 多栏版面读串了顺序、扫描件 OCR 认错字、表格单元格空掉、页眉页脚重复、编码坏掉变成一堆问号。
 * 这些问题肉眼很难逐个检查，但一旦进了索引，检索出来的资料就是错的，模型基于它给出的回答也是错的。
 * 本类把这些坑量化成六个维度的分数，加权成总分，再按阈值裁决：直接可用、带警告可用、需要人工复核、直接拒绝。</p>
 *
 * <p>为什么强调「确定性」：同一份 IR 无论评估多少次、在哪台机器上评估，结果必须完全一样。
 * 所有分数都保留固定小数位，所有遍历都按稳定顺序，不引入随机、时间或并发因素。
 * 只有这样，压测和回归测试才能拿它当基准。</p>
 *
 * <p>属于哪一层：领域层（domain）的纯计算服务，手动 new 出来用，不是 Spring Bean。无状态、线程安全。</p>
 *
 * <p>谁会调用它：摄取 Worker 在解析完文档、准备切片之前调一次，用裁决结果决定是否继续往下走。</p>
 *
 * <p>它向下调用什么：什么都不调用，只读传进来的 IR。</p>
 *
 * <p>它不负责什么：不修改 IR、不做清洗、不做切片、不写库、不发告警、不决定失败后怎么处理
 * （裁决结果交给调用方去执行）。它也不判断内容对不对题，只判断「解析这一步有没有搞坏」。</p>
 */
public final class DocumentParseQualityEvaluator {

    /**
     * 来源覆盖率在总分里的权重，0.25，是六个维度里最高的。
     *
     * <p>给它最高权重的原因：其他维度描述的是「解析出来的内容质量如何」，而覆盖率描述的是
     * 「原文有多少内容根本没解析出来」。漏抓内容是最致命的问题——检索时压根找不到，
     * 而且用户完全无从察觉。六个权重加起来正好是 1，保证总分始终落在 0 到 1 之间。</p>
     */
    private static final double COVERAGE_WEIGHT = 0.25;
    /**
     * 阅读顺序在总分里的权重，0.15。
     *
     * <p>顺序错乱会让相邻的句子被拼到一起却语义不连贯，切片之后尤其明显。</p>
     */
    private static final double ORDER_WEIGHT = 0.15;
    /**
     * OCR 置信度在总分里的权重，0.15。
     *
     * <p>只影响扫描件和图片文字；纯电子文档这一项恒为满分，不会稀释其他维度。</p>
     */
    private static final double OCR_WEIGHT = 0.15;
    /**
     * 表格质量在总分里的权重，0.15。
     *
     * <p>表格是最容易解析坏的结构；单元格错位或空掉会让数字对不上行列，回答里引用这种数据非常危险。</p>
     */
    private static final double TABLE_WEIGHT = 0.15;
    /**
     * 重复内容在总分里的权重，0.15。
     *
     * <p>重复占比高说明有效内容密度低（大多是页眉页脚水印），检索时会被同样的片段反复命中，挤掉真正有用的内容。</p>
     */
    private static final double DUPLICATE_WEIGHT = 0.15;
    /**
     * 替换字符在总分里的权重，0.15。
     *
     * <p>替换字符（问号菱形）意味着解码失败，是文本已经损坏的直接证据。</p>
     */
    private static final double REPLACEMENT_WEIGHT = 0.15;

    /**
     * 处置阈值配置，决定总分落在哪一档就给什么裁决。
     *
     * <p>做成可注入而不是写死常量，是为了让不同业务线能按自己的容忍度调整严格程度，
     * 同时也让测试能构造极端阈值验证四种裁决路径。构造后不可变。</p>
     */
    private final Config config;

    /**
     * 用指定阈值创建评估器。
     *
     * <p>阈值为空直接拒绝构造，而不是等评估时才空指针——那时候摄取任务已经跑到一半，排查成本高得多。</p>
     */
    public DocumentParseQualityEvaluator(Config config) {
        // 阈值配置是必需依赖，缺了整个裁决无从判断，宁可在装配阶段就失败。
        this.config = Objects.requireNonNull(config, "config不能为空");
    }

    /**
     * 创建平台标准评估器，生产链路用的就是这一套阈值。
     *
     * <p>五个数字依次是：总分 0.90 以上算直接可用；0.70 以上算带警告可用；低于 0.45 直接拒绝；
     * 覆盖率低于 0.35 无论总分多高都直接拒绝（漏抓太多的文档没有抢救价值）；
     * 任何单项低于 0.50 就转人工复核（哪怕总分被其他高分拉上去了，一个维度塌了就说明这份文档有明确缺陷）。</p>
     */
    public static DocumentParseQualityEvaluator standard() {
        // 用平台约定的标准阈值构造实例；数字集中在这一处，调整策略时只改这一行。
        return new DocumentParseQualityEvaluator(new Config(0.90, 0.70, 0.45, 0.35, 0.50));
    }

    /**
     * 评估一份文档 IR，输出六个维度的分数、发现的问题清单和最终裁决。
     *
     * <p>各层职责：
     * 第一层：入参兜底；
     * 第二层：依次算六个维度的分数，每个维度在发现问题时往同一个清单里追加一条 finding；
     * 第三层：额外标注安全与语言风险（提示注入、敏感信息、语言不符），只记录不扣分；
     * 第四层：按固定权重加权算总分，空文档直接给 0；
     * 第五层：按阈值裁决四档处置；
     * 第六层：把所有分数统一保留四位小数后打包成不可变报告。</p>
     *
     * <p>数据流：
     * 文档 IR
     * → 非空校验
     * → 覆盖率 / 阅读顺序 / OCR / 表格 / 重复 / 替换字符 六项打分（同时收集问题清单）
     * → 追加安全与语言风险标注
     * → 加权求总分（空文档为 0）
     * → 阈值裁决（拒绝 / 复核 / 带警告可用 / 可用）
     * → 四位小数取整 → 返回报告</p>
     *
     * <p>为什么安全风险只记录不扣分：提示注入和敏感内容不是「解析质量」问题，一份被投毒的文档可能解析得非常完美。
     * 把它算进质量分会混淆两件事；正确做法是记进报告，由下游的隔离和脱敏环节去处理。</p>
     *
     * <p>为什么空文档总分为 0：一份什么都没解析出来的文档，六个维度里有几项会因为「没有数据」而拿到满分
     * （例如没有表格所以表格满分）。若照常加权，空文档反而会得高分，那是完全错误的结论。</p>
     *
     * <p>只读，不写库、不调外部服务、不修改 IR。同一份 IR 重复评估结果完全一致。</p>
     */
    public DocumentParseQualityReport evaluate(DocumentIr document) {
        // 文档为空说明调用方用法有问题，直接暴露而不是返回一份看起来正常的空报告。
        Objects.requireNonNull(document, "document不能为空");
        // 收集所有维度发现的问题。六个打分方法共用这一个清单，最后一起写进报告，
        // 所以清单里的顺序就是维度的计算顺序，稳定可比。
        List<Finding> findings = new ArrayList<>();
        // 第二层第一项：来源覆盖率，衡量原文有多少内容真的被解析出来了。
        double coverage = coverage(document, findings);
        // 第二项：阅读顺序，衡量块的先后关系有没有错乱。
        double order = order(document, findings);
        // 第三项：OCR 置信度，只针对扫描件和图片文字。
        double ocr = ocr(document, findings);
        // 第四项：表格质量，看单元格是不是大面积空缺。
        double table = table(document, findings);
        // 第五项：重复内容占比，反映有效内容密度。
        double duplicate = duplicate(document, findings);
        // 第六项：替换字符密度，反映文本有没有解码损坏。
        double replacement = replacement(document, findings);
        // 第三层：把安全和语言风险追加进问题清单。它们只作为记录，不参与扣分。
        annotateRisks(document, findings);
        // 第四层：加权求总分。空文档直接给 0——否则「没有表格」「没有 OCR」这类维度会拿满分，
        // 把一份什么都没解析出来的文档拉成高分，得出完全错误的结论。
        double overall = document.blocks().isEmpty() ? 0
                : round(coverage * COVERAGE_WEIGHT + order * ORDER_WEIGHT + ocr * OCR_WEIGHT
                + table * TABLE_WEIGHT + duplicate * DUPLICATE_WEIGHT + replacement * REPLACEMENT_WEIGHT);
        // 第五层：按阈值裁决。裁决同时看总分和单项最低分，还会参考问题清单是否为空。
        DocumentQualityDisposition disposition = disposition(document, coverage, order, ocr, table,
                duplicate, replacement, overall, findings);
        // 第六层：所有分数统一保留四位小数后打包成不可变报告。总分已经在上面取整过，这里不再重复处理。
        return new DocumentParseQualityReport(round(coverage), round(order), round(ocr), round(table),
                round(duplicate), round(replacement), overall, disposition, findings);
    }

    /**
     * 算来源覆盖率：解析出来的内容一共覆盖了原文多少字符。
     *
     * <p>各层职责：
     * 第一层：按来源位置（页 / 文件）把所有块的字符区间分组；
     * 第二层：一个区间都没有时无法评估，记一条问题并给折中分；
     * 第三层：逐个来源把区间排序后做区间合并，累计「被覆盖的字符数」；
     * 第四层：用覆盖数除以预期总长得到比率，低于可用阈值时记一条问题。</p>
     *
     * <p>数据流：
     * 全部块的来源区间
     * → 按来源位置分组
     * → 每组按起点、终点排序
     * → 线性扫描合并重叠区间，累加覆盖长度
     * → 覆盖长度 / 预期长度 → 夹紧到 0~1
     * → 低于阈值则记一条问题
     * → 返回比率</p>
     *
     * <p>为什么要合并区间而不是直接把长度相加：同一段原文可能被两个块重复引用（例如跨栏的段落）。
     * 直接相加会把重复部分算两次，覆盖率甚至可能超过 100%，掩盖真正的漏抓。</p>
     *
     * <p>只读，会往问题清单里追加元素（这是本方法唯一的副作用）。</p>
     */
    private double coverage(DocumentIr document, List<Finding> findings) {
        // 按来源位置分组的容器：键是来源位置（哪一页 / 哪个文件），值是该来源下所有块的字符区间。
        // 必须分组，因为不同来源的偏移量各自从 0 开始，混在一起算区间毫无意义。用保序映射保证遍历顺序稳定。
        Map<String, List<SourceSpan>> bySource = new LinkedHashMap<>();
        // 流式分组：全部块 → 取来源区间 → 丢掉没有区间的块 → 按来源位置塞进对应分组。
        // 没有区间的块无法参与覆盖率计算（解析器没告诉我们它对应原文哪一段），只能跳过。
        document.blocks().stream().map(Block::sourceSpan).filter(Objects::nonNull)
                .forEach(span -> bySource.computeIfAbsent(span.sourceLocation(), ignored -> new ArrayList<>()).add(span));
        // 第二层：一个块都没有带来源区间，说明解析器压根没输出定位信息，无法真正评估覆盖率。
        if (bySource.isEmpty()) {
            // 记一条警告说明「缺少来源区间」，让人知道这个分数是估的，而不是真算出来的。
            findings.add(finding("SOURCE_SPAN_MISSING", Severity.WARNING,
                    "解析结果没有来源字符区间", document.blocks()));
            // 空文档给 0（确实什么都没有），有内容但缺区间给 0.5 折中分：
            // 既不因为解析器不提供定位信息就把文档判死，也不假装它质量没问题。
            return document.blocks().isEmpty() ? 0 : 0.5;
        }
        // 累计所有来源合并后被覆盖的字符数。
        long covered = 0;
        // 累计预期应覆盖的字符总数，用每个来源里最大的终点偏移来近似原文长度。
        long expected = 0;
        // 第三层：逐个来源分别做区间合并。不同来源的偏移量互不相干，必须分开算。
        for (List<SourceSpan> spans : bySource.values()) {
            // 先按起点排序，起点相同再按终点排序。排序是线性合并的前提：
            // 排好序后只需要从左到右扫一遍，就能判断下一个区间是不是和当前区间连着。
            List<SourceSpan> sorted = spans.stream().sorted(Comparator.comparingInt(SourceSpan::startOffset)
                    .thenComparingInt(SourceSpan::endOffset)).toList();
            // 用第一个区间的起点初始化「当前正在合并的区间」的左端。
            int start = sorted.get(0).startOffset();
            // 同样用第一个区间的终点初始化右端，后面不断向右扩张。
            int end = sorted.get(0).endOffset();
            // 预期长度取这个来源里最大的终点偏移：它是解析器见过的最靠后的位置，可以近似当作原文长度。
            expected += sorted.stream().mapToInt(SourceSpan::endOffset).max().orElse(0);
            // 从第二个区间开始扫描，逐个判断它和当前合并区间的关系。
            for (int index = 1; index < sorted.size(); index++) {
                // 取出这一个区间准备判断。
                SourceSpan span = sorted.get(index);
                // 起点已经越过当前区间的右端，说明中间有一段原文没有任何块覆盖——出现了空隙。
                if (span.startOffset() > end) {
                    // 空隙前的那一段合并区间到此结束，把它的长度结算进覆盖数。
                    covered += end - start;
                    // 从这个区间重新开始一段新的合并区间，左端换成它的起点。
                    start = span.startOffset();
                    // 右端也换成它的终点，后续继续向右扩张。
                    end = span.endOffset();
                } else {
                    // 与当前区间重叠或紧邻，不需要新开一段，只把右端向右扩张到两者较大的那个终点。
                    // 这一步正是「合并」的关键：重叠部分只会被计算一次。
                    end = Math.max(end, span.endOffset());
                }
            }
            // 扫描结束，把最后一段合并区间的长度也结算进覆盖数，否则末尾那段会被漏掉。
            covered += end - start;
        }
        // 覆盖长度除以预期长度得到比率；预期为 0（没有任何有效偏移）时给 0，
        // 并用夹紧函数保证浮点误差不会让结果越出 0 到 1。
        double score = expected == 0 ? 0 : clamp(covered / (double) expected);
        // 低于「直接可用」阈值就说明漏抓比较明显，需要在报告里明确指出来。
        if (score < config.readyThreshold()) {
            // 记一条问题，严重程度按分数高低自动决定（低于复核阈值升级为错误）。
            // 受影响范围写成全部块：覆盖率是整篇文档的指标，没法归因到某几个块上。
            findings.add(finding("LOW_SOURCE_COVERAGE", severity(score),
                    "来源字符覆盖率不足", document.blocks()));
        }
        // 返回覆盖率比率，交给上层参与加权和裁决。
        return score;
    }

    /**
     * 算阅读顺序得分：页面内相邻块的阅读序号有没有出现重复或回退。
     *
     * <p>各层职责：
     * 第一层：逐页遍历，逐对比较相邻块；
     * 第二层：后一块的序号不大于前一块就算一次违规，并记下这个块；
     * 第三层：用「1 减违规比例」得到分数，有违规就记一条问题。</p>
     *
     * <p>数据流：每一页的块列表 → 相邻两块比较阅读序号 → 统计比较次数与违规次数
     * → 1 - 违规/比较 → 夹紧 → 有违规则记问题 → 返回分数</p>
     *
     * <p>为什么只在页内比较：阅读序号是页内编号，跨页比较毫无意义（第二页的第 1 块当然小于第一页的第 10 块）。</p>
     *
     * <p>为什么顺序错乱要扣分：块顺序决定了切片时哪些内容会被拼到一起。多栏版面若被横向读串，
     * 左栏一句和右栏一句会被拼成一段，语义完全错乱，检索出来的资料也就不可信了。</p>
     *
     * <p>只读，会往问题清单里追加元素。</p>
     */
    private double order(DocumentIr document, List<Finding> findings) {
        // 统计一共做了多少次相邻比较，作为违规比例的分母。
        int comparisons = 0;
        // 统计其中有多少次违规（序号没有严格递增）。
        int violations = 0;
        // 记下所有违规的块，写进问题清单让人能直接定位到具体位置。
        List<Block> affected = new ArrayList<>();
        // 第一层：逐页处理。阅读序号是页内编号，跨页比较没有意义。
        for (DocumentIr.Page page : document.pages()) {
            // 从第二块开始，每一块都和它前面那块比一次；下标从 1 开始正好保证不会越界。
            for (int index = 1; index < page.blocks().size(); index++) {
                // 先累加分母：无论是否违规，这都算做了一次比较。
                comparisons++;
                // 取出前一块。
                Block previous = page.blocks().get(index - 1);
                // 取出当前块。
                Block current = page.blocks().get(index);
                // 阅读序号必须严格递增。小于说明顺序回退（版面读串了），等于说明序号重复（解析器没编好号），
                // 两者都会导致切片时内容被错误地拼接。
                if (current.readingOrder() <= previous.readingOrder()) {
                    // 累加违规次数。
                    violations++;
                    // 记下这个有问题的块，便于人工定位到具体页面和位置。
                    affected.add(current);
                }
            }
        }
        // 用「1 减违规比例」得到分数：一次违规都没有得满分，全都违规得 0 分。
        // 没做过任何比较（每页最多一块）时给满分——没有相邻关系，就不存在顺序问题。
        double score = comparisons == 0 ? 1 : clamp(1 - violations / (double) comparisons);
        // 只要有违规就记进报告，即使分数还不算低——顺序问题往往集中在少数几个位置，值得人工看一眼。
        if (violations > 0) {
            // 记一条问题，严重程度按分数决定，受影响范围精确到那些违规的块。
            findings.add(finding("READING_ORDER_CONFLICT", severity(score),
                    "页面阅读顺序存在重复或回退", affected));
        }
        // 返回顺序得分。
        return score;
    }

    /**
     * 算 OCR 置信度得分：扫描件和图片文字识别得有多准。
     *
     * <p>只挑出「来自 OCR」或「图片文字」这两类块来算平均置信度，纯电子文本一律不参与。
     * 一个 OCR 块都没有时直接给满分，表示这份文档不存在 OCR 风险。</p>
     *
     * <p>数据流：全部块 → 筛出 OCR 与图片文字块 → 为空则返回满分 → 取置信度平均值
     * → 低于阈值则记问题 → 返回分数</p>
     *
     * <p>为什么不能把原生文本也算进来：原生文本的置信度是 1，把它们混进平均值会把 OCR 的低分稀释掉。
     * 一份 99% 是电子文本、1% 是模糊扫描图的文档，平均下来几乎满分，但那 1% 恰恰是错得最厉害的内容。</p>
     *
     * <p>只读，会往问题清单里追加元素。</p>
     */
    private double ocr(DocumentIr document, List<Finding> findings) {
        // 流式筛选：全部块 → 只保留带 OCR 标记的块，或类型是图片文字的块 → 收成列表。
        // 两个条件用或：有的解析器只打标记，有的只给类型，两种都要认。
        List<Block> blocks = document.blocks().stream()
                .filter(block -> block.flags().contains(Flag.OCR_TEXT)
                        || block.type() == BlockType.IMAGE_TEXT).toList();
        // 没有任何 OCR 内容，说明这是纯电子文档，不存在识别风险，直接给满分。
        if (blocks.isEmpty()) return 1;
        // 只对 OCR 块取置信度平均值，这样低置信度不会被原生文本稀释掉。
        double score = blocks.stream().mapToDouble(Block::confidence).average().orElse(0);
        // 平均置信度低于「直接可用」阈值，说明识别结果可疑。
        if (score < config.readyThreshold()) {
            // 记一条问题，受影响范围就是这些 OCR 块，便于人工抽查识别效果。
            findings.add(finding("LOW_OCR_CONFIDENCE", severity(score),
                    "OCR文本平均置信度不足", blocks));
        }
        // 返回 OCR 得分。
        return score;
    }

    /**
     * 算表格质量得分：表格里有多少单元格是真的有内容的。
     *
     * <p>各层职责：
     * 第一层：挑出所有表格块（按类型判断，或按是否带表格结构判断）；
     * 第二层：完全没有单元格的表格算一个「不可用单位」，直接计入分母；
     * 第三层：有单元格的表格逐格统计，非空格计入可用数；
     * 第四层：任何存在空格的表格都记进受影响清单；
     * 第五层：可用数除以总数得到分数，低于阈值则记一条问题。</p>
     *
     * <p>数据流：全部块 → 筛出表格块 → 为空则返回满分 → 逐表格逐单元格统计
     * → 可用/总数 → 低于阈值则记问题 → 返回分数</p>
     *
     * <p>为什么空表格也要计入分母：一个完全没解析出单元格的表格是最严重的失败。
     * 若直接跳过，它反而不影响分数，等于奖励了彻底失败的情况。</p>
     *
     * <p>只读，会往问题清单里追加元素。</p>
     */
    private double table(DocumentIr document, List<Finding> findings) {
        // 流式筛选表格块：类型标成表格的，或者虽然类型不是但带了表格结构的，都算。
        // 两种判断都要，是因为不同解析器对表格的标注方式不一致。
        List<Block> tables = document.blocks().stream()
                .filter(block -> block.type() == BlockType.TABLE || block.table() != null).toList();
        // 一个表格都没有，不存在表格风险，直接给满分。
        if (tables.isEmpty()) return 1;
        // 统计单元格总数（分母），空表格按一个单位计入。
        int total = 0;
        // 统计其中有实际内容的单元格数（分子）。
        int usable = 0;
        // 记下所有存在缺失的表格块，便于人工定位到具体是哪张表。
        List<Block> affected = new ArrayList<>();
        // 第二层：逐个表格处理。
        for (Block block : tables) {
            // 表格结构缺失或一个单元格都没有，属于最严重的解析失败。
            if (block.table() == null || block.table().cells().isEmpty()) {
                // 把这张表记进受影响清单。
                affected.add(block);
                // 计入分母但不计入分子，让它实实在在地拉低分数；跳过它反而等于奖励彻底失败。
                total++;
                // 这张表没有单元格可遍历，直接进入下一张。
                continue;
            }
            // 第三层：逐个单元格统计。
            for (DocumentIr.TableCell cell : block.table().cells()) {
                // 每个单元格都计入分母。
                total++;
                // 只有归一化后确实有文字的单元格才计入分子；纯空白单元格意味着这一格的内容丢了。
                if (!cell.normalizedText().isBlank()) usable++;
            }
            // 第四层：这张表只要存在任何一个空单元格，就记进受影响清单。
            // 判断条件比分数更敏感：即使整体分数还不错，人工也应该看一眼具体是哪一格丢了。
            if (block.table().cells().stream().anyMatch(cell -> cell.normalizedText().isBlank())) {
                // 记下这张表。
                affected.add(block);
            }
        }
        // 第五层：可用格数除以总格数。总数为 0 给 0 分（走到这里说明表格列表非空却一格都没统计到，属于异常情况）。
        double score = total == 0 ? 0 : usable / (double) total;
        // 低于「直接可用」阈值说明表格大面积缺格，数据已经不可信。
        if (score < config.readyThreshold()) {
            // 记一条问题，受影响范围精确到那些有缺失的表格块。
            findings.add(finding("TABLE_CELL_COVERAGE_LOW", severity(score),
                    "表格存在缺失或空白单元格", affected));
        }
        // 返回表格得分。
        return score;
    }

    /**
     * 算重复内容得分：清洗之后还剩多少有效内容。
     *
     * <p>只看有文字的块，其中被标记为「重复块」的占比越高，分数越低。
     * 重复标记是清洗阶段打上的（典型来源是页眉、页脚、水印、每页固定的免责声明）。</p>
     *
     * <p>数据流：全部块 → 筛出有文字的块 → 其中筛出被标记重复的 → 1 - 重复占比 → 夹紧
     * → 有重复则记问题 → 返回分数</p>
     *
     * <p>为什么重复要扣分：检索是按相似度取前几名。如果一份文档里几十个块内容完全一样，
     * 它们会一起挤进结果，把其他真正有用的内容顶出去，最终注入模型的资料变成同一句话重复几十遍。</p>
     *
     * <p>只读，会往问题清单里追加元素。</p>
     */
    private double duplicate(DocumentIr document, List<Finding> findings) {
        // 只统计有文字的块作为分母：图片、分隔线这类空文本块本来就不参与检索，算进来会稀释比例。
        List<Block> textual = document.blocks().stream().filter(block -> !block.normalizedText().isBlank()).toList();
        // 在有文字的块里筛出被清洗阶段标记为重复的那些。
        List<Block> duplicates = textual.stream()
                .filter(block -> block.flags().contains(Flag.DUPLICATE_BLOCK)).toList();
        // 用「1 减重复占比」得到分数；一个有文字的块都没有时给 0 分（空文档不该拿满分）。
        double score = textual.isEmpty() ? 0 : clamp(1 - duplicates.size() / (double) textual.size());
        // 只要存在重复就记进报告，让人知道这份文档有多少内容是被抑制掉的。
        if (!duplicates.isEmpty()) {
            // 记一条问题，受影响范围精确到那些重复块。
            findings.add(finding("DUPLICATE_CONTENT", severity(score),
                    "文档包含被抑制的重复内容块", duplicates));
        }
        // 返回重复内容得分。
        return score;
    }

    /**
     * 算替换字符得分：文本里有没有解码损坏的痕迹。
     *
     * <p>统计 Unicode 替换字符（U+FFFD，显示成菱形问号）的密度，按「每百字符」放大惩罚。
     * 也就是说每 100 个字符里出现 1 个替换字符，就扣掉 1 分（直接到 0）。</p>
     *
     * <p>数据流：全部块文本 → 统计总字符数 → 统计替换字符数 → 1 - 替换数×100/总数 → 夹紧
     * → 有替换字符则记问题 → 返回分数</p>
     *
     * <p>为什么惩罚这么重：替换字符是「这段文字已经彻底读坏了」的铁证，不是概率问题。
     * 哪怕比例很低，也说明解析器用错了编码或者原文件本身损坏，这种文档进索引只会污染检索结果。
     * 所以刻意放大一百倍，让哪怕零星出现也能明显拉低分数。</p>
     *
     * <p>只读，会往问题清单里追加元素。</p>
     */
    private double replacement(DocumentIr document, List<Finding> findings) {
        // 统计所有块归一化文本的总字符数，作为密度的分母。
        long characters = document.blocks().stream().map(Block::normalizedText).mapToLong(String::length).sum();
        // 流式统计替换字符个数：全部块文本 → 摊平成字符流 → 只留 U+FFFD → 计数。
        // U+FFFD 是解码失败时的通用占位符，出现它就意味着这几个字节没能正确还原成文字。
        long replacements = document.blocks().stream().map(Block::normalizedText)
                .flatMapToInt(String::chars).filter(value -> value == '\uFFFD').count();
        // 密度乘 100 再从 1 里减掉，等价于「每百字符一个替换字符就扣满一分」；
        // 总字符数为 0 时给 0 分（没有任何文本，不该拿满分），并夹紧防止负值。
        double score = characters == 0 ? 0 : clamp(1 - replacements * 100.0 / characters);
        // 只要出现过替换字符就必须记进报告，哪怕分数还没跌破阈值。
        if (replacements > 0) {
            // 精确筛出包含替换字符的那些块，便于人工定位到具体是哪一段读坏了。
            List<Block> affected = document.blocks().stream()
                    .filter(block -> block.normalizedText().indexOf('\uFFFD') >= 0).toList();
            // 记一条问题，明确指出文本包含替换字符。
            findings.add(finding("REPLACEMENT_CHARACTER_FOUND", severity(score),
                    "解析文本包含Unicode替换字符", affected));
        }
        // 返回替换字符得分。
        return score;
    }

    /**
     * 把安全与语言风险写进报告，但不改动任何质量分。
     *
     * <p>三类风险：提示注入特征（文档里有试图操纵模型的语句）、敏感信息特征、块语言与文档语言不一致。</p>
     *
     * <p>数据流：全部块 → 分别筛出三种风险标记 → 各自非空则记一条警告</p>
     *
     * <p>为什么不扣分：这三项和「解析得好不好」是两回事。一份被投毒的文档完全可能解析得非常完美，
     * 把它算进质量分会让两件事混在一起，既误判了解析质量，也可能因为总分够高而让风险被忽略。
     * 正确做法是原样记进报告，交给下游的资料隔离和脱敏环节处理。</p>
     *
     * <p>只读，唯一副作用是往问题清单里追加元素。</p>
     */
    private void annotateRisks(DocumentIr document, List<Finding> findings) {
        // 筛出带提示注入标记的块：它们的内容里有试图给模型下指令的语句。
        List<Block> injection = flagged(document, Flag.PROMPT_INJECTION);
        // 存在这类块就必须提醒下游：注入模型前要用受限的资料标签把它围起来，不能当普通正文处理。
        if (!injection.isEmpty()) {
            // 固定记为警告级别，不参与扣分，只作为风险告知。
            findings.add(finding("PROMPT_INJECTION_MARKED", Severity.WARNING,
                    "内容包含提示注入特征，进入模型前需要隔离", injection));
        }
        // 筛出带敏感信息标记的块。
        List<Block> sensitive = flagged(document, Flag.SENSITIVE_CONTENT);
        // 存在敏感内容时记一条警告，供下游决定是否需要脱敏或限制可见范围。
        if (!sensitive.isEmpty()) {
            // 同样固定为警告级别。
            findings.add(finding("SENSITIVE_CONTENT_MARKED", Severity.WARNING,
                    "内容包含敏感信息特征", sensitive));
        }
        // 筛出语言与文档整体语言不一致的块。
        List<Block> language = flagged(document, Flag.LANGUAGE_MISMATCH);
        // 语言不一致通常意味着文档是混排的，或者语言识别出了错。
        if (!language.isEmpty()) {
            // 记一条警告：它会影响后续向量化的效果（模型对混排文本的表示质量较差），但不算解析错误。
            findings.add(finding("LANGUAGE_MISMATCH", Severity.WARNING,
                    "块语言与文档语言不一致", language));
        }
    }

    /**
     * 按阈值裁决这份文档的处置方式，四档从严到宽依次判断。
     *
     * <p>各层职责：
     * 第一层：硬拒绝——空文档、覆盖率跌破底线、或总分过低，一律不许进索引；
     * 第二层：算出六个维度里最低的那一项；
     * 第三层：总分不够高、或任一单项塌了，转人工复核；
     * 第四层：总分未达可用线、或存在任何问题与告警，标成带警告可用；
     * 第五层：其余情况才是直接可用。</p>
     *
     * <p>数据流：
     * 六个维度分数 + 总分 + 问题清单 + 文档告警
     * → 空文档 / 覆盖率跌破底线 / 总分低于拒绝线 → REJECTED
     * → 取六项最小值
     * → 总分低于复核线 或 最小项低于单项复核线 → NEEDS_REVIEW
     * → 总分低于可用线 或 有问题 或 有告警 → READY_WITH_WARNING
     * → 其余 → READY</p>
     *
     * <p>为什么覆盖率有独立的硬底线：其他维度差还能靠人工判断是否可用，但漏抓了大半内容的文档
     * 无论如何都没有价值——检索它只会得到片面的资料，而用户完全无从察觉缺了什么。</p>
     *
     * <p>为什么要看「单项最低分」：加权总分会掩盖单点崩塌。一份表格全空但其他维度满分的文档，
     * 总分可能还有 0.85 看起来不错，但它的表格数据已经完全不可用了。单项阈值专门用来抓这种情况。</p>
     *
     * <p>为什么有问题就降级成「带警告可用」：这一档的含义是「可以进索引，但要留痕」。
     * 这样出问题时能顺着报告回溯到当初就已经发现的隐患，而不是事后重新猜。</p>
     *
     * <p>纯计算，不写库、不修改问题清单。</p>
     */
    private DocumentQualityDisposition disposition(DocumentIr document, double coverage, double order,
                                                   double ocr, double table, double duplicate,
                                                   double replacement, double overall, List<Finding> findings) {
        // 第一层：三种硬拒绝情形——一个块都没解析出来；来源覆盖率跌破独立底线（漏抓太多，没有抢救价值）；
        // 总分低于拒绝线（整体质量已经不可接受）。
        if (document.blocks().isEmpty() || coverage < config.minimumCoverage()
                || overall < config.rejectedThreshold()) {
            // 直接拒绝，这份文档不允许进入索引；调用方应把摄取任务判为失败并提示重新上传或换解析方式。
            return DocumentQualityDisposition.REJECTED;
        }
        // 第二层：层层取最小，得到六个维度里最差的那一项。加权总分容易掩盖单点崩塌，必须单独看最低分。
        double minimumComponent = Math.min(coverage, Math.min(order,
                Math.min(ocr, Math.min(table, Math.min(duplicate, replacement)))));
        // 第三层：总分不够高，或者任何一个维度塌到了单项复核线以下（哪怕总分被其他维度拉得很好看）。
        if (overall < config.reviewThreshold() || minimumComponent < config.reviewComponentThreshold()) {
            // 转人工复核：文档不至于废掉，但有明确缺陷，需要人看一眼再决定。
            return DocumentQualityDisposition.NEEDS_REVIEW;
        }
        // 第四层：总分没到「直接可用」线，或者虽然分数够高但发现过问题、或 IR 自带告警。
        if (overall < config.readyThreshold() || !findings.isEmpty() || !document.warnings().isEmpty()) {
            // 标成带警告可用：允许进索引，但把隐患留痕，日后回溯问题时有据可查。
            return DocumentQualityDisposition.READY_WITH_WARNING;
        }
        // 第五层：分数达标、没有任何问题、也没有告警，才判为直接可用。
        return DocumentQualityDisposition.READY;
    }

    /**
     * 找出带某个风险标记的全部块。
     *
     * <p>供安全与语言风险标注复用，避免三处各写一遍相同的筛选逻辑。只读，不修改 IR。</p>
     */
    private List<Block> flagged(DocumentIr document, Flag flag) {
        // 流式筛选：全部块 → 只留标记集合里包含目标标记的 → 收成列表。
        return document.blocks().stream().filter(block -> block.flags().contains(flag)).toList();
    }

    /**
     * 把一条问题的受影响块压缩成去重后的块编号列表。
     *
     * <p>只保留块编号而不是整个块对象：报告会被序列化落库和上报，带上正文会让它体积暴涨，
     * 还可能把文档内容泄露到日志和监控里。去重是为了让同一个块被多次记录时只出现一次，
     * 顺序则保持首次出现的顺序，保证报告可复算、可逐字节比对。</p>
     */
    private Finding finding(String code, Severity severity, String message, List<Block> blocks) {
        // 流式转换：受影响块 → 取块编号 → 去重（保留首次出现顺序）→ 收成列表，连同代码、级别、描述打包成一条发现。
        return new Finding(code, severity, message, blocks.stream().map(Block::blockId).distinct().toList());
    }

    /**
     * 按分数高低决定一条问题算错误还是警告。
     *
     * <p>低于单项复核阈值就升级成错误，否则算警告。让严重程度自动跟着分数走，
     * 避免各个维度各自硬编码级别导致标准不一致。</p>
     */
    private Severity severity(double score) {
        // 跌破单项复核线说明这个维度已经塌了，升级为错误；否则只是警告。
        return score < config.reviewComponentThreshold() ? Severity.ERROR : Severity.WARNING;
    }

    /**
     * 把分数夹紧到 0 到 1 之间。
     *
     * <p>各维度都用比例计算，浮点误差和放大惩罚（例如替换字符乘一百）都可能让结果越界。
     * 统一夹紧后，报告里的分数永远是可解释的概率值，加权总分也不会跑出范围。</p>
     */
    private double clamp(double value) {
        // 小于 0 取 0，大于 1 取 1，保证分数始终是一个可解释的比例值。
        return Math.max(0, Math.min(1, value));
    }

    /**
     * 把分数统一保留四位小数。
     *
     * <p>这是「确定性」的关键一步：不取整的话，浮点运算顺序的微小差异会让两次评估得到
     * 末位不同的结果，报告就无法逐字节比对，回归测试也没法断言。取整前先夹紧，避免越界值被放大。</p>
     */
    private double round(double value) {
        // 先夹紧再乘一万取整再除回去，等价于保留四位小数；先夹紧是为了不让越界值影响取整结果。
        return Math.round(clamp(value) * 10_000.0) / 10_000.0;
    }

    /**
     * 四档处置的阈值配置。
     *
     * <p>五个字段的含义：readyThreshold 总分达到它算直接可用；reviewThreshold 总分达到它算带警告可用；
     * rejectedThreshold 总分低于它直接拒绝；minimumCoverage 覆盖率的独立硬底线（跌破就拒绝，不看总分）；
     * reviewComponentThreshold 单项维度的复核线（任一维度跌破就转人工，不看总分）。</p>
     *
     * <p>构造时会校验每个值都是 0 到 1 之间的有限数，并且三档总分阈值必须严格递减——
     * 否则四档判断的边界会互相重叠，出现「既该拒绝又该可用」这种自相矛盾的裁决。</p>
     *
     * <p>不可变值对象，不涉及持久化。</p>
     */
    public record Config(double readyThreshold,
                         double reviewThreshold,
                         double rejectedThreshold,
                         double minimumCoverage,
                         double reviewComponentThreshold) {
        /**
         * 构造时校验五个阈值各自合法，并且三档总分阈值严格递减。
         *
         * <p>宁可在构造阶段就失败，也不允许一份自相矛盾的阈值配置进入生产——那会让裁决结果随判断顺序而变，
         * 完全无法解释。</p>
         */
        public Config {
            // 校验「直接可用」总分线是 0 到 1 之间的有限数。
            validate(readyThreshold, "readyThreshold");
            // 校验「带警告可用」总分线合法。
            validate(reviewThreshold, "reviewThreshold");
            // 校验「直接拒绝」总分线合法。
            validate(rejectedThreshold, "rejectedThreshold");
            // 校验覆盖率独立底线合法。
            validate(minimumCoverage, "minimumCoverage");
            // 校验单项维度复核线合法。
            validate(reviewComponentThreshold, "reviewComponentThreshold");
            // 三档总分阈值必须严格递减：可用线 > 复核线 > 拒绝线。
            // 一旦顺序错乱，四档判断的区间就会互相重叠，同一个总分可能既满足拒绝又满足可用，裁决完全不可解释。
            if (!(readyThreshold > reviewThreshold && reviewThreshold > rejectedThreshold)) {
                // 直接抛非法参数异常拒绝构造，绝不容忍一份自相矛盾的阈值配置进入生产。
                throw new IllegalArgumentException("处置阈值必须按READY、REVIEW、REJECTED递减");
            }
        }

        /**
         * 校验单个阈值是 0 到 1 之间的有限数。
         *
         * <p>同时挡住 NaN 和无穷（它们参与比较时会让所有判断结果都变成 false，导致裁决静默失效），
         * 以及越界的概率值。字段名一起传进来，报错时能直接指出是哪个阈值配错了。</p>
         */
        private static void validate(double value, String field) {
            // 非有限数（NaN、无穷）必须拒绝：NaN 参与任何比较都返回 false，会让阈值判断静默失效；
            // 越界值则会让某一档永远无法命中。
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                // 带上字段名抛出，配错阈值时一眼就能看出是哪一项。
                throw new IllegalArgumentException(field + "必须位于0到1之间");
            }
        }
    }
}
