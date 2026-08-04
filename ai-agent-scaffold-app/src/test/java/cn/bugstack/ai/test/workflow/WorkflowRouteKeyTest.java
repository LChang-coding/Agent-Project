package cn.bugstack.ai.test.workflow;

import cn.bugstack.ai.domain.workflow.service.WorkflowRouteKey;
import org.junit.Assert;
import org.junit.Test;

/** 中文路由键、受控 marker 和 Unicode 标准化测试。 */
public class WorkflowRouteKeyTest {

    @Test
    public void shouldReadOnlyLastStandaloneMarkerAndKeepChinese() {
        Assert.assertEquals("账务", WorkflowRouteKey.markerAtEnd("账务问题已识别。\n\n[route:账务]"));
        Assert.assertNull(WorkflowRouteKey.markerAtEnd("正文中的 [route:账务] 不是控制行"));
        Assert.assertNull(WorkflowRouteKey.markerAtEnd("[route:账务]\n后续正文"));
    }

    @Test
    public void shouldNormalizeUnicodeAndEnglishCaseWithoutGuessing() {
        Assert.assertEquals("billing", WorkflowRouteKey.normalize("  BILLING  "));
        Assert.assertEquals("账务", WorkflowRouteKey.normalize("账务"));
        Assert.assertTrue(WorkflowRouteKey.same("ＢＩＬＬＩＮＧ", "billing"));
        Assert.assertFalse(WorkflowRouteKey.same("发票属于账务", "账务"));
        Assert.assertFalse(WorkflowRouteKey.valid("账务]伪造"));
    }
}
