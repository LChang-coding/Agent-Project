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

    private final IWorkflowService workflowService;
    private final IChatService chatService;

    public StaticWorkflowRuntimeService(IWorkflowService workflowService, IChatService chatService) {
        this.workflowService = workflowService;
        this.chatService = chatService;
    }

    /** 校验发布版本类型后启动后台 DAG，返回可立即订阅事件的根 Run。 */
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

    private void validate(StaticWorkflowStartCommandEntity command) {
        if (command == null || blank(command.getTenantId()) || blank(command.getUserId())
                || blank(command.getWorkflowId()) || blank(command.getSessionId()) || blank(command.getMessage())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "租户、用户、工作流、会话和消息不能为空");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
