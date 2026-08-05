package cn.bugstack.ai.domain.asset.service;

import cn.bugstack.ai.domain.asset.adapter.IAssetRepository;
import cn.bugstack.ai.domain.asset.model.AssetEntity;
import cn.bugstack.ai.domain.context.adapter.port.ContextContributor;
import cn.bugstack.ai.domain.context.model.ContextAssembleRequest;
import cn.bugstack.ai.domain.context.model.ContextContribution;
import cn.bugstack.ai.domain.context.model.ContextFragmentType;
import cn.bugstack.ai.domain.context.model.ContextPolicyProperties;
import cn.bugstack.ai.domain.context.service.CharacterTokenCounter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把「用户这段对话里带过的附件正文」拼成一段 XML 文本，交给上下文组装器塞进提示词。
 *
 * <p>所属层次：领域层（domain）asset 子域的服务，同时实现 context 子域定义的
 * {@code ContextContributor} 端口，属于「上下文的一个供稿方」。</p>
 *
 * <p>谁会调用它：上下文组装流程（{@code ConversationMemoryService} 通过 {@code ContextAssembler}
 * 收集所有贡献方）。它和 RAG 贡献方是平级的，各自申报内容，最终由组装器按预算取舍。</p>
 *
 * <p>它向下调用什么：只调 {@code IAssetRepository#queryContextAssets} 读库，
 * 不读对象存储、不重新解析文件——文本是上传时就提取好落库的。</p>
 *
 * <p>为什么要裁剪：模型的上下文窗口是固定的，附件正文动辄几万字，如果全塞进去，
 * 真正重要的对话历史和用户当前问题就会被挤出窗口，模型反而答不好。
 * 所以这里按配置里的附件预算（attachmentTokens）严格限量：裁掉的是「较早消息带的附件」和
 * 「单个附件的尾部内容」，保留的是「最近消息带的附件」和「每份附件的开头部分」，
 * 因为越近的附件越可能是用户当前正在讨论的东西。</p>
 *
 * <p>它不负责什么：不判断权限（SQL 条件里已经限定租户、拥有者、会话和消息有效性）、
 * 不修改任何数据、不发事件、不决定最终是否真的注入（那是组装器按总预算决定的）。</p>
 */
@Service
public class AssetContextContributor implements ContextContributor {

    /**
     * 附件记录的读取出口。
     *
     * <p>只用它的 queryContextAssets 一个方法。那条 SQL 已经把「不属于本人」「未解析成功」
     * 「已删除」「绑定的消息已失效」的附件统统过滤掉了，所以这里拿到的都是可以放心注入的内容，
     * 不需要在 Java 里再判一遍归属。</p>
     */
    private final IAssetRepository repository;

    /**
     * 注入附件仓储，构造这个上下文贡献方。
     *
     * <p>Spring 启动时创建单例；因为没有可变成员，多个请求并发调用是安全的。</p>
     */
    public AssetContextContributor(IAssetRepository repository) {
   // 保存附件档案的读取出口；这个类没有其他可变状态，因此可以作为单例被并发调用。
        this.repository = repository;
    }

    /**
     * 挑出本轮可以注入的附件正文，拼成一段 {@code <attachments>} 包裹的文本作为唯一一个上下文片段返回。
     *
     * <p>各层职责：
     * 第一层：参数与开关校验。缺身份、缺会话或附件预算配成 0 时直接返回空，等于本次不带附件。
     * 第二层：确定可见范围并查库。只取「摘要已覆盖之后、本轮可见之前」这段区间里、绑在有效用户消息上的附件。
     * 第三层：按 token 预算和字符上限逐个装填，同内容去重，装不下就停手。
     * 第四层：把顺序倒回时间正序，包上外层标签，产出一个 ATTACHMENT 类型的贡献片段。</p>
     *
     * <p>数据流：
     * 组装请求（租户/用户/会话/序号区间）+ 策略配置
     * → 参数与预算校验
     * → 仓储查询候选附件（按消息序号倒序，最近优先）
     * → 逐个计算包裹标签开销
     * → 按剩余字符额度截断正文
     * → 按剩余 token 额度二分截断
     * → 内容哈希去重
     * → 累加进片段列表并扣减预算
     * → 反转为时间正序
     * → 拼接 &lt;attachments&gt; 容器
     * → 返回单个上下文贡献片段</p>
     *
     * <p>不写库、不改状态、不发事件。任何一步预算不够都只是少带内容，不会抛异常，
     * 因为附件带不全绝不该让整轮对话失败。</p>
     */
    @Override
    public List<ContextContribution> contribute(ContextAssembleRequest request, ContextPolicyProperties properties) {
    // 第一层：身份或会话缺失就无法安全查库，附件预算或读取上限配成非正数说明运维显式关闭了附件注入，
        // 两种情况都直接返回空列表，本轮上下文就不带任何附件。
        if (request == null || isBlank(request.getUserId()) || isBlank(request.getSessionId())
                || properties == null || properties.getAttachmentTokens() <= 0
                || properties.getAttachmentCandidateLimit() <= 0
                || properties.getAttachmentMaxContentChars() <= 0) {
        // 空列表表示「我这个供稿方本轮没有内容」，组装器会跳过它继续处理其他供稿方。
            return List.of();
        }
        // 第二层：确定附件的可见上界。附件有独立的可见序号是因为「重新生成回答」时消息可见范围和附件
        // 可见范围可能不同；没有单独指定就退回用整体可见序号，保证不会把未来的消息内容提前泄露进来。
        Integer attachmentVisibleThrough = request.getAttachmentVisibleThroughSequence() == null
                ? request.getVisibleThroughSequence() : request.getAttachmentVisibleThroughSequence();
        // 查出候选附件：从长期摘要已覆盖的序号之后开始（更早的内容已经压缩进摘要，不必再原样重复），
    // 到可见序号为止；SQL 内部按最近消息优先排序并做了条数与字符总量限制，结果集是有界的。
        List<AssetEntity> assets = repository.queryContextAssets(request.getTenantId(), request.getUserId(),
                request.getSessionId(), request.getCoveredToSequence(), attachmentVisibleThrough,
                properties.getAttachmentCandidateLimit(), properties.getAttachmentMaxContentChars());
 // 存放每个附件渲染后的 XML 片段，最后拼成一整段。
        List<String> sections = new ArrayList<>();
        // 记录已经收录过的内容标识，用来跳过重复附件，避免同一份文件在上下文里出现两遍白花 token。
        Set<String> contentHashes = new HashSet<>();
     // 本地新建一个字符级 token 估算器；它无状态，用局部实例避免与其他请求共享。
        CharacterTokenCounter tokenCounter = new CharacterTokenCounter();
        // 外层容器的开标签，给模型一个明确的「以下是附件」边界，防止附件内容被误当成用户指令。
        String containerPrefix = "<attachments>\n";
        // 外层容器的闭标签。
        String containerSuffix = "\n</attachments>";
        // 先把外层标签自身要花的 token 从预算里扣掉，剩下的才是真正能装正文的额度，
      // 否则拼完标签就可能超出预算，导致模型请求被服务端拒绝。
        int remainingTokens = properties.getAttachmentTokens()
// 这一行把外层标签自身的开销从预算里扣掉，剩下的才是能装正文的真实额度。
                - tokenCounter.estimate(containerPrefix + containerSuffix);
        // 除了 token 预算之外再设一道字符总量闸门，防止极端情况下 token 估算偏乐观而读进过多文本。
        int remainingContentChars = properties.getAttachmentMaxContentChars();
        // 连外层标签都装不下，说明预算配得太小，直接放弃本轮附件注入。
        if (remainingTokens <= 0) return List.of();
        // 第三层：按「最近优先」的顺序逐个装填，先到先得，装不下就停。
        for (AssetEntity asset : assets) {
            // 没有提取到文本的附件（图片、未识别格式）对模型没有意义，跳过但文件本身仍然保留在库里。
            if (asset.getExtractedText() == null || asset.getExtractedText().isBlank()) continue;
       // 用内容哈希作为去重键；哈希缺失时退回用资产编号，保证键一定存在不会误判成同一份内容。
            String contentKey = isBlank(asset.getSha256()) ? "asset:" + safe(asset.getAssetId()) : "sha256:" + asset.getSha256();
     // 查询按最近消息优先；相同内容只保留最近一次引用，避免重复注入和计费。
            if (!contentHashes.add(contentKey)) continue;
       // 单个附件的开标签，带上资产编号和文件名，模型回答时可以据此说明「依据哪个文件」。
            String prefix = "<attachment asset_id=\"" + safe(asset.getAssetId()) + "\" file_name=\""
                    + safe(asset.getFileName()) + "\">\n";
            // 单个附件的闭标签。
            String suffix = "\n</attachment>";
            // 先算出这层包裹标签自身要占多少 token，正文只能用剩下的额度。
            int wrapperTokens = tokenCounter.estimate(prefix + suffix);
       // 剩余额度连标签都装不下，说明后面的附件也一定装不下（额度只会越用越少），直接结束循环。
            if (remainingTokens <= wrapperTokens) break;
     // 先按剩余字符额度粗裁一次，避免把一份超大文本整个交给后面的 token 估算反复计算。
            String boundedText = asset.getExtractedText().substring(0,
                    Math.min(asset.getExtractedText().length(), remainingContentChars));
            // 再按扣掉标签后的 token 额度精裁，得到这份附件真正能放进去的最长开头部分。
            String text = truncateToTokens(boundedText, remainingTokens - wrapperTokens, tokenCounter);
        // 裁剪后一个字都不剩，说明额度已经用光，这份附件放不进来，换下一个继续尝试。
            if (text.isBlank()) continue;
     // 拼成完整的单附件片段。
            String section = prefix + text + suffix;
            // 收录进结果列表，此时顺序仍是「最近的在前」。
            sections.add(section);
            // 扣掉本次实际读取的字符数，字符闸门与 token 闸门各自独立收紧。
            remainingContentChars -= boundedText.length();
 // 扣掉本片段（含标签）的 token 消耗，保证累计不超预算。
            remainingTokens -= tokenCounter.estimate(section);
     // 任一额度用尽就立刻收手，不再浪费时间估算后面的附件。
            if (remainingTokens <= 0 || remainingContentChars <= 0) break;
        }
// 一个附件都没装进来（全被跳过或额度不足），如实返回空，让组装器把预算让给其他供稿方。
        if (sections.isEmpty()) return List.of();
    // 仓储按最近消息优先返回，渲染时恢复时间正序，兼顾预算与可读性。
        java.util.Collections.reverse(sections);
   // 第四层：包上外层容器返回唯一一个片段。声明为 ATTACHMENT 类型，组装器据此决定它在提示词里的位置和优先级；
        // source 标明来源是聊天附件，便于上下文洞察展示「这次带了哪几类内容」。
        return List.of(ContextContribution.builder().type(ContextFragmentType.ATTACHMENT)
                .content(containerPrefix + String.join("\n", sections) + containerSuffix)
                .source("chat_attachment").build());
    }

    /**
     * 在给定 token 额度内截出文本最长的开头部分。
     *
     * <p>为什么用二分而不是按比例估算：token 数和字符数不是线性关系（中文一字约一 token，
     * 英文约四字符一 token），按比例算容易一次切太多或太少。二分能保证「切完之后正好卡在额度以内的最长长度」，
     * 把宝贵的窗口额度用满。</p>
     *
     * <p>数据流：原文 → 整体估算是否已在额度内 → 若超出则在 [0, 长度] 上二分查找最大可行前缀 → 返回该前缀。</p>
     *
     * <p>纯计算，不查库不改状态。额度非正或文本为空时返回空串，由调用方决定跳过。</p>
     */
    private String truncateToTokens(String value, int maxTokens, CharacterTokenCounter tokenCounter) {
 // 没有额度或没有内容，直接给空串，调用方会跳过这份附件。
        if (maxTokens <= 0 || value == null || value.isBlank()) return "";
// 整段都装得下就原样返回，省掉一次二分查找。
        if (tokenCounter.estimate(value) <= maxTokens) return value;
        // 二分下界：0 个字符一定装得下。
        int low = 0;
        // 二分上界：全文长度，已知它装不下。
        int high = value.length();
        // 逐步收窄区间，最终 low 就是「仍在额度内的最大字符数」。
        while (low < high) {
            // 取偏上的中点，保证区间每轮都真正缩小，不会死循环。
            int mid = (low + high + 1) >>> 1;
    // 前 mid 个字符仍在额度内就把下界抬到 mid，否则说明 mid 太长，把上界压到 mid-1。
            if (tokenCounter.estimate(value.substring(0, mid)) <= maxTokens) low = mid;
            else high = mid - 1;
        }
        // 返回卡在额度内的最长开头部分，尾部内容被丢弃。
        return value.substring(0, low);
    }

    /**
     * 把要写进 XML 属性和正文的值做转义。
     *
     * <p>为什么必须转义：文件名和资产编号会被拼进 {@code <attachment file_name="...">} 里，
     * 如果文件名本身带引号或尖括号，就能把标签结构破坏掉，甚至伪造出一段假的附件标签或指令，
     * 让模型把攻击者的文字当成系统提示来执行。转义后这些字符只是普通文本。</p>
     *
     * <p>注意 {@code &} 必须先替换，否则后面替换出来的实体符号会被二次转义。</p>
     */
    private String safe(String value) {
        // 空值统一变成空串，避免把 "null" 四个字母写进提示词里让模型困惑。
        return value == null ? "" : value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 判断一个可选的字符串字段是不是没填。
     *
     * <p>null 和纯空白都算没填。上下文组装的入参很多来自上游透传，空串比 null 更常见，
     * 只判 null 会让空串一路传到 SQL 里匹配不到任何数据。</p>
     */
    private boolean isBlank(String value) {
        // null 或去掉空白后什么都不剩，都视为缺失。
        return value == null || value.isBlank();
    }
}
