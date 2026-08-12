package cn.bugstack.ai.test.agent;

import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/** 子任务状态查询不得将无界历史全部送入模型上下文。 */
public class SubagentTaskMapperContractTest {
    @Test
    public void queryByIdsMustHaveServerSideLimitWhenTaskIdsAreOmitted() throws Exception {
        Path mapper = Path.of("../ai-agent-scaffold-infrastructure/src/main/resources/mybatis/mapper/subagent_task_mapper.xml");
        if (!Files.exists(mapper)) mapper = Path.of("ai-agent-scaffold-infrastructure/src/main/resources/mybatis/mapper/subagent_task_mapper.xml");
        String xml = Files.readString(mapper);
        int queryStart = xml.indexOf("<select id=\"queryByIds\"");
        int queryEnd = xml.indexOf("</select>", queryStart);
        Assert.assertTrue(queryStart >= 0 && queryEnd > queryStart);
        Assert.assertTrue(xml.substring(queryStart, queryEnd).contains("LIMIT 100"));
    }
}
