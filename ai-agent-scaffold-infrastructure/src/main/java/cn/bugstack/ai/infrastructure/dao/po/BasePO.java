package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BasePO {

    private Long id;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer deleted;
}
