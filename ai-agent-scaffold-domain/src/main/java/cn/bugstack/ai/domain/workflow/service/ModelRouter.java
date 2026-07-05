package cn.bugstack.ai.domain.workflow.service;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowOptionEntity;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 动态模型路由器。
 */
@Service
public class ModelRouter {

    public static final String MODEL_FLASH = "deepseek-v4-flash";
    public static final String MODEL_PRO = "deepseek-v4-pro";

    private static final Set<String> SUPPORTED_MODELS = Set.of(MODEL_FLASH, MODEL_PRO);

    /**
     * 选择本次有效模型；参数是请求模型、节点模型和工作流默认模型；返回可用模型编码。
     */
    public String route(String requestModelCode, String nodeModelCode, String workflowDefaultModelCode) {
        String modelCode = firstNotBlank(requestModelCode, nodeModelCode, workflowDefaultModelCode, MODEL_FLASH);
        if (!SUPPORTED_MODELS.contains(modelCode)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "不支持的模型：" + modelCode);
        }
        return modelCode;
    }

    /**
     * 查询模型选项；无参数；返回前端可展示模型列表。
     */
    public List<WorkflowOptionEntity> modelOptions() {
        return List.of(
                WorkflowOptionEntity.builder()
                        .value(MODEL_FLASH)
                        .label("DeepSeek V4 Flash")
                        .description("默认快速模型，适合日常对话和轻量工作流")
                        .type("deepseek")
                        .status("active")
                        .build(),
                WorkflowOptionEntity.builder()
                        .value(MODEL_PRO)
                        .label("DeepSeek V4 Pro")
                        .description("增强模型，适合复杂分析、规划和长链路推理")
                        .type("deepseek")
                        .status("active")
                        .build()
        );
    }

    /**
     * 取第一个非空字符串；参数是候选值；返回非空值。
     */
    private String firstNotBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return MODEL_FLASH;
    }
}
