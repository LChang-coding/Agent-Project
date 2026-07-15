package cn.bugstack.ai.api.dto.asset;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 资产游标分页响应。
 */
@Data
@Builder
public class AssetPageResponseDTO {
    private List<AssetResponseDTO> items;
    private String nextCursor;
    private Boolean hasMore;
}
