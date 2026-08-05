package cn.bugstack.ai.domain.asset.service;

import cn.bugstack.ai.domain.asset.adapter.AssetTextExtractor;
import cn.bugstack.ai.domain.asset.adapter.IAssetRepository;
import cn.bugstack.ai.domain.asset.model.AssetEntity;
import cn.bugstack.ai.domain.asset.model.AssetParseResultEntity;
import cn.bugstack.ai.domain.asset.model.AssetUploadCommandEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageResultEntity;
import cn.bugstack.ai.domain.storage.service.ObjectStorageService;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 聊天附件的全生命周期管家：上传、列表、下载、软删除，以及把附件绑定到某条用户消息上。
 *
 * <p>所属层次：领域层（domain）asset 子域的领域服务，业务规则的落脚点。</p>
 *
 * <p>谁会调用它：触发器层的附件接口（上传/列表/下载/删除），以及对话流程在保存完用户消息后
 * 调 {@code bindToMessage} 让附件生效。</p>
 *
 * <p>它向下调用什么：
 * 1) {@code IAssetRepository}：附件档案的读写，所有 SQL 都带租户和拥有者条件；
 * 2) {@code AssetTextExtractor}：把文件内容提成纯文本，供之后注入模型；
 * 3) {@code ObjectStorageService}：保存和读取真正的文件字节，以及失败时的补偿删除；
 * 4) {@code SessionDomain}：复核「这个会话真的属于当前用户吗」。</p>
 *
 * <p>归属校验贯穿每一个方法，且遵循同一条原则：一切以传进来的可信身份为准，
 * 任何按 assetId 或 sessionId 的操作都先带上租户+用户去查一遍，查不到就当作不存在。
 * 因此拿到别人的 assetId 也读不到、删不掉、绑不上。</p>
 *
 * <p>它不负责什么：不做认证（身份由上层从 JWT/租户上下文取好传进来）、不生成 HTTP 响应、
 * 不解析文件格式细节、不物理删除对象存储里的文件（删除一律软删，文件保留）。</p>
 */
@Service
public class AssetService {

    /**
     * 单个附件允许的最大字节数（20 MiB）。
     *
     * <p>两处用到：上传前拦截超大文件，避免一份内容把 JVM 堆和数据库文本字段撑爆；
     * 下载时作为读取上限传给对象存储，防止有人绕过上传路径塞进一个超大对象后再触发读取把内存打满。</p>
     */
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    /**
     * 一条用户消息最多能携带的附件个数。
     *
     * <p>在绑定阶段校验。限制它是因为每个附件都要占模型上下文预算，一次带几十个附件
     * 会把对话历史全挤出窗口，模型反而答不好；同时也避免一次绑定产生过大的批量 UPDATE。</p>
     */
    public static final int MAX_ATTACHMENTS_PER_MESSAGE = 10;

    /**
     * 展示文件名允许的最大字符数，与数据库字段长度对齐。
     *
     * <p>超长时保留尾部而不是开头，这样扩展名（.pdf、.docx）不会被切掉，
     * 前端仍能正确显示图标和下载后缀。</p>
     */
    private static final int MAX_FILE_NAME_LENGTH = 255;

    /**
     * 附件档案的读写出口。
     *
     * <p>所有方法都带租户+拥有者，是租户隔离和防越权的执行者。绑定、软删除靠它的
     * 条件 UPDATE 返回的影响行数来判断是否真的成功，这是并发下唯一可靠的判据。</p>
     */
    private final IAssetRepository repository;

    /**
     * 文件正文提取器。
     *
     * <p>只在上传阶段调用一次，结果落库；之后组装上下文时直接读库里的文本，不会重复解析。
     * 它不抛异常，失败会返回带失败状态的结果，所以解析问题不会让上传失败。</p>
     */
    private final AssetTextExtractor textExtractor;

    /**
     * 文件字节的存取出口。
     *
     * <p>上传时写对象、下载时读对象；数据库写失败时还要用它做补偿删除，
     * 否则桶里会留下没人引用的孤儿文件。</p>
     */
    private final ObjectStorageService storageService;

    /**
     * 会话领域服务，这里只借它做一件事：确认某个 sessionId 确实属于当前用户。
     *
     * <p>为什么不能省：附件可以预挂到会话上，也要绑定到会话内的消息上。
     * 如果不复核会话归属，攻击者就能把自己的附件挂进别人的会话，进而影响别人的对话上下文。</p>
     */
    private final SessionDomain sessionDomain;

    /**
     * 注入四个依赖端口，构造附件领域服务。
     *
     * <p>Spring 启动时创建单例；没有可变成员，并发调用安全。</p>
     */
    public AssetService(IAssetRepository repository, AssetTextExtractor textExtractor,
                        ObjectStorageService storageService, SessionDomain sessionDomain) {
     // 保存档案读写出口，后续所有查询和写入都经过它并自带租户隔离条件。
        this.repository = repository;
    // 保存正文提取器，只在上传时用一次。
        this.textExtractor = textExtractor;
        // 保存对象存储出口，负责真正的文件字节读写与补偿删除。
        this.storageService = storageService;
        // 保存会话领域服务，用于复核会话归属，防止把附件挂到别人的会话上。
        this.sessionDomain = sessionDomain;
    }

    /**
     * 上传一个聊天附件：校验、去重、存文件、提正文、写档案，失败时回收已上传的文件。
     *
     * <p>各层职责：
     * 第一层：参数与大小校验，并在声明了会话时复核会话归属，把越权请求挡在最前面。
     * 第二层：按内容哈希找同一用户是否已上传过相同内容，命中就复用位置，省掉一次上传和一次解析。
     * 第三层：没命中才真正写对象存储，并采信存储端重算出的哈希。
     * 第四层：提取正文（复用时直接沿用旧结果），然后写数据库档案。
     * 第五层：数据库写失败时补偿删除刚上传的对象，避免留下孤儿文件，随后把原始异常原样抛出。</p>
     *
     * <p>数据流：
     * 上传命令（可信身份 + 文件字节）
     * → 参数与 20 MiB 上限校验
     * → 会话归属复核（可选）
     * → 计算内容 SHA-256
     * → 查同用户可复用记录
     * → 命中则复用桶与对象键 / 未命中则写对象存储并取回存储端哈希
     * → 提取正文或沿用已有解析结果
     * → 插入资产档案
     * → 失败则补偿删除对象
     * → 返回资产元数据</p>
     *
     * <p>会写对象存储、会写数据库，不发事件、不调模型。主要失败情形：缺少可信身份、
     * 文件为空或超限、会话不属于当前用户、对象存储不可用、数据库写入冲突。</p>
     */
    public AssetEntity uploadChatAttachment(AssetUploadCommandEntity command) {
        // 第一层：先把明显不合法的请求挡掉（没身份、没文件名、空内容、超过 20 MiB），避免白跑一次上传。
        validateUpload(command);
// 租户为空串时统一落成 null，这样才能和 SQL 里「都为空或相等」的匹配写法对上，否则查不到数据。
        String tenantId = blankToNull(command.getTenantId());
    // 只有上传时声明了会话才需要复核；不声明则是游离附件，等绑定消息时再校验会话。
        if (!isBlank(command.getSessionId())) {
   // 会话不属于当前用户就直接抛异常终止上传，防止把附件预挂进别人的会话。
            sessionDomain.assertSessionAccess(tenantId, command.getOwnerUserId(), command.getSessionId(), null);
        }
      // 第二层：先算内容指纹，它既是去重依据，也会作为对象键的一部分保证不同内容不撞键。
        String hash = sha256(command.getBytes());
   // 查这个用户之前是否上传过一模一样的内容；只在同一用户范围内找，避免跨用户探测别人上传过什么。
        AssetEntity reusable = repository.queryReusableByHash(tenantId, command.getOwnerUserId(), hash);
  // 记住这次到底有没有真的往对象存储写东西；只有真写了，后面失败时才需要补偿删除。
        boolean newlyStored = reusable == null;
        // 新内容放进配置的附件桶；复用时沿用旧记录的桶，避免同一份内容在不同桶里出现两份。
        String bucket = newlyStored ? storageService.assetBucket() : reusable.getBucket();
        // 新内容按「租户/用户/哈希」生成安全对象键；复用时沿用旧键，从而共享同一份物理文件。
        String objectKey = newlyStored ? objectKey(tenantId, command.getOwnerUserId(), hash, command.getFileName()) : reusable.getObjectKey();
 // 第三层：只有确实是新内容才发起真正的上传。
        if (newlyStored) {
   // 把字节写进对象存储；MIME 缺失时兜底成通用二进制类型，避免浏览器把未知文件内联打开。
            ObjectStorageResultEntity stored = storageService.putObject(ObjectStorageCommandEntity.builder()
                    .bucket(bucket).objectKey(objectKey).bytes(command.getBytes())
                    .contentType(defaultMime(command.getMimeType())).build());
    // 改用存储端重新算出的哈希落库，作为「文件真的写成了这个内容」的权威凭据。
            hash = stored.getSha256();
        }
 // 第四层：新内容才需要真解析；复用时直接沿用旧记录的解析结论，省掉一次可能很慢的 PDF/Word 解析。
        AssetParseResultEntity parsed = reusable == null
                ? textExtractor.extract(command.getFileName(), command.getMimeType(), command.getBytes())
                : AssetParseResultEntity.builder().parseStatus(reusable.getParseStatus())
                .extractedText(reusable.getExtractedText()).errorSummary(reusable.getParseError()).build();
      // 数据库写入可能失败，而文件此时可能已经躺在对象存储里，所以必须包起来做补偿。
        try {
            // 写入资产档案：可见范围固定 private（附件不对外公开）；messageId 刻意不填，
          // 表示它还是游离附件，只有之后绑定到消息才算真正被使用；status 置 active 表示可用。
            return repository.insert(AssetEntity.builder()
                    .tenantId(tenantId).ownerUserId(command.getOwnerUserId()).visibility("private")
                    .sessionId(blankToNull(command.getSessionId())).assetId("asset_" + UUID.randomUUID())
                    .assetKind("chat_attachment").assetType(assetType(command.getFileName(), command.getMimeType()))
                    .bucket(bucket).objectKey(objectKey).fileName(safeFileName(command.getFileName()))
                    .mimeType(defaultMime(command.getMimeType())).sizeBytes((long) command.getBytes().length)
                    .sha256(hash).status("active").parseStatus(parsed.getParseStatus())
                    .extractedText(parsed.getExtractedText()).parseError(parsed.getErrorSummary()).build());
        } catch (RuntimeException e) {
            // 第五层：档案没写成，说明这个文件没人引用，是个孤儿，需要回收。
            if (newlyStored) {
                // 复用场景绝不能删——那份对象还被之前的档案引用着，删了会让历史附件全部失效。
                try {
           // 尽力删掉刚上传的对象，把桶恢复到上传前的状态。
                    storageService.deleteObject(bucket, objectKey);
                } catch (RuntimeException ignored) {
            // 数据库失败优先返回原始异常，孤儿对象后续可由清理任务回收。
                }
            }
    // 把数据库的原始异常原样抛出，保留真正的失败原因，不被补偿删除的次要错误掩盖。
            throw e;
        }
    }

    /**
     * 分页列出当前用户的附件，供前端「我的附件」和「本会话附件」面板使用。
     *
     * <p>关键输入是可信身份；cursor 是上一页最后一条的自增主键，limit 是本页条数，
     * sessionId 和 assetKind 是可选过滤条件。</p>
     *
     * <p>只读，不写库不改状态。缺少可信用户身份或指定了不属于自己的会话都会抛异常。</p>
     */
    public List<AssetEntity> queryAssets(String tenantId, String ownerUserId, Long cursor, Integer limit,
                                         String sessionId, String assetKind) {
        // 没有可信用户身份就无从隔离数据，直接拒绝，绝不允许「不带用户查全表」。
        requireIdentity(ownerUserId);
      // 控制器多取一条判断下一页，因此领域层允许 101 条的内部窗口。
        int safeLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 101));
        // 只要指定了会话，就必须先确认这个会话属于自己，否则等于允许按会话号窥探别人的附件列表。
        if (!isBlank(sessionId)) {
     // 不通过直接抛异常，方法就此结束，不会返回任何数据。
            sessionDomain.assertSessionAccess(blankToNull(tenantId), ownerUserId, sessionId, null);
        }
      // 查库；空串统一转 null 才能命中 SQL 的可选条件判断，否则会变成「等于空串」而查不到数据。
        return repository.queryOwnedList(blankToNull(tenantId), ownerUserId, cursor, safeLimit,
                blankToNull(sessionId), isBlank(assetKind) ? null : assetKind);
    }

  /**
   * 下载一个附件的原始内容。
   *
   * <p>先按可信身份把档案查出来（顺带完成归属校验），再用档案里记录的桶和对象键去读文件。
   * 注意不是拿前端传来的路径去读，这样即使有人猜到对象键也无法直接下载。</p>
   *
   * <p>读取上限设为 20 MiB，与上传上限一致，防止有人绕过上传通道塞入超大对象后触发读取把内存打满。</p>
   *
   * <p>不写库不改状态。附件不存在、不属于自己、已软删除都会抛「资产不存在或无权访问」。</p>
   */
    public byte[] download(String tenantId, String ownerUserId, String assetId) {
        // 先做归属校验并拿到档案；查不到就在这里抛异常，后面的读取根本不会发生。
        AssetEntity asset = requireOwned(tenantId, ownerUserId, assetId);
        // 用档案里落库的桶和对象键去读文件，位置完全由服务端决定，前端无法左右。
        return storageService.getObject(asset.getBucket(), asset.getObjectKey(), MAX_FILE_BYTES);
    }

    /**
     * 按可信身份取出一个必须属于当前用户且仍然可用的附件档案，是所有单条操作前的统一闸门。
     *
     * <p>下载和删除都先走它。它把「不存在」「不属于你」「已删除」三种情况合并成同一个错误
     * ASSET_NOT_FOUND，刻意不区分——否则攻击者可以通过错误码差异探测某个 assetId 是否真的存在。</p>
     *
     * <p>只读，不写库。校验不通过一律抛 AppException，绝不返回 null，
     * 所以调用方拿到返回值就可以放心使用。</p>
     */
    public AssetEntity requireOwned(String tenantId, String ownerUserId, String assetId) {
        // 没有可信身份就没有隔离依据，直接拒绝。
        requireIdentity(ownerUserId);
        // 查询本身带租户和拥有者条件，别人的附件在 SQL 层就查不出来。
        AssetEntity asset = repository.queryOwned(blankToNull(tenantId), ownerUserId, assetId);
        // 查不到，或者状态已经不是 active（例如刚被并发删除），都视为不可访问。
        if (asset == null || !"active".equals(asset.getStatus())) {
     // 统一错误信息，不透露到底是「不存在」还是「不属于你」。
            throw new AppException("ASSET_NOT_FOUND", "资产不存在或无权访问");
        }
        // 校验通过，返回可以放心使用的档案。
        return asset;
    }

    /**
     * 软删除一个附件：状态改成 deleted，档案和对象存储里的文件都保留。
     *
     * <p>为什么不物理删：附件可能已经绑定在历史消息上，真删会让历史对话出现引用不到的空洞。
     * 软删之后上下文组装的 SQL 会自动过滤它，效果是这个附件从后续对话里静默消失，
     * 但历史消息本身不受影响，也随时可以恢复。因此「正在被消息引用」并不阻止删除。</p>
     *
     * <p>会写数据库（一条条件 UPDATE），不删对象存储里的文件，不发事件。
     * 影响行数不等于 1 说明记录已被并发改动，抛冲突让用户刷新重试，而不是假装成功。</p>
     */
    public void delete(String tenantId, String ownerUserId, String assetId) {
        // 先做归属校验；不是自己的附件在这一步就被拒绝，UPDATE 根本不会执行。
        requireOwned(tenantId, ownerUserId, assetId);
        // 条件 UPDATE 只会命中「属于本人且当前 active」的行，返回行数是并发下唯一可靠的成功判据。
        if (repository.softDelete(blankToNull(tenantId), ownerUserId, assetId) != 1) {
   // 没改到行说明另一个请求刚删过或状态已变，如实告知冲突而不是静默返回成功。
            throw new AppException("ASSET_DELETE_CONFLICT", "资产删除冲突，请刷新后重试");
        }
    }

    /**
     * 把用户这次选中的附件一次性绑定到刚保存的那条用户消息上，附件从此才真正参与对话。
     *
     * <p>各层职责：
     * 第一层：清洗并去重附件编号；一个都不剩就直接返回，这轮就是不带附件的普通消息。
     * 第二层：数量上限校验，防止一次带太多附件把模型上下文预算吃光。
     * 第三层：复核会话归属，确保不是往别人的会话里塞附件。
     * 第四层：一条带严格条件的批量 UPDATE 完成绑定，并用影响行数判断是否全部成功；
     *       不全成功就抛异常，由事务整体回滚，绝不留下「一半生效」的状态。</p>
     *
     * <p>数据流：
     * 附件编号列表
     * → 去空去重（保留首次出现顺序）
     * → 空则直接返回
     * → 数量上限校验
     * → 会话归属复核
     * → 批量条件 UPDATE 绑定会话与消息
     * → 比对影响行数与请求个数
     * → 全部成功则提交 / 任一失败则抛异常回滚</p>
     *
     * <p>标注了事务：绑定必须与调用方保存消息的动作同生共死。否则可能出现消息存了但附件没绑上，
     * 用户看到自己发了附件而模型完全看不到它，问题极难排查。</p>
     *
     * <p>绑定 SQL 的条件要求附件属于本人、状态 active、解析结果为 ready、之前没绑过任何消息、
     * 且原本没有会话或就是本会话。因此别人的附件、还没解析完的附件、已删除的附件、
     * 以及已经用在其他消息里的附件都绑不上，这是防止附件被重复引用和越权引用的核心。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void bindToMessage(String tenantId, String ownerUserId, String sessionId, String messageId,
                              List<String> attachmentIds) {
        // 第一层：先把 null、空串和重复项清掉，得到一份干净的待绑定编号列表。
        List<String> ids = normalizeIds(attachmentIds);
   // 清洗后一个都不剩，说明这条消息本来就没带附件，直接返回，不做任何写入。
        if (ids.isEmpty()) {
            // 提前返回，避免执行一条没有意义的 UPDATE。
            return;
        }
    // 第二层：超过单条消息的附件上限就整体拒绝；每个附件都要占上下文预算，放开会挤掉对话历史。
        if (ids.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
            // 直接抛异常终止，本次消息的附件一个都不会生效。
            throw new AppException("ASSET_ATTACHMENT_LIMIT", "单次最多引用 10 个附件");
        }
  // 第三层：复核会话归属，防止拿到别人的 sessionId 就往那个会话里塞附件。
        sessionDomain.assertSessionAccess(blankToNull(tenantId), ownerUserId, sessionId, null);
        // 第四层：一条批量 UPDATE 完成绑定；所有安全条件都写在 SQL 的 WHERE 里，由数据库原子判定。
        int updated = repository.bindReadyAssets(blankToNull(tenantId), ownerUserId, sessionId, messageId, ids);
        // 只要有任何一个附件没绑上（不属于自己、未解析完、已被别的消息占用、已删除），就整体失败。
        if (updated != ids.size()) {
   // 抛异常触发事务回滚，把已经绑上的那几个也一并撤销，避免出现半成功状态；
            // 错误文案列出所有可能原因，因为服务端刻意不区分具体是哪一条，以免暴露别人附件的存在。
            throw new AppException("ASSET_BIND_DENIED",
                    "附件不可发送：可能已用于其他消息、尚未解析完成、已删除或不属于当前会话，请重新选择或重新上传");
        }
    }

    /**
     * 把前端传来的附件编号列表清洗成一份干净、去重、保持原顺序的列表。
     *
     * <p>为什么要去重：前端重复提交同一个附件时，如果原样传给绑定 SQL，
     * 请求个数会大于实际能更新的行数，导致合法请求被误判成失败。</p>
     *
     * <p>为什么保留首次出现的顺序：用 LinkedHashSet 而不是 HashSet，让附件在提示词里的顺序
     * 与用户选择顺序一致，便于用户理解模型引用的是哪一个。</p>
     *
     * <p>数据流：原始列表 → 过滤 null 与空白 → 去掉首尾空格 → 按首次出现顺序去重 → 不可变列表。</p>
     */
    private List<String> normalizeIds(List<String> ids) {
        // 完全没传附件是最常见的情况，直接给一个不可变空列表，调用方据此跳过绑定。
        if (ids == null || ids.isEmpty()) {
      // 返回空列表而不是 null，免得调用方还要判空。
            return List.of();
        }
// 流式清洗：先剔除空值，再去掉首尾空格，最后借 LinkedHashSet 按首次出现顺序去重并转成不可变列表。
        return ids.stream().filter(id -> id != null && !id.isBlank()).map(String::trim)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
    }

    /**
     * 上传前的硬性校验：必须有可信用户、有文件名、有非空内容，且不超过 20 MiB。
     *
     * <p>放在最前面是为了「快速失败」——这些条件不满足时，任何一次对象存储写入和解析都是浪费。
     * 大小上限用真实字节长度判断，不采信前端声明的大小。</p>
     */
    private void validateUpload(AssetUploadCommandEntity command) {
  // 缺任何一项必要信息都无法生成一条合法档案，统一报参数不完整。
        if (command == null || isBlank(command.getOwnerUserId()) || command.getBytes() == null
                || command.getBytes().length == 0 || isBlank(command.getFileName())) {
 // 直接终止上传，不写对象存储也不写库。
            throw new AppException("ASSET_UPLOAD_INVALID", "附件参数不完整");
        }
        // 超限单独给一个明确错误码，让前端能提示「文件太大」而不是笼统的参数错误。
        if (command.getBytes().length > MAX_FILE_BYTES) {
            // 在上传发生之前拦下，避免大文件白占带宽和内存。
            throw new AppException("ASSET_FILE_TOO_LARGE", "单个附件不能超过 20 MiB");
        }
    }

    /**
     * 确认调用方带来了可信的用户身份，这是所有资产操作的前置条件。
     *
     * <p>身份必须由上层从认证上下文取出后传进来。一旦这里为空，说明认证环节出了问题
     * （例如令牌解析失败却继续往下走），此时绝不能放行，否则查询会失去隔离条件，可能读到全表数据。</p>
     */
    private void requireIdentity(String ownerUserId) {
        // 空身份意味着无法隔离数据，宁可报错也不放行。
        if (isBlank(ownerUserId)) {
  // 用专门的错误码，便于排查时一眼看出是认证上下文缺失而不是业务参数问题。
            throw new AppException("AUTH_CONTEXT_MISSING", "缺少可信用户身份");
        }
    }

    /**
     * 把浏览器传来的原始文件名洗成一个只用于展示的安全名字。
     *
     * <p>做三件事：把 NUL 和反斜杠替换掉、只保留最后一段（丢掉所有目录部分）、超长时截尾部。
     * 前两件是安全动作——原始文件名里可能带 {@code ../../} 或 {@code C:\}，
     * 一旦被拼进路径或日志就可能造成目录穿越或日志注入。</p>
     *
     * <p>超长时保留尾部而不是开头，是为了不切掉扩展名，让前端仍能正确显示类型。
     * 清洗后为空则兜底成 attachment，保证界面上不会出现一个没有名字的附件。</p>
     */
    private String safeFileName(String fileName) {
        // 先把 NUL 换成下划线（它会截断 C 层字符串），再把反斜杠统一成正斜杠便于后面一次性取末段。
        String normalized = fileName.replace('\0', '_').replace('\\', '/');
  // 找最后一个路径分隔符的位置，它之后才是真正的文件名。
        int slash = normalized.lastIndexOf('/');
        // 丢掉所有目录部分只留末段，顺手去掉首尾空格。
        String value = (slash < 0 ? normalized : normalized.substring(slash + 1)).trim();
        // 清洗后什么都不剩（例如原名就是 "/"），给一个通用名字，避免出现空白附件名。
        if (value.isBlank()) value = "attachment";
   // 超长则保留尾部 255 个字符，这样扩展名一定还在，前端图标和下载后缀都不会错。
        return value.length() <= MAX_FILE_NAME_LENGTH ? value : value.substring(value.length() - MAX_FILE_NAME_LENGTH);
    }

    /**
     * 生成这份文件在对象存储里的完整路径（对象键）。
     *
     * <p>规则是 {@code assets/租户段/用户段/哈希前两位/完整哈希.扩展名}，每一段都有明确目的：
     * 租户段和用户段做数据分区，方便按租户统计和清理；哈希前两位再分一层目录，
     * 避免单个目录下堆积几十万个文件影响列举性能；用完整哈希作文件名保证内容相同就复用、
     * 内容不同绝不撞键，因此同名文件不会互相覆盖。</p>
     *
     * <p>租户和用户都被替换成只含字母数字下划线短横线，这样即使它们含有 {@code ../} 或斜杠，
     * 也不可能穿越到别的目录去。扩展名同样经过白名单清洗后才拼接。</p>
     *
     * <p>纯计算，不查库不写库。</p>
     */
    private String objectKey(String tenantId, String userId, String hash, String fileName) {
        // 个人模式没有租户，用固定的 personal 段占位，保证路径层级始终一致；有租户则清洗掉所有特殊字符。
        String tenantSegment = isBlank(tenantId) ? "personal" : tenantId.replaceAll("[^a-zA-Z0-9_-]", "_");
        // 用户段同样只保留安全字符，防止用户编号里的特殊字符影响路径结构。
        String userSegment = userId.replaceAll("[^a-zA-Z0-9_-]", "_");
        // 取一个只含小写字母数字的扩展名，纯粹为了下载时文件名好看，不参与任何安全判断。
        String extension = extension(fileName);
     // 拼成最终对象键：哈希前两位作为二级目录分散文件，完整哈希作为文件名保证唯一且可去重。
        return "assets/" + tenantSegment + "/" + userSegment + "/" + hash.substring(0, 2) + "/" + hash
                + (extension.isEmpty() ? "" : "." + extension);
    }

    /**
     * 推断一个供前端显示图标用的粗类型标签：image、pdf、word、text 或 file。
     *
     * <p>MIME 和扩展名双重判断，因为浏览器给的 MIME 经常缺失或写成通用二进制。
     * 这个值只影响界面显示，不参与权限或解析决策，所以判错了也不会有安全影响。</p>
     */
    private String assetType(String fileName, String mimeType) {
        // 统一转小写再比较，避免 "Application/PDF" 这类大小写差异导致判断失败。
        String mime = defaultMime(mimeType).toLowerCase(Locale.ROOT);
      // 图片类只看 MIME 前缀就够了，各种图片格式都归成 image。
        if (mime.startsWith("image/")) return "image";
// PDF 有确切的 MIME，优先精确匹配。
        if (mime.equals("application/pdf")) return "pdf";
      // Word 的 MIME 又长又多变，所以 MIME 含 word 或扩展名是 docx 都算。
        if (mime.contains("word") || extension(fileName).equals("docx")) return "word";
  // 纯文本类：MIME 以 text/ 开头，或是 Markdown 这种常被识别成通用二进制的格式。
        if (mime.startsWith("text/") || extension(fileName).equals("md")) return "text";
        // 都对不上就归为通用文件，前端显示一个默认图标。
        return "file";
    }

    /**
     * 从文件名里取出一个只含小写字母和数字的扩展名。
     *
     * <p>先复用文件名清洗逻辑去掉路径，再取最后一个点之后的部分，最后把所有非字母数字字符剔除。
     * 这一步清洗是必要的：扩展名会被拼进对象键，如果放行斜杠或点，就可能改变存储路径结构。</p>
     *
     * <p>没有扩展名、或点在最后一位（例如 "a."）都返回空串，调用方据此不拼后缀。</p>
     */
    private String extension(String fileName) {
        // 先走一遍文件名清洗，确保这里处理的已经是不含目录的安全末段。
        String safe = safeFileName(fileName);
        // 找最后一个点，它之后才是扩展名。
        int dot = safe.lastIndexOf('.');
        // 没有点、或点就在末尾（没有后缀内容）都视为没有扩展名；否则转小写并剔除所有非字母数字字符。
        return dot < 0 || dot == safe.length() - 1 ? "" : safe.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

/**
 * MIME 类型缺失时兜底成通用二进制类型。
 *
 * <p>兜底成 {@code application/octet-stream} 而不是留空，是为了让浏览器把未知文件按「下载」处理
 * 而不是内联展示。若留空，某些浏览器会自行猜测类型，把 HTML 或脚本文件直接渲染执行。</p>
 */
    private String defaultMime(String mimeType) {
  // 空值统一兜底，非空则去掉首尾空格后使用。
        return isBlank(mimeType) ? "application/octet-stream" : mimeType.trim();
    }

    /**
     * 计算文件内容的 SHA-256 十六进制摘要，作为同一用户内容去重的稳定依据。
     *
     * <p>用摘要而不是文件名判断「是不是同一份内容」：文件名随时可改，内容指纹不会。
     * 摘要相同就复用已有对象，省掉一次上传和一次解析。</p>
     *
     * <p>SHA-256 是 JVM 必备算法，理论上不会缺失；这里仍然兜住异常并翻译成业务错误码，
     * 避免把底层的 NoSuchAlgorithmException 直接抛给上层。</p>
     */
    private String sha256(byte[] bytes) {
        // 摘要计算本身不该失败，但环境异常时也要给出可识别的业务错误。
        try {
   // 一次性算出摘要并转成小写十六进制字符串，格式与对象存储端保持一致便于比对。
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            // 翻译成业务异常，上层按统一错误码返回，不暴露底层算法细节。
            throw new AppException("ASSET_HASH_FAILED", "附件摘要计算失败");
        }
    }

    /**
     * 把可选字段里的空串统一变成 null。
     *
     * <p>这一步直接影响查询正确性：SQL 里判断租户和会话用的是「都为空或相等」的写法，
     * 空串不等于 NULL，一旦把空串传下去，个人模式的数据就一条都匹配不到。</p>
     */
    private String blankToNull(String value) {
        // 空白视为没填，返回 null 交给 SQL 走「为空」分支。
        return isBlank(value) ? null : value;
    }

    /**
     * 判断字符串是不是没填（null 或纯空白）。
     *
     * <p>为什么不只判 null：来自 HTTP 请求的字段大量是空串，只判 null 会让空串一路穿到 SQL 和路径拼接里，
     * 造成查不到数据或生成畸形对象键。</p>
     */
    private boolean isBlank(String value) {
    // null 或去掉空白后什么都不剩，都算缺失。
        return value == null || value.isBlank();
    }
}
