package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.rag.adapter.repository.ISessionRagSelectionRepository;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.infrastructure.dao.ISessionRagBindingSelectionDao;
import cn.bugstack.ai.infrastructure.dao.po.SessionRagBindingSelectionPO;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * 会话RAG手动选择仓储实现。
 */
@Repository
public class SessionRagSelectionRepository implements ISessionRagSelectionRepository {

    private final ISessionRagBindingSelectionDao selectionDao;

    /**
     * 创建会话RAG选择仓储。
     *
     * @param selectionDao 会话选择DAO
     */
    public SessionRagSelectionRepository(ISessionRagBindingSelectionDao selectionDao) {
        this.selectionDao = selectionDao;
    }

    /** 查询会话选择并仅向领域层返回绑定ID。 */
    @Override
    public List<String> listSelectedBindingIds(String tenantId, String userId, String sessionId) {
        return selectionDao.queryBySession(tenantId, userId, sessionId).stream()
                .map(SessionRagBindingSelectionPO::getBindingId)
                .toList();
    }

    /** 在外层领域事务内先删后插，完整替换会话选择。 */
    @Override
    public void replaceSelections(String tenantId, String userId, String sessionId,
                                  RagBindingTargetType targetType, String targetId, List<String> bindingIds) {
        selectionDao.deleteBySession(tenantId, userId, sessionId);
        if (bindingIds == null || bindingIds.isEmpty()) {
            return;
        }
        List<SessionRagBindingSelectionPO> items = IntStream.range(0, bindingIds.size())
                .mapToObj(index -> SessionRagBindingSelectionPO.builder()
                        .tenantId(tenantId)
                        .userId(userId)
                        .sessionId(sessionId)
                        .targetType(targetType.name().toLowerCase(Locale.ROOT))
                        .targetId(targetId)
                        .bindingId(bindingIds.get(index))
                        .selectionOrder(index)
                        .build())
                .toList();
        selectionDao.batchInsert(items);
    }
}
