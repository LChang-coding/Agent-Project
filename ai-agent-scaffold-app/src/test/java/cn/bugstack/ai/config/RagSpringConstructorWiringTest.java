package cn.bugstack.ai.config;

import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** 防止测试便利构造器导致 RAG Bean 在真实 Spring 容器中无法装配。 */
public class RagSpringConstructorWiringTest {

    private static final List<String> RAG_PACKAGES = List.of(
            "cn.bugstack.ai.domain.rag",
            "cn.bugstack.ai.infrastructure.rag"
    );

    @Test
    public void multiConstructorRagComponentsMustDeclareOneAutowiredConstructor() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class, true, true));

        for (String packageName : RAG_PACKAGES) {
            for (var candidate : scanner.findCandidateComponents(packageName)) {
                Class<?> componentType = Class.forName(candidate.getBeanClassName());
                Constructor<?>[] constructors = componentType.getDeclaredConstructors();
                if (constructors.length <= 1) continue;
                long autowiredConstructors = Arrays.stream(constructors)
                        .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                        .count();
                assertEquals(componentType.getName() + " 有多个构造器时必须且只能声明一个 @Autowired 构造器",
                        1L, autowiredConstructors);
            }
        }
    }
}
