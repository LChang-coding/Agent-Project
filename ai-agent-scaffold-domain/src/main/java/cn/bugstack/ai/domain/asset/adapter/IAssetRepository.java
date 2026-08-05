package cn.bugstack.ai.domain.asset.adapter;

import cn.bugstack.ai.domain.asset.model.AssetEntity;

import java.util.List;

/**
 * 资产（聊天附件）记录的读写出口，所有方法都强制带租户和拥有者，把「越权」在接口签名上就堵死。
 *
 * <p>所属层次：领域层（domain）asset 子域的适配器出口。实现在基础设施层
 * （{@code AssetRepository} + MyBatis 映射 {@code artifact_asset_mapper.xml}），由 Spring 注入。</p>
 *
 * <p>谁会调用它：{@code AssetService}（上传、列表、下载、删除、绑定消息）和
 * {@code AssetContextContributor}（组装上下文时读取附件文本）。</p>
 *
 * <p>为什么每个方法都要传 tenantId + ownerUserId：这两个值是数据隔离的唯一依据，
 * SQL 的 WHERE 条件里一定带上它们，所以即使调用方拿到了别人的 assetId 也查不出记录。
 * 注意 tenantId 允许为 null（个人模式），SQL 里用「都为空或相等」的写法匹配，
 * 因此不能把空串当 null 传进来，否则会匹配不到任何数据。</p>
 *
 * <p>它不负责什么：不读写对象存储里的文件内容（那是 ObjectStorageService 的事）、
 * 不做业务规则判断（例如附件数量上限在领域服务里校验）、不管事务边界（事务由领域服务的注解控制）。</p>
 */
public interface IAssetRepository {

    /**
     * 插入一条资产记录，把「文件已经在对象存储里」这件事登记进数据库。
     *
     * <p>调用顺序上它一定发生在文件上传成功之后。实现端会把数据库自增主键回填进传入的实体，
     * 所以返回的就是同一个对象，只是多了 id。</p>
     *
     * <p>这一步失败意味着对象存储里留下了没人引用的孤儿文件，领域服务必须捕获异常做补偿删除。</p>
     */
    AssetEntity insert(AssetEntity asset);

    /**
     * 按租户 + 拥有者 + 资产编号查一条记录，是所有「按 assetId 操作」前的权限闸门。
     *
     * <p>查不到就返回 null，领域服务据此统一抛「资产不存在或无权访问」——
     * 刻意把「不存在」和「不属于你」合成同一句话，避免攻击者靠错误信息探测别人的资产是否存在。</p>
     *
     * <p>SQL 里带 status='active' 和 deleted=0，所以已软删除的资产查不出来。</p>
     */
    AssetEntity queryOwned(String tenantId, String ownerUserId, String assetId);

    /**
     * 按内容哈希找同一用户之前上传过的同样内容，用来做秒传去重。
     *
     * <p>命中时领域服务会跳过上传、直接复用已有的桶和对象键，也复用已有的解析文本，
     * 省掉一次网络传输和一次解析。为什么限定「同一用户」：跨用户复用会让 A 通过哈希碰撞探测 B 上传过什么文件。</p>
     *
     * <p>只返回仍然可用的记录（active、未删除、桶和键都不为空），并取最新一条；
     * 没有则返回 null，表示必须真的上传一次。</p>
     */
    AssetEntity queryReusableByHash(String tenantId, String ownerUserId, String sha256);

    /**
     * 分页列出当前用户的资产，供前端「我的附件」列表使用。
     *
     * <p>用 id 倒序游标翻页而不是 offset：附件会不断新增，offset 翻页会漏数据或重复。
     * cursor 传上一页最后一条的 id，为空表示第一页。</p>
     *
     * <p>sessionId 和 assetKind 是可选过滤条件，为空则不加该条件。
     * 只返回 active 且未删除的记录，租户和拥有者永远是硬条件。</p>
     */
    List<AssetEntity> queryOwnedList(String tenantId, String ownerUserId, Long cursor, int limit,
                                     String sessionId, String assetKind);

    /**
     * 把一批附件一次性绑定到刚保存的那条用户消息上，是附件真正「生效」的动作。
     *
     * <p>关键在于这是一条带严格条件的批量 UPDATE，条件里要求：属于本人、active、
     * 解析状态必须是 ready、原来没绑过任何消息（message_id IS NULL）、且原来要么没会话要么就是本会话。
     * 因此一个附件一辈子只能绑一条消息，别人的附件、还没解析完的附件、已删除的附件都绑不上。</p>
     *
     * <p>返回真正更新成功的行数。领域服务会拿它和请求的附件个数比较，只要不相等就整体拒绝并回滚，
     * 这样就不会出现「10 个附件只生效了 3 个，用户却以为都发出去了」的半成功状态。</p>
     */
    int bindReadyAssets(String tenantId, String ownerUserId, String sessionId, String messageId,
                        List<String> assetIds);

    /**
     * 软删除一条资产：把状态改成 deleted 并置删除标记，记录仍留在库里。
     *
     * <p>为什么是软删除：附件可能已经被历史消息引用，物理删掉会让历史对话出现引用不到的空洞；
     * 而且对象存储里的文件也不在这里删，所以随时可以恢复。</p>
     *
     * <p>注意条件里只校验「属于本人且当前是 active」，并不检查它是否已经绑定到某条消息，
     * 也就是说正在被消息引用的附件同样可以删。删除后上下文组装的 SQL 会自动过滤掉它，
     * 效果是这个附件从之后的对话上下文里静默消失，但历史消息记录本身不受影响。</p>
     *
     * <p>返回影响行数；返回 0 说明记录已被并发删除或状态已变，领域服务据此抛冲突让用户刷新重试。</p>
     */
    int softDelete(String tenantId, String ownerUserId, String assetId);

    /**
     * 取出这次组装上下文时可以注入给模型的附件文本，是附件参与对话的唯一读取入口。
     *
     * <p>它连接 artifact_asset 和 chat_message 两张表，只挑同时满足这些条件的附件：
     * 属于本人本会话、active 且解析成功、并且绑定的那条消息是「有效的用户消息」。
     * 一旦某轮对话被取消或被重新生成导致消息失效，对应附件就自动不再进上下文，
     * 避免用户已经撤回的内容还继续影响模型回答。</p>
     *
     * <p>fromSequenceExclusive 是长期摘要已经覆盖到的消息序号：这之前的内容已经被压缩进摘要，
     * 附件不必再重复注入一遍。visibleThroughSequence 是本轮可见的最大序号，
     * 防止把当前这条还没回答完的消息之后的东西提前带进来。</p>
     *
     * <p>candidateLimit 限制最多扫多少条，maxContentChars 是所有附件文本合计的字符上限——
     * SQL 内部用窗口函数累加前面附件已经占掉的字符数，给后面的附件按剩余额度裁剪，
     * 保证一次组装读回来的总量是有界的，不会因为某个会话附件特别多而把内存和模型窗口打满。</p>
     *
     * <p>返回结果按消息序号倒序（最近的在前），因此预算是优先给最近的附件的。</p>
     */
    List<AssetEntity> queryContextAssets(String tenantId, String ownerUserId, String sessionId,
                                         Integer fromSequenceExclusive, Integer visibleThroughSequence,
                                         int candidateLimit, int maxContentChars);

    /**
     * 只数一下上面那批「可进上下文的附件」有多少个，不取正文。
     *
     * <p>用途是给上下文洞察 / 诊断信息用：想告诉用户或排查人员「这轮带了几个附件」时，
     * 没必要把几十万字符的正文全查回来，单独走一次计数更省内存和带宽。</p>
     */
    int countContextAssets(String tenantId, String ownerUserId, String sessionId,
                           Integer fromSequenceExclusive, Integer visibleThroughSequence);
}
