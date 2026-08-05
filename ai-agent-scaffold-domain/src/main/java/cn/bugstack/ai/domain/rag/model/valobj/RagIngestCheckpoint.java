package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 摄取任务的「存档点」：记录跑到哪一步、处理了多少分块、解析产物存在哪里。
 *
 * <p>属于哪一层：领域层值对象，不可变。每次推进都是造一个新对象，而不是改旧对象。</p>
 *
 * <p>解决什么问题：一份大文档的摄取要经过解析、切块、向量化、写索引好几步，中途 Worker
 * 崩溃是常态。有了存档点，接管的 Worker 能从上次的进度接着做，不用把整份文档重新解析、
 * 重新调一遍 Embedding（那是真金白银的模型费用）。</p>
 *
 * <p>谁读写它：RagIngestJobEntity 持有它，通过 advance / advanceDeletion 换成新的存档点，
 * 并在 complete 时检查「分块数和向量数是否对齐」；摄取 Worker 每完成一小段就写一次。</p>
 *
 * <p>不变量：进度只能前进不能后退（canAdvanceTo 强制），解析事实只能写一次
 * （parsedFactsCanAdvanceTo 强制）。违反任何一条都会被判为检查点非法，因为倒退的进度
 * 会导致同一批向量被写两遍，改动过的解析事实会让最终激活的内容和实际索引的内容对不上。</p>
 *
 * @param stage 当前流水线阶段，是断点续跑的粗粒度定位。
 * @param processedChunks 已处理分块数，必须小于等于 totalChunks。
 * @param totalChunks 已知分块总数；解析和切块完成前是 0，所以不能用它判断「有没有内容」。
 * @param embeddingBatchIndex 下一个待处理的 Embedding 批次序号；重跑时从这里续上，
 *            避免重复给已算过的批次付费。
 * @param vectorUpsertIndex 下一个待写入向量库的序号；完成时必须等于 totalChunks，
 *             否则说明索引只写了一半。
 * @param pageCount 解析得到的页数，属于「解析事实」，写入后不可再变。
 * @param characterCount 解析得到的字符数，同为解析事实；有解析产物时必须大于 0。
 * @param parsedObjectBucket 解析产物所在的对象存储桶，必须和 parsedObjectKey 成对出现。
 * @param parsedObjectKey 解析产物的对象键；重试会覆盖同一个键，保证一个版本只有一份解析产物。
 * @param parsedContentHash 解析产物内容的 SHA-256（强制 64 位小写十六进制）；
 *     它是「激活的内容就是当初解析出来的内容」的凭据，改了就无法证明一致性。
 * @param parsedSizeBytes 解析产物字节数，有解析产物时必须大于 0。
 */
public record RagIngestCheckpoint(RagIngestStage stage,
           int processedChunks,
  int totalChunks,
                int embeddingBatchIndex,
           int vectorUpsertIndex,
          int pageCount,
      long characterCount,
     String parsedObjectBucket,
     String parsedObjectKey,
  String parsedContentHash,
            long parsedSizeBytes) {

    /**
   * 只带进度、不带解析产物的简化入口。
     *
     * <p>两类场景要用它：一是删除任务，它根本不产生解析产物；二是历史数据里那些在
  * 解析产物字段上线之前就存下来的旧检查点。解析相关字段一律填零和空，
     * 后面的构造校验会因此走「没有解析事实」的分支，不会误判为参数非法。</p>
     */
    public RagIngestCheckpoint(RagIngestStage stage, int processedChunks, int totalChunks,
            int embeddingBatchIndex, int vectorUpsertIndex) {
        // 解析事实全部留空，表示这个检查点还没有（或永远不会有）解析产物。
        this(stage, processedChunks, totalChunks, embeddingBatchIndex, vectorUpsertIndex,
                0, 0L, null, null, null, 0L);
    }

    /**
     * 构造校验：把所有「明显不可能」的进度组合挡在门外。
     *
     * <p>各层职责：
     * 第一层：基础数值合法性——阶段必填、各项计数不能为负、已处理不能超过总数、
     *         写入向量数不能超过总分块数。
     * 第二层：VERIFYING 阶段的特殊硬要求——到了验证阶段就必须已经全部处理完、全部写完，
     *         否则验证的对象根本不完整，通过验证也毫无意义。
     * 第三层：解析事实的成组完整性——桶、键、哈希、大小、字符数要么全没有，要么全都合法，
     *         不允许出现「有键没哈希」这种半截数据，否则激活时无法核对内容一致性。</p>
     *
  * <p>数据流：入参 → 数值区间与单调关系校验 → 验证阶段完整性校验 → 解析事实成组校验
     * → 通过则对象成立，否则抛出参数异常。</p>
   */
    public RagIngestCheckpoint {
 // 第一层加第二层合并判断：任一条不成立就说明这份进度自相矛盾，不能作为断点续跑的依据。
        if (stage == null || processedChunks < 0 || totalChunks < 0 || processedChunks > totalChunks
     || embeddingBatchIndex < 0 || vectorUpsertIndex < 0 || vectorUpsertIndex > totalChunks
         || pageCount < 0 || characterCount < 0 || parsedSizeBytes < 0
          || stage == RagIngestStage.VERIFYING
      && (totalChunks < 1 || processedChunks != totalChunks || vectorUpsertIndex != totalChunks)) {
            // 直接拒绝，避免一份半截进度被存进数据库后再也说不清该从哪继续。
    throw new IllegalArgumentException("摄取检查点参数非法");
        }
  // 第三层：先看解析事实的四个字段里有没有任何一个被填过，判断这份检查点是否声称已完成解析。
        boolean anyParsedLocation = hasText(parsedObjectBucket) || hasText(parsedObjectKey)
  || hasText(parsedContentHash) || parsedSizeBytes > 0;
        // 一旦声称有解析产物，桶、键、64 位哈希、字节数、字符数就必须全部齐备且合法。
        if (anyParsedLocation && (!hasText(parsedObjectBucket) || !hasText(parsedObjectKey)
             || parsedContentHash == null || !parsedContentHash.matches("[0-9a-f]{64}")
        || parsedSizeBytes < 1 || characterCount < 1)) {
          // 半截的解析事实会让后续激活无法核对内容哈希，等于放弃了一致性凭据，必须拒绝。
            throw new IllegalArgumentException("解析产物检查点参数非法");
   }
    }

    /**
     * 造一个全新任务的起始存档点：停在「已受理」，各项进度都是 0。
     *
     * <p>被 RagIngestJobEntity.pending 和 requeueIngest 使用——后者表示「从头彻底重跑」，
     * 所以刻意丢掉旧进度，而不是接着上次的位置。</p>
     */
    public static RagIngestCheckpoint initial() {
        // 阶段回到已受理、所有计数归零，等价于「这份文档还一点没处理」。
   return new RagIngestCheckpoint(RagIngestStage.RECEIVED, 0, 0, 0, 0);
    }

    /**
     * 判断能不能从当前存档点走到目标存档点。
     *
   * <p>要同时满足两类条件：阶段相邻或原地（禁止跳级和倒退），以及四个进度计数都不减少。
     * 计数不减少这一条很关键——如果允许减少，一个慢半拍的重试请求就可能把进度往回冲，
     * 导致已经算好的 Embedding 批次被重复付费计算、已经写好的向量被重复写入。</p>
     *
     * <p>返回 false 时调用方（advance）会抛 RAG_INGEST_CHECKPOINT_REGRESSION，
     * 任务保持原状，不会被脏进度污染。</p>
     */
    public boolean canAdvanceTo(RagIngestCheckpoint target) {
        // 目标必须存在、阶段合法相邻，且四项进度计数都只增不减，最后解析事实也不能被改写。
        return target != null && stage.canAdvanceTo(target.stage)
       && target.processedChunks >= processedChunks
                && target.totalChunks >= totalChunks
              && target.embeddingBatchIndex >= embeddingBatchIndex
          && target.vectorUpsertIndex >= vectorUpsertIndex
          && parsedFactsCanAdvanceTo(target);
    }

    /**
     * 校验解析事实「只能首次写入，之后必须原样携带」。
     *
     * <p>为什么这么设计：解析产物是整个摄取的事实基准，最终激活时要靠它的内容哈希证明
     * 「用户看到的这一版内容就是当初解析出来的内容」。如果允许中途替换，就等于允许
     * 悄悄把索引内容换成另一份文档，而外部完全看不出来。</p>
     *
     * <p>还没有解析产物时直接放行，因为此时任何写入都属于首次写入。</p>
     */
    private boolean parsedFactsCanAdvanceTo(RagIngestCheckpoint target) {
      // 当前还没有解析产物，说明目标是首次写入，允许它自由填写。
   if (!hasText(parsedObjectKey)) {
      // 放行，具体合法性由目标对象自己的构造校验负责。
            return true;
        }
  // 已经有解析事实了，目标必须逐字段完全一致，任何一处不同都视为篡改。
        return pageCount == target.pageCount && characterCount == target.characterCount
 && parsedSizeBytes == target.parsedSizeBytes
      && parsedObjectBucket.equals(target.parsedObjectBucket)
           && parsedObjectKey.equals(target.parsedObjectKey)
    && parsedContentHash.equals(target.parsedContentHash);
    }

    /**
     * 判断一个解析事实字段是不是真的填过。
  *
     * <p>空串和纯空白一律按「没填」处理，避免历史数据里的空字符串被当成「已有解析产物」，
  * 从而错误地触发那一整组严格校验。</p>
     */
    private static boolean hasText(String value) {
        // 只有非 null 且含有非空白字符才算真正填写过。
      return value != null && !value.isBlank();
    }
}
