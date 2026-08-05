package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.asset.AssetPageResponseDTO;
import cn.bugstack.ai.api.dto.asset.AssetResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.asset.model.AssetEntity;
import cn.bugstack.ai.domain.asset.model.AssetUploadCommandEntity;
import cn.bugstack.ai.domain.asset.service.AssetService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 用户文件（聊天附件、资产中心文件）的上传、查询、下载、删除四个 HTTP 入口。
 *
 * <p>解决什么问题：用户要在对话里带图片和文档，就得先把文件传到服务端并拿到一个可引用的 assetId；
 * 之后还要能翻列表、下载原文件、删掉不想要的。这个控制器就是这些文件操作的唯一对外入口。</p>
 *
 * <p>所属层次：触发器层（trigger），系统最外层，直接面向前端。</p>
 *
 * <p>谁会调用它：Web 前端的附件上传控件和资产管理页面，通过 /api/v1/assets 下的 HTTP 接口调用。</p>
 *
 * <p>它向下调用什么：只调 {@code AssetService}——由它做归属校验、写对象存储、落库资产记录、
 * 触发文档解析、执行软删除。</p>
 *
 * <p>它不负责什么：不校验文件类型和大小上限、不算哈希、不碰对象存储、不做文档解析、不判断资产归谁。
 * 这里只做三件事：从认证上下文取可信身份、把 multipart 或查询参数翻成领域命令、把领域实体裁剪成对外 DTO
 * 并保证响应里不出现存储桶名和对象键。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    /**
     * 资产领域服务，本控制器唯一的下游依赖。
     *
     * <p>上传、翻页、下载、删除全部走它。租户隔离和「这个文件是不是你的」这类归属判断都在它内部完成，
     * 所以控制器只要把可信的 tenantId 和 userId 传进去，就不可能读到别人的文件。
     * final 且构造注入，运行期不变，并发请求共享同一实例是安全的。</p>
     */
    private final AssetService assetService;

    /**
     * 启动时由 Spring 注入资产领域服务，注入后依赖不再变化。
     *
     * @param assetService 资产上传、查询、下载和删除领域服务
     */
    public AssetController(AssetService assetService) {
        // 保存领域服务引用；这是本类唯一的可变初始化动作，之后所有请求都复用它。
        this.assetService = assetService;
    }

    /**
     * 上传一个聊天附件，登记成资产并返回可在后续消息里引用的 assetId。
     *
     * <p>各层职责：
     * 第一层：把 multipart 文件读成字节，并强制用认证上下文里的用户当所有者，杜绝伪造归属。
     * 第二层：交给领域层写对象存储、落库资产记录、按类型触发解析。
     * 第三层：把领域实体裁剪成对外元数据，抹掉存储桶和对象键。
     * 第四层：把两类异常分开处理，业务异常原样透传，技术异常收敛成系统错误码。</p>
     *
     * <p>数据流：
     * multipart 请求
     * → 读取文件字节与客户端元数据
     * → 拼装上传命令（租户、可信用户、可选会话）
     * → 领域层写对象存储并落库资产
     * → 裁剪成对外 DTO
     * → 返回 assetId 给前端引用</p>
     *
     * <p>会写对象存储、会写数据库。主要失败情形：认证上下文缺失、文件超出领域层限制、会话不属于当前用户、
     * 对象存储不可用。前三类是业务异常按原码返回，最后一类收敛成系统错误码，细节只留在日志里。</p>
     *
     * @param file 附件内容和客户端文件元数据
     * @param sessionId 可选目标会话；提供时由领域层校验归属
     * @return 可在后续消息中引用的资产元数据
     */
    @PostMapping(value = "/chat-attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<AssetResponseDTO> upload(@RequestPart("file") MultipartFile file,
                                             @RequestParam(value = "sessionId", required = false) String sessionId) {
        // 读文件字节、写对象存储都可能抛异常，整段包住，保证前端拿到的永远是可解析的响应而不是异常堆栈。
        try {
            // 所有者强制使用当前 JWT 用户，防止请求体伪造资产归属。
            AssetEntity asset = assetService.uploadChatAttachment(AssetUploadCommandEntity.builder()
                    .tenantId(TenantContextHolder.getTenantId()).ownerUserId(requireUserId()).sessionId(sessionId)
                    .fileName(file.getOriginalFilename()).mimeType(file.getContentType()).bytes(file.getBytes()).build());
            // 上传成功，把裁剪后的资产元数据返回；前端拿到 assetId 才能在下一条消息里引用这个附件。
            return success(toResponse(asset));
        } catch (AppException e) {
            // 领域层明确拒绝（身份缺失、文件不合规、会话越权），错误码和文案都是设计好的，可直接展示。
            return failure(e.getCode(), e.getInfo());
        } catch (Exception e) {
            // 不在响应中暴露文件系统或对象存储异常，日志保留用户和会话定位字段。
            log.error("聊天附件上传失败 userId:{} sessionId:{}", TenantContextHolder.getUserId(), sessionId, e);
            // 对外统一成系统错误码；此时文件可能已写进对象存储但没落库，需要靠清理策略回收，不在这里处理。
            return failure(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo());
        }
    }

    /**
     * 按游标翻页查询当前用户的资产列表。
     *
     * <p>为什么用游标而不是页码：资产会不断新增，用 offset 翻页会出现「翻到第二页又看到第一页那条」的错位；
     * 游标以上一页最后一条的数据库自增 id 为界，翻页结果稳定。</p>
     *
     * <p>数据流：
     * 查询参数
     * → 收敛页大小到 1~100
     * → 按 pageSize+1 多取一条
     * → 用多出来的那条判断还有没有下一页
     * → 截掉多余那条
     * → 取末条 id 当下一页游标
     * → 裁剪成对外 DTO 列表返回</p>
     *
     * <p>不写库、不改状态。身份缺失会在读数据前直接拒绝，因此不存在越权翻到别人资产的可能。</p>
     *
     * @param cursor 上一页末尾数据库游标
     * @param limit 页大小，服务端限制为 1 到 100
     * @param sessionId 可选会话过滤
     * @param kind 可选资产类型过滤
     * @return 当前页、下一游标和是否有更多数据
     */
    @GetMapping
    public Response<AssetPageResponseDTO> list(@RequestParam(value = "cursor", required = false) Long cursor,
                                               @RequestParam(value = "limit", required = false) Integer limit,
                                               @RequestParam(value = "sessionId", required = false) String sessionId,
                                               @RequestParam(value = "kind", required = false) String kind) {
        // 身份缺失和非法过滤条件都会抛业务异常，统一接住转成错误码。
        try {
            // 多取一条判断 hasMore，避免执行额外 count 查询。
            int pageSize = limit == null ? 50 : Math.max(1, Math.min(limit, 100));
            // 用可信身份加过滤条件取数据；这里故意多要一条，用来判断是否还有下一页。
            List<AssetEntity> rows = assetService.queryAssets(TenantContextHolder.getTenantId(), requireUserId(),
                    cursor, pageSize + 1, sessionId, kind);
            // 真的多取到了，说明后面还有数据，前端应该继续显示「加载更多」。
            boolean hasMore = rows.size() > pageSize;
            // 把多取的那一条切掉，返回给前端的必须正好是一页的量。
            List<AssetEntity> pageRows = hasMore ? rows.subList(0, pageSize) : rows;
            // 下一页游标取本页最后一条的数据库 id；没有下一页或本页为空时不给游标，前端据此停止翻页。
            String nextCursor = hasMore && !pageRows.isEmpty()
                    ? String.valueOf(pageRows.get(pageRows.size() - 1).getId()) : null;
            // 组装分页响应：本页数据裁剪成对外 DTO，再带上游标和是否有更多。
            return success(AssetPageResponseDTO.builder().items(pageRows.stream().map(this::toResponse).toList())
                    .nextCursor(nextCursor).hasMore(hasMore).build());
        } catch (AppException e) {
            // 身份或参数被领域层拒绝，原样返回业务错误码，不返回半截数据。
            return failure(e.getCode(), e.getInfo());
        }
    }

    /**
     * 下载一个属于当前用户的资产原文件。
     *
     * <p>返回的不是统一 Response 结构，而是原始字节流，因为浏览器要直接把它存成文件。</p>
     *
     * <p>数据流：
     * assetId
     * → 校验归属并取出元数据
     * → 读取对象存储里的字节
     * → 解析媒体类型（失败则退化为二进制）
     * → 拼 attachment 下载头并按 UTF-8 编码文件名
     * → 返回字节给浏览器</p>
     *
     * <p>不写库、不改状态。归属校验失败会直接抛业务异常，由全局异常处理返回错误，
     * 因此别人的 assetId 既下载不到也探测不出是否存在。</p>
     *
     * @param assetId 资产ID
     * @return 带安全文件名、媒体类型和长度的文件响应
     */
    @GetMapping("/{assetId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String assetId) {
        // 先校验资产归属，再读取对象内容，避免利用资产ID探测或下载他人文件。
        AssetEntity asset = assetService.requireOwned(TenantContextHolder.getTenantId(), requireUserId(), assetId);
        // 归属确认后才真正去对象存储取字节；顺序反了就等于给了别人一个探测文件是否存在的口子。
        byte[] bytes = assetService.download(TenantContextHolder.getTenantId(), requireUserId(), assetId);
        // 先声明响应的媒体类型，下面按库里记录的 MIME 解析，解析不了再兜底。
        MediaType mediaType;
        // 库里存的 MIME 可能是客户端上传时乱填的，解析失败不能让整个下载失败。
        try {
            // 按资产记录里的 MIME 解析出媒体类型，让浏览器知道该怎么处理这个文件。
            mediaType = MediaType.parseMediaType(asset.getMimeType());
        } catch (Exception ignored) {
            // 非法或缺失 MIME 类型使用二进制下载，不能让元数据错误阻断文件取回。
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        // 使用 attachment 强制下载，并按 UTF-8 编码原文件名。
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(asset.getFileName() == null ? assetId : asset.getFileName(), StandardCharsets.UTF_8).build();
        // 三件套一起返回：媒体类型、字节长度和下载头；浏览器据此弹出保存对话框而不是直接渲染。
        return ResponseEntity.ok().contentType(mediaType).contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString()).body(bytes);
    }

    /**
     * 软删除一个属于当前用户的资产。
     *
     * <p>只把资产标记为已删除，前端立刻看不到；对象存储里的文件由领域层的清理策略决定何时真正回收，
     * 这样误删还有挽回余地，也避免删除请求卡在对象存储上。</p>
     *
     * <p>会写数据库。归属不符或资产不存在时返回业务错误码，不会误删别人的文件。</p>
     */
    @DeleteMapping("/{assetId}")
    public Response<Void> delete(@PathVariable String assetId) {
        // 归属校验失败会抛业务异常，统一接住转成错误码返回。
        try {
            // 交给领域层把资产标记为已删除；真正的对象回收由后台清理策略负责。
            assetService.delete(TenantContextHolder.getTenantId(), requireUserId(), assetId);
            // 删除成功没有可返回的数据，只回一个成功码，前端据此把这条从列表里移除。
            return success(null);
        } catch (AppException e) {
            // 资产不存在或不属于当前用户，返回业务错误码，前端提示刷新列表。
            return failure(e.getCode(), e.getInfo());
        }
    }

    /**
     * 取出本次请求的可信用户身份，取不到就立刻拒绝。
     *
     * <p>为什么要单独抽出来：资产的每一个操作都以 userId 作为归属边界，一旦这里返回空值，
     * 下游查询条件就会失去用户维度，可能读到或删掉别人的文件。所以宁可提前失败，也不让空身份往下走。</p>
     */
    private String requireUserId() {
        // 只认认证上下文里的用户，请求参数里写谁都不作数。
        String userId = TenantContextHolder.getUserId();
        // 上下文里没有用户，说明请求没通过认证或认证信息被清空了。
        if (userId == null || userId.isBlank()) {
            // 立刻抛业务异常终止本次请求，绝不带着空身份去访问任何资产。
            throw new AppException("AUTH_CONTEXT_MISSING", "缺少可信用户身份");
        }
        // 返回可信用户编号，后续所有查询和写入都以它作为归属边界。
        return userId;
    }

    /**
     * 把资产领域实体转换成对外元数据。
     *
     * <p>这是一道边界：领域实体里带着存储桶名和对象键，一旦泄露出去别人就能绕过归属校验直接拉文件。
     * 这里只挑前端展示、下载和显示解析进度真正需要的字段。</p>
     *
     * <p>不查库、不改状态，纯结构转换；入参为空会抛空指针，调用方必须先确认有值。</p>
     */
    private AssetResponseDTO toResponse(AssetEntity asset) {
        // 逐字段搬运：身份与归属、文件本身的名字大小类型、以及状态和解析进度（供前端显示「解析中/解析失败」）。
        return AssetResponseDTO.builder().assetId(asset.getAssetId()).assetKind(asset.getAssetKind())
                .assetType(asset.getAssetType()).sessionId(asset.getSessionId()).messageId(asset.getMessageId())
                .fileName(asset.getFileName()).mimeType(asset.getMimeType()).sizeBytes(asset.getSizeBytes())
                .sha256(asset.getSha256()).status(asset.getStatus()).parseStatus(asset.getParseStatus())
                .parseError(asset.getParseError()).createTime(asset.getCreateTime()).build();
    }

    /** 用统一的成功码和文案包装数据，让所有接口的成功响应结构一致，前端只需写一套解析逻辑。 */
    private <T> Response<T> success(T data) {
        // 成功码 + 成功文案 + 业务数据，三段固定结构。
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                .data(data).build();
    }

    /** 按给定错误码和文案构造失败响应；调用方已经决定了对外说什么，这里不再夹带任何内部异常细节。 */
    private <T> Response<T> failure(String code, String info) {
        // 只回错误码和文案，不带 data，前端据此提示用户。
        return Response.<T>builder().code(code).info(info).build();
    }
}
