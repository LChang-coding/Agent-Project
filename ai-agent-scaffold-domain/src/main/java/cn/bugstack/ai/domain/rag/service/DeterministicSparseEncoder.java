package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.port.SparseEncoderPort;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 不依赖租户词表或语料统计的确定性稀疏向量编码器。
 * <p>编码器将规范化词项映射到固定维度的哈希空间，使索引与查询在相同算法版本和维度下
 * 生成一致向量。实现无可变运行状态，不读取数据库或外部服务。</p>
 */
public final class DeterministicSparseEncoder implements SparseEncoderPort {

    /**
     * 默认哈希桶数量，约一百万维（2 的 20 次方）。
     *
     * <p>维度固定的意义在于：不需要为任何租户维护词表，新词直接落进已有的桶里，索引结构永远不用扩容。
     * 代价是不同词项可能撞进同一个桶（哈希碰撞），维度越大碰撞越少。</p>
     */
    public static final int DEFAULT_DIMENSION = 1 << 20;
    /**
     * 参与哈希计算的算法版本号。
     *
     * <p>它会被拼进哈希输入，所以版本号一变，所有词项的落桶位置全部改变。
     * 这正是想要的效果：升级切词或加权算法后，新向量和旧索引自然处在两个互不相干的空间里，
     * 不会出现「一半旧向量一半新向量」的脏索引。换算法时必须同时改这个值并重建索引。</p>
     */
    public static final String VOCABULARY_REVISION = "hashing-logtf-v1";
    /**
     * 本实例实际使用的桶数量，构造后不再变化。
     *
     * <p>它决定了输出向量的下标范围。同一批索引和查询必须使用同一维度，否则下标含义不同、算出的相似度毫无意义。</p>
     */
    private final int dimension;

    /**
     * 用平台默认维度创建编码器，生产装配走的就是这个入口。
     */
    public DeterministicSparseEncoder() {
        // 转调带维度的构造方法，保证默认路径和自定义路径共用同一套校验逻辑。
        this(DEFAULT_DIMENSION);
    }

    /**
     * 用指定维度创建编码器，主要给测试和调参用。
     *
     * <p>维度太小会让大量不同的词撞进同一个桶，稀疏向量就失去区分能力，检索结果接近随机，
     * 所以这里设了 1024 的下限直接拒绝明显不可用的配置。</p>
     */
    public DeterministicSparseEncoder(int dimension) {
        // 维度低于 1024 时哈希碰撞会严重到让向量失去意义，宁可构造失败也不要产出一堆废向量。
        if (dimension < 1024) throw new IllegalArgumentException("稀疏向量维度不能小于1024");
        // 记下维度，后续所有落桶计算都以它取模。
        this.dimension = dimension;
    }

    /**
     * 批量编码，输出顺序与输入顺序严格一致。
     *
     * <p>业务职责：把一批文本（分块正文或用户问题）逐条转成稀疏向量。
     * 顺序必须保持，因为调用方是按下标把向量和分块对应起来的，错位就等于把 A 的向量写给了 B。</p>
     *
     * <p>数据流：文本列表 → 逐条切词与加权编码 → 稀疏向量列表 → 连同算法版本一起返回。</p>
     *
     * <p>返回结果里带回算法版本号，调用方会把它一起写进索引，日后可以据此判断某批向量是哪套算法生成的。</p>
     *
     * <p>不写库、不调外部服务；纯计算，可重复执行且结果完全一致。</p>
     */
    @Override
    public SparseEncodingResult encode(SparseEncodingCommand command) {
        // 流式逐条编码：输入文本流 → 每条按算法版本编成一个稀疏向量 → 收成列表。
        // 用 map 而不是并行流，正是为了保证输出顺序和输入下标一一对应。
        List<SparseVector> vectors = command.inputs().stream()
                .map(input -> encodeOne(input, command.vocabularyRevision())).toList();
        // 把向量列表和算法版本一起返回；版本号会随索引落库，作为日后判断向量兼容性的依据。
        return new SparseEncodingResult(vectors, command.vocabularyRevision());
    }

    /**
     * 把一段文本编成一个 L2 归一化的稀疏向量。
     *
     * <p>各层职责：
     * 第一层：切词并统计每个词项在这段文本里出现了几次；
     * 第二层：把词项哈希到桶下标，权重用 log 形式的词频（出现越多权重越高，但增长越来越慢），
     * 撞进同一个桶的不同词项权重直接相加；
     * 第三层：算出整个向量的长度，把每一维除以长度做归一化；
     * 第四层：用有序映射输出，保证同样输入得到逐字节一致的结果。</p>
     *
     * <p>数据流：
     * 文本 → 切词 → 词项计数
     * → 逐词项哈希到桶下标，权重 1 + ln(词频)
     * → 同桶权重累加（哈希碰撞的处理方式）
     * → 计算 L2 长度
     * → 每维除以长度归一化
     * → 按桶下标升序输出稀疏向量</p>
     *
     * <p>为什么要用 log 词频：某个词重复一百次，并不代表这段文字比出现十次的相关性高十倍。
     * 取对数能压住高频词的影响，避免一个刷了满屏的词把整个向量方向带偏。</p>
     *
     * <p>为什么要 L2 归一化：归一化之后向量长度都是 1，两个向量的点积就等于余弦相似度，
     * 长文档不会因为词多就天然得高分，短文档也不会被压死。</p>
     *
     * <p>纯计算，不写库。需要注意：若这段文本一个词项都切不出来（例如全是空白），
     * 词频表为空，L2 长度会是 0，此时映射为空所以不会真的做除法，返回的是一个零维稀疏向量，
     * 上层需要能接受「空向量」这种输入。</p>
     */
    private SparseVector encodeOne(String input, String revision) {
        // 先统计词频：键是词项，值是它在这段文本里出现的次数。
        Map<String, Integer> termFrequency = new HashMap<>();
        // 逐个词项累加计数；merge 在首次出现时写 1，之后每次加 1。
        for (String term : tokenize(input)) termFrequency.merge(term, 1, Integer::sum);
        // 再建「桶下标 → 累计权重」的映射。之所以要单独一层，是因为不同词项可能哈希到同一个桶，
        // 必须在这一层把碰撞合并掉，而不是让后一个词把前一个词的权重覆盖掉。
        Map<Integer, Double> collided = new HashMap<>();
        // 逐个词项算落桶下标并累加权重：权重取 1 + ln(词频)，词频为 1 时正好是 1，
        // 之后随出现次数缓慢增长；同桶用相加合并，这就是哈希碰撞的处理策略。
        termFrequency.forEach((term, frequency) -> collided.merge(index(revision, term),
                1D + Math.log(frequency), Double::sum));
        // 算整个向量的 L2 长度（各维平方和再开根号），下一步用它把向量缩放到单位长度。
        double norm = Math.sqrt(collided.values().stream().mapToDouble(value -> value * value).sum());
        // 用按键排序的映射承接归一化结果，保证输出的稀疏向量下标始终升序，
        // 这样同样的输入会得到逐字节相同的结果，索引可复现、测试可断言。
        Map<Integer, Float> normalized = new TreeMap<>();
        // 逐维除以 L2 长度完成归一化，并降精度存成 float：稀疏向量维度多，用 float 能省一半内存，
        // 而检索排序对这点精度损失不敏感。
        collided.forEach((index, weight) -> normalized.put(index, (float) (weight / norm)));
        // 返回归一化后的稀疏向量，交给上层写索引或做相似度计算。
        return new SparseVector(normalized);
    }

    /**
     * 把一段文本切成带类型前缀的词项列表，中英文分别用不同策略。
     *
     * <p>各层职责：
     * 第一层：先做 Unicode 兼容归一化并转小写，让全角半角、各种等价写法统一成同一种形式；
     * 第二层：逐个字符扫描，遇到中日韩文字就攒进 CJK 缓冲，遇到字母数字就攒进英文单词缓冲；
     * 第三层：两种缓冲互相打断时立即提交对方，保证「中文夹英文」这种混排不会把词粘在一起；
     * 第四层：非字母数字且非空白的符号单独成一个词项；
     * 第五层：扫描结束后把两个缓冲里剩下的内容补提交，避免结尾的词被丢掉。</p>
     *
     * <p>数据流：
     * 原始文本 → NFKC 归一化 + 转小写
     * → 逐码点扫描（CJK 攒缓冲 / 字母数字攒单词 / 符号直接成词）
     * → 类型切换时提交对应缓冲
     * → 收尾提交残留缓冲
     * → 带前缀的词项列表</p>
     *
     * <p>为什么词项要带 w:／c:／b:／s: 前缀：英文单词、中文单字、中文双字、符号如果不加前缀，
     * 就可能因为字面相同而被当成同一个词哈希到一起，把不同粒度的匹配信号混在一块。
     * 加了前缀，各类词项在向量空间里彼此独立。</p>
     *
     * <p>为什么先做 NFKC 归一化：同一个字可能有全角半角、组合与预组合等多种编码写法。
     * 不归一化，索引里存的是一种写法、查询用的是另一种写法，字面完全一样却永远匹配不上。</p>
     *
     * <p>纯计算，不写库。</p>
     */
    private List<String> tokenize(String input) {
        // 第一层：NFKC 兼容归一化把全角、上下标、连字等等价写法折叠成标准形式，再统一转小写；
        // 用固定的 ROOT 语区而不是系统默认语区，避免同一段文字在不同机器上转出不同结果（例如土耳其语的 i）。
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        // 收集最终切出的所有词项，顺序即扫描顺序。
        List<String> terms = new ArrayList<>();
        // 英文单词缓冲：连续的字母数字先攒在这里，遇到边界时整体提交。
        StringBuilder word = new StringBuilder();
        // 中日韩文字缓冲：这类文字词间没有空格，要按字符攒起来再切单字和双字。
        StringBuilder cjk = new StringBuilder();
        // 第二层：按码点（而不是按 char）逐个扫描，这样超出基本平面的生僻字和表情符也不会被拆成两半。
        normalized.codePoints().forEach(codePoint -> {
            // 遇到中日韩文字：说明当前不是英文单词的一部分。
            if (isCjk(codePoint)) {
                // 先把攒着的英文单词提交掉，否则「AI智能」会把 ai 和后面的中文粘成一个词项。
                flushWord(word, terms);
                // 再把这个字攒进 CJK 缓冲，等遇到非 CJK 字符时统一切成单字和双字。
                cjk.appendCodePoint(codePoint);
            } else {
                // 走到非 CJK 分支，先把攒着的中文提交掉，否则中文和紧跟的英文会被误当成一个整体。
                flushCjk(cjk, terms);
                // 字母或数字属于英文单词的一部分（包括数字和型号），继续往单词缓冲里攒。
                if (Character.isLetterOrDigit(codePoint)) {
                    // 把这个字符接到当前英文单词后面，等遇到边界再整体提交。
                    word.appendCodePoint(codePoint);
                } else {
                    // 既不是 CJK 也不是字母数字，说明是空白或标点，此处正是英文单词的边界，先把单词提交。
                    flushWord(word, terms);
                    // 空白只用来分词、本身不构成词项；其余标点符号单独成一个 s: 词项，
                    // 因为像 C++、#、% 这类符号本身携带检索意义，全部丢掉会让这类查询召不回来。
                    if (!Character.isWhitespace(codePoint)) terms.add("s:" + new String(Character.toChars(codePoint)));
                }
            }
        });
        // 第五层：扫描结束，补提交残留的英文单词，否则以字母结尾的文本最后一个词会丢。
        flushWord(word, terms);
        // 同样补提交残留的中文缓冲，否则以中文结尾的文本最后几个字会丢。
        flushCjk(cjk, terms);
        // 返回完整词项列表，交给上层统计词频。
        return terms;
    }

    /**
     * 提交一个攒好的英文单词，并清空缓冲。
     *
     * <p>加 w: 前缀标明这是英文/数字词项，避免和中文词项、符号词项撞在同一个哈希空间里。
     * 缓冲为空时什么都不做，所以可以在任意边界放心调用。</p>
     */
    private void flushWord(StringBuilder word, List<String> terms) {
        // 缓冲为空说明这里没有待提交的单词（例如连续两个空格），直接跳过，不产生空词项。
        if (!word.isEmpty()) {
            // 加 w: 前缀后收进词项列表，前缀保证英文词和同形的中文词、符号词彼此独立。
            terms.add("w:" + word);
            // 清空缓冲，准备接收下一个单词；复用同一个 StringBuilder 是为了避免逐词新建对象。
            word.setLength(0);
        }
    }

    /**
     * 把攒好的中日韩文字同时切成单字和相邻双字，然后清空缓冲。
     *
     * <p>为什么要两种粒度都出：只切单字，查「深度学习」会被拆成四个字，任何含这些字的文档都可能命中，精度太差；
     * 只切双字，单字查询（例如查一个人名的姓）就完全召不回。两种粒度同时进向量，
     * 既保住了单字的召回能力，也用双字提供了短语级的区分度。</p>
     *
     * <p>c: 前缀标记单字，b: 前缀标记双字，前缀不同保证两种粒度在向量空间里互不干扰。</p>
     */
    private void flushCjk(StringBuilder value, List<String> terms) {
        // 缓冲为空说明这段没有中文可提交，直接返回，避免产出空词项。
        if (value.isEmpty()) return;
        // 先转成码点数组，后面按码点取单字和双字；用码点而不是 char，生僻字才不会被拆坏。
        int[] points = value.codePoints().toArray();
        // 第一种粒度：每个字单独成一个 c: 词项，保证单字查询也能命中。
        for (int point : points) terms.add("c:" + new String(Character.toChars(point)));
        // 第二种粒度：滑动窗口取每一对相邻字。循环到倒数第二个字为止，保证窗口不会越界。
        for (int i = 0; i + 1 < points.length; i++) {
            // 相邻两字拼成一个 b: 词项，为短语匹配提供比单字强得多的区分度。
            terms.add("b:" + new String(points, i, 2));
        }
        // 清空缓冲，准备接收下一段中文。
        value.setLength(0);
    }

    /**
     * 判断一个字符属不属于「需要按字切分」的东亚文字。
     *
     * <p>涵盖汉字、日文平假名与片假名、韩文。这些文字词与词之间没有空格，
     * 按空白切词会把一整句话当成一个词，所以必须走字符级切分。</p>
     */
    private boolean isCjk(int codePoint) {
        // 取这个码点所属的文字体系（script），比逐个判断 Unicode 区间更准确也更好维护。
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        // 四种文字体系任一命中就走字符级切分：汉字、平假名、片假名、韩文；
        // 它们的共同点是词间无空格，因此不能按空白分词。
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    /**
     * 把「算法版本 + 词项」稳定映射成一个非负桶下标。
     *
     * <p>各层职责：
     * 第一层：把版本号和词项拼成一个字节串，中间用 \0 隔开；
     * 第二层：跑 64 位 FNV-1a 哈希，得到一个分布均匀的整数；
     * 第三层：对维度取模（用 floorMod 保证结果非负），得到最终桶下标。</p>
     *
     * <p>数据流：算法版本 + \0 + 词项 → UTF-8 字节 → FNV-1a 64 位哈希 → floorMod(维度) → 桶下标</p>
     *
     * <p>为什么中间要塞一个 \0：如果直接拼接，「版本 ab + 词 c」和「版本 a + 词 bc」会得到同一个字节串，
     * 落进同一个桶。用词项里不可能出现的 \0 做分隔符，就杜绝了这种歧义。</p>
     *
     * <p>为什么必须用 floorMod 而不是 %：哈希值是有符号 64 位，很可能是负数，
     * 用 % 会算出负下标直接把向量写坏。floorMod 永远返回非负值。</p>
     *
     * <p>为什么用固定的 FNV-1a 而不是 String.hashCode：这套常量和运算写死在代码里，
     * 跨 JVM、跨版本、跨机器结果完全一致，索引和查询才能永远落在同一个桶。</p>
     *
     * <p>纯计算，不写库、不调外部服务。</p>
     */
    private int index(String revision, String term) {
        // 第一层：算法版本 + 空字符分隔符 + 词项，一起按 UTF-8 编成字节；
        // 指定 UTF-8 而不是平台默认编码，避免同一个中文词在不同环境编出不同字节、落进不同桶。
        byte[] bytes = (revision + '\0' + term).getBytes(StandardCharsets.UTF_8);
        // 第二层：FNV-1a 的 64 位初始偏移量，算法固定值，不能改动，改了就等于换了一套哈希空间。
        long hash = 0xcbf29ce484222325L;
        // 逐字节混入哈希；FNV-1a 的特点是先异或、再乘质数。
        for (byte value : bytes) {
            // 先异或：与 0xff 相与是为了把有符号 byte 当成 0~255 的无符号值，否则负字节会把高位全部搅乱。
            hash ^= value & 0xffL;
            // 再乘 FNV 质数，让每个字节的影响迅速扩散到全部 64 位，得到均匀分布。
            hash *= 0x100000001b3L;
        }
        // 第三层：对维度取模拿到桶下标。floorMod 保证结果非负，哈希值为负数时也不会算出非法下标。
        return (int) Math.floorMod(hash, dimension);
    }
}
