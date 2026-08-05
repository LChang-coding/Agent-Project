package cn.bugstack.ai.domain.storage.service;

import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageDownloadCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageDownloadResultEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageFileCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageResultEntity;

/**
 * 领域层对「文件存到哪、怎么取回来」的唯一抽象出口，屏蔽底层到底用的是 MinIO 还是本地磁盘目录。
 *
 * <p>所属层次：领域层（domain）的适配器出口。领域服务只认这个接口，
 * 真正的实现是基础设施层的 {@code MinioObjectStorageService}，由 Spring 在启动时注入。
 * 该实现支持降级：配置里 type 不是 minio 时会把文件写到本地目录，方便本地开发不依赖中间件。</p>
 *
 * <p>谁会调用它：{@code asset.service.AssetService}（聊天附件）、
 * {@code rag.service.RagDocumentUploadService}（知识库原始文档）、
 * {@code tool.service.ToolPublishService} 与 {@code ToolGateway}（Skill 工具包）、
 * {@code share.service.SessionShareService}（会话导出文件）。</p>
 *
 * <p>文件存到哪：由「桶名 + 对象键」两级定位。桶名从下面三个 xxxBucket() 方法取，按业务分开存放；
 * 对象键由各业务领域服务自己生成，例如附件用 {@code assets/租户/用户/哈希前两位/完整哈希.扩展名}，
 * 租户段和用户段都被清洗成只含字母数字下划线短横线。这样既不会因为文件名里带 {@code ../} 造成目录遍历，
 * 也不会因为两个用户上传同名文件而互相覆盖——键里带内容哈希，内容不同键就不同。</p>
 *
 * <p>它不负责什么：不判断调用方是否有权访问这个对象（权限一律由上层领域服务先校验完），
 * 不记录数据库里的资产元数据，不做引用计数，也不会自动清理孤儿对象。
 * 因此「先写对象存储、再写数据库」的流程中，如果数据库写失败，
 * 调用方必须自己调 deleteObject 做补偿删除，否则桶里会留下无人引用的孤儿文件。</p>
 */
public interface ObjectStorageService {

    /**
     * 把内存里的一整份字节内容写成一个对象，适合小文件（导出的 Markdown、聊天附件等）。
     *
     * <p>输入是桶名、对象键、字节内容和 MIME 类型。同键写入是覆盖语义，
     * 所以要靠对象键本身（通常带内容哈希）来避免覆盖掉别人的文件。</p>
     *
     * <p>返回写入结果，其中的 SHA-256 是实现端重新算出来的，调用方通常直接采信它作为去重依据。</p>
     *
     * <p>失败抛 AppException（错误码 OBJECT_STORAGE_UPLOAD_FAILED）。
     * 此时对象可能根本没写成，调用方不能把它当成已存在。</p>
     */
    ObjectStorageResultEntity putObject(ObjectStorageCommandEntity command);

    /**
     * 从一个已经落盘的本地暂存文件流式写成对象，用于大文件，避免把整份内容读进 JVM 堆把内存打满。
     *
     * <p>调用方必须提前告知文件的准确长度。实现端在上传过程中核对长度，
     * 一旦对不上就报 OBJECT_STORAGE_SIZE_MISMATCH，用来防止文件在上传中途被别的线程替换掉。</p>
     *
     * <p>返回的摘要是边读边算出来的，因此不需要为了算哈希再多读一遍文件。</p>
     */
    ObjectStorageResultEntity putFile(ObjectStorageFileCommandEntity command);

    /**
     * 按桶名和对象键把整个对象读进内存，使用实现端的默认上限 64 MiB。
     *
     * <p>适合确定文件不大的场景。对象不存在或超过上限都会抛 AppException 而不是返回 null，
     * 所以调用方拿到返回值就一定是完整内容。</p>
     */
    byte[] getObject(String bucket, String objectKey);

    /**
     * 按调用方指定的字节上限读取对象，适合上限比默认值更严格的业务（例如聊天附件限 20 MiB）。
     *
     * <p>上限校验发生在分配字节数组之前，目的是防止一个超大对象直接把服务内存吃光。
     * 超限时抛 OBJECT_STORAGE_TOO_LARGE，读不到任何内容。</p>
     */
    byte[] getObject(String bucket, String objectKey, long maxBytes);

    /**
     * 把对象边下载边写进本地磁盘的受控目录，不在内存里整体缓冲，用于 Skill 包这类较大的文件。
     *
     * <p>「受控」的含义：命令里给的是一个根目录加一段相对路径，实现端会规范化后确认最终路径仍在根目录之内，
     * 并逐级拒绝符号链接，从而防止恶意对象键把文件写到根目录外面去。</p>
     *
     * <p>写入采用「先写临时文件、成功后原子改名」的方式，所以读方不会看到半截文件；
     * 中途失败会删掉临时文件，不留垃圾。超过 maxBytes 会中止并抛 OBJECT_STORAGE_TOO_LARGE。</p>
     */
    ObjectStorageDownloadResultEntity downloadToFile(ObjectStorageDownloadCommandEntity command);

    /**
     * 删除一个对象，是业务侧做补偿清理（数据库写失败后回收刚上传的文件）和真删除时的入口。
     *
     * <p>删除不存在的对象不算失败，因此可以安全地重复调用；只有底层真的报错才抛 OBJECT_STORAGE_DELETE_FAILED。</p>
     *
     * <p>注意它不检查这个对象是否还被数据库里的资产记录引用，防止误删的责任完全在调用方。</p>
     */
    void deleteObject(String bucket, String objectKey);

    /**
     * 探测对象是否还在，主要用于删除流程的收尾验证——确认清理动作真的生效了。
     *
     * <p>只有底层明确回答「键或桶不存在」才返回 false；其他服务端错误会抛 OBJECT_STORAGE_STAT_FAILED
     * 而不是悄悄返回 false，避免把「查不动」误判成「已删除」而漏掉真正的残留文件。</p>
     */
    boolean objectExists(String bucket, String objectKey);

    /**
     * 返回存放 Skill 工具包的桶名，取自配置文件。
     *
     * <p>工具发布和运行时拉取工具包都用这个桶，与用户上传的附件分开放，
     * 便于单独配置生命周期策略和访问权限。</p>
     */
    String skillBucket();

    /**
     * 返回存放聊天附件（用户上传的资产）的桶名，取自配置文件。
     *
     * <p>不同租户共用这一个桶，租户隔离靠对象键前缀完成；真正的权限校验在资产领域服务里
     * 按 tenantId + ownerUserId 查库判断，绝不能因为知道对象键就允许下载。</p>
     */
    String assetBucket();

    /**
     * 返回存放知识库原始文档的桶名，取自配置文件。
     *
     * <p>RAG 的解析、切块、向量化都要回头读原始文件，所以原文必须长期保留在这个桶里，
     * 不能在解析完成后删除，否则文档重新解析和引用溯源就都没有依据了。</p>
     */
    String ragBucket();
}
