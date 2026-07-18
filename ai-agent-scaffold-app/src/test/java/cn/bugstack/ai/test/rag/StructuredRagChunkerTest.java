package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.adapter.port.RagDocumentParserPort;
import cn.bugstack.ai.domain.rag.service.StructuredRagChunker;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Random;

/** 结构化分块器属性与边界测试。 */
public class StructuredRagChunkerTest {

    private final StructuredRagChunker chunker = new StructuredRagChunker();

    @Test
    public void shouldReturnEmptyResultForEmptyDocument() {
        Assert.assertTrue(chunker.chunk("version-1", "  \n\t", List.of(), config()).chunks().isEmpty());
    }

    @Test
    public void shouldPreserveChineseHeadingPageAndDeterministicHashes() {
        var section = new RagDocumentParserPort.ParsedSection("员工手册 > 假期",
                "年假申请需要提前三个工作日提交。\n\n审批通过后方可休假。", 3, 0);

        var first = chunker.chunk("version-1", "fallback", List.of(section), config());
        var second = chunker.chunk("version-1", "fallback", List.of(section), config());

        Assert.assertEquals(first, second);
        Assert.assertFalse(first.children().isEmpty());
        Assert.assertEquals("员工手册 > 假期", first.children().get(0).headingPath());
        Assert.assertEquals(Integer.valueOf(3), first.children().get(0).pageNumber());
        Assert.assertEquals(64, first.children().get(0).contentHash().length());
    }

    @Test
    public void shouldKeepTableAndCodeFenceAsIndependentStructuralChunks() {
        String markdown = "# 技术手册\n\n| 参数 | 含义 |\n| --- | --- |\n| timeout | 超时 |\n"
                + "\n```java\nvoid run() {\n  execute();\n}\n```\n";

        var result = chunker.chunk("version-table", markdown, List.of(),
                new StructuredRagChunker.Config(300, 100, 800, 250, 20));

        Assert.assertTrue(result.children().stream().anyMatch(chunk ->
                chunk.content().contains("| --- | --- |") && chunk.content().contains("timeout")));
        Assert.assertTrue(result.children().stream().anyMatch(chunk ->
                chunk.content().startsWith("```java") && chunk.content().endsWith("```")));
    }

    @Test
    public void shouldSafelySplitOversizedParagraphWithinCharacterAndTokenBudgets() {
        String text = "超长段落".repeat(40) + "😀".repeat(30) + "后续内容".repeat(40) + " end-marker";
        var result = chunker.chunk("version-long", text, List.of(),
                new StructuredRagChunker.Config(80, 40, 240, 120, 12));

        Assert.assertTrue(result.children().size() > 2);
        for (var chunk : result.children()) {
            Assert.assertTrue(chunk.content().length() <= 80);
            Assert.assertTrue(chunk.tokenCount() <= 40);
            Assert.assertFalse(Character.isHighSurrogate(chunk.content().charAt(chunk.content().length() - 1)));
            Assert.assertFalse(Character.isLowSurrogate(chunk.content().charAt(0)));
        }
        String firstContent = result.children().get(0).content();
        Assert.assertTrue(result.children().get(1).content()
                .startsWith(firstContent.substring(firstContent.length() - 12)));
        Assert.assertTrue(result.children().get(result.children().size() - 1).content().contains("end-marker"));
    }

    @Test
    public void shouldCreateParentAndBidirectionalChildAdjacency() {
        String markdown = "# 章节\n\n第一段较长的内容。\n\n第二段较长的内容。\n\n第三段较长的内容。";
        var result = chunker.chunk("version-links", markdown, List.of(),
                new StructuredRagChunker.Config(16, 12, 80, 60, 2));

        Assert.assertTrue(result.children().size() >= 3);
        var first = result.children().get(0);
        var second = result.children().get(1);
        Assert.assertNull(first.previousChunkId());
        Assert.assertEquals(second.chunkId(), first.nextChunkId());
        Assert.assertEquals(first.chunkId(), second.previousChunkId());
        Assert.assertTrue(result.parents().stream().anyMatch(parent -> parent.chunkId().equals(first.parentChunkId())));
    }

    @Test
    public void shouldNeverExceedConfiguredBudgetForRandomMixedText() {
        Random random = new Random(20260718L);
        var config = new StructuredRagChunker.Config(96, 32, 288, 96, 8);
        for (int sample = 0; sample < 100; sample++) {
            StringBuilder text = new StringBuilder();
            int length = 50 + random.nextInt(500);
            for (int i = 0; i < length; i++) {
                int choice = random.nextInt(5);
                text.append(choice == 0 ? '知' : choice == 1 ? '识' : choice == 2 ? 'a' : choice == 3 ? ' ' : '.');
            }
            var result = chunker.chunk("property-" + sample, text.toString(), List.of(), config);
            for (var child : result.children()) {
                Assert.assertTrue(child.content().length() <= config.childMaxChars());
                Assert.assertTrue(child.tokenCount() <= config.childMaxTokens());
            }
            for (var parent : result.parents()) {
                Assert.assertTrue(parent.content().length() <= config.parentMaxChars());
                Assert.assertTrue(parent.tokenCount() <= config.parentMaxTokens());
            }
        }
    }

    private StructuredRagChunker.Config config() {
        return new StructuredRagChunker.Config(120, 50, 400, 160, 12);
    }
}
