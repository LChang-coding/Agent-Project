// AI 智能体对话 By Ai Agent Scaffold - lcode
// 修改此文件切换环境

var APP_CONFIG = {
  // API 根地址
  baseURL: 'http://127.0.0.1:8091',

  // API 版本前缀
  apiPrefix: '/api/v1',

  // 完整 API 地址
  get apiBase() {
    return this.baseURL + this.apiPrefix;
  },

  // 接口路径
  endpoints: {
    queryAgentList: '/query_ai_agent_config_list',
    createSession: '/create_session',
    chat: '/chat',
    chatStream: '/chat_stream'
  }
};
