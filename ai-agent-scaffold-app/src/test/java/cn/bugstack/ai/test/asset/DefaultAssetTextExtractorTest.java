package cn.bugstack.ai.test.asset;

import cn.bugstack.ai.domain.asset.model.AssetParseResultEntity;
import cn.bugstack.ai.infrastructure.asset.DefaultAssetTextExtractor;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * 默认附件解析器测试。
 */
public class DefaultAssetTextExtractorTest {

    @Test
    public void shouldExtractTextAndKeepImageReadyWithoutPromptText() {
        DefaultAssetTextExtractor extractor = new DefaultAssetTextExtractor();
        AssetParseResultEntity text = extractor.extract("readme.md", "text/markdown",
                "hello".getBytes(StandardCharsets.UTF_8));
        AssetParseResultEntity image = extractor.extract("photo.png", "image/png", new byte[]{1, 2, 3});

        Assert.assertEquals("ready", text.getParseStatus());
        Assert.assertEquals("hello", text.getExtractedText());
        Assert.assertEquals("unsupported", image.getParseStatus());
        Assert.assertNull(image.getExtractedText());
    }

    @Test
    public void shouldRejectUnsupportedBinaryFromPrompt() {
        AssetParseResultEntity result = new DefaultAssetTextExtractor().extract("archive.zip",
                "application/zip", new byte[]{1, 2, 3});
        Assert.assertEquals("unsupported", result.getParseStatus());
        Assert.assertNull(result.getExtractedText());
    }
}
