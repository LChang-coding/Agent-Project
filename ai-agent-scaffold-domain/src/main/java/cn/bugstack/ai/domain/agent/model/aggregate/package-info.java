/**
 * Agent 领域的聚合对象：把关联紧密的实体和值对象包成一个整体，保证它们一起被读写。
 *
 * <p>聚合里只放围绕自身数据的简单方法，不注入仓储、不调用外部服务；跨聚合的复杂编排一律放到领域服务里，
 * 否则聚合会变成第二个 Service，事务边界也会失控。</p>
 */
package cn.bugstack.ai.domain.agent.model.aggregate;
