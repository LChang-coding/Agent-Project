package cn.bugstack.ai.api.dto;

import lombok.Builder;
import lombok.Data;

/** 经当前权限和版本校验后返回的引用来源。 */
@Data
@Builder
public class RagCitationSourceDTO {
    private String citationId;
    private String documentId;
    private String documentName;
    private Integer documentVersion;
    private Integer pageNumber;
    private String headingPath;
    private String excerpt;
}
