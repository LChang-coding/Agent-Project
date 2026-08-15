package cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 将统一思考配置映射为供应商协议，并把响应归一化成内部信封。 */
public interface ReasoningModelAdapter {

    String provider();

    void prepareRequest(ObjectNode request, ReasoningMode mode);

    void normalizeResponse(ObjectNode response);
}
