# Agent-Project
trigger 接收 HTTP 请求，调用 domain 的 service
domain 实现核心业务逻辑，通过 adapter 调用 infrastructure 的 port/repository
infrastructure 实现数据持久化（DAO）、缓存（Redis）、第三方网关调用
api 负责定义所有层之间的数据传输对象
types 提供全局常量、异常、枚举支持
app 作为启动入口，组装所有模块配置
****