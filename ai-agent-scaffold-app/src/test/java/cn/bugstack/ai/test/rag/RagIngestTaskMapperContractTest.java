package cn.bugstack.ai.test.rag;

import org.junit.Assert;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** RAG摄取任务Mapper的强租户与有界列表契约测试。 */
public class RagIngestTaskMapperContractTest {

    @Test
    public void shouldKeepKnowledgeBaseTaskListTenantScopedOrderedAndBounded() throws Exception {
        String resource = "mybatis/mapper/rag_ingest_task_mapper.xml";
        try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            Assert.assertNotNull("找不到RAG摄取任务Mapper", stream);
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").toLowerCase();
            String marker = "id=\"querylistbytenantandknowledgebaseid\"";
            int start = xml.indexOf(marker);
            Assert.assertTrue("缺少知识库任务列表查询", start >= 0);
            int end = xml.indexOf("</select>", start);
            String query = xml.substring(start, end);
            Assert.assertTrue(query.contains("tenant_id = #{tenantid}"));
            Assert.assertTrue(query.contains("kb_id = #{knowledgebaseid}"));
            Assert.assertTrue(query.contains("deleted = 0"));
            Assert.assertTrue(query.contains("order by id desc"));
            Assert.assertTrue(query.contains("limit #{limit}"));
        }
    }

    @Test
    public void shouldKeepDeleteLocksAndFinalCasTenantDocumentAndStatusScoped() throws Exception {
        String task = mapper("mybatis/mapper/rag_ingest_task_mapper.xml");
        String document = mapper("mybatis/mapper/rag_document_mapper.xml");
        String version = mapper("mybatis/mapper/rag_document_version_mapper.xml");
        String chunk = mapper("mybatis/mapper/rag_chunk_mapper.xml");

        String active = statement(task, "id=\"queryactivebytenantanddocumentid\"", "</select>");
        Assert.assertTrue(active.contains("tenant_id = #{tenantid}"));
        Assert.assertTrue(active.contains("document_id = #{documentid}"));
        Assert.assertTrue(active.contains("status in ('pending', 'running', 'retrying', 'cancel_requested')"));

        String lock = statement(document,
                "id=\"querybytenantknowledgebaseanddocumentidforupdate\"", "</select>");
        Assert.assertTrue(lock.contains("tenant_id = #{tenantid}"));
        Assert.assertTrue(lock.contains("kb_id = #{knowledgebaseid}"));
        Assert.assertTrue(lock.contains("document_id = #{documentid}"));
        Assert.assertTrue(lock.contains("for update"));

        String closeDocument = statement(document, "id=\"markdeletedbytenantandrevision\"", "</update>");
        String closeVersion = statement(version, "id=\"markdeletedbytenantandrevision\"", "</update>");
        String versionSetLock = statement(version,
                "id=\"querylistbytenantanddocumentidforupdate\"", "</select>");
        Assert.assertTrue(versionSetLock.contains("tenant_id = #{tenantid}"));
        Assert.assertTrue(versionSetLock.contains("document_id = #{documentid}"));
        Assert.assertTrue(versionSetLock.contains("for update"));
        for (String sql : java.util.List.of(closeDocument, closeVersion)) {
            Assert.assertTrue(sql.contains("tenant_id = #{tenantid}"));
            Assert.assertTrue(sql.contains("kb_id = #{knowledgebaseid}"));
            Assert.assertTrue(sql.contains("document_id = #{documentid}"));
            Assert.assertTrue(sql.contains("status = 'deleting'"));
        }
        Assert.assertTrue(closeDocument.contains("revision = #{expectedrevision}"));
        Assert.assertTrue(closeVersion.contains("row_version = #{expectedrevision}"));
        Assert.assertTrue(closeVersion.contains("version_id = #{versionid}"));

        String purgeChunks = statement(chunk, "id=\"deletebytenantandversionid\"", "</delete>");
        Assert.assertTrue(purgeChunks.contains("delete from rag_chunk"));
        Assert.assertTrue(purgeChunks.contains("tenant_id = #{tenantid}"));
        Assert.assertTrue(purgeChunks.contains("version_id = #{versionid}"));
        Assert.assertFalse(purgeChunks.contains("deleted = 0"));
        String countAll = statement(chunk, "id=\"countallbytenantandversionid\"", "</select>");
        Assert.assertTrue(countAll.contains("count(*)"));
        Assert.assertTrue(countAll.contains("tenant_id = #{tenantid}"));
        Assert.assertTrue(countAll.contains("version_id = #{versionid}"));
        Assert.assertFalse(countAll.contains("deleted = 0"));

        Assert.assertTrue(task.contains("when #{task.status} = 'pending' then null"));
    }

    private String mapper(String resource) throws Exception {
        try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            Assert.assertNotNull("找不到Mapper: " + resource, stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").toLowerCase();
        }
    }

    private String statement(String xml, String marker, String close) {
        int start = xml.indexOf(marker);
        Assert.assertTrue("缺少Mapper语句: " + marker, start >= 0);
        int end = xml.indexOf(close, start);
        Assert.assertTrue("Mapper语句未闭合: " + marker, end > start);
        return xml.substring(start, end);
    }
}
