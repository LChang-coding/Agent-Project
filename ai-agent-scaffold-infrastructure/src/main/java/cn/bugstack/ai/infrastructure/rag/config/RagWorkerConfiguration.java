package cn.bugstack.ai.infrastructure.rag.config;

import cn.bugstack.ai.domain.rag.adapter.port.SparseEncoderPort;
import cn.bugstack.ai.domain.rag.service.DeterministicSparseEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** RAG 本地轻量算法组件配置。 */
@Configuration
public class RagWorkerConfiguration {

    /** 注册确定性稀疏编码器，使入库和在线查询使用相同词表算法。 */
    @Bean
    public SparseEncoderPort ragSparseEncoder() {
        return new DeterministicSparseEncoder();
    }
}
