/**
 * 业务域「仓储端口」的预留包：按领域驱动设计的分层约定，领域层只声明自己需要的数据出入口（接口），
 * 真正连数据库、发 HTTP 请求的实现放在基础设施层，由 Spring 在启动时注入进来。
 *
 * <p>所属层次：领域层（domain）的适配器出口，是领域向外部世界要数据的唯一开口。</p>
 *
 * <p>谁会用它：业务域的领域服务（{@code cn.bugstack.ai.domain.business.service}）只依赖这里的接口；
 * 基础设施层则反过来实现这些接口。这样领域逻辑不会被具体的表结构和 SQL 绑死。</p>
 *
 * <p>当前状态：这个包是脚手架预留的空壳，还没有任何接口落地。真正在跑的仓储端口在各业务子域下，
 * 例如会话用 {@code session.adapter.repository.ISessionRepository}、认证用 {@code auth.adapter.repository.IAuthRepository}。
 * 新增业务子域时照这些包的写法建接口，不要把 MyBatis Mapper 或实体直接引进领域层。</p>
 *
 * <p>它不负责什么：不写 SQL、不做事务控制、不处理连接池与重试，这些都属于基础设施层的职责。</p>
 */
