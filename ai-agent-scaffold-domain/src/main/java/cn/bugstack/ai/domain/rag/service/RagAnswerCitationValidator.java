package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.context.model.RagContextEvidence;
import cn.bugstack.ai.domain.rag.model.entity.RagAnswerCitationValidation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 最终回答的 RAG 引用范围校验服务。
 * <p>服务从回答中提取规定格式的引用标识，并与本次实际注入模型的证据集合比对。
 * 校验结果区分未使用 RAG、有证据但未引用、引用合法和存在超出范围的引用。</p>
 */
@Service
public class RagAnswerCitationValidator {

    /**
     * 平台生成的引用标记格式：cite_ 加 24 位小写十六进制。
     *
     * <p>为什么要卡这么死：如果用宽松匹配（比如只看到 cite 就算引用），回答里正常提到的英文单词、
     * 代码片段都会被误判成引用，校验结果就没法信。前后的 (?<!...) 和 (?!...) 保证左右不能紧邻
     * 字母数字下划线，避免从一个更长的标识符中间抠出一段来误报。</p>
     *
     * <p>常量、无状态、线程安全，被所有请求共用，不涉及租户数据。</p>
     */
    private static final Pattern CITATION_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_])cite_[0-9a-f]{24}(?![A-Za-z0-9_])");

    /**
     * 用「本次实际注入模型的证据」当白名单，校验最终回答里的全部引用标记。
     *
     * <p>各层职责：
     * 第一层：把这次注入的证据摊平成两份索引——检索批次编号集合，以及「引用 ID → 引用出处」白名单；
     * 第二层：顺路做一次自洽性检查，同一个引用 ID 绝不允许指向两份不同的出处；
     * 第三层：从回答文本里按出现顺序提取所有引用标记；
     * 第四层：把提取到的标记切成「白名单内的合法引用」和「白名单外的伪造引用」两堆；
     * 第五层：按优先级判定总体状态，并把明细一起打包返回。</p>
     *
     * <p>数据流：
     * 最终回答文本 + 本次注入证据列表
     * → 遍历证据，收集检索批次编号，展开出每条引用建立白名单
     * → 白名单冲突检查（同一 ID 指向不同出处则直接抛错）
     * → 正则提取回答中的引用标记（按出现顺序去重）
     * → 按白名单切分为已用引用与非法引用
     * → 判定状态（无 RAG / 有资料未引用 / 全部合法 / 存在伪造）
     * → 返回状态 + 检索批次 + 白名单 + 已用 + 非法 + 已用引用的完整出处</p>
     *
     * <p>关键输入：answer 允许为 null（模型没输出内容）；evidence 为 null 或空表示这次没走 RAG。</p>
     *
     * <p>返回结果：一个完整的校验记录，顺序稳定（都用保序集合），便于落库、比对和回归测试。</p>
     *
     * <p>不写库、不改状态、不调外部服务。唯一的硬失败是白名单自相矛盾：
     * 那说明上游证据组装出了 Bug，此时抛 IllegalStateException 而不是勉强校验，避免用错误白名单放过伪造引用。</p>
     */
    public RagAnswerCitationValidation validate(String answer, List<RagContextEvidence> evidence) {
        // 证据为 null 统一当成空列表处理：这次没走 RAG 也要能正常校验，而不是抛空指针。
        List<RagContextEvidence> safeEvidence = evidence == null ? List.of() : evidence;
        // 收集这次回答涉及的检索批次编号，用保序集合去重；它落进消息元数据后，
        // 排查问题时可以据此把回答、检索日志、被读文档三者对上。
        Set<String> retrievalIds = new LinkedHashSet<>();
        // 建立「引用 ID → 该引用的完整出处」白名单，保序是为了让返回的白名单顺序稳定、可比对。
        // 这张表就是判断真假出处的唯一依据：不在表里的引用一律视为模型编造。
        Map<String, RagContextEvidence.CitationReference> allowed = new LinkedHashMap<>();
        // 第一层：逐条遍历这次真正注入模型的证据（一次回答可能来自多个检索批次）。
        for (RagContextEvidence item : safeEvidence) {
            // 容错：上游若塞进 null 元素就跳过，避免整条校验链因为一个脏数据崩掉。
            if (item == null) continue;
            // 记下这条证据所属的检索批次编号，作为「这次回答读了哪几批资料」的凭证。
            retrievalIds.add(item.retrievalId());
            // 第二层：把这条证据里的每一个引用摊进白名单。一条证据通常对应多个命中分块。
            for (RagContextEvidence.CitationReference citation : item.citations()) {
                // putIfAbsent 只在首次出现时写入，并返回已存在的旧值；后面用它判断有没有冲突。
                // 同一个引用 ID 在多批证据里重复出现是正常的（同一分块被多次命中），只要出处一致就没问题。
                RagContextEvidence.CitationReference previous = allowed.putIfAbsent(citation.citationId(), citation);
                // 冲突检查：同一个引用 ID 却指向了两份不同的出处，说明上游生成引用 ID 的逻辑出了问题。
                if (previous != null && !previous.equals(citation)) {
                    // 白名单已经不可信，继续校验只会把伪造引用当成合法的，所以直接抛错让这次回答走异常处理，
                    // 宁可整条链路失败，也不能拿一张自相矛盾的白名单去放行引用。
                    throw new IllegalStateException("同一引用ID映射到不同证据");
                }
            }
        }
        // 第三层：从回答正文里按出现顺序提取并去重所有引用标记，得到「模型声称引用了什么」。
        Set<String> mentioned = extract(answer);
        // 第四层之一：流式过滤，标记流 → 只保留白名单里存在的 → 收成有序列表，这就是真正合法被使用的引用。
        List<String> used = mentioned.stream().filter(allowed::containsKey).toList();
        // 第四层之二：反向过滤，标记流 → 只保留白名单里没有的 → 收成有序列表，这些就是模型编造或越界的引用。
        List<String> invalid = mentioned.stream().filter(id -> !allowed.containsKey(id)).toList();
        // 第五层：按优先级把两堆明细收敛成一个总体状态，供上层决定是否拦截或提示。
        RagAnswerCitationValidation.Status status = status(allowed, used, invalid);
        // 打包完整校验结果：状态 + 检索批次 + 全部可用引用 ID + 实际使用的 ID + 非法 ID +
        // 已使用引用的完整出处（最后一段把已用 ID 逐个换回白名单里的出处对象，供前端渲染引用卡片）。
        return new RagAnswerCitationValidation(status, new ArrayList<>(retrievalIds),
                new ArrayList<>(allowed.keySet()), used, invalid,
                used.stream().map(allowed::get).toList());
    }

    /**
     * 从回答文本里提取所有符合平台格式的引用标记。
     *
     * <p>按在文本中出现的先后顺序返回并去重，所以同一个引用被回答提到多次只算一次，
     * 顺序稳定也让校验结果可以直接做断言比对。answer 为 null 时按空文本处理，返回空集合。</p>
     *
     * <p>只读文本，不修改回答，不写库。</p>
     */
    private Set<String> extract(String answer) {
        // 用保序去重集合承接结果：既避免同一个引用重复统计，又保留首次出现的顺序。
        Set<String> result = new LinkedHashSet<>();
        // 让正则在回答文本上滑动匹配；answer 为 null 时换成空串，保证没有输出时也能安全走完。
        Matcher matcher = CITATION_PATTERN.matcher(answer == null ? "" : answer);
        // 逐个把命中的完整标记文本收进集合，直到扫完整篇回答。
        while (matcher.find()) result.add(matcher.group());
        // 返回「模型声称引用了哪些出处」，交给上层与白名单比对。
        return result;
    }

    /**
     * 把「白名单、已用引用、非法引用」三份明细收敛成一个总体状态。
     *
     * <p>判定顺序刻意固定，且伪造优先级最高：
     * 1) 只要出现白名单外的引用，无论用了多少合法引用，都判 INVALID_CITATIONS，因为回答已经不可信；
     * 2) 白名单为空说明这次压根没注入资料，判 NO_RAG，不能算模型的错；
     * 3) 有资料但一条都没引用，判 RAG_AVAILABLE_UNUSED，通常意味着检索没召回到有用内容或提示词没生效；
     * 4) 其余情况才是 VALID。</p>
     *
     * <p>纯计算，不写库、不改状态。</p>
     */
    private RagAnswerCitationValidation.Status status(Map<String, ?> allowed, List<String> used,
                                                       List<String> invalid) {
        // 伪造优先：哪怕只有一个引用不在白名单里，整次回答的出处都判为不可信，交由上层拦截或提示。
        if (!invalid.isEmpty()) return RagAnswerCitationValidation.Status.INVALID_CITATIONS;
        // 白名单为空说明这次没有注入任何资料，回答本来就无从引用，标成「未使用 RAG」而不是判错。
        if (allowed.isEmpty()) return RagAnswerCitationValidation.Status.NO_RAG;
        // 有资料却一个都没引用：出处没问题，但检索或提示词效果可疑，用单独状态标出来方便统计和调优。
        if (used.isEmpty()) return RagAnswerCitationValidation.Status.RAG_AVAILABLE_UNUSED;
        // 走到这里说明引用全部命中白名单，回答的每个出处都能追溯到本次真实读过的分块。
        return RagAnswerCitationValidation.Status.VALID;
    }
}
