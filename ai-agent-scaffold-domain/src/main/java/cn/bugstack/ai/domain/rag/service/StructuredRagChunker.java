package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.port.RagDocumentParserPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把一篇文档切成「父块 + 子块」两层结构，既保证检索精准，又保证上下文完整。
 *
 * <p>解决什么问题：切片粒度是 RAG 效果的关键矛盾。切太碎，向量检索很准，但命中的那一小段往往缺少前后文，
 * 模型看不懂；切太粗，上下文完整，但一个块里混了好几个主题，向量语义被稀释，该命中的召不回来。
 * 这里用父子两层解决：子块小而精，负责被向量检索命中；父块大而全，负责在命中后把完整章节送进上下文。
 * 检索时用子块打分、用父块补内容，两个目标同时满足。</p>
 *
 * <p>为什么要「结构优先」：绝不在标题、表格、代码块中间硬切。一个表格被切成两半，行列关系就没了；
 * 一段代码被切断，语法就废了。所以先按 Markdown 语法识别出结构块，再在结构边界上合并，只有单个结构块
 * 本身就超预算时才不得不滑窗硬切。</p>
 *
 * <p>为什么强调「确定性」：同一份文档切一百次必须得到完全一样的块和一样的块编号。块编号由
 * 「来源 + 层级 + 序号 + 内容摘要」算出，不含随机数和时间戳。这样重复摄取不会产生重复数据，
 * 引用也能长期稳定地指向同一段内容。</p>
 *
 * <p>属于哪一层：领域层（domain）的纯计算服务。无状态、线程安全，手动 new 出来用。</p>
 *
 * <p>谁会调用它：摄取 Worker 在文档解析（并可选清洗）之后调用，把结果交给向量化和入库。</p>
 *
 * <p>它向下调用什么：只用 JDK 的正则、字符串和摘要能力，不调外部服务、不读库。</p>
 *
 * <p>它不负责什么：不解析 PDF / DOCX（那是解析端口的事）、不清洗页眉页脚、不生成向量、不写库、
 * 不判断内容质量，也不做真正的分词（Token 数是估算的，只用于控制预算）。</p>
 */
public final class StructuredRagChunker {

    /**
     * 分块算法的版本标识，会随每个分块一起写进元数据。
     *
     * <p>作用是让索引里的每个块都能追溯到「它是用哪版算法切出来的」。切分逻辑一改，
     * 同一份文档就会切出不同的块和不同的块编号，新旧数据不能混着比效果，也不能混着做增量更新。
     * 改动切分行为时必须同步升级这个值。</p>
     */
    public static final String CHUNKER_VERSION = "structured-java-v1";
    /**
     * Token 估算算法的版本标识，同样写进每个分块的元数据。
     *
     * <p>和分块版本分开，是因为两者可以独立演进：只调 Token 估算规则时，块边界会变但结构识别逻辑没变，
     * 分开记录才能准确定位是哪一处改动导致了结果差异。</p>
     */
    public static final String TOKENIZER_VERSION = "approx-unicode-v1";
    /**
     * 识别 Markdown 标题行的正则，捕获井号级别和标题文字。
     *
     * <p>要求井号后必须有空格才算标题，避免把正文里的话题标签误判成标题。
     * 标题不产生内容块，而是用来维护标题栈，为后续的块打上「它属于哪个章节」的路径。</p>
     *
     * <p>常量、线程安全、被所有调用共用。</p>
     */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    /**
     * 识别 Markdown 列表项的正则，兼容无序符号和有序编号两种写法。
     *
     * <p>同样要求符号后必须跟空格，避免把「3.14」这样的数字误判成有序列表。
     * 列表要整块保留：只切一半会让条目失去上下文，读起来就是一堆断句。</p>
     */
    private static final Pattern LIST = Pattern.compile("^\\s*(?:[-*+] |\\d+[.)] ).+");
    /**
     * 识别 Markdown 表格分隔行的正则（那种只有横线和竖线的行）。
     *
     * <p>它是判断表格的关键依据：一行含竖线不代表是表格（正文里也可能有竖线），
     * 只有「含竖线的行 + 紧跟一条合法分隔行」才确认是表格表头。这样能避免把普通正文误判成表格。</p>
     */
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?(?:\\s*:?-{3,}:?\\s*\\|)+\\s*$");

    /**
     * 从解析结果直接切块，取它的归一化 Markdown 和结构章节。
     *
     * <p>只是一个便捷入口，真正的逻辑在下面那个重载方法里。解析结果为空时直接拒绝，
     * 因为后面每一步都依赖它的正文和章节信息。</p>
     *
     * <p>纯计算，不写库。</p>
     */
    public ChunkingResult chunk(String sourceId, RagDocumentParserPort.ParsedDocument document, Config config) {
        // 解析结果为空说明调用方用法有问题，直接暴露而不是返回一个空结果掩盖问题。
        if (document == null) throw new IllegalArgumentException("解析文档不能为空");
        // 取出归一化 Markdown 和解析出的章节，转调完整实现，保证两个入口行为完全一致。
        return chunk(sourceId, document.normalizedMarkdown(), document.sections(), config);
    }

    /**
     * 把归一化文本切成父子两层分块。
     *
     * <p>各层职责：
     * 第一层：入参校验，来源标识和配置必须齐全（它们决定块编号和预算）；
     * 第二层：空文本直接返回空结果，这是正常情形不是错误；
     * 第三层：把文本解析成结构块——有章节信息就按章节顺序逐段解析并继承章节标题，没有就整篇解析；
     * 第四层：在结构边界和子块预算内合并成子块草稿；
     * 第五层：把同章节的相邻子块合并成父块分组；
     * 第六层：生成稳定编号并串联相邻子块。</p>
     *
     * <p>数据流：
     * 归一化文本 + 章节列表 + 预算配置
     * → 入参校验
     * → 空文本则返回空结果
     * → 解析成结构块（段落 / 列表 / 表格 / 代码，各自带章节路径与页码）
     * → 合并成子块草稿（不跨结构边界、不超子块预算）
     * → 按章节合并成父块分组（不超父块预算）
     * → 生成父块与子块的稳定编号、串联前后相邻关系
     * → 返回完整分块结果</p>
     *
     * <p>为什么优先用章节而不是整篇解析：解析器给出的章节自带标题路径和页码，比从纯文本重新推断准确得多。
     * 按章节顺序处理还能保证块的先后关系与原文一致。只有解析器没提供章节时才退化成整篇解析。</p>
     *
     * <p>纯计算，不写库、不调外部服务。同样的输入永远得到同样的输出。</p>
     */
    public ChunkingResult chunk(String sourceId, String normalizedText,
                                List<RagDocumentParserPort.ParsedSection> sections, Config config) {
        // 第一层：来源标识决定块编号（它是稳定编号的输入之一），配置决定预算，两者缺一都无法切块。
        if (sourceId == null || sourceId.isBlank() || config == null) {
            // 直接抛非法参数异常，这是调用契约问题，不是业务错误。
            throw new IllegalArgumentException("分块来源或配置不能为空");
        }
        // 第二层：空文本返回空结果而不是抛错。一份没有可提取文字的文档（例如纯图片 PDF）是正常存在的，
        // 由上层的质量评估去判断它能不能进索引，这里只负责如实返回「切不出块」。
        if (normalizedText == null || normalizedText.isBlank()) return new ChunkingResult(List.of());
        // 收集解析出来的全部结构块，顺序即最终块的先后顺序。
        List<Block> blocks = new ArrayList<>();
        // 第三层：优先走章节路径，解析器给的章节自带标题层级和页码，比从纯文本重新推断可靠得多。
        if (sections != null && !sections.isEmpty()) {
            // 按章节声明的顺序排序后逐个解析。必须排序，因为解析器返回的集合顺序不保证；
            // 顺序一乱，块的先后关系和原文就对不上了。每个章节把自己的标题路径和页码传下去，
            // 这样切出来的块都知道自己出自文档的哪一节、哪一页。
            sections.stream().sorted(java.util.Comparator.comparingInt(RagDocumentParserPort.ParsedSection::order))
                    .forEach(section -> blocks.addAll(parseBlocks(section.content(), section.headingPath(), section.pageNumber())));
        } else {
            // 解析器没提供章节（例如纯 Markdown 文件），退化成整篇解析，标题路径靠正文里的井号标题自行推断。
            blocks.addAll(parseBlocks(normalizedText, null, null));
        }
        // 第四层：把结构块合并成子块草稿——不跨结构边界、不跨章节、不超子块预算。
        List<Draft> children = childDrafts(blocks, config);
        // 一个子块都没有（正文全是空白或只有标题）就返回空结果，不生成任何空块。
        if (children.isEmpty()) return new ChunkingResult(List.of());
        // 第五层：把同章节的相邻子块聚成父块分组，父块负责在命中后提供完整上下文。
        List<ParentGroup> groups = parentGroups(children, config);
        // 第六层：生成稳定编号、串联相邻关系，产出最终结果。
        return materialize(sourceId, groups);
    }

    /**
     * 把一段文本解析成带章节路径的结构块序列。
     *
     * <p>各层职责：
     * 第一层：初始化标题栈，若外部传入了继承标题就作为栈底；
     * 第二层：统一换行符后逐行扫描；
     * 第三层：遇到标题行只更新标题栈，不产生内容块；
     * 第四层：遇到代码围栏，一直吃到配对的结束围栏为止，整段作为一个代码块；
     * 第五层：遇到表格表头（含竖线且下一行是分隔行），一直吃到不含竖线或空行为止；
     * 第六层：遇到列表项，连同后续的列表项和缩进续行一起吃掉；
     * 第七层：空行跳过；
     * 第八层：其余情况按段落处理，一直吃到空行或下一个结构起点为止。</p>
     *
     * <p>数据流：
     * 文本 + 继承标题 + 页码
     * → 统一换行符 → 按行拆分
     * → 逐行判断类型（标题 / 代码围栏 / 表格 / 列表 / 空行 / 段落）
     * → 标题只更新标题栈；其余各自吃掉完整的一段，打上当前章节路径与页码
     * → 返回结构块列表</p>
     *
     * <p>为什么要维护标题栈：Markdown 的标题是有层级的。遇到三级标题时，要先把栈里所有三级及更深的标题弹掉，
     * 再把它压进去，这样栈从底到顶就是「一级 > 二级 > 三级」的完整路径。有了它，每个块都知道自己在文档里的位置，
     * 后续合并子块和父块时也能据此判断「是不是同一节的内容」。</p>
     *
     * <p>为什么代码块和表格必须整段吃掉：它们内部完全可能出现空行、井号、竖线。若按普通规则逐行判断，
     * 代码里的注释会被当成标题，表格中间会被切断，结构彻底破坏。</p>
     *
     * <p>纯字符串处理，不写库。循环由内部游标推进（不是每次加一），所以每个分支都必须把游标推到已消费位置之后。</p>
     */
    private List<Block> parseBlocks(String text, String inheritedHeading, Integer page) {
        // 收集本段解析出的结构块。
        List<Block> result = new ArrayList<>();
        // 标题栈：从底到顶就是当前所在的章节路径，随扫描过程不断增删。
        List<String> headings = new ArrayList<>();
        // 外部传入的章节标题作为栈底，这样按章节解析时，块的路径能带上章节自身的标题。
        if (inheritedHeading != null && !inheritedHeading.isBlank()) headings.add(inheritedHeading.trim());
        // 统一换行符再拆行：Windows 的回车换行和老式 Mac 的单回车都归一成换行，
        // 否则行首行尾会残留回车符，影响后面所有的正则匹配和空行判断。limit 传 -1 保留末尾空行，
        // 让「文档以空行结尾」这种情况也能被正确识别为段落边界。
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        // 第二层：逐行扫描。注意循环没有自增部分，游标由各个分支自己推进——
        // 因为代码块、表格、列表一次会消费多行，必须由它们决定跳到哪里。
        for (int i = 0; i < lines.length;) {
            // 取出当前行。
            String line = lines[i];
            // 先尝试按标题行匹配。
            var heading = HEADING.matcher(line);
            // 第三层：这是一个标题行。标题本身不作为内容块（它太短，单独切块检索价值很低），
            // 只用来更新章节路径。
            if (heading.matches()) {
                // 井号个数就是标题层级。
                int level = heading.group(1).length();
                // 把栈里同级和更深的标题全部弹掉：遇到新的二级标题时，之前的二级、三级标题都已经结束了。
                // 这样栈里剩下的就是这个标题的所有祖先。
                while (headings.size() >= level) headings.remove(headings.size() - 1);
                // 再把当前标题压栈，此时栈从底到顶就是完整的章节路径。
                headings.add(heading.group(2).trim());
                // 游标前进一行。
                i++;
                // 标题不产生块，直接进入下一轮扫描。
                continue;
            }
            // 第四层：代码围栏起始行（三个反引号或三个波浪号）。
            if (line.trim().startsWith("```") || line.trim().startsWith("~~~")) {
                // 记下用的是哪种围栏符号，结束围栏必须用同一种，避免反引号块被波浪号提前结束。
                String fence = line.trim().substring(0, 3);
                // 从下一行开始找配对的结束围栏。
                int end = i + 1;
                // 一直往前找，直到遇到同种围栏或文本结束。代码内部的空行、井号、竖线在这里全部被跳过，
                // 这正是「整段吃掉」的意义所在。
                while (end < lines.length && !lines[end].trim().startsWith(fence)) end++;
                // 找到了结束围栏就把它也包含进来（结束围栏是代码块的一部分）；
                // 没找到（围栏没闭合）就吃到文本末尾，宁可多包一点也不要把代码切断。
                if (end < lines.length) end++;
                // 把这一整段作为代码块存下来，带上当前章节路径和页码。
                result.add(block(join(lines, i, end), path(headings), page, BlockType.CODE));
                // 游标跳到代码块之后，避免重复扫描已消费的行。
                i = end;
                // 本轮处理完毕，进入下一轮。
                continue;
            }
            // 第五层：表格起始行。判断依据是「本行含竖线且下一行是合法分隔行」，单看竖线会误判正文。
            if (isTableStart(lines, i)) {
                // 从下一行开始吃表格主体。
                int end = i + 1;
                // 只要行里还有竖线且不是空行就继续吃：这是表格结束的自然边界。
                while (end < lines.length && lines[end].contains("|") && !lines[end].isBlank()) end++;
                // 整张表作为一个块存下来，绝不切开——切一半的表格行列关系就全乱了。
                result.add(block(join(lines, i, end), path(headings), page, BlockType.TABLE));
                // 游标跳到表格之后。
                i = end;
                // 本轮处理完毕。
                continue;
            }
            // 第六层：列表项起始行。
            if (LIST.matcher(line).matches()) {
                // 从下一行开始吃后续条目。
                int end = i + 1;
                // 继续吃的条件有两种：下一行还是列表项，或者它是以空白开头的非空行（列表项的缩进续行）。
                // 第二个条件很重要：多行的列表项如果被切开，续行就变成了没有上下文的孤立句子。
                while (end < lines.length && (LIST.matcher(lines[end]).matches()
                        || !lines[end].isBlank() && Character.isWhitespace(lines[end].charAt(0)))) end++;
                // 整个列表作为一个块存下来。
                result.add(block(join(lines, i, end), path(headings), page, BlockType.LIST));
                // 游标跳到列表之后。
                i = end;
                // 本轮处理完毕。
                continue;
            }
            // 第七层：空行只是分隔符，不产生任何块。
            if (line.isBlank()) {
                // 游标前进一行。
                i++;
                // 进入下一轮。
                continue;
            }
            // 第八层：普通段落，从下一行开始寻找段落结束位置。
            int end = i + 1;
            // 段落一直吃到遇到任一结构边界为止：空行、标题行、代码围栏、列表项、表格起始。
            // 这些都意味着「段落到此结束，下面是新的结构」，继续吃下去就会把不同结构混进同一个块。
            while (end < lines.length && !lines[end].isBlank() && !HEADING.matcher(lines[end]).matches()
                    && !lines[end].trim().startsWith("```") && !lines[end].trim().startsWith("~~~")
                    && !LIST.matcher(lines[end]).matches() && !isTableStart(lines, end)) end++;
            // 把这一段作为段落块存下来。
            result.add(block(join(lines, i, end), path(headings), page, BlockType.PARAGRAPH));
            // 游标跳到段落之后；这里不需要 continue，因为已经是循环体的最后一句。
            i = end;
        }
        // 返回本段解析出的全部结构块，顺序与原文一致。
        return result;
    }

    /**
     * 把结构块合并成子块草稿：在不破坏结构、不跨章节、不超预算的前提下尽量攒满。
     *
     * <p>各层职责：
     * 第一层：跳过空白块；
     * 第二层：单个结构块本身就超预算时，先把手上攒的交出去，再对它滑窗硬切；
     * 第三层：预演合并后的内容，判断是否存在结构边界（代码块、表格、换章节）；
     * 第四层：遇到边界或合并后超预算，就把手上攒的先交出去；
     * 第五层：把当前块并入或作为新的起点继续攒；
     * 第六层：循环结束把最后攒的交出去。</p>
     *
     * <p>数据流：
     * 结构块序列 + 预算配置
     * → 跳过空白块
     * → 单块超预算 → 交出已攒的 → 滑窗切成多份 → 各自成为独立子块
     * → 否则预演合并 → 判断结构边界与预算
     * → 需要断开则交出已攒的
     * → 并入或新建当前草稿
     * → 收尾交出最后一份
     * → 返回子块草稿列表</p>
     *
     * <p>为什么代码块和表格要单独成块（不与相邻内容合并）：它们的语义是自成一体的。
     * 把一段说明文字和一张表格塞进同一个子块，向量表示会被两种完全不同的内容拉扯，两边都检索不准。</p>
     *
     * <p>为什么换章节必须断开：不同章节讲的是不同主题。合并会让一个子块横跨两个主题，
     * 检索命中后模型也难以判断这段内容到底属于哪一节。</p>
     *
     * <p>为什么字符数和 Token 数两个预算都要卡：字符数控制存储和传输开销，Token 数控制模型上下文占用。
     * 中文一个字约一个 Token，英文约四个字符一个 Token，只卡一个的话另一个必然会在某类文档上失控。</p>
     *
     * <p>纯计算，不写库。</p>
     */
    private List<Draft> childDrafts(List<Block> blocks, Config config) {
        // 收集最终产出的子块草稿。
        List<Draft> result = new ArrayList<>();
        // 手上正在攒的草稿；为空表示还没开始攒。
        Draft current = null;
        // 逐个结构块处理，顺序即原文顺序。
        for (Block block : blocks) {
            // 第一层：空白块没有任何检索价值，直接跳过，避免产出空子块。
            if (block.content().isBlank()) continue;
            // 第二层：这个结构块自己就超了子块预算（例如一段极长的正文或一张大表）。
            if (!fits(block.content(), config.childMaxChars(), config.childMaxTokens())) {
                // 先把手上攒的草稿交出去并清空——超长块要独立处理，不能和前面的内容混在一起。
                if (current != null) { result.add(current); current = null; }
                // 对超长内容做滑窗切分，相邻片段之间保留一段重叠，避免正好被切断的句子在两边都读不通。
                for (String part : splitOversized(block.content(), config.childMaxChars(),
                        config.childMaxTokens(), config.overlapChars())) {
                    // 每个切片各自成为一个独立子块，沿用原结构块的章节路径、页码和类型。
                    result.add(new Draft(part, block.heading(), block.page(), block.type()));
                }
                // 超长块处理完毕，直接进入下一个结构块，不参与后面的合并逻辑。
                continue;
            }
            // 第三层：预演一下「把当前块并进手上草稿」会得到什么内容，用空行分隔以保持段落边界清晰。
            // 只是预演，还没真的合并——要先判断该不该合并。
            String combined = current == null ? block.content() : current.content() + "\n\n" + block.content();
            // 判断是否存在必须断开的结构边界，三种情况任一成立即为边界——
            // 一，当前块是代码块或表格（它们必须独立成块，语义自成一体）；
            // 二，手上攒的草稿是代码块或表格（同理，不能再往里塞别的内容）；
            // 三，当前块与草稿的章节路径不同（跨章节会让一个子块横跨两个主题）。
            boolean structuralBoundary = block.type() == BlockType.CODE || block.type() == BlockType.TABLE
                    || current != null && (current.type() == BlockType.CODE || current.type() == BlockType.TABLE)
                    || current != null && !same(current.heading(), block.heading());
            // 第四层：手上有草稿，且（遇到结构边界 或 合并后会超预算），就必须先把它交出去。
            if (current != null && (structuralBoundary
                    || !fits(combined, config.childMaxChars(), config.childMaxTokens()))) {
                // 交付这份已经攒好的草稿。
                result.add(current);
                // 清空手上的草稿，让下面重新以当前块作为起点。
                current = null;
            }
            // 第五层：草稿为空就以当前块作为新起点；否则用预演好的合并内容替换草稿，
            // 并沿用草稿原有的章节路径、页码和类型（合并的前提就是它们同章节，所以沿用是安全的）。
            current = current == null ? new Draft(block.content(), block.heading(), block.page(), block.type())
                    : new Draft(combined, current.heading(), current.page(), current.type());
        }
        // 第六层：循环结束，把最后攒的草稿交出去，否则文档末尾的内容会被丢掉。
        if (current != null) result.add(current);
        // 返回全部子块草稿，此时还没有分配稳定编号。
        return result;
    }

    /**
     * 把相邻的子块聚成父块分组：同一章节、且合起来不超父块预算。
     *
     * <p>各层职责：
     * 第一层：逐个子块，预演加入当前分组后的内容；
     * 第二层：换章节或超父块预算就先收口当前分组；
     * 第三层：把子块加入分组；
     * 第四层：循环结束收口最后一个分组。</p>
     *
     * <p>数据流：子块草稿序列 + 预算配置 → 预演合并 → 换章节或超预算则收口分组
     * → 加入当前分组 → 收尾收口 → 返回分组列表</p>
     *
     * <p>父块的用途：检索是用子块打分的，但命中之后要送进模型的是父块的完整内容。
     * 这样既有子块的检索精度，又有父块的上下文完整度。</p>
     *
     * <p>为什么用第一个子块的章节路径做比较基准：一个分组从建立起就绑定了一个章节，
     * 后续子块只要章节不同就必须开新组，保证一个父块永远只覆盖一个章节。</p>
     *
     * <p>纯计算，不写库。</p>
     */
    private List<ParentGroup> parentGroups(List<Draft> children, Config config) {
        // 收集最终的父块分组。
        List<ParentGroup> groups = new ArrayList<>();
        // 当前正在攒的分组成员。
        List<Draft> current = new ArrayList<>();
        // 逐个子块处理，顺序即原文顺序。
        for (Draft child : children) {
            // 预演一下把这个子块加进当前分组后的父块正文，用来判断是否超预算。
            String combined = joinDrafts(current, child);
            // 分组非空，且（章节变了 或 加进来会超父块预算），就必须先收口当前分组。
            // 章节以分组第一个成员为基准，保证一个父块只覆盖一个章节。
            if (!current.isEmpty() && (!same(current.get(0).heading(), child.heading())
                    || !fits(combined, config.parentMaxChars(), config.parentMaxTokens()))) {
                // 收口：把成员列表复制一份存进分组，复制是为了防止后面 clear 把已收口的分组内容清掉。
                groups.add(new ParentGroup(List.copyOf(current)));
                // 清空成员列表，准备攒下一组。
                current.clear();
            }
            // 把当前子块加入分组（无论上面有没有收口，它都属于新的当前分组）。
            current.add(child);
        }
        // 循环结束，收口最后一个分组，否则末尾的子块不会有父块。
        if (!current.isEmpty()) groups.add(new ParentGroup(List.copyOf(current)));
        // 返回全部父块分组。
        return groups;
    }

    /**
     * 给所有父块和子块分配稳定编号，并串联相邻子块，产出最终结果。
     *
     * <p>各层职责：
     * 第一层：逐个分组，先产出父块（内容是全部成员拼接），再产出它的各个子块；
     * 第二层：编号由「来源 + 层级 + 全局序号 + 内容摘要」算出，保证可复现；
     * 第三层：把所有子块在结果里的位置收集起来；
     * 第四层：按这些位置串联前后相邻关系（跨父块也串），供检索时扩展上下文；
     * 第五层：统一组装成对外分块，带上版本和层级元数据。</p>
     *
     * <p>数据流：
     * 父块分组列表
     * → 逐组：拼父块正文 → 算摘要 → 生成父块编号 → 产出父块
     * → 组内逐子块：算摘要 → 生成子块编号（全局递增序号）→ 产出子块并挂上父块编号
     * → 收集所有子块的位置
     * → 按位置建立前一块 / 后一块映射
     * → 逐个组装成对外分块（含相邻编号、序号、Token 数、页码、章节、摘要、版本元数据）
     * → 返回分块结果</p>
     *
     * <p>为什么子块序号是全局递增而不是组内递增：编号必须全局唯一。若组内从 0 开始，
     * 不同父块下内容相同的子块会算出完全一样的编号，索引里就会互相覆盖。</p>
     *
     * <p>为什么相邻关系要跨父块串联：检索命中一个子块后，可以顺着前后指针把相邻内容一起取出来补充上下文。
     * 章节交界处的内容往往语义相连，只在组内串联会让这种衔接断掉。</p>
     *
     * <p>为什么内容摘要要参与编号计算：内容一变编号就变，天然做到「内容不同必然是不同的块」。
     * 同时摘要本身也会存下来，检索时可以用它校验读到的内容是否还是当初那一段。</p>
     *
     * <p>纯计算，不写库。</p>
     */
    private ChunkingResult materialize(String sourceId, List<ParentGroup> groups) {
        // 中间态列表：先按父块、子块的顺序把内容都生成出来，相邻关系稍后统一补。
        List<Partial> partials = new ArrayList<>();
        // 子块的全局序号，跨父块持续递增，保证编号全局唯一。
        int childOrdinal = 0;
        // 第一层：逐个父块分组处理，下标同时作为父块序号。
        for (int parentOrdinal = 0; parentOrdinal < groups.size(); parentOrdinal++) {
            // 取出这一组。
            ParentGroup group = groups.get(parentOrdinal);
            // 父块正文就是组内全部子块内容的拼接，它提供的是完整章节级上下文。
            String parentContent = group.content();
            // 算父块内容摘要，既参与编号计算，也存下来供事后校验内容是否被改动。
            String parentHash = sha256(parentContent);
            // 生成父块稳定编号：来源 + 层级 + 序号 + 内容摘要，不含随机数和时间，重复摄取结果一致。
            String parentId = stableId(sourceId, "parent", parentOrdinal, parentHash);
            // 产出父块中间态：父块没有上级，所以父编号为空；页码和章节取组内第一个子块的
            // （父块覆盖同一章节，所以第一个成员的章节就是整组的章节）。
            partials.add(new Partial(Level.PARENT, parentId, null, parentContent,
                    approximateTokens(parentContent), group.children().get(0).page(),
                    group.children().get(0).heading(), parentHash));
            // 逐个产出组内子块。
            for (Draft child : group.children()) {
                // 算子块内容摘要，同样既参与编号又用于事后校验。
                String childHash = sha256(child.content());
                // 产出子块中间态并挂上所属父块编号；序号在这里后置自增，保证全局唯一。
                // 子块保留自己的页码和章节路径，检索命中后可以精确告诉用户出自第几页。
                partials.add(new Partial(Level.CHILD,
                        stableId(sourceId, "child", childOrdinal++, childHash), parentId,
                        child.content(), approximateTokens(child.content()), child.page(), child.heading(), childHash));
            }
        }
        // 第三层：收集所有子块在中间态列表里的位置。相邻关系只在子块之间建立，
        // 父块夹在中间会打断连续性，所以要先把子块的位置挑出来。
        List<Integer> childPositions = new ArrayList<>();
        // 逐个扫描中间态，记下每个子块的位置。
        for (int i = 0; i < partials.size(); i++) if (partials.get(i).level() == Level.CHILD) childPositions.add(i);
        // 位置到「前一个子块编号」的映射。
        Map<Integer, String> previous = new LinkedHashMap<>();
        // 位置到「后一个子块编号」的映射。
        Map<Integer, String> next = new LinkedHashMap<>();
        // 第四层：按子块位置顺序建立前后相邻关系。
        for (int i = 0; i < childPositions.size(); i++) {
            // 取出这个子块在中间态列表里的位置。
            int position = childPositions.get(i);
            // 不是第一个子块，就把上一个子块的编号记为它的前驱。
            if (i > 0) previous.put(position, partials.get(childPositions.get(i - 1)).chunkId());
            // 不是最后一个子块，就把下一个子块的编号记为它的后继。
            if (i + 1 < childPositions.size()) next.put(position, partials.get(childPositions.get(i + 1)).chunkId());
        }
        // 第五层：最终对外的分块列表。
        List<StructuredChunk> result = new ArrayList<>();
        // 逐个把中间态组装成对外分块；下标即块在结果里的位置，也用作对外的块序号。
        for (int i = 0; i < partials.size(); i++) {
            // 取出这一个中间态。
            Partial value = partials.get(i);
            // 组装对外分块：层级、自身编号、父编号、前后相邻编号（父块取不到会是 null，符合预期）、
            // 序号、正文、估算 Token 数、页码、章节路径、内容摘要，
            // 以及三项元数据——分块算法版本、Token 估算版本、层级。这三项让索引里的每个块都能追溯到生成方式。
            result.add(new StructuredChunk(value.level(), value.chunkId(), value.parentChunkId(), previous.get(i),
                    next.get(i), i, value.content(), value.tokenCount(), value.page(), value.heading(),
                    value.contentHash(), Map.of("chunker_version", CHUNKER_VERSION,
                    "tokenizer_version", TOKENIZER_VERSION, "chunk_level", value.level().name().toLowerCase())));
        }
        // 返回完整分块结果，构造时会冻结列表防止外部修改。
        return new ChunkingResult(result);
    }

    /**
     * 对超预算的单个结构块做带重叠的滑窗切分。
     *
     * <p>各层职责：
     * 第一层：确定本片的起点，非首片会往前回退一段作为重叠；
     * 第二层：算出字符预算允许的硬上界，并避免切在代理对中间；
     * 第三层：在硬上界内二分求出 Token 预算允许的最大终点；
     * 第四层：终点没有越过已消费位置时（重叠回退导致的死循环风险），强制不带重叠重算一次；
     * 第五层：把终点调整到自然断句处；
     * 第六层：截取并去空白，非空则收下；
     * 第七层：游标推进到本片终点。</p>
     *
     * <p>数据流：
     * 超长内容 + 字符预算 + Token 预算 + 重叠长度
     * → 定起点（非首片回退重叠长度）
     * → 字符硬上界（避开代理对）
     * → 二分求 Token 允许的终点
     * → 若终点未越过已消费位置则去掉重叠重算
     * → 调整到自然断句处
     * → 截取片段 → 游标前进
     * → 返回片段列表</p>
     *
     * <p>为什么要重叠：硬切必然会切断某个句子。如果不重叠，被切断的那句话在前后两片里都是残缺的，
     * 检索时两边都命中不了。让相邻片段共享一段内容，就能保证任何一句话至少在某一片里是完整的。</p>
     *
     * <p>为什么要防代理对：一个超出基本平面的字符（例如某些表情和生僻字）在 Java 里占两个 char。
     * 正好切在它们中间会产生一个无效的半字符，写进索引后会变成乱码，甚至让后续解析报错。</p>
     *
     * <p>为什么要有「终点未越过已消费位置」这道保护：起点会因为重叠而回退，
     * 如果预算很小而重叠很大，算出的终点可能不比上一片的终点更靠后，循环就永远推进不下去。
     * 这时放弃重叠、从已消费位置重新算，保证每一轮游标都必然前进。</p>
     *
     * <p>纯计算，不写库。</p>
     */
    private List<String> splitOversized(String content, int maxChars, int maxTokens, int overlapChars) {
        // 收集切出来的片段。
        List<String> parts = new ArrayList<>();
        // 游标：已经消费到的位置，也是下一片的基准起点。
        int cursor = 0;
        // 一直切到内容末尾。
        while (cursor < content.length()) {
            // 第一层：首片从游标开始；后续片往前回退一段作为重叠，让被切断的句子至少在一片里完整。
            int start = parts.isEmpty() ? cursor : Math.max(0, cursor - overlapChars);
            // 第二层：字符预算允许的最远终点，同时不能越过内容末尾。
            int hardEnd = Math.min(content.length(), start + maxChars);
            // 若正好落在一个代理对的后半个 char 上，往回退一格，避免切出无效的半字符导致乱码。
            if (hardEnd < content.length() && Character.isLowSurrogate(content.charAt(hardEnd))) hardEnd--;
            // 第三层：在字符硬上界之内，二分求出 Token 预算允许的最大终点。两个预算同时满足才算合规。
            int end = maxTokenEnd(content, start, hardEnd, maxTokens);
            // 第四层：终点没有越过已消费位置，说明重叠回退吃掉了全部可用预算，再切下去游标不会前进。
            if (end <= cursor) {
                // 放弃重叠，起点改回已消费位置，保证这一片一定是全新内容。
                start = cursor;
                // 用新起点重算字符硬上界。
                hardEnd = Math.min(content.length(), start + maxChars);
                // 同样避开代理对。
                if (hardEnd < content.length() && Character.isLowSurrogate(content.charAt(hardEnd))) hardEnd--;
                // 重算 Token 允许的终点；这样每一轮游标必然向前推进，杜绝死循环。
                end = maxTokenEnd(content, start, hardEnd, maxTokens);
            }
            // 第五层：把终点往前挪到最近的自然断句处（换行、句号、分号、空格等），让片段读起来是完整的句子。
            end = safeBoundary(content, start, end, cursor);
            // 第六层：截取本片并去掉首尾空白。
            String part = content.substring(start, end).trim();
            // 只收下非空片段：切在连续空白处时可能得到空串，收进去只会产生无意义的空块。
            if (!part.isBlank()) parts.add(part);
            // 第七层：游标推进到本片终点。下一片会从这里（可能回退一段重叠）继续。
            cursor = end;
        }
        // 返回全部片段，每一片都同时满足字符和 Token 两个预算。
        return parts;
    }

    /**
     * 二分求出「从 start 开始、不超过 Token 预算」的最大字符终点。
     *
     * <p>为什么用二分而不是逐字符累加：Token 数是对整段文本估算的（连续 ASCII 词按四字符一个 Token），
     * 不能简单地逐字符累加，只能整段重算。二分把重算次数从「字符数」降到「字符数的对数」，
     * 对上万字的长块差别巨大。</p>
     *
     * <p>二分的单调性前提：文本越长，估算出的 Token 数不会变少。这一点由估算算法本身保证。</p>
     *
     * <p>同样要避开代理对：中点可能正好落在一个代理对中间，回退一格再判断。
     * 注意收缩区间时用的是未回退的原始中点，否则区间可能不收缩而导致死循环。</p>
     *
     * <p>纯计算，不写库。返回值最小为 start（表示一个字符都放不下）。</p>
     */
    private int maxTokenEnd(String value, int start, int hardEnd, int maxTokens) {
        // 二分区间：下界至少往前一个字符（避免空区间），上界是字符预算给的硬上界，
        // best 记录目前找到的可行终点，初始为 start 表示还没找到任何可行位置。
        int low = Math.min(start + 1, hardEnd), high = hardEnd, best = start;
        // 标准二分循环。
        while (low <= high) {
            // 用无符号右移求中点，避免下界与上界相加溢出。
            int rawMiddle = (low + high) >>> 1;
            // 单独保留一个可调整的中点副本；区间收缩要用未调整的原始中点。
            int middle = rawMiddle;
            // 中点落在代理对后半时回退一格，保证用于估算的子串不含半个字符。
            if (middle < value.length() && Character.isLowSurrogate(value.charAt(middle))) middle--;
            // 估算这一段的 Token 数，判断是否在预算内。
            if (approximateTokens(value.substring(start, middle)) <= maxTokens) {
                // 在预算内：记下这个可行终点，并把下界推到原始中点之后继续向右探。
                // 用原始中点而不是回退后的中点，保证区间一定收缩，不会卡死。
                best = middle; low = rawMiddle + 1;
            } else high = rawMiddle - 1;
        }
        // 返回找到的最大可行终点；一个字符都放不下时会返回 start，由调用方的边界保护继续处理。
        return best;
    }

    /**
     * 把切分终点调整到最近的自然断句处。
     *
     * <p>从建议终点往前找换行、中文句号、中文感叹号、中文问号、英文句点、分号或空格，
     * 找到就在那里切。这样片段读起来是完整的句子，向量表示也更干净。</p>
     *
     * <p>往前找有两个下限：一是必须越过已消费位置（保证游标前进，不死循环），
     * 二是不能把片段砍掉一半以上（否则一段没有任何标点的长文本会被切得极短，产生大量碎片）。
     * 两个下限取较大者。找不到合适位置就用原建议终点，宁可切在字中间也要保证进度。</p>
     *
     * <p>纯计算，不写库。</p>
     */
    private int safeBoundary(String value, int start, int proposed, int consumedThrough) {
        // 计算能往前回退的下限：既要越过已消费位置（保证游标前进），
        // 也不能回退超过本片长度的一半（否则没有标点的长文本会被切成大量碎片）。
        int minimum = Math.max(consumedThrough + 1, start + Math.max(1, (proposed - start) / 2));
        // 从建议终点往前逐个位置试，优先选离建议终点最近的断句点，尽量少浪费预算。
        for (int i = proposed; i >= minimum; i--) {
            // 看这个位置前面的那个字符是不是断句符号。
            char c = value.charAt(i - 1);
            // 命中换行、中英文句末标点、分号或空格，就在这里切；返回的是切点位置（该字符包含在本片内）。
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.' || c == ';' || c == ' ') return i;
        }
        // 整段都找不到断句符号（例如一长串连续的数字或代码），只能按原建议终点硬切，保证切分继续推进。
        return proposed;
    }

    /**
     * 估算一段文本的 Token 数。
     *
     * <p>各层职责：
     * 第一层：空文本直接为 0；
     * 第二层：逐码点扫描，连续的 ASCII 字母数字累计成一个「词」；
     * 第三层：遇到非 ASCII 字母数字时先把攒着的 ASCII 词按每四字符一个 Token 结算，
     * 再把当前字符本身算一个 Token（空白不算）；
     * 第四层：收尾结算残留的 ASCII 词。</p>
     *
     * <p>数据流：文本 → 逐码点扫描 → ASCII 词累计 / 非 ASCII 字符逐个计数 → 收尾结算 → Token 总数</p>
     *
     * <p>为什么要自己估算而不用真实分词器：真实分词器要加载词表、依赖具体模型、跨版本结果还会变，
     * 而这里只需要一个「够准且永远一致」的数字来控制预算。规则写死在代码里，任何环境结果都相同，
     * 这是整个分块过程可复现的前提之一。</p>
     *
     * <p>为什么中文一个字算一个 Token、英文四字符算一个：这是主流模型的经验比例。
     * 中日韩字符通常一字一 Token，英文单词平均约四个字符一个 Token。</p>
     *
     * <p>为什么公开成静态方法：入库前统计每个块的 Token 数也要用它，必须和切分时用的是同一套算法，
     * 否则统计出来的数字和实际用于控制预算的数字会对不上。</p>
     *
     * <p>纯计算，无状态，线程安全。</p>
     */
    public static int approximateTokens(String value) {
        // 空文本或纯空白没有任何 Token。
        if (value == null || value.isBlank()) return 0;
        // tokens 是累计结果；asciiRun 是当前连续 ASCII 字母数字的长度（还没结算成 Token）。
        int tokens = 0, asciiRun = 0;
        // 按码点遍历，超出基本平面的字符不会被拆成两半重复计数。
        for (int codePoint : value.codePoints().toArray()) {
            // ASCII 范围内的字母或数字属于一个英文单词，先攒起来。
            if (codePoint < 128 && Character.isLetterOrDigit(codePoint)) {
                // 累加当前词的长度，等遇到词边界再统一结算。
                asciiRun++;
            } else {
                // 走到词边界了：先把攒着的 ASCII 词按每四字符一个 Token 结算（向上取整），再清零重新攒。
                if (asciiRun > 0) { tokens += (asciiRun + 3) / 4; asciiRun = 0; }
                // 当前字符本身算一个 Token：中日韩文字、标点、符号都按一字一 Token 估算。
                // 空白只是分隔符，不占 Token。
                if (!Character.isWhitespace(codePoint)) tokens++;
            }
        }
        // 收尾：文本以 ASCII 字母数字结尾时，最后一个词还没结算，这里补上，否则会少算。
        if (asciiRun > 0) tokens += (asciiRun + 3) / 4;
        // 返回估算的 Token 总数。
        return tokens;
    }

    /**
     * 判断一段文本是否同时满足字符预算和 Token 预算。
     *
     * <p>两个都必须满足。只卡字符数，中文文本会因为一字一 Token 而超出模型上下文；
     * 只卡 Token 数，英文文本会因为四字符一 Token 而占用过多存储和传输带宽。</p>
     */
    private boolean fits(String value, int chars, int tokens) {
        // 字符数和估算 Token 数都不超预算才算合规。
        return value.length() <= chars && approximateTokens(value) <= tokens;
    }

    /**
     * 判断某一行是不是 Markdown 表格的表头行。
     *
     * <p>判定条件是「本行含竖线，且下一行是合法的表格分隔行」。只看竖线会把正文里的竖线误判成表格；
     * 加上分隔行这个强特征后，误判率极低。已经是最后一行时直接不算表格（没有下一行可做分隔行）。</p>
     */
    private boolean isTableStart(String[] lines, int index) {
        // 三个条件同时成立才算表格起始：存在下一行、本行含竖线、下一行匹配表格分隔行模式。
        return index + 1 < lines.length && lines[index].contains("|")
                && TABLE_SEPARATOR.matcher(lines[index + 1]).matches();
    }

    /**
     * 构造一个结构块，顺手去掉正文首尾空白。
     *
     * <p>去空白是为了让后续拼接和摘要计算稳定：同一段内容因为多了个尾部换行就算出不同摘要、
     * 生成不同块编号，会让重复摄取产生重复数据。</p>
     */
    private Block block(String content, String heading, Integer page, BlockType type) {
        // 去掉首尾空白后连同章节路径、页码、类型一起打包成结构块。
        return new Block(content.trim(), heading, page, type);
    }

    /**
     * 用换行把指定半开区间内的行拼回一段文本。
     *
     * <p>区间是左闭右开，与调用处的游标语义一致。拼完去掉首尾空白，理由同上：让摘要和编号稳定。</p>
     */
    private String join(String[] lines, int from, int to) {
        // 复制出区间内的行再用换行拼接，最后去首尾空白。
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, to)).trim();
    }

    /**
     * 把标题栈压平成一个可读的章节路径，例如「安装指南 > 环境要求 > JDK 版本」。
     *
     * <p>栈为空时返回 null 而不是空串：null 明确表示「不属于任何章节」，
     * 而空串在后续的章节相等比较里容易和真实的空标题混淆。</p>
     */
    private String path(List<String> headings) { return headings.isEmpty() ? null : String.join(" > ", headings); }
    /**
     * 空值安全地比较两个章节路径是否相同。
     *
     * <p>两个都为 null 视为相同（都不属于任何章节，可以合并）。用工具方法而不是直接 equals，
     * 是因为无章节的块非常常见，直接调用会空指针。</p>
     */
    private boolean same(String left, String right) { return java.util.Objects.equals(left, right); }
    /**
     * 预演「把一个子块加入当前父块分组」之后的父块正文。
     *
     * <p>只用于判断是否超预算，不产生任何实际状态改变。分组为空时结果就是这个子块自己。
     * 拼接用空行分隔，和最终生成父块正文时的规则保持一致——否则预演算出的长度和实际长度会不一样，
     * 导致父块偶尔超预算。</p>
     */
    private String joinDrafts(List<Draft> values, Draft addition) {
        // 分组还是空的，加入后的内容就是这个子块本身。
        if (values.isEmpty()) return addition.content();
        // 把已有成员用空行拼起来，再用空行接上新成员；分隔规则必须和最终生成父块正文时完全一致。
        return values.stream().map(Draft::content).collect(java.util.stream.Collectors.joining("\n\n"))
                + "\n\n" + addition.content();
    }

    /**
     * 生成可复现的分块编号。
     *
     * <p>输入是「来源标识 + 层级 + 序号 + 内容摘要」，中间用空字符分隔以避免拼接歧义
     * （否则「来源 ab + 层级 c」和「来源 a + 层级 bc」会算出同一个编号）。
     * 取摘要前 48 位十六进制字符，既足够避免碰撞，又不至于让编号长到难用。</p>
     *
     * <p>四个输入各自的作用：来源保证不同文档不冲突；层级区分父块和子块；
     * 序号让内容相同但位置不同的块也有不同编号；内容摘要保证内容一变编号就变。</p>
     *
     * <p>纯计算，不含随机数和时间戳，所以同一份文档重复切块得到完全一样的编号，
     * 重复摄取不会产生重复数据，历史引用也能长期指向同一段内容。</p>
     */
    private String stableId(String sourceId, String level, int ordinal, String contentHash) {
        // 用 chk_ 前缀标明这是分块编号，后面接四项输入的摘要前 48 位。
        return "chk_" + sha256(sourceId + '\0' + level + '\0' + ordinal + '\0' + contentHash).substring(0, 48);
    }
    /**
     * 计算 SHA-256 摘要，用于内容指纹和分块编号。
     *
     * <p>跨进程、跨机器、跨 JVM 版本结果完全一致，这是分块编号可复现的基础。</p>
     */
    private String sha256(String value) {
        // SHA-256 是 JDK 必备算法，实际不会缺失，但接口签名要求处理异常，所以包一层。
        try {
            // 按 UTF-8 取字节算摘要再转十六进制；显式指定编码，保证含中文的内容在任何环境算出同一个摘要。
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("JVM缺少SHA-256", e); }
    }

    /**
     * 分块的层级：父块提供扩展上下文，子块承担精确召回。
     *
     * <p>检索时只对子块做向量匹配，命中后再顺着父块编号把完整章节取出来送进模型。</p>
     */
    public enum Level { PARENT, CHILD }
    /**
     * Markdown 结构类型，影响子块能否与相邻内容合并。
     *
     * <p>段落和列表可以和相邻内容合并；代码块和表格必须独立成块，因为它们的语义自成一体，
     * 混进别的内容会让向量表示被两种不同内容拉扯。</p>
     */
    private enum BlockType { PARAGRAPH, LIST, TABLE, CODE }
    /**
     * 从 Markdown 解析出来的最小结构单位：一段完整的正文、列表、表格或代码。
     *
     * <p>带着它所属的章节路径和页码，这两项会一路传递到最终分块，用于引用时精确定位。
     * 不可变值对象，不涉及持久化。</p>
     */
    private record Block(String content, String heading, Integer page, BlockType type) {}
    /**
     * 已经合并到位、但还没分配稳定编号的子块。
     *
     * <p>字段与结构块相同。之所以单独一个类型，是为了在类型上区分「原始结构块」和「已按预算合并的子块」，
     * 避免两个阶段的数据被混用。不可变值对象，不涉及持久化。</p>
     */
    private record Draft(String content, String heading, Integer page, BlockType type) {}
    /**
     * 一个父块分组：同一章节、且合起来不超父块预算的若干相邻子块。
     *
     * <p>它是父块的雏形，父块正文就是成员内容的拼接。不可变值对象，不涉及持久化。</p>
     */
    private record ParentGroup(List<Draft> children) {
        /**
         * 拼出这个分组对应的父块正文。
         *
         * <p>用空行连接各成员，与预演合并时的规则完全一致，保证预算判断和实际结果不会出现偏差。</p>
         */
        String content() { return children.stream().map(Draft::content)
                .collect(java.util.stream.Collectors.joining("\n\n")); }
    }
    /**
     * 分块的中间态：内容和编号都已确定，但前后相邻关系还没补上。
     *
     * <p>需要这个中间态是因为相邻关系必须等所有块都生成完才能确定（前一块和后一块可能属于不同父块）。
     * 不可变值对象，不涉及持久化。</p>
     */
    private record Partial(Level level, String chunkId, String parentChunkId, String content,
                           int tokenCount, Integer page, String heading, String contentHash) {}

    /**
     * 父子分块的预算配置：子块和父块各自的字符与 Token 上限，以及硬切时的重叠长度。
     *
     * <p>构造时会校验一组约束：子块字符不少于 16、子块 Token 不少于 4（太小切不出有意义的内容）；
     * 父块预算不能小于子块预算（父块要装得下至少一个子块）；重叠不能为负；
     * 重叠必须小于子块字符预算的一半——否则每次滑窗回退掉的内容超过一半，游标推进会极其缓慢甚至停滞。</p>
     *
     * <p>不可变值对象，不涉及持久化。</p>
     */
    public record Config(int childMaxChars, int childMaxTokens, int parentMaxChars,
                         int parentMaxTokens, int overlapChars) {
        /**
         * 构造时校验预算配置自洽，非法配置一律拒绝进入切分流程。
         *
         * <p>宁可在构造阶段失败，也不允许一份矛盾的配置让切分产出畸形的块或陷入死循环。</p>
         */
        public Config {
            // 六项约束任一不满足就拒绝——
            // 子块字符预算太小（切不出有意义的内容）；
            // 子块 Token 预算太小（同上）；
            // 父块字符预算小于子块（父块装不下一个子块，父子结构就没意义了）；
            // 父块 Token 预算小于子块（同上）；
            // 重叠为负（没有意义）；
            // 重叠达到或超过子块字符预算的一半（每片回退超过一半，滑窗推进会极慢甚至停滞）。
            if (childMaxChars < 16 || childMaxTokens < 4 || parentMaxChars < childMaxChars
                    || parentMaxTokens < childMaxTokens || overlapChars < 0
                    || overlapChars >= childMaxChars / 2) {
                // 直接抛非法参数异常拒绝构造，把问题挡在切分开始之前。
                throw new IllegalArgumentException("分块字符、Token或重叠配置非法");
            }
        }
        /**
         * 平台默认预算：子块 1800 字符 / 480 Token，父块 6000 字符 / 1500 Token，重叠 160 字符。
         *
         * <p>子块控制在几百 Token 是为了让向量语义足够聚焦；父块给到一千多 Token 是为了装下一个完整小节；
         * 160 字符的重叠大约是两三句话，足够让被切断的句子在某一片里保持完整。</p>
         */
        public static Config defaults() { return new Config(1800, 480, 6000, 1500, 160); }
    }

    /**
     * 对外输出的一个分块，包含内容本身和全部溯源信息。
     *
     * <p>字段用途：层级区分父子；自身编号和父编号构成父子关系；前后相邻编号供检索时扩展上下文；
     * 序号是它在整份文档里的位置；Token 数供上下文预算核算；页码和章节路径供引用时精确定位；
     * 内容摘要供事后校验内容是否被改动；元数据记录生成它的算法版本和层级。</p>
     *
     * <p>不可变值对象。它会被写进分块表并向量化，是检索链路真正读到的数据结构。</p>
     */
    public record StructuredChunk(Level level, String chunkId, String parentChunkId,
                                  String previousChunkId, String nextChunkId, int chunkIndex,
                                  String content, int tokenCount, Integer pageNumber,
                                  String headingPath, String contentHash, Map<String, String> metadata) {}
    /**
     * 一次切分的完整结果，含全部父块和子块。
     *
     * <p>提供按层级取子集的便捷方法：子块交给向量化和召回，父块用于命中后扩展上下文。
     * 构造时复制列表，阻断外部修改。</p>
     */
    public record ChunkingResult(List<StructuredChunk> chunks) {
        /**
         * 构造时把列表冻结成不可变副本。
         *
         * <p>null 按空列表处理，避免调用方还要判空；复制则防止外部拿着原列表继续增删，
         * 导致已经产出的分块结果被悄悄改掉。</p>
         */
        public ChunkingResult { chunks = chunks == null ? List.of() : List.copyOf(chunks); }
        /**
         * 取出承担向量召回的子块。
         *
         * <p>只有子块会被向量化并参与相似度匹配，因为它们粒度小、语义聚焦，检索更准。</p>
         */
        public List<StructuredChunk> children() {
            // 流式筛选出层级为子块的分块，保持原有顺序。
            return chunks.stream().filter(chunk -> chunk.level() == Level.CHILD).toList();
        }
        /**
         * 取出用于扩展上下文的父块。
         *
         * <p>父块不参与向量匹配，只在子块命中之后按父编号取出来，把完整章节送进模型。</p>
         */
        public List<StructuredChunk> parents() {
            // 流式筛选出层级为父块的分块，保持原有顺序。
            return chunks.stream().filter(chunk -> chunk.level() == Level.PARENT).toList();
        }
    }
}
