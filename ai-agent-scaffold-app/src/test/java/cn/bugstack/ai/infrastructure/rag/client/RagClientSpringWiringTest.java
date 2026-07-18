package cn.bugstack.ai.infrastructure.rag.client;

import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** 防止生产构造器与包内测试构造器导致 Spring 无法选择注入点。 */
public class RagClientSpringWiringTest {

    @Test
    public void shouldInstantiateAllRagHttpAdaptersWithProductionConstructors() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RagProperties.class, () -> new RagProperties());
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(TeiEmbeddingAdapter.class, TeiRerankerAdapter.class,
                    DoclingDocumentParserAdapter.class, QdrantVectorStoreAdapter.class);
            context.refresh();

            Assert.assertNotNull(context.getBean(TeiEmbeddingAdapter.class));
            Assert.assertNotNull(context.getBean(TeiRerankerAdapter.class));
            Assert.assertNotNull(context.getBean(DoclingDocumentParserAdapter.class));
            Assert.assertNotNull(context.getBean(QdrantVectorStoreAdapter.class));
        }
    }
}
