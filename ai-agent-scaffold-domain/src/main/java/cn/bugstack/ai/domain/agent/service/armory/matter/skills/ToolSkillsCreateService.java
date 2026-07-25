package cn.bugstack.ai.domain.agent.service.armory.matter.skills;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;

/** 将技能目录配置转换为可供 Agent 调用的 SkillsTool。 */
public interface ToolSkillsCreateService {

    /** 解析文件系统或 classpath 技能目录并返回工具回调。 */
    ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) throws Exception;

}
