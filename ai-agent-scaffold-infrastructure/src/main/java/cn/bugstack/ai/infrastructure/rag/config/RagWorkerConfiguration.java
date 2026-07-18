package cn.bugstack.ai.infrastructure.rag.config;

import cn.bugstack.ai.domain.rag.adapter.port.SparseEncoderPort;
import cn.bugstack.ai.domain.rag.service.DeterministicSparseEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** RAG 本地轻量算法组件配置。 */
@Configuration
public class RagWorkerConfiguration {

    @Bean
    public SparseEncoderPort ragSparseEncoder() {
        return new DeterministicSparseEncoder();
    }
}
