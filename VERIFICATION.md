# 安卓客户端测试情况

2026-09-14：显示版本按用户要求重置为 **1.0.0**，内部 versionCode 增至 8。本次只调整版本元数据及约定，下面的截图与功能测试保留原版本记录。

以下为 **1.5.1** 的界面验收记录，最低 Android 12。按确认的手机设计重新校正分组、留白、导航、二级设置、日期面板、全屏控制和深浅色界面。

- 构建、签名检查通过；47 项单元测试通过，Lint 无错误。
- 已截取 49 个页面及状态的浅色、深色运行图。素材为无人物测试图，不使用现场事件照片。
- 页面与时间轴回归共 23 项：22 项首次通过；1 项因测试收起输入法超时，修正测试等待方式后通过。
- 最后一次修改后，浅色和深色各 5 项页面、异常状态和重连测试通过。
- 实际开发板的全天录像定位、内嵌播放、暂停、旋转恢复和退出全屏最新一次通过。
- 实际全天索引曾间歇性加载失败。已增加有上限的只读重试；这不是长期稳定性通过，配置保存不会自动重发。
- 未完成 Android 12 真机、五路摄像头实拍和长时间运行验收。

安装包：`dist/smart-recorder-1.5.1-debug.apk`。覆盖安装可保留当前地址和偏好，默认地址为 `http://192.168.10.209:8080`。

具体截图来源与边界见 [界面核对记录](docs/VERIFICATION-PHONE-20260914.md)。

<details><summary>1.5.0 历史记录</summary>

# 安卓客户端测试情况

当前版本 **1.5.0**，最低 Android 12。本轮按确认的手机设计更新了深浅色界面、五路预览、通道分级设置和事件详情。

- 44 项单元测试通过；Lint 无错误，16 项警告主要为依赖升级建议。
- API 33 手机模拟器：14 项页面回归、5 项时间轴测试和深色页面检查通过。
- 已连接真实安卓录像主机，验证实时画面、全屏、录像播放与跳转、旋转恢复、日志下载。
- 真实全天索引专项仍遇到响应中断，未通过；模拟时间轴测试通过。
- 尚未完成 Android 12 真机和五路真实摄像头同时在线的性能验收。

本轮记录见 [1.5.0 手机界面验证](docs/VERIFICATION-PHONE-20260914.md)。下方保留旧版本历史。

<details>
<summary>展开详细说明与原始记录</summary>

# Android 客户端验证记录

2026-09-08 目录迁移说明：本工程已从 `smart_recorder/client/android/` 完整移动至独立仓库 `C:\Users\pjz\Desktop\rk3399-project\android`。本文件中的旧绝对路径保留为当时记录，`dist/` 内的历史产物与证据均随工程保留；迁移后的重新构建检查见 [迁移验证](docs/REPOSITORY_MIGRATION.md)。

最新交付为 **1.3.0 · 方案 B / 浅色与深色主题**：[跳到本轮构建与运行验收](#android-130-b)。下面保留各旧版本的历史记录。

验证日期：2026-09-07。本记录对应 Windows 上构建的 Android 客户端 1.0.0；开发板地址为 `http://192.168.10.172:8080`。

## 交付 APK

| 项目 | 实际结果 |
| --- | --- |
| 文件 | `dist/smart-recorder-1.0.0-debug.apk` |
| 包名 / 版本 | `com.neardi.recorder` / `1.0.0`（versionCode 1） |
| 最低版本 / target SDK | Android 8.0（API 26）/ API 36 |
| 大小 | 22,651,993 字节 |
| SHA256 | `76a106a9725d5ea98ad8938c4c3550d65275a1528bebd1092ed97a642fe7ff2f` |
| 签名 | Android 调试签名；`apksigner verify --verbose` 通过，APK Signature Scheme v2 有效 |

APK 校验值已重新读取，与 `dist/SHA256SUMS.txt`、`dist/build-verification.json` 一致。这是可直接安装的调试版本，正式发布应使用长期保管的发布签名。

## 构建、单元测试与静态检查

实际执行 `build-apk.ps1 -BuildDeviceTests`，Gradle 返回 `BUILD SUCCESSFUL`，同时生成主 APK 和仪器测试 APK。构建使用 AGP 9.2.1、Gradle 9.4.1、Kotlin / Compose 编译插件 2.3.10、Android SDK 36、Build Tools 36.0.0，JBR 25 运行构建工具。

| JVM 测试组 | 通过数量 |
| --- | ---: |
| ConfigMergeTest | 4 |
| RecorderApiTest | 10 |
| ResponseValidationTest | 2 |
| MjpegReaderTest | 4 |
| SettingsDraftTest | 4 |
| 合计 | **24** |

全部测试失败数和错误数均为 0。Android Lint 为 **0 错误、13 警告**，警告主要是依赖及 SDK 版本更新提示、图标与备份配置建议、Kotlin 写法建议；未通过屏蔽错误使构建通过。

证据：`dist/build.log`、`dist/build-verification.json`、`dist/signature-verification.log`；五份 JUnit XML 已归档到 `dist/verification/unit/`，Lint XML 已归档到 `dist/verification/lint-results-debug.xml`。原始 HTML 报告位于 `app/build/reports/lint-results-debug.html`。

## Android 13 模拟器：模拟服务 UI 验证

在专用 API 33 模拟器上安装本次主 APK 和测试 APK，执行 `RecorderUiTest`，结果为 **OK (5 tests)**，耗时 **38.239 秒**。所有模拟配置提交均发送到模拟器内的 MockWebServer，没有向真实录像机写入测试配置。

1. 五路通道分别显示，未接入的四路显示无信号；有画面的通道可进入并退出全屏，无信号通道不会发起视频流请求。
2. 录像和事件页面导航、按通道和事件类型组合筛选；横竖屏切换后保留筛选条件，已清理录像不可播放。
3. 修改停留阈值和名称后旋转屏幕，草稿保持；批量应用保留其他通道的名称、输入源和裁剪配置；保存请求响应期间再次旋转，成功状态传递到新页面，整个操作仅提交一次 PUT。
4. 服务暂时返回不可用后自动恢复连接，保留当前页面和事件筛选。
5. 切换到另一台录像机后载入新设备配置，不恢复上一台设备未保存的草稿，也不自动提交这些草稿。

完整日志：`dist/verification/mock-ui.txt`。五张测试结束截图归档于 `dist/verification/mock/`，截图是辅助证据，测试通过依据为断言结果。

## 手机尺寸模拟器：真实开发板联调

API 33 模拟器使用手机尺寸连接实际开发板 `192.168.10.172:8080`，执行 `BoardSmokeTest.realPreviewFullscreenPlaybackAndLogs`，结果为 **OK (1 test)**，耗时 **16.259 秒**。

实际验证内容：

- 读取 AHD1 的实时 MJPEG 画面，并在单路全屏页面再次收到画面。
- 从真实录像列表打开 MP4；播放器进入 `Player.STATE_READY`，播放位置超过 500 毫秒。
- 打开真实事件页面。
- 使用客户端共享下载器下载真实日志 ZIP，并成功遍历、解压至少三个文件条目。

此项测试只读取真实开发板的预览、录像、事件和日志，没有提交板端配置。日志下载验证覆盖客户端网络下载和 ZIP 内容解析，未自动操作 Android 系统文件保存对话框。

完整日志：`dist/verification/board-phone.txt`。实时、全屏、回放、事件截图归档于 `dist/verification/phone/`。

## 平板尺寸联调

在同一专用 API 33 模拟器上设置 **2560 × 1600、density 240** 的平板尺寸，重新执行真实开发板 `BoardSmokeTest`，结果为 **OK (1 test)**，耗时 **18.735 秒**。主 APK 与上述手机尺寸测试相同。

三列通道预览和侧栏布局正常；真实 AHD1 预览、单路全屏、H.264 录像播放、事件页面与日志 ZIP 下载解析均通过。手机与平板尺寸联调结束后，主任务确认板端配置与测试前完全相同，`smart-recorder.service`、`smart-recorder-npu.service` 均为 active。

完整日志：`dist/verification/board-tablet.txt`。四张实际页面截图归档于 `dist/verification/tablet/`。

随后补充“等待至少一张事件截图实际解码显示”的断言，只重新构建测试 APK，交付主 APK 保持不变。手机和平板复测分别为 **16.331 秒**、**21.115 秒**，均 **OK (1 test)**。日志为 `dist/verification/board-phone-events.txt`、`dist/verification/board-tablet-events.txt`；上述两组截图已更新为本次结果。已目视检查真实人员照片、红色停留框、蓝色人员出现框及关联录像入口，手机为单列事件卡片，平板为三列。

## 验证范围

- 本轮安装与仪器测试均在专用模拟器完成，**没有安装或操作用户的物理手机**。
- 实际开发板目前只有 AHD1 接摄像头。无信号处理通过模拟场景验证，尚未进行五个真实摄像头同时输入的客户端帧率、解码负载或持续运行性能验收。
- 当前记录没有给出安卓端五路预览的实测帧率。目标手机和平板的网络质量、解码能力、电量与温度表现仍需真机测量。
- Android 8.0 是编译声明的最低支持版本；本轮运行验证使用 Android 13 / API 33，不代表所有 Android 版本均已实机验证。


## 2026-09-08 Android 1.1.0 界面精简与升级验证

本章记录新版 1.1.0，前文 1.0.0 内容完整保留为历史快照。`dist/build-verification.json` 与 `dist/signature-verification.log` 已更新为 1.1.0；旧构建摘要归档于 `dist/verification-20260908/build/previous-build-verification.json`。

### 新版产物与覆盖安装

| 项目 | 实际结果 |
| --- | --- |
| APK | `dist/smart-recorder-1.1.0-debug.apk` |
| 包名 / 版本 | `com.neardi.recorder` / `1.1.0`，versionCode 2 |
| 最低版本 / target SDK | Android 8.0（API 26）/ API 36 |
| 大小 | 16,537,611 字节 |
| SHA256 | `1eb56e6cb46b529b35de4086cef5c7f4c07311852d99d4f6cfb91b49fc093d01` |
| 签名 | `apksigner verify --verbose --print-certs` 通过，v2 签名有效 |
| 与 1.0.0 的签名关系 | 包名相同，证书 SHA256 相同，versionCode 从 1 升至 2 |

新版和 1.0.0 的证书摘要均为 `0d5e977c920cf53512a11dcf1f4834abbd9d99b0f45b10f20b7a986be308c403`。除离线签名与版本比对外，主任务还在专用模拟器上实际先安装 1.0.0，再执行 `install -r` 安装 1.1.0，覆盖更新成功。本次仍是调试签名 APK，正式发布应使用长期保管的发布签名。

### 界面与功能

新版使用白底、近黑色主操作、灰色辅助文字、细分隔和低阴影；减少重复说明和多余空白，统一导航、筛选和设置组件。事件列表取消彩色卡片外框，在截图下的类型文字旁用小圆点区分长时间停留、人员、车辆、动物，继续使用红、蓝、紫、绿的类别含义。

五路独立预览、单路全屏、原生 H.264 回放、通道与事件组合筛选、设备状态、通道设置与批量应用、存储介质配置、日志下载和断网重连均保留。手机使用底部导航，平板使用侧栏及多列内容；设置草稿、保存过程、筛选条件和不同录像机配置隔离继续受回归测试覆盖。

### 构建与 JVM / Lint 结果

最终构建返回 `BUILD SUCCESSFUL`，准确统计为 **24 项 JVM 测试全部通过，失败 0、错误 0**。分组为 ConfigMerge 4、RecorderApi 10、ResponseValidation 2、MjpegReader 4、SettingsDraft 4。

Android Lint 为 **0 错误、13 警告**，包含依赖与 SDK 更新提示、图标与备份配置建议和 Kotlin 写法建议。APK 签名与包信息、构建日志、五份 JUnit XML、Lint XML 归档于 `dist/verification-20260908/build/`。

### Android 13 / API 33 界面与真板联调

| 验证 | 设备配置 | 结果 | 日志 |
| --- | --- | --- | --- |
| MockWebServer UI 回归 | 专用 API 33 模拟器 | **OK (5 tests)**，85.166 秒 | `dist/verification-20260908/mock-ui.txt` |
| 手机尺寸真实开发板联调 | 1080 × 2400，density 420，系统 night mode = yes | **OK (1 test)**，22.432 秒 | `dist/verification-20260908/board-phone.txt` |
| 平板尺寸真实开发板联调 | 2560 × 1600，density 240，系统 night mode = no | **OK (1 test)**，40.941 秒 | `dist/verification-20260908/board-tablet.txt` |

五项模拟 UI 测试验证未连接通道独立处理、全屏、录像/事件组合筛选、旋转后状态恢复、保存响应期间旋转且只提交一次配置、批量应用保留接线映射、服务恢复，以及切换设备后不恢复旧设备草稿。所有配置提交均指向 MockWebServer。

手机和平板尺寸均连接真实开发板 `192.168.10.172:8080`，验证 AHD1 实时图像与全屏、真实事件截图、原生录像播放达到 `Player.STATE_READY` 且播放位置前进、真实日志 ZIP 下载与解析。真实开发板联调为只读操作，没有提交测试配置；日志验证覆盖共享下载器及压缩包内容，未自动操作系统文件保存对话框。

两种尺寸各保留七张实际运行截图，目录为 `dist/verification-20260908/phone/`、`dist/verification-20260908/tablet/`。主任务已查看手机实时/事件、平板录像/事件截图，设置页检查也确认无控件重叠。手机录像列表截图恰好记录首次加载过程；实际录像播放断言已通过，平板截图中列表已完整显示，该截图时机不作为功能失败。

### 本轮验证范围

- 本轮只安装、测试专用模拟器；**未安装或操作用户的实体 Redmi 手机**。
- 实际开发板目前仅 AHD1 接摄像头。五个实体摄像头同时预览的安卓端帧率、长期解码负载、温升与电量表现尚未实测。
- 手机尺寸测试在系统深色模式开启时进行，但客户端按本次设计保持白底近黑文字；这验证该系统设置下可运行，不代表另行实现或验收了深色主题。
- Android 8.0 是最低编译支持版本；本轮运行验证是 Android 13 / API 33 模拟器，尚未覆盖各厂商实体手机/平板和所有 Android 版本。

## 2026-09-08 Android 1.2.0 通道详情与设置分类

本章对应 1.2.0，前文 1.0.0 与 1.1.0 记录保留为历史快照。当前构建摘要更新于 `dist/build-verification.json`，本轮构建证据独立归档于 `dist/verification-1.2.0/build/`。

### 产物与构建

| 项目 | 实际结果 |
| --- | --- |
| APK | `dist/smart-recorder-1.2.0-debug.apk` |
| 包名 / 版本 | `com.neardi.recorder` / `1.2.0`，versionCode 3 |
| 最低版本 / target SDK | Android 8.0（API 26）/ API 36 |
| 大小 | 16,020,485 字节 |
| SHA256 | `b04edbca30bdb061a6d6a9f99cb9aa9b623250db8ad7e144b9c43ae8bbeed053` |
| 签名 | `apksigner verify --verbose --print-certs` 通过，v2 签名有效 |
| 与 1.1.0 的关系 | 同包名、同签名证书，versionCode 从 2 升至 3；离线核验满足覆盖更新条件 |

签名证书 SHA256 为 `0d5e977c920cf53512a11dcf1f4834abbd9d99b0f45b10f20b7a986be308c403`，仍使用 Android 调试签名。签名检查本身没有执行设备安装。

执行 `build-apk.ps1 -BuildDeviceTests`，最终为 `BUILD SUCCESSFUL in 1m 29s`，主 APK 和仪器测试 APK 均成功生成，已包括横屏详情布局修复、截图检查点和可用列表高度断言。首次构建暴露新增测试误用了 Android `org.json` 未提供的比较方法，已改为测试内的递归 JSON 结构比较；失败日志和修复后成功日志均保留，没有修改业务逻辑来放宽断言。

准确统计 **26 项 JVM 测试全部通过，失败 0、错误 0**：ConfigMerge 6、RecorderApi 10、ResponseValidation 2、MjpegReader 4、SettingsDraft 4。本轮新增的配置测试验证按通道 ID 合并而非按数组位置、服务器通道重排时保留其他通道与扩展字段，以及全局存储修改不覆盖同时发生的通道修改。

Android Lint 为 **0 错误、13 警告**，主要仍为固定依赖/SDK 更新、图标/备份配置及 Kotlin 写法建议。构建日志、五份 JUnit XML、Lint XML、包信息与签名比对均归档于 `dist/verification-1.2.0/build/`。旧 1.1.0 构建摘要及签名日志以 `previous-` 前缀保留。

### 本轮功能与回归重点

点击摄像头卡片先进入通道详情：上方显示该路预览，下方切换仅属于该通道的录像和事件；右上角进入该路设置。全屏由预览内的显式按钮进入，普通点击画面不会切到全屏；退出全屏或回放后返回原通道详情并保留事件筛选。紧凑手机横屏采用左右分栏，为历史列表保留可滚动空间；竖屏和较高的平板使用上下布局。无信号通道也能打开自己的历史与设置。

全局设置分为设备连接、录像存储、识别模型、诊断日志四类，分类首页不再堆叠通道表单。单路设置复用共享校验和配置合并；全局、各通道按入口分别保存草稿和异步结果，避免另一通道成功保存误清此前失败的草稿。保留旋转恢复、批量应用、断网重连和不同录像机间的草稿隔离。

### Android 13 / API 33 运行验收

以下结果均对应上表最终主 APK。仪器测试 APK 的 SHA256 为 `61af100d98ca496c96c0322568835ce3762ec424492d1a94a0d692e1e458c437`。仅使用专用模拟器 `emulator-5556`，没有安装或操作用户的实体 Redmi 手机。

| 验证 | 尺寸 / 密度 | 结果 | 日志（相对 `dist/verification-channel-20260908/`） |
| --- | --- | --- | --- |
| 手机 MockWebServer 完整回归 | 1080 × 2400，density 420；包含旋转 | **OK (9 tests)**，64.168 秒 | `mock-phone.txt` |
| 详情旋转专项复测 | 手机尺寸 | **OK (1 test)**，13.003 秒 | `mock-channel-rotation.txt` |
| 平板 MockWebServer 核心回归 | 2560 × 1600，density 240；旋转后 1600 × 2560 | **OK (3 tests)**，21.721 秒 | `mock-tablet.txt` |
| 手机连接真实开发板 | 1080 × 2400，density 420 | **OK (1 test)**，23.048 秒 | `board-phone.txt` |
| 平板连接真实开发板 | 2560 × 1600，density 240 | **OK (1 test)**，24.758 秒 | `board-tablet.txt` |

九项手机模拟服务回归覆盖：

1. 五路独立显示，未接入四路不请求视频流；点击卡片先到详情，点击预览不误进全屏，显式全屏后可以返回。
2. 全局录像与事件的组合筛选，旋转后保留；被清理的录像不可点击播放。
3. 通道草稿在旋转后保持，批量应用保留其他通道名称/输入源/裁剪；保存响应期间旋转仍只提交一次 PUT 并正确显示完成。
4. 服务暂时不可用后自动恢复，保留所在页面与筛选。
5. 切换设备地址后不恢复旧录像机的未保存草稿，不自动提交这些草稿。
6. 详情中的录像与事件请求固定为当前通道，筛选在全屏返回与旋转后保持；旋转后的真实列表视口高度至少 120dp。
7. 从二号通道详情打开设置，保存只改变该通道的实际编辑项，其他通道和全局配置保持。
8. 全局分类首页没有通道字段；存储草稿在分类切换及旋转后保持，保存只修改存储配置。
9. 一号通道保存的延迟失败响应在离页后返回，随后二号通道成功保存；再回一号设置仍保留失败草稿和错误提示，不将二号成功误认为一号成功。

平板复跑上述详情旋转、单通道保存、全局分类与存储草稿三个用例，未重复计作新的独立测试。所有模拟配置 PUT 均发往模拟器本地 MockWebServer。

真实开发板两种尺寸均验证：AHD1 实时图像、通道详情与显式全屏；从本通道列表打开真实 MP4，播放器进入 `Player.STATE_READY` 且播放位置超过 500 毫秒；事件截图实际解码；共享下载器下载真实日志 ZIP 并成功解包至少三个条目；通道设置、全局分类和存储页可只读打开。测试没有向开发板提交配置。日志测试覆盖下载与 ZIP 内容，未自动操作 Android 系统文件保存对话框。

### 运行证据与目视检查

运行摘要及准确尺寸、APK 校验值记录于 `dist/verification-channel-20260908/verification-result.json`。最终截图目录为：

- `mock-phone-final/test-results/`：13 张手机截图，含详情竖屏/横屏、通道设置和分类首页检查点。
- `mock-tablet-final/`：7 张确定来自本轮平板测试的截图。文件名中的 portrait/landscape 表示测试旋转前后检查点，实际尺寸以前述表格和摘要为准。
- `board-phone/verification/`、`board-tablet/verification/`：两组各 10 张真实开发板联调截图。

上述截图路径相对 `dist/verification-channel-20260908/`。已查看手机详情两种方向、分类首页，以及手机/平板实际画面、回放、事件和设置截图；最终布局未见控件重叠。手机 `phone-channel-settings.png` 截图恰好记录切页前的事件详情最后一帧，不将该图片当作设置页证据；后续实际设置字段截图、模拟设置截图和平板设置截图有效，设置页面断言已通过。

本轮回归确实发现并修复了问题：首轮 7/9 的两处失败源于测试在滚动后未先将返回按钮滚入视区；随后 8/9 暴露横屏分栏切换时历史组合位置改变导致筛选丢失。已改为同一布局节点只调整测量与放置，保持回归断言，再得到最终 9/9。失败日志分别保留为 `mock-phone-initial.txt`、`mock-phone-layout-regression.txt`，未覆盖；最终交付依据为 `mock-phone.txt`。

### 本轮边界

实际板上只有 AHD1 接摄像头。本轮确认单路实际预览/回放与五路逻辑通道容错，没有进行五个实体摄像头同时输入的安卓端帧率、长时间解码负载、温升或耗电验收。APK 使用调试签名，最低声明为 Android 8.0；实际运行验证为 API 33 模拟器，未覆盖全部 Android 版本或各厂商实体手机和平板。

<a id="android-130-b"></a>

## 2026-09-08 Android 1.3.0 方案 B 与主题

用户最终改选方案 B：等权视频墙，浅色、深色和跟随系统三种主题，默认浅色。此前方案 A 的候选构建和截图仅保留为历史证据，不是本次交付。当前完整摘要为 `dist/build-verification.json`；本轮构建、测试日志与截图独立归档于 `dist/verification-b-20260908/`。

### 最终产物与构建

| 项目 | 结果 |
| --- | --- |
| APK | `dist/smart-recorder-1.3.0-debug.apk` |
| 包名 / 版本 | `com.neardi.recorder` / `1.3.0`，versionCode 4 |
| 最低 / target SDK | API 26 / API 36 |
| 大小 | 16,005,186 字节 |
| SHA256 | `f86a406321ed7e68d84cdc3c3b94d29d809e8eb0b99b90b30091d29e968d76ea` |
| 签名 | `apksigner verify` 通过，v2 签名有效 |
| 覆盖更新 | 与 1.2.0 同包名、同证书，versionCode 从 3 升至 4；专用模拟器中已实际执行覆盖安装，最终安装日志为 `build/final-install.log` |

签名证书 SHA256 仍为 `0d5e977c920cf53512a11dcf1f4834abbd9d99b0f45b10f20b7a986be308c403`，使用 Android 调试签名。最终测试 APK SHA256 为 `83430c8a5ae76341eb1976c360d2f98b9dac5640bb45fd6391feaac1a576d895`。

最终执行 `build-apk.ps1 -BuildDeviceTests`，返回 **BUILD SUCCESSFUL in 1m 43s**。**36 项 JVM 测试全部通过，失败 0、错误 0**：ConfigMerge 6、RecorderApi 10、ResponseValidation 2、MediaDownload 4、MjpegReader 4、RecordingTimeline 6、SettingsDraft 4。Android Lint 为 **0 错误、14 警告**，主要为已有依赖/SDK 更新、图标/备份配置及 Kotlin 写法建议。最终构建日志为 `build/final-alignment-build.log`，JUnit XML、Lint XML、包信息、签名结果和生产源码 SHA256 清单均在 `build/` 下。

新增 JVM 验证包括：真实片段时间轴的缺口/通道隔离、重叠与半开区间边界、跨午夜和夏令时、精确播放偏移；17 MiB 录像下载流式 CRC 校验、日志已知/未知长度的 16 MiB 上限、传输中断不能误报成功。录像下载在客户端显式允许最大 2 GiB，日志保持默认 16 MiB。

### 实际运行结果

运行环境为唯一自有 API 33 / Android 13 Google Play x86_64 无窗口模拟器。手机尺寸 1080 × 2400、density 420；平板尺寸 2560 × 1600、density 240，测试内旋转至 1600 × 2560。各组日志相对 `dist/verification-b-20260908/`：

| 分组 | 结果 | 耗时 | 日志 |
| --- | --- | --- | --- |
| 手机浅色 Mock 全量 | 11 / 11 | 90.817 秒 | `mock-phone-light.txt` |
| 手机深色 Mock 全量 | 11 / 11 | 78.771 秒 | `mock-phone-dark.txt` |
| 平板浅色 Mock 核心 | 5 / 5 | 39.881 秒 | `mock-tablet-light.txt` |
| 平板深色 Mock 核心 | 5 / 5 | 33.925 秒 | `mock-tablet-dark.txt` |
| 手机浅色真实开发板 | 1 / 1 | 37.384 秒 | `board-phone-light-final.txt` |
| 手机深色真实开发板 | 1 / 1 | 39.834 秒 | `board-phone-dark-final.txt` |
| 平板浅色真实开发板 | 1 / 1 | 25.047 秒 | `board-tablet-light-final.txt` |
| 平板深色真实开发板 | 1 / 1 | 36.671 秒 | `board-tablet-dark-final.txt` |

Mock 共有 **11 个独立用例、32 次分组执行**。覆盖五路独立状态与四路缺席不请求视频、显式全屏、通道详情历史范围、事件分类组合筛选与旋转保持、断网重连、设备切换草稿隔离、异步保存期间旋转只提交一次、批量应用保留接线映射、全局存储与单通道设置互不覆盖、另一通道成功不清除先前失败草稿。所有配置 PUT 都发往模拟器内 MockWebServer。

本轮新增的界面断言验证：浅/深主题即时生效、Activity 重建后选择仍保留、跟随系统明暗变化、状态栏与导航栏图标明暗正确；外观设置只存本机，不发板端配置。日期选择使用两天的真实格式片段数据，前后切换和旋转保持选择；点击时间轴缺口必须显示“所选时间没有已加载的可用片段”，且不能发起媒体请求。

真实板端组通过 `192.168.10.172:8080` 验证 AHD1 实时图像、通道详情与全屏、MP4 达到 `STATE_READY` 且进度实际推进、暂停后前后 15 秒、全屏/旋转保持暂停和位置、非全屏 16:9、事件截图解码、诊断 ZIP 下载并解包至少三个条目，以及通道/分类/存储设置页只读打开。**未向开发板发送配置 PUT，也未重启板端服务**；本轮没有对板端配置做前后摘要相等校验，因此不声称已证明配置摘要不变。

最终媒体 trace 为：手机浅色 1782 → 16782 → 1782 ms；手机深色 1033 → 16033 → 1033 ms；平板浅色 2246 → 17246 → 2246 ms；平板深色 1550 → 16550 → 1550 ms，均保持暂停且回到 `STATE_READY`。平板两个方向都断言视频顶边与上方日期/时间控件接近：实际视频 top=147 px、时间 top=232 px，间隔 56.667 dp，避免视频在整页居中而控件靠顶部。实际 bounds、duration、位置、状态与按钮语义记录在各组 `verification/playback-steps.txt`。

验证按修改范围推进：32 次 Mock 回归运行于 B 候选 `edb2a1e0…620887f`；其后只修复 PlaybackScreen 的相对定位，手机两组与首轮平板两组运行于 `d31b628a…ce96736`；最后仅调整平板分栏视频的 Y 位置，平板浅/深在最终 `f86a4063…968d76ea` 上再次完成全部媒体断言。未将未重跑的组误记为最终字节产物上的新执行；各组完整 APK 校验值见 `verification-result.json`，两个前序 APK 也保存在 `build/`。

### 本轮发现与修复

- 深色首次完整 Mock 的失败来自输入法未收起时保存按钮没有实际触发。测试显式收起键盘并确认按钮可见可用，保留原 PUT 次数、数据与失败草稿断言，随后 11 / 11 通过。失败日志以 `mock-phone-dark-initial.txt` 保留。
- 平板实际回放暴露生产问题：暂停在 2046 ms，前进后只到 15463 ms，时长 299756 ms、按钮可用且播放器已 READY，排除片尾钳制与持续缓冲。原因是按钮使用了旧的 UI 缓存位置；现改为点击当下的 `player.currentPosition`，并按实时有效时长钳制。原始 trace 与失败截图保留在 `board-tablet-light-diagnostic/`，修复后四组实际位移均精确为 15000 ms，没有放宽断言。
- 目视检查发现平板竖屏左视频与右控制区垂直错位，已将分栏视频顶边与控件对齐，保留同一播放器组合节点和 16:9；最终两组平板新增顶边断言通过。
- 一次手机浅色真实 ZIP 下载出现 `unexpected end of stream`，下载器正确抛出失败；原日志 `board-phone-light-final-log-download-failure.txt` 保留。代码与断言不变，重新执行该组后完整下载及解包通过。短读的传输根因未单独归因；平板深色先前也曾有单张事件缩略图短读，其他图片仍可显示。

### 截图、边界与清理

最终媒体截图分别在 `board-phone-light-final/verification/`、`board-phone-dark-final/verification/`、`board-tablet-light-final/verification/`、`board-tablet-dark-final/verification/`，各 12 张。Mock 手机每组 20 张、平板每组 13 张，位于对应分组 `test-results/`。已目视检查手机浅深首页、通道详情、分类设置、日期时间轴，以及平板浅深回放两种方向；最终布局与颜色通过。部分实时切页截图记录连接中的过渡帧，不能单凭该张图片声称已解码；实际预览与播放器断言、已解码截图分别保留。

模拟器时区为 **GMT / UTC**，例如系统栏 07:35 对应 Windows 本地 15:35。客户端按运行设备的本地时区显示录像日期时间，没有修改开发板时钟。

当前录像接口只提供最新 200 条，无服务端分页或按日检索。日期和时间轴仅代表已加载片段，空白不代表完整全天从未录像。普通录像有真实起点，可显示绝对日期/时间；事件关联录像没有起点时显示相对进度。日志/录像下载测试覆盖共享网络层与 ZIP 内容，未自动操作 Android 系统文件保存选择器。

实际开发板仅接入 AHD1；未验收五个实体摄像头同时输入时的安卓端帧率、长期解码负载、温升或耗电。本轮只在 API 33 模拟器运行，未操作用户 USB Redmi 手机或其他实体手机/平板。Android 8.0 为声明的最低支持版本，不能把 API 33 运行结果等同于全部系统和厂商机型验收。

已用 `adb -s emulator-5556 emu kill` 关闭本轮自有模拟器，确认 launcher PID 30132、qemu PID 28772 均退出且 ADB 中该序列号消失；没有调用全局 `adb kill-server`。清理记录为 `emulator-stopped.json`，完整分组结果及产物信息为 `verification-result.json`。冻结后未再改动生产源码。

</details>

</details>
