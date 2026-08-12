package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.agent.ToolApprovalDecisionRequestDTO;
import cn.bugstack.ai.api.dto.agent.ToolApprovalEventDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.model.entity.ToolApprovalRequestEntity;
import cn.bugstack.ai.domain.agent.service.ToolApprovalService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 工具审批 SSE 下行与单次 HTTP 决策入口。 */
@RestController
@RequestMapping("/api/v1/tool-approvals")
public class ToolApprovalController {
    private final ToolApprovalService service;
    public ToolApprovalController(ToolApprovalService service){this.service=service;}

    @GetMapping(value="/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(defaultValue="0") long afterSequence) throws Exception {
        String tenantId=TenantContextHolder.getTenantId(), userId=TenantContextHolder.getUserId();
        AtomicLong cursor=new AtomicLong(Math.max(0,afterSequence)); SseEmitter emitter=new SseEmitter(30*60*1000L);
        emitter.send(SseEmitter.event().name("STREAM_METADATA").data(Map.of("schemaVersion","tool-approval-v1","afterSequence",cursor.get())));
        CompositeDisposable subscriptions=new CompositeDisposable(); emitter.onCompletion(subscriptions::dispose);
        emitter.onTimeout(subscriptions::dispose); emitter.onError(error->subscriptions.dispose());
        subscriptions.add(Flowable.interval(0,1,TimeUnit.SECONDS).concatMap(ignored->Flowable.fromIterable(
                service.streamPage(tenantId,userId,cursor.get()))).subscribe(value->send(emitter,cursor,value),
                emitter::completeWithError));
        subscriptions.add(Flowable.interval(15,15,TimeUnit.SECONDS).subscribe(ignored->emitter.send(
                SseEmitter.event().name("heartbeat").data(Map.of("cursor",cursor.get()))),ignored->subscriptions.dispose()));
        return emitter;
    }

    @PostMapping("/{approvalId}/decision")
    public Response<Map<String,Object>> decide(@PathVariable String approvalId,
                                                @RequestBody ToolApprovalDecisionRequestDTO request){
        service.decide(TenantContextHolder.getTenantId(),TenantContextHolder.getUserId(),approvalId,
                request==null?null:request.getDecision(),request==null?null:request.getComment(),
                request==null?null:request.getAmendedInput(),request==null||request.getExpectedRevision()==null?0:request.getExpectedRevision());
        return Response.<Map<String,Object>>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                .data(Map.of("approvalId",approvalId,"accepted",true)).build();
    }

    private void send(SseEmitter emitter,AtomicLong cursor,ToolApprovalRequestEntity value){
        try { cursor.set(value.getSequence()); emitter.send(SseEmitter.event().id(String.valueOf(value.getSequence()))
                .name("tool_approval").data(ToolApprovalEventDTO.builder().sequence(value.getSequence())
                        .approvalId(value.getApprovalId()).parentAgentId(value.getParentAgentId())
                        .parentRunId(value.getParentRunId()).sourceRunId(value.getSourceRunId())
                        .parentSessionId(value.getParentSessionId()).traceId(value.getTraceId())
                        .toolCode(value.getToolCode()).requestedInput(value.getRequestedInput())
                        .suggestions(value.getSuggestions()).allowedSubAgentIds(value.getAllowedSubAgentIds())
                        .timeoutDecision(value.getTimeoutDecision()).status(value.getStatus())
                        .expiresAt(String.valueOf(value.getExpiresAt())).revision(value.getRevision()).build())); }
        catch(Exception exception){emitter.completeWithError(exception);throw new IllegalStateException("工具审批 SSE 已关闭",exception);}
    }
}
