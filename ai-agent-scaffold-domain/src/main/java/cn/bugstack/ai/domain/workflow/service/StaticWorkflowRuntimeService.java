package cn.bugstack.ai.domain.workflow.service;

import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStreamEntity;
import cn.bugstack.ai.domain.workflow.model.entity.StaticWorkflowStartCommandEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRuntimeEntity;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/** 启动与 HTTP 连接解耦的普通 DAG；节点状态通过统一工作流事件流输出。 */
@Slf4j
@Service
public class StaticWorkflowRuntimeService {

    /** 加载已经发布且当前用户可读的工作流运行定义。 */
    private final IWorkflowService workflowService;
    /** 创建通用运行并启动普通 DAG 的后台执行。 */
    private final IChatService chatService;

    /**
     * 创建普通工作流启动服务。
     *
     * @param workflowService 加载已发布工作流运行定义的服务
     * @param chatService 创建运行并执行普通工作流的会话服务
     */
    public StaticWorkflowRuntimeService(IWorkflowService workflowService, IChatService chatService) {
        this.workflowService = workflowService;
        this.chatService = chatService;
    }

    /**
     * 校验发布版本为普通 DAG 后创建运行并启动后台执行。
     *
     * @param command 包含可信身份、工作流、会话和用户消息的启动命令
     * @return 可立即用于订阅工作流事件的通用运行
     */
    public ChatRunEntity start(StaticWorkflowStartCommandEntity command) {
        validate(command);
        WorkflowRuntimeEntity runtime = workflowService.loadRuntime(command.getTenantId(), command.getUserId(),
                command.getRoleCode(), command.getWorkflowId(), command.getWorkflowVersion(), command.getModelCode());
        if (runtime.getDagPlan() == null || !"STATIC".equalsIgnoreCase(runtime.getDagPlan().getWorkflowKind())) {
            throw new AppException("WORKFLOW_NOT_STATIC", "所选发布版本不是普通 DAG 工作流");
        }
        RunStreamEntity<String> started = chatService.startWorkflowMessageTextStream(
                command.getWorkflowId(), runtime.getVersion(), runtime.getEffectiveModelCode(), command.getUserId(),
                command.getSessionId(), command.getMessage(), command.getRequestedRunId(),
                command.getAttachmentIds() == null ? List.of() : command.getAttachmentIds());
        started.getStream().subscribe(
                ignored -> { },
                error -> log.warn("普通工作流后台运行失败 runId:{} traceId:{} errorType:{}",
                        started.getRun().getRunId(), started.getRun().getTraceId(), error.getClass().getSimpleName()));
        return started.getRun();
    }

    /** 校验静态工作流启动所需的可信身份、工作流和用户消息。 */
    private void validate(StaticWorkflowStartCommandEntity command) {
        if (command == null || blank(command.getTenantId()) || blank(command.getUserId())
                || blank(command.getWorkflowId()) || blank(command.getSessionId()) || blank(command.getMessage())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "租户、用户、工作流、会话和消息不能为空");
        }
    }

    /** 判断必填文本是否缺失。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
