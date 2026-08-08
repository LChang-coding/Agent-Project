package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 知识库级联删除任务的可持久化检查点。
 * <p>检查点仅保存删除阶段、总文档数、已完成数和当前文档标识，使协调器重启或租约过期后
 * 可以继续未完成的级联删除。</p>
 *
 * @param stage 级联删除的当前阶段
 * @param totalDocuments 任务登记时确定且后续不可变的文档总数
 * @param completedDocuments 已完成删除的文档数
 * @param currentDocumentId 当前正在处理的文档标识，可为空
 */
public record RagKnowledgeBaseDeleteCheckpoint(RagKnowledgeBaseDeleteStage stage,
         int totalDocuments,
 int completedDocuments,
      String currentDocumentId) {

    /**
     * 构造校验：挡住自相矛盾的删除进度。
     *
     * <p>第一层：阶段必填、数量非负、完成数不能超过总数，正在处理的文档编号要么为空
     * 要么是长度合法的非空串（空串会让排查日志里出现看不出含义的占位值）。
     * 第二层：阶段已经是 COMPLETED 时，完成数必须等于总数——否则就是「宣布删完了但还有文档没删」，
     * 那些残留数据将永远没有任何任务负责清理。</p>
     */
    public RagKnowledgeBaseDeleteCheckpoint {
     // 第一层：基础数值与字段格式校验，任一不成立说明这份进度本身就是脏的。
        if (stage == null || totalDocuments < 0 || completedDocuments < 0
          || completedDocuments > totalDocuments
           || currentDocumentId != null && (currentDocumentId.isBlank()
          || currentDocumentId.length() > 64)) {
            // 直接拒绝，避免脏进度落库后协调器再也算不清还剩多少要删。
            throw new IllegalArgumentException("知识库删除检查点非法");
        }
        // 第二层：完成阶段必须名副其实，防止残留文档失去清理责任人。
if (stage == RagKnowledgeBaseDeleteStage.COMPLETED
     && completedDocuments != totalDocuments) {
        // 数量不符就拒绝构造，让问题在写库之前暴露。
            throw new IllegalArgumentException("知识库删除完成检查点数量不一致");
  }
    }

    /**
     * 按建屏障那一刻的文档总数造一个起始存档点。
     *
   * <p>阶段为已受理、完成数为 0、当前文档为空。总数在这里被一次性钉死，
  * 后续即使有人往库里塞新文档也不会改变分母——因为删除屏障已经立起来，
     * 库不再接受新的写入。</p>
     */
    public static RagKnowledgeBaseDeleteCheckpoint initial(int totalDocuments) {
  // 从已受理阶段起步，完成数归零，尚未指向任何具体文档。
      return new RagKnowledgeBaseDeleteCheckpoint(RagKnowledgeBaseDeleteStage.RECEIVED,
                totalDocuments, 0, null);
    }
}
