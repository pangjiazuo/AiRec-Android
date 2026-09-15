# AiRec 安卓客户端

用手机查看五路摄像头、回放录像、筛选事件和修改录像机设置。支持 **Android 12 及以上**，提供浅色和深色界面。

## 安装与连接

1. 从 [Releases](https://github.com/pangjiazuo/AiRec-Android/releases) 下载 APK，安装到手机。
2. 手机与 AiRec 录像机连接同一局域网。
3. 在设置中填写 `http://录像机IP:8080`，例如 `http://192.168.10.209:8080`。

需要配合 [安卓录像主机](https://github.com/pangjiazuo/AiRec-Host-Android) 或 [Ubuntu 录像机](https://github.com/pangjiazuo/AiRec-Rec) 使用。断网后会自动重连。

## 界面

| 实时画面 | 录像回放 | 设置 |
| --- | --- | --- |
| <img src="docs/screenshots/live.png" width="240"> | <img src="docs/screenshots/recordings.png" width="240"> | <img src="docs/screenshots/settings.png" width="240"> |

截图来自模拟器，使用无人物测试画面。

## 自行构建

用 Android Studio 打开工程，安装 Android SDK 36，在 PowerShell 运行 `./build-apk.ps1`。APK 输出到 `dist/`，首次构建需要联网。当前提供调试包，正式发布需自己的签名。

[更多说明](docs/README.md) · [马赛克设置](docs/PRIVACY.md) · [测试记录](VERIFICATION.md)
