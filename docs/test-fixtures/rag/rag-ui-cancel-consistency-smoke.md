# RAG 取消一致性联调样本

该文件仅用于验证未被 Worker 领取的摄取任务取消行为。

验收条件：取消后任务进入 cancelled，未激活版本同步关闭，文档不再保持 processing，且不会产生向量块。

唯一标识：rag-cancel-consistency-20260719-0155。
