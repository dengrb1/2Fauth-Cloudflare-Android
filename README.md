# 2Fauth-Cloudflare-Android

这是一个面向 [dengrb1/2Fauth-Cloudflare](https://github.com/dengrb1/2Fauth-Cloudflare) 的 Android 客户端。

应用采用 `WebView` 方式加载你自己的 Cloudflare Worker 页面，提供：

- 快捷打开 2FA 页面
- Android 原生返回键回退网页历史
- 页面加载中的进度提示与错误提示
- **构建时可配置 Worker URL**（本地构建 + GitHub Actions）

## 本地构建

```bash
gradle assembleDebug -PworkerUrl="https://你的worker地址.workers.dev"
```

如果不传 `-PworkerUrl`，将使用 `gradle.properties` 中的默认值。

## GitHub Actions 自动构建

仓库内置工作流：`.github/workflows/android-build.yml`

支持两种方式设定 Worker URL：

1. 手动触发 `workflow_dispatch` 时传入 `worker_url`
2. 在仓库 Variables 中设置 `WORKER_URL`（当手动输入为空时使用）

构建命令会自动将参数注入：

```bash
gradle assembleDebug -PworkerUrl="${WORKER_URL}"
```

构建成功后会上传 `app-debug.apk` 作为 Artifact。

## Android 客户端会话说明（适配上游新接口）

2Fauth-Cloudflare上游已将 `POST /api/session/close-soon` 标记为 Web 页面 `beforeunload` 内部机制，不建议第三方客户端调用。

本 Android 客户端已适配：在 WebView 中拦截该接口请求，避免 App 生命周期触发页面卸载时提前缩短会话，减少“自动退出登录”问题。
