package cn.bugstack.ai.domain.context.service;

/**
 * 保守字符 token 预估器。
 * <p>中文字符按一个 token 估算，其余字符按四字符一个 token 估算。</p>
 */
public class CharacterTokenCounter implements TokenCounter {

    /**
     * 预估文本 token 数；参数是文本；返回保守估算值。
     */
    @Override
    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            // 中文按一字符一 Token 保守估计，其他文本按常见四字符一 Token。
            if (value >= 0x4E00 && value <= 0x9FFF) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (int) Math.ceil(other / 4.0D);
    }
}
