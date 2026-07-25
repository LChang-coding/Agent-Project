package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 所有数据库记录共享的主键、审计时间和软删除标记。 */
@Data
public class BasePO {

    /** 数据库自增主键，不对外暴露。 */
    private Long id;

    /** 记录首次入库时间。 */
    private LocalDateTime createTime;

    /** 数据库最近更新时间，不等同于领域修订号。 */
    private LocalDateTime updateTime;

    /** 软删除标记：0 有效，1 已删除。 */
    private Integer deleted;
}
