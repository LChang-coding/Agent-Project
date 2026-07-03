# Middleware Observability Stack on 69.165.65.123

这套资产用于在 `69.165.65.123` 中间件服务器上部署最小日志观测栈，只包含：

- `Grafana`
- `Loki`

> 当前目录名仍保留为 `hz`，只是历史命名；实际服务器统一按 `69.165.65.123` 这台中间件服务器理解。

## 1. 安装 Docker / Compose

在目标服务器上进入本目录后执行：

```bash
chmod +x install-docker.sh deploy-observability.sh
./install-docker.sh
```

## 2. 准备环境变量

```bash
cp .env.example .env
vim .env
```

至少修改：

- `GRAFANA_ADMIN_USER`
- `GRAFANA_ADMIN_PASSWORD`
- `GRAFANA_PORT`
- `GF_SERVER_ROOT_URL`
- `GRAFANA_DEFAULT_LANGUAGE`

## 3. 启动 Grafana + Loki

```bash
./deploy-observability.sh
```

启动后：

- Grafana 公网端口：`${GRAFANA_PORT:-13000}`
- Loki 仅监听：`127.0.0.1:3100`

## 4. 登录 Grafana

浏览器访问：

```text
http://69.165.65.123:13000
```

登录后会自动看到：

- 预置 `loki` 数据源
- `AI Agent Scaffold Observability` 看板
- `AI Agent Scaffold 应用日志` 看板

## 5. 本地接入 Loki

本地开发机需要通过 SSH 隧道把远端 Loki 映射到本机：

```bash
ssh -i ~/dadaikuai -o ProxyCommand=none -N -L 13100:127.0.0.1:3100 root@69.165.65.123
```

然后再启动本地 Alloy，把本地应用日志推送到 `http://127.0.0.1:13100/loki/api/v1/push`。
