package cn.bugstack.ai.test.auth;

import cn.bugstack.ai.infrastructure.adapter.repository.AuthRepository;
import cn.bugstack.ai.infrastructure.dao.IUserSecretDao;
import cn.bugstack.ai.infrastructure.dao.po.UserSecretPO;
import org.junit.Assert;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 认证仓储测试。
 */
public class AuthRepositoryTest {

    /**
     * 校验刷新令牌保存；无参数；连续签发时应覆盖同一条凭证而不是重复插入。
     */
    @Test
    public void shouldUpsertRefreshTokenWhenIssueRepeatedly() {
        FakeUserSecretDao userSecretDao = new FakeUserSecretDao();
        AuthRepository repository = new AuthRepository(null, null, null, userSecretDao);

        repository.saveRefreshToken("tenant_1", "user_1", "hash_1", LocalDateTime.now().plusDays(1));
        repository.saveRefreshToken("tenant_1", "user_1", "hash_2", LocalDateTime.now().plusDays(2));

        Assert.assertEquals(0, userSecretDao.insertCount);
        Assert.assertEquals(2, userSecretDao.upsertCount);
        Assert.assertEquals("hash_2", userSecretDao.stored.getSecretValueHash());
        Assert.assertEquals("active", userSecretDao.stored.getStatus());
    }

    private static class FakeUserSecretDao implements IUserSecretDao {

        private int insertCount;
        private int upsertCount;
        private UserSecretPO stored;

        /**
         * 新增用户凭证；参数是凭证对象；返回影响行数。
         */
        @Override
        public int insert(UserSecretPO userSecret) {
            insertCount++;
            stored = userSecret;
            return 1;
        }

        /**
         * 覆盖用户凭证；参数是凭证对象；返回影响行数。
         */
        @Override
        public int upsertByUserIdAndType(UserSecretPO userSecret) {
            upsertCount++;
            stored = userSecret;
            return 1;
        }

        /**
         * 按主键更新凭证；参数是凭证对象；返回影响行数。
         */
        @Override
        public int updateById(UserSecretPO userSecret) {
            stored = userSecret;
            return 1;
        }

        /**
         * 按主键查询凭证；参数是主键；返回凭证对象。
         */
        @Override
        public UserSecretPO queryById(Long id) {
            return stored;
        }

        /**
         * 按租户查询凭证；参数是租户ID；返回凭证列表。
         */
        @Override
        public List<UserSecretPO> queryListByTenantId(String tenantId) {
            return Collections.emptyList();
        }

        /**
         * 按用户查询凭证；参数是用户ID；返回凭证列表。
         */
        @Override
        public List<UserSecretPO> queryListByUserId(String userId) {
            return stored == null ? Collections.emptyList() : List.of(stored);
        }

        /**
         * 查询密码凭证；参数是用户ID；返回密码凭证。
         */
        @Override
        public UserSecretPO queryPasswordByUserId(String userId) {
            return null;
        }

        /**
         * 查询可用凭证；参数是用户ID和凭证类型；返回可用凭证。
         */
        @Override
        public UserSecretPO queryActiveByUserIdAndType(String userId, String secretType) {
            if (stored == null || !"active".equals(stored.getStatus())) {
                return null;
            }
            return stored;
        }

        /**
         * 禁用可用凭证；参数是用户ID和凭证类型；返回影响行数。
         */
        @Override
        public int disableActiveByUserIdAndType(String userId, String secretType) {
            if (stored != null) {
                stored.setStatus("disabled");
            }
            return 1;
        }
    }
}
