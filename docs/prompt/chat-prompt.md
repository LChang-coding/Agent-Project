开发ai对话页面，要求如下；
0. 在用户login登录后，跳转到 index.html 页面，处理对话。如果 index.html 页面，没有检测到登录，则跳转到 login 页面进行登录，登录的用户ID，作为下面创建会话ID和对话使用。
1. 对话agent选择，可以通过 queryAiAgentConfigList接口获取，并展示智能体名称
2. 每次发起对话的时候，都要接口createSession(@RequestBody CreateSessionRequestDTO requestDTO)，创建会话ID
3. 调用会话服务，传入必要的参数，包括；agentId 下拉选择、userId 登录的用户、sessionId 会话ID、以及用户的提问信息 message。返回的信息，要渲染到页面展示。用户信息在左侧，接口返回信息在右侧。
4. 增加配置 js，填写接口，访问的根地址 http://127.0.0.1:8091
5. 整个对话页面，要设计的美观，间接，优雅。铺满整个浏览器。