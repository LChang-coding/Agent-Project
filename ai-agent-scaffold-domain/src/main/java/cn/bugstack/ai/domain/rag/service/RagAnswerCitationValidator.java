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

/** 根据实际注入白名单校验最终回答中的引用。 */
@Service
public class RagAnswerCitationValidator {

    private static final Pattern CITATION_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_])cite_[0-9a-f]{24}(?![A-Za-z0-9_])");

    /** 校验完整最终回答；参数是回答和实际注入证据；返回稳定有序的校验结果。 */
    public RagAnswerCitationValidation validate(String answer, List<RagContextEvidence> evidence) {
        List<RagContextEvidence> safeEvidence = evidence == null ? List.of() : evidence;
        Set<String> retrievalIds = new LinkedHashSet<>();
        Map<String, RagContextEvidence.CitationReference> allowed = new LinkedHashMap<>();
        for (RagContextEvidence item : safeEvidence) {
            if (item == null) continue;
            retrievalIds.add(item.retrievalId());
            for (RagContextEvidence.CitationReference citation : item.citations()) {
                RagContextEvidence.CitationReference previous = allowed.putIfAbsent(citation.citationId(), citation);
                if (previous != null && !previous.equals(citation)) {
                    throw new IllegalStateException("同一引用ID映射到不同证据");
                }
            }
        }
        Set<String> mentioned = extract(answer);
        List<String> used = mentioned.stream().filter(allowed::containsKey).toList();
        List<String> invalid = mentioned.stream().filter(id -> !allowed.containsKey(id)).toList();
        RagAnswerCitationValidation.Status status = status(allowed, used, invalid);
        return new RagAnswerCitationValidation(status, new ArrayList<>(retrievalIds),
                new ArrayList<>(allowed.keySet()), used, invalid,
                used.stream().map(allowed::get).toList());
    }

    private Set<String> extract(String answer) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer == null ? "" : answer);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private RagAnswerCitationValidation.Status status(Map<String, ?> allowed, List<String> used,
                                                       List<String> invalid) {
        if (!invalid.isEmpty()) return RagAnswerCitationValidation.Status.INVALID_CITATIONS;
        if (allowed.isEmpty()) return RagAnswerCitationValidation.Status.NO_RAG;
        if (used.isEmpty()) return RagAnswerCitationValidation.Status.RAG_AVAILABLE_UNUSED;
        return RagAnswerCitationValidation.Status.VALID;
    }
}
