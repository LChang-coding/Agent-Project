package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.IWorkflowApiService;
import cn.bugstack.ai.api.dto.workflow.WorkflowCreateRequestDTO;
import cn.bugstack.ai.api.dto.workflow.WorkflowDetailResponseDTO;
import cn.bugstack.ai.api.dto.workflow.WorkflowDeleteResponseDTO;
import cn.bugstack.ai.api.dto.workflow.WorkflowGraphDTO;
import cn.bugstack.ai.api.dto.workflow.WorkflowNodeOptionsResponseDTO;
import cn.bugstack.ai.api.dto.workflow.WorkflowOptionDTO;
import cn.bugstack.ai.api.dto.workflow.WorkflowResponseDTO;
import cn.bugstack.ai.api.dto.workflow.WorkflowSaveDraftRequestDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowCreateCommandEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowDetailEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowGraphEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowNodeOptionsEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowOptionEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowSaveDraftCommandEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowVersionEntity;
import cn.bugstack.ai.domain.workflow.service.IWorkflowService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 工作流接口控制器。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/workflows")
@CrossOrigin(origins = "*")
public class WorkflowController implements IWorkflowApiService {

    @Resource
    private IWorkflowService workflowService;

    /**
     * 查询工作流列表；无参数；返回当前租户可见工作流。
     */
    @Override
    @GetMapping
    public Response<List<WorkflowResponseDTO>> queryWorkflowList() {
        try {
            List<WorkflowResponseDTO> data = workflowService.queryWorkflowList(
                            currentTenantId(), currentUserId(), TenantContextHolder.getRoleCode())
                    .stream()
                    .map(this::toWorkflowResponse)
                    .collect(Collectors.toList());
            return success(data);
        } catch (AppException e) {
            log.error("查询工作流列表异常", e);
            return fail(e);
        } catch (Exception e) {
            log.error("查询工作流列表失败", e);
            return fail();
        }
    }

    /**
     * 创建工作流；参数是名称、描述和默认模型；返回工作流摘要。
     */
    @Override
    @PostMapping
    public Response<WorkflowResponseDTO> createWorkflow(@RequestBody WorkflowCreateRequestDTO requestDTO) {
        try {
            WorkflowCreateCommandEntity command = new WorkflowCreateCommandEntity();
            command.setTenantId(currentTenantId());
            command.setUserId(currentUserId());
            command.setWorkflowName(requestDTO.getWorkflowName());
            command.setDescription(requestDTO.getDescription());
            command.setDefaultModelCode(requestDTO.getDefaultModelCode());
            command.setVisibility(requestDTO.getVisibility());
            return success(toWorkflowResponse(workflowService.createWorkflow(command)));
        } catch (AppException e) {
            log.error("创建工作流异常 workflowName:{}", requestDTO == null ? null : requestDTO.getWorkflowName(), e);
            return fail(e);
        } catch (Exception e) {
            log.error("创建工作流失败 workflowName:{}", requestDTO == null ? null : requestDTO.getWorkflowName(), e);
            return fail();
        }
    }

    /**
     * 查询工作流详情；参数是工作流ID；返回工作流版本和画布数据。
     */
    @Override
    @GetMapping("/{workflowId}")
    public Response<WorkflowDetailResponseDTO> queryWorkflowDetail(@PathVariable String workflowId) {
        try {
            return success(toDetailResponse(workflowService.queryWorkflowDetail(
                    currentTenantId(), currentUserId(), TenantContextHolder.getRoleCode(), workflowId)));
        } catch (AppException e) {
            log.error("查询工作流详情异常 workflowId:{}", workflowId, e);
            return fail(e);
        } catch (Exception e) {
            log.error("查询工作流详情失败 workflowId:{}", workflowId, e);
            return fail();
        }
    }

    /**
     * 保存工作流草稿；参数是工作流ID和画布数据；返回最新详情。
     */
    @Override
    @PostMapping("/{workflowId}/draft")
    public Response<WorkflowDetailResponseDTO> saveDraft(@PathVariable String workflowId,
                                                         @RequestBody WorkflowSaveDraftRequestDTO requestDTO) {
        try {
            WorkflowSaveDraftCommandEntity command = new WorkflowSaveDraftCommandEntity();
            command.setTenantId(currentTenantId());
            command.setUserId(currentUserId());
            command.setRoleCode(TenantContextHolder.getRoleCode());
            command.setWorkflowId(workflowId);
            command.setWorkflowName(requestDTO.getWorkflowName());
            command.setDescription(requestDTO.getDescription());
            command.setDefaultModelCode(requestDTO.getDefaultModelCode());
            command.setVisibility(requestDTO.getVisibility());
            command.setGraph(toGraphEntity(requestDTO.getGraph()));
            return success(toDetailResponse(workflowService.saveDraft(command)));
        } catch (AppException e) {
            log.error("保存工作流草稿异常 workflowId:{}", workflowId, e);
            return fail(e);
        } catch (Exception e) {
            log.error("保存工作流草稿失败 workflowId:{}", workflowId, e);
            return fail();
        }
    }

    /**
     * 发布工作流；参数是工作流ID；返回已发布详情。
     */
    @Override
    @PostMapping("/{workflowId}/publish")
    public Response<WorkflowDetailResponseDTO> publishWorkflow(@PathVariable String workflowId) {
        try {
            return success(toDetailResponse(workflowService.publishWorkflow(currentTenantId(), currentUserId(), TenantContextHolder.getRoleCode(), workflowId)));
        } catch (AppException e) {
            log.error("发布工作流异常 workflowId:{}", workflowId, e);
            return fail(e);
        } catch (Exception e) {
            log.error("发布工作流失败 workflowId:{}", workflowId, e);
            return fail();
        }
    }

    /** 删除工作流；参数是工作流ID；返回幂等软删除结果。 */
    @Override
    @DeleteMapping("/{workflowId}")
    public Response<WorkflowDeleteResponseDTO> deleteWorkflow(@PathVariable String workflowId) {
        try {
            workflowService.deleteWorkflow(currentTenantId(), currentUserId(), TenantContextHolder.getRoleCode(), workflowId);
            return success(WorkflowDeleteResponseDTO.builder().workflowId(workflowId).status("deleted").build());
        } catch (AppException e) {
            return Response.<WorkflowDeleteResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("删除工作流失败 workflowId:{}", workflowId, e);
            return Response.<WorkflowDeleteResponseDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    /**
     * 查询节点选项；无参数；返回节点类型、模型、MCP 和 Skill 下拉数据。
     */
    @Override
    @GetMapping("/node-options")
    public Response<WorkflowNodeOptionsResponseDTO> queryNodeOptions() {
        try {
            return success(toNodeOptionsResponse(workflowService.queryNodeOptions(currentTenantId())));
        } catch (AppException e) {
            log.error("查询工作流节点选项异常", e);
            return fail(e);
        } catch (Exception e) {
            log.error("查询工作流节点选项失败", e);
            return fail();
        }
    }

    /**
     * 转换工作流摘要；参数是实体；返回响应 DTO。
     */
    private WorkflowResponseDTO toWorkflowResponse(WorkflowEntity entity) {
        WorkflowResponseDTO response = new WorkflowResponseDTO();
        response.setWorkflowId(entity.getWorkflowId());
        response.setWorkflowName(entity.getWorkflowName());
        response.setDescription(entity.getDescription());
        response.setVisibility(entity.getVisibility());
        response.setStatus(entity.getStatus());
        response.setDefaultModelCode(entity.getDefaultModelCode());
        response.setCurrentVersion(entity.getCurrentVersion());
        response.setPublishedVersion(entity.getPublishedVersion());
        return response;
    }

    /**
     * 转换工作流详情；参数是实体；返回响应 DTO。
     */
    private WorkflowDetailResponseDTO toDetailResponse(WorkflowDetailEntity entity) {
        WorkflowDetailResponseDTO response = new WorkflowDetailResponseDTO();
        response.setWorkflow(toWorkflowResponse(entity.getWorkflow()));
        WorkflowVersionEntity version = entity.getVersion();
        if (version != null) {
            response.setVersion(version.getVersion());
            response.setVersionStatus(version.getVersionStatus());
            response.setGraph(toGraphDTO(version.getGraph()));
        }
        return response;
    }

    /**
     * 转换节点选项；参数是实体；返回响应 DTO。
     */
    private WorkflowNodeOptionsResponseDTO toNodeOptionsResponse(WorkflowNodeOptionsEntity entity) {
        WorkflowNodeOptionsResponseDTO response = new WorkflowNodeOptionsResponseDTO();
        response.setNodeTypes(toOptionDTOList(entity.getNodeTypes()));
        response.setModels(toOptionDTOList(entity.getModels()));
        response.setMcpServers(toOptionDTOList(entity.getMcpServers()));
        response.setSkills(toOptionDTOList(entity.getSkills()));
        return response;
    }

    /**
     * 转换选项列表；参数是实体列表；返回 DTO 列表。
     */
    private List<WorkflowOptionDTO> toOptionDTOList(List<WorkflowOptionEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }
        return entities.stream().map(entity -> {
            WorkflowOptionDTO dto = new WorkflowOptionDTO();
            dto.setValue(entity.getValue());
            dto.setLabel(entity.getLabel());
            dto.setDescription(entity.getDescription());
            dto.setType(entity.getType());
            dto.setStatus(entity.getStatus());
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * 转换画布实体；参数是 DTO；返回实体。
     */
    private WorkflowGraphEntity toGraphEntity(WorkflowGraphDTO dto) {
        if (dto == null) {
            return null;
        }
        return WorkflowGraphEntity.builder()
                .mode(dto.getMode())
                .rootNodeId(dto.getRootNodeId())
                .nodes(dto.getNodes() == null ? Collections.emptyList() : dto.getNodes().stream().map(this::toNodeEntity).collect(Collectors.toList()))
                .edges(dto.getEdges() == null ? Collections.emptyList() : dto.getEdges().stream().map(this::toEdgeEntity).collect(Collectors.toList()))
                .build();
    }

    /**
     * 转换画布 DTO；参数是实体；返回 DTO。
     */
    private WorkflowGraphDTO toGraphDTO(WorkflowGraphEntity entity) {
        if (entity == null) {
            return null;
        }
        WorkflowGraphDTO dto = new WorkflowGraphDTO();
        dto.setMode(entity.getMode());
        dto.setRootNodeId(entity.getRootNodeId());
        dto.setNodes(entity.getNodes() == null ? Collections.emptyList() : entity.getNodes().stream().map(this::toNodeDTO).collect(Collectors.toList()));
        dto.setEdges(entity.getEdges() == null ? Collections.emptyList() : entity.getEdges().stream().map(this::toEdgeDTO).collect(Collectors.toList()));
        return dto;
    }

    /**
     * 转换节点实体；参数是 DTO；返回实体。
     */
    private WorkflowGraphEntity.Node toNodeEntity(WorkflowGraphDTO.Node node) {
        return WorkflowGraphEntity.Node.builder()
                .nodeId(node.getNodeId())
                .nodeType(node.getNodeType())
                .name(node.getName())
                .description(node.getDescription())
                .instruction(node.getInstruction())
                .modelCode(node.getModelCode())
                .mcpIds(node.getMcpIds())
                .skillIds(node.getSkillIds())
                .maxIterations(node.getMaxIterations())
                .x(node.getX())
                .y(node.getY())
                .build();
    }

    /**
     * 转换节点 DTO；参数是实体；返回 DTO。
     */
    private WorkflowGraphDTO.Node toNodeDTO(WorkflowGraphEntity.Node entity) {
        WorkflowGraphDTO.Node node = new WorkflowGraphDTO.Node();
        node.setNodeId(entity.getNodeId());
        node.setNodeType(entity.getNodeType());
        node.setName(entity.getName());
        node.setDescription(entity.getDescription());
        node.setInstruction(entity.getInstruction());
        node.setModelCode(entity.getModelCode());
        node.setMcpIds(entity.getMcpIds());
        node.setSkillIds(entity.getSkillIds());
        node.setMaxIterations(entity.getMaxIterations());
        node.setX(entity.getX());
        node.setY(entity.getY());
        return node;
    }

    /**
     * 转换连线实体；参数是 DTO；返回实体。
     */
    private WorkflowGraphEntity.Edge toEdgeEntity(WorkflowGraphDTO.Edge edge) {
        return WorkflowGraphEntity.Edge.builder()
                .edgeId(edge.getEdgeId())
                .sourceNodeId(edge.getSourceNodeId())
                .targetNodeId(edge.getTargetNodeId())
                .build();
    }

    /**
     * 转换连线 DTO；参数是实体；返回 DTO。
     */
    private WorkflowGraphDTO.Edge toEdgeDTO(WorkflowGraphEntity.Edge entity) {
        WorkflowGraphDTO.Edge edge = new WorkflowGraphDTO.Edge();
        edge.setEdgeId(entity.getEdgeId());
        edge.setSourceNodeId(entity.getSourceNodeId());
        edge.setTargetNodeId(entity.getTargetNodeId());
        return edge;
    }

    /**
     * 当前租户ID；无参数；返回 JWT 里的租户ID。
     */
    private String currentTenantId() {
        return TenantContextHolder.getTenantId();
    }

    /**
     * 当前用户ID；无参数；返回 JWT 里的用户ID。
     */
    private String currentUserId() {
        return TenantContextHolder.getUserId();
    }

    /**
     * 成功响应；参数是数据；返回统一响应体。
     */
    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    /**
     * 业务失败响应；参数是异常；返回统一响应体。
     */
    private <T> Response<T> fail(AppException e) {
        return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
    }

    /**
     * 未知失败响应；无参数；返回统一响应体。
     */
    private <T> Response<T> fail() {
        return Response.<T>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
    }
}
