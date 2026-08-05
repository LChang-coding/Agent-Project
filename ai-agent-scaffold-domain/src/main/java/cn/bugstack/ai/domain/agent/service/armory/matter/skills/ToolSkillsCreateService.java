package cn.bugstack.ai.domain.agent.service.armory.matter.skills;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;

/**
 * 把「技能包目录」配置变成模型可以调用的技能工具。
 *
 * <p>解决什么问题：Skill 是以文件形式提供的能力描述（一个目录下若干说明文件）。
 * 这个接口负责把配置里写的路径解析成真实可读的目录，再包装成工具交给模型。</p>
 *
 * <p>所属层次：领域层的装配辅料接口。</p>
 *
 * <p>谁会调用它：装配 Agent 或模型工具时，对配置里每条 Skill 项调用一次。</p>
 *
 * <p>它不负责什么：不解析技能文件的内容（那是技能工具自己的事）、不做技能权限控制。</p>
 */
public interface ToolSkillsCreateService {

    /**
     * 解析技能目录并返回对应的工具回调。
     *
     * <p>关键输入：目录来源类型（磁盘目录还是工程资源）和路径。</p>
     *
     * <p>这一步会真实读文件，因此路径不存在、没有读权限、目录为空都会失败，
     * 让配置问题在装配阶段暴露，而不是等模型调用技能时才发现无能力可用。</p>
     */
    ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) throws Exception;

}
