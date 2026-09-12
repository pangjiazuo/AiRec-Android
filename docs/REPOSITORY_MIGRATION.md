# 目录迁移说明

当前工程已经独立，直接打开本仓库即可开发。它不依赖相邻客户端或服务端的源码才能构建。

根目录的 AiRec 仓库是当前 GitHub 工程；`gitee/` 是本机保留的历史副本。旧测试文档中的路径只用于追溯，不是现在的工作目录。

下面保留当时的迁移过程和验证结果。

<details>
<summary>展开详细说明与原始记录</summary>

# 2026-09-08 仓库拆分验证

Android 工程从工作区 `smart_recorder/client/android/` 完整移动至独立 `android/`，未修改业务源码，构建不再位于 Ubuntu 工程内部。接口说明已保存在本仓库 `docs/CLIENT_API.md`，来源见 `API_SYNC.md`。

移动前后 44 项源码、构建文件及 APK 的摘要立即比对一致。新路径执行 `build-apk.ps1` 成功（229 秒），36 项 JVM 测试全部通过，Lint 为 0 错误 / 14 警告。新构建完成后，43 项源码和构建文件摘要仍保持一致。

重新构建更新了 `dist/smart-recorder-1.3.0-debug.apk`：15,951,105 字节，SHA256 `01aaea2db6a7f367a9147d00f6d83baab09db91b039a21e3f1df3d7212e089ce`。原 APK 的 SHA256 为 `f86a406321ed7e68d84cdc3c3b94d29d809e8eb0b99b90b30091d29e968d76ea`；本次不宣称构建产物逐字节复现。

签名验证通过，证书 SHA256 `0d5e977c920cf53512a11dcf1f4834abbd9d99b0f45b10f20b7a986be308c403` 与迁移前相同，包名 `com.neardi.recorder`、版本 1.3.0 / code 4 不变。迁移任务未操作模拟器/手机或修改开发板，已有功能验收及历史产物仍保留于本仓库。

只提交源码、Gradle Wrapper、构建配置、文档和测试；`dist/`、`app/build/`、缓存、`local.properties` 与签名密钥均由 `.gitignore` 排除。未初始化 Git、提交或推送。

</details>
