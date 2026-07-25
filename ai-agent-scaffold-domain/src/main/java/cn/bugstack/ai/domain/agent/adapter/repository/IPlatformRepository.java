package cn.bugstack.ai.domain.agent.adapter.repository;

/**
 * 平台数据库仓储入口。
 * <p>
 * Day 2 先建立数据库地基，具体业务用例后续再按会话、用量、RAG、调度等领域继续拆细。
 */
public interface IPlatformRepository {

    /** 探测平台数据库仓储是否可用。 */
    boolean available();
}
