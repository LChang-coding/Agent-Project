# MinIO 对象存储

## 服务器信息

- 部署服务器：`69.165.65.123`
- 部署目录：`~/middleware/minio`
- API 端口：`9000`
- 控制台端口：`9001`
- Skill 桶：`ai-agent-skills`
- 通用资产桶：`ai-agent-assets`

## 本地后端连接

本地开发默认仍可使用 `OBJECT_STORAGE_TYPE=local`，不强制依赖远端 MinIO。

如果要把 Skill 包上传到服务器 MinIO，启动 Spring Boot 前设置：

```bash
export OBJECT_STORAGE_TYPE=minio
export MINIO_ENDPOINT=http://69.165.65.123:9000
export MINIO_ACCESS_KEY=ai_agent_admin
export MINIO_SECRET_KEY='<服务器 ~/middleware/minio/.env 中的 MINIO_ROOT_PASSWORD>'
export MINIO_SKILL_BUCKET=ai-agent-skills
export MINIO_ASSET_BUCKET=ai-agent-assets
```

## 重新部署

```bash
cd docs/dev-ops/minio
ssh -o ProxyCommand=none -i ~/dadaikuai root@69.165.65.123 'mkdir -p ~/middleware/minio'
scp -o ProxyCommand=none -i ~/dadaikuai docker-compose.yml deploy-minio.sh .env.example root@69.165.65.123:~/middleware/minio/
ssh -o ProxyCommand=none -i ~/dadaikuai root@69.165.65.123 'cd ~/middleware/minio && bash deploy-minio.sh'
```
