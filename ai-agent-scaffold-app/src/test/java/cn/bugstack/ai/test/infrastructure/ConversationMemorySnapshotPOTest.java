package cn.bugstack.ai.test.infrastructure;

import cn.bugstack.ai.infrastructure.dao.po.ConversationMemorySnapshotPO;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/**
 * 会话记忆快照持久化对象测试。
 */
public class ConversationMemorySnapshotPOTest {

    /**
     * 校验 MyBatis 可使用无参构造创建快照对象；无参数；验证 UUID 会话ID不会触发构造参数错位。
     */
    @Test
    public void shouldProvideNoArgConstructorForMyBatisResultMapping() {
        boolean hasNoArgConstructor = Arrays.stream(ConversationMemorySnapshotPO.class.getDeclaredConstructors())
                .anyMatch(constructor -> constructor.getParameterCount() == 0);

        Assert.assertTrue("MyBatis 结果映射需要快照 PO 提供无参构造", hasNoArgConstructor);
    }
}
