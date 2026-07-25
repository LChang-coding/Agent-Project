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
 * 无语料库依赖的确定性稀疏编码器。
 * <p>英文按 Unicode 词切分，中文生成单字和双字词项。词项通过带 vocabulary revision
 * 的 64 位 FNV-1a 映射到固定维度；碰撞时累加 log-TF 权重，最后做 L2 归一化。</p>
 */
public final class DeterministicSparseEncoder implements SparseEncoderPort {

    /** 默认哈希桶数量；空间固定，避免为每个租户维护词表。 */
    public static final int DEFAULT_DIMENSION = 1 << 20;
    /** 参与哈希的算法版本，升级算法时隔离新旧索引。 */
    public static final String VOCABULARY_REVISION = "hashing-logtf-v1";
    /** 当前实例使用的哈希桶数量。 */
    private final int dimension;

    /** 使用平台默认维度。 */
    public DeterministicSparseEncoder() {
        this(DEFAULT_DIMENSION);
    }

    /** 使用指定维度；过小维度会显著放大哈希碰撞。 */
    public DeterministicSparseEncoder(int dimension) {
        if (dimension < 1024) throw new IllegalArgumentException("稀疏向量维度不能小于1024");
        this.dimension = dimension;
    }

    /** 保持输入顺序，批量生成可复现的稀疏向量。 */
    @Override
    public SparseEncodingResult encode(SparseEncodingCommand command) {
        List<SparseVector> vectors = command.inputs().stream()
                .map(input -> encodeOne(input, command.vocabularyRevision())).toList();
        return new SparseEncodingResult(vectors, command.vocabularyRevision());
    }

    /** 计算 log-TF 权重，合并哈希碰撞后做 L2 归一化。 */
    private SparseVector encodeOne(String input, String revision) {
        Map<String, Integer> termFrequency = new HashMap<>();
        for (String term : tokenize(input)) termFrequency.merge(term, 1, Integer::sum);
        Map<Integer, Double> collided = new HashMap<>();
        termFrequency.forEach((term, frequency) -> collided.merge(index(revision, term),
                1D + Math.log(frequency), Double::sum));
        double norm = Math.sqrt(collided.values().stream().mapToDouble(value -> value * value).sum());
        Map<Integer, Float> normalized = new TreeMap<>();
        collided.forEach((index, weight) -> normalized.put(index, (float) (weight / norm)));
        return new SparseVector(normalized);
    }

    /** 将英文词、CJK 单字/双字和非空白符号拆成带类型前缀的词项。 */
    private List<String> tokenize(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        normalized.codePoints().forEach(codePoint -> {
            if (isCjk(codePoint)) {
                flushWord(word, terms);
                cjk.appendCodePoint(codePoint);
            } else {
                flushCjk(cjk, terms);
                if (Character.isLetterOrDigit(codePoint)) {
                    word.appendCodePoint(codePoint);
                } else {
                    flushWord(word, terms);
                    if (!Character.isWhitespace(codePoint)) terms.add("s:" + new String(Character.toChars(codePoint)));
                }
            }
        });
        flushWord(word, terms);
        flushCjk(cjk, terms);
        return terms;
    }

    /** 提交一个连续的拉丁字母或数字词。 */
    private void flushWord(StringBuilder word, List<String> terms) {
        if (!word.isEmpty()) {
            terms.add("w:" + word);
            word.setLength(0);
        }
    }

    /** 同时提交 CJK 单字与相邻双字，兼顾精确匹配和短语召回。 */
    private void flushCjk(StringBuilder value, List<String> terms) {
        if (value.isEmpty()) return;
        int[] points = value.codePoints().toArray();
        for (int point : points) terms.add("c:" + new String(Character.toChars(point)));
        for (int i = 0; i + 1 < points.length; i++) {
            terms.add("b:" + new String(points, i, 2));
        }
        value.setLength(0);
    }

    /** 识别需要采用字符级切分的东亚文字。 */
    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    /** 将算法版本和词项稳定映射到非负桶下标。 */
    private int index(String revision, String term) {
        byte[] bytes = (revision + '\0' + term).getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte value : bytes) {
            hash ^= value & 0xffL;
            hash *= 0x100000001b3L;
        }
        return (int) Math.floorMod(hash, dimension);
    }
}
