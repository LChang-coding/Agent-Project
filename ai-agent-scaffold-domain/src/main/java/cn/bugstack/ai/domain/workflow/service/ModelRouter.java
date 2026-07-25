package cn.bugstack.ai.domain.workflow.service;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowOptionEntity;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** 按请求覆盖、节点覆盖、工作流默认、平台默认的顺序选择模型。 */
@Service
public class ModelRouter {

    /** 低延迟模型代码。 */
    public static final String MODEL_FLASH = "deepseek-v4-flash";
    /** 增强推理模型代码。 */
    public static final String MODEL_PRO = "deepseek-v4-pro";

    /** 运行时只接受显式白名单。 */
    private static final Set<String> SUPPORTED_MODELS = Set.of(MODEL_FLASH, MODEL_PRO);

    /** 选择首个非空模型并校验白名单；未知代码不静默回退。 */
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
