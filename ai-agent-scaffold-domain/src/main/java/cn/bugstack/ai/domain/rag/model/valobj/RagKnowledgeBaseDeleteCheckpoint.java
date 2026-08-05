package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 知识库级联删除的「存档点」：删到第几个文档、当前正在删哪一个。
 *
 * <p>属于哪一层：领域层值对象，不可变。</p>
 *
 * <p>解决什么问题：删一个知识库要逐个删掉里面所有文档，可能上千个。协调器中途重启后
 * 靠它知道已经删完多少个，剩下的接着删，而不是从头再扫一遍。</p>
 *
 * <p>刻意不放什么：不放文档正文、不放任何访问凭据。这份进度会被写进任务表并被跨实例读取，
 * 越轻越安全，只留能定位进度的最小信息。</p>
 *
 * <p>谁读写它：RagKnowledgeBaseDeleteTaskEntity 持有它，通过 advance / waitForChild 换新；
 * complete 时会检查「完成数是否等于总数」。</p>
 *
 * @param stage 级联删除的当前阶段。
 * @param totalDocuments 建屏障那一刻库里的文档总数；它是删除任务的分母，
 *  一旦确定在整个任务生命周期内都不允许改变（advance 会逐次比对）。
 * @param completedDocuments 已经删完的文档数，只能增不能减；等于总数才允许进入完成。
 * @param currentDocumentId 正在处理的文档编号，可为空；长度限制 64，
 *        只用于排查「卡在哪个文档上」，不作为任何判断依据。
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
