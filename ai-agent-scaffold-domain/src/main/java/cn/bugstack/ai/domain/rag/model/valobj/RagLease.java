package cn.bugstack.ai.domain.rag.model.valobj;

import java.time.Instant;

/**
 * 摄取 Worker 的「工作许可」：谁在跑这个任务、这个许可什么时候到期。
 *
 * <p>属于哪一层：领域层值对象，不可变。被 RagIngestJobEntity 和
 * RagKnowledgeBaseDeleteTaskEntity 共用。</p>
 *
 * <p>解决什么问题：任务是分布式跑的，某台机器可能突然宕机。如果只用「谁领了就归谁」，
 * 宕机的任务会永远卡住没人管。加上到期时间后，别的 Worker 只要发现租约过期就能接管。
 * 正常在跑的 Worker 则通过心跳不断续租，让别人抢不走。</p>
 *
 * <p>谁读写它：任务实体在 claim（新建租约）、renewLease（续租）、assertFence（校验归属）时用它；
 * 任务终结时会把它置空，因为已经没人在跑了。</p>
 *
 * <p>它不负责什么：不负责防止「过期后旧 Worker 突然复活继续写数据」——那靠单调递增的
 * fencing token 拦截。租约只解决「谁有资格接手」，fencing token 解决「旧的写入必须被拒绝」。</p>
 *
 * @param owner 持有这个租约的 Worker 标识；assertFence 会拿它和调用方传来的名字逐字比对，
 *         不一致就判定为「你已经不是负责人了」，所有后续写入和外部调用一律拒绝。
 * @param expiresAt 租约失效时刻；到点之后其他 Worker 就能合法接管这个任务。
 *             续租其实就是把这个时间往后推。
 */
public record RagLease(String owner, Instant expiresAt) {

    /**
     * 构造校验：持有者和到期时间都不能缺。
     *
  * <p>为什么必须严格：没有持有者的租约谁都能冒充；没有到期时间的租约永不过期，
     * 一旦机器宕机这个任务就再也没人能接管，会永久卡在运行中。</p>
     */
  public RagLease {
    // 缺持有者等于无法认定归属，缺到期时间等于永不释放，两种都会让故障接管机制失效。
        if (owner == null || owner.isBlank() || expiresAt == null) {
 // 直接拒绝构造，把脏数据挡在领域模型之外，而不是等到接管时才发现问题。
            throw new IllegalArgumentException("摄取租约参数不完整");
      }
    }

    /**
     * 判断在给定时刻这个租约是不是已经失效。
     *
     * <p>两种相反的用途都靠它：接管前用它确认「原持有者确实失联了」，
     * 继续干活前用它确认「我的许可还没过期」。过期还继续调用外部服务是危险的——
* 那时别人可能已经接管，两个 Worker 会同时往向量库里写同一批数据。</p>
     *
     * <p>边界取「到点即失效」：expiresAt 正好等于 now 也算过期，宁可早一点释放，
     * 也不要出现两个 Worker 都认为自己有效的重叠瞬间。</p>
     */
    public boolean expiredAt(Instant now) {
// 没有比较基准就无法判断有效性，宁可报错也不能默认「有效」而放行外部写入。
        if (now == null) {
    // 抛参数异常提醒调用方补上时间源，避免静默地把过期租约当成有效。
  throw new IllegalArgumentException("租约校验时间不能为空");
        }
        // 到期时间没有严格晚于当前时刻，就算失效；等于的临界情况也算过期，避免归属重叠。
        return !expiresAt.isAfter(now);
  }
}
