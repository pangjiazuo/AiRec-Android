# 客户端接口速查

这份文档供开发客户端的人使用。只想安装或使用应用，请返回 [项目首页](../README.md)。

客户端通过 HTTP 连接录像机，不需要复制服务端 Python 代码。

| 想做什么 | 接口 |
| --- | --- |
| 查看设备和通道状态 | `GET /api/status` |
| 读取设置 | `GET /api/config` |
| 保存设置 | `PUT /api/config` |
| 查看第一路实时画面 | `GET /stream/1.mjpg` |
| 查询完整时间窗口的录像与事件 | `GET /api/timeline` |
| 下载诊断日志 | `GET /api/logs/download` |

保存前先读取完整配置，只修改需要的字段。时间轴应查询完整日期范围，不能用最近 200 条录像拼成全天。参数、响应和错误处理见下方完整协议。

<details>
<summary>查看完整协议与示例</summary>

# 客户端开发说明

板卡地址示例：`http://192.168.10.172:8080`。本文对应当前源码中的 HTTP 接口。

## 需要哪些代码

| 位置 | 职责 | 开发客户端时的用法 |
| --- | --- | --- |
| `web/static/index.html`、`app.js`、`style.css` | 网页界面、接口调用、预览与回放 | 可以复用或参考 |
| `web/server.py` | 开发板上的 HTTP 服务 | 留在开发板运行，向各种客户端提供接口 |
| `client/launcher.py` | 可选的 Tkinter 启动窗口，打开系统浏览器 | 目前只是入口，不是完整的原生播放器 |
| 独立 Android 仓库 `AiRec-Android/` | Kotlin / Compose 原生安卓客户端 | 已实现独立预览、回放、事件、设置及日志下载，可参考数据层和媒体层 |
| 独立鸿蒙仓库 `AiRec-Harmony/` | ArkTS / ArkUI 原生鸿蒙客户端 | 通过相同 HTTP 接口连接板端，构建与服务端分离 |
| `run.py`、`core/`、`capture/`、`detection/`、`storage/` | 配置、采集、录像、识别、事件和存储 | 留在开发板运行，客户端不用复制这些模块 |

表内 `web/`、`client/` 和业务模块均相对于 Ubuntu 服务端仓库根目录；移动客户端是相邻的独立仓库，单独克隆时不要求该目录关系。如果做桌面 WebView 客户端，直接加载开发板网址即可复用现有界面。如果做独立 Python、Qt 或其他原生界面，调用以下 HTTP 接口即可；客户端可以放进独立仓库，不需要导入服务端 Python 模块。

单独复制整个 `web/` 不能得到完整可运行的录像机：其中的服务端依赖其他业务模块。直接双击本地 HTML 也不能自动连接板卡，因为现有前端使用 `/api/...` 等同源相对地址。

## 主要接口

通道编号为 `1`～`5`。JSON 接口返回 UTF-8；失败时通常返回 `{"error":"中文原因"}`，客户端应先检查 HTTP 状态。

| 方法与路径 | 用途与返回 |
| --- | --- |
| `GET /api/health` | 服务存活、版本、运行时长 |
| `GET /api/status` | `channels`、`detector`、`system`、`storage`、`uptime_seconds`；通道包含在线状态、帧率和 `detections` |
| `GET /api/config` | 获取完整配置 |
| `PUT /api/config` | 提交完整配置，返回 `ok`、保存后的 `config`、`restart_required` |
| `GET /api/storage/targets` | `targets` 可用介质列表及 `selected_id`；配置应使用列表中的 `id` |
| `GET /api/devices` | `devices` 中的采集设备路径、名称 |
| `GET /stream/1.mjpg` | 通道 1 的实时 MJPEG 长连接，其余通道替换编号 |
| `GET /api/snapshot/1.jpg` | 通道 1 的当前 JPEG 截图 |
| `GET /api/recordings?channel_id=1` | `items` 为已完成片段，包含 `url`、`available` 等；省略通道则查询全部 |
| `GET /api/events?channel_id=1&event_type=dwell` | `items` 为筛选后的事件；两个筛选条件都可独立省略 |
| `GET /api/timeline?channel_id=1&start=...&end=...` | 指定通道、时间窗口内的全部相交已完成录像及合并事件区间，用于全天时间轴；详见下文 |
| `GET /media/...` | 使用列表返回的媒体地址回放或下载；支持单段 HTTP Range 请求 |
| `GET /api/diagnostics/model` | 当前识别模型、运行后端、跟踪器等信息 |
| `GET /api/logs` | 当前日志清单 |
| `GET /api/logs/download` | 下载 ZIP 诊断包；同一时间只生成一个包 |

原有 `/api/recordings` 和 `/api/events` 列表按时间从新到旧返回，各最多 200 条，行为保持不变。事件筛选在数据库查询时执行，不是在这 200 条中再次筛选。全天回放使用独立的 `/api/timeline` 时间范围接口，不把这 200 条当作全天记录；当前没有列表分页、远程重启或单条删除 API。

## 全天时间轴

`GET /api/timeline` 必须各传入一次以下参数：

| 参数 | 约束 |
| --- | --- |
| `channel_id` | 必填，整数 `1`～`5`；不支持一次查询全部通道 |
| `start` | 必填，带时区的 ISO8601 时间，例如 `2026-09-08T00:00:00+08:00` 或 `2026-09-07T16:00:00Z` |
| `end` | 必填，格式同 `start`，且真实时刻晚于 `start`；窗口最大 26 小时 |

时间格式包含日期、时、分、秒，可含最多 6 位小数秒；时区为 `Z` 或 `±HH:MM`。请求按实际时刻比较，允许首尾偏移不同；26 小时上限用于兼容夏令时切换的 25 小时日。客户端用选定日期的当地零点与次日当地零点构造窗口，不把所有日期硬编码成 86400 秒。必须使用标准 URL 查询编码，尤其将 `+08:00` 的加号编码为 `%2B`。

示例：`/api/timeline?channel_id=1&start=2026-09-08T00%3A00%3A00%2B08%3A00&end=2026-09-09T00%3A00%3A00%2B08%3A00`。

响应顶层固定为以下结构，空日也正常返回 HTTP 200 和空数组：

```json
{
  "start": "2026-09-07T16:00:00+00:00",
  "end": "2026-09-08T16:00:00+00:00",
  "recordings": [
    {
      "id": "example-recording-id",
      "channel_id": 1,
      "created_at": "2026-09-07T23:59:30+08:00",
      "duration_seconds": 60.0,
      "size_bytes": 1048576,
      "target_id": "internal",
      "available": true,
      "error": "",
      "url": "/media/recordings/ch1/example.mp4",
      "name": "example.mp4"
    }
  ],
  "event_segments": [
    {
      "start": "2026-09-07T16:00:03+00:00",
      "end": "2026-09-07T16:00:04+00:00",
      "event_type": "person"
    }
  ]
}
```

顶层 `start` / `end` 统一返回 UTC ISO8601，真实时刻和请求一致。窗口采用半开区间 `[start,end)`：片段恰好结束于 `start` 或开始于 `end` 不属于本窗口。

- `recordings` 包含全部与窗口相交、持续时长有效的已完成片段，包括开始于前一日或结束于次日的片段。字段与旧录像列表相同；`created_at` 保留原始值、`duration_seconds` 保留完整片段长度，不裁剪字段。按真实起点升序，同起点按 `id` 排序。客户端绘制时再将范围裁到窗口，定位播放偏移始终相对完整片段起点计算。
- `.mp4.part`、尚未完成封装的片段不会返回；索引中未知、为零或异常的时长无法确定覆盖范围，也不会据此填充录像区间。循环清理、外置介质拔出或文件丢失时，残留索引可返回 `available:false` 和 `error`，不可绘为可播放覆盖；列表加载之后媒体仍可能消失，应处理 HTTP 404。
- `event_segments` 每项为 `start`、`end`、`event_type`，统一 UTC ISO8601，已经裁到查询窗口。同类型区间重叠或首尾严格相接时合并，类型之间独立；按起点及类型排序。不同颜色只表示事件类型，不表示该时段一定存在录像。
- 长时间停留事件使用已保存的 `created_at` 与 `dwell_seconds` 导出 `[created_at-dwell_seconds, created_at)`；其时长截至事件触发，不代表触发之后仍然持续。出现事件没有已记录的持续量，只返回 `[created_at,created_at+1秒)` 的可见点标记，**不代表目标实际只出现或停留 1 秒**。历史停留记录缺少有效持续量时也仅作为 1 秒点标记。
- 时间轴保留无录像的真实空白。客户端不能将相邻片段之间的空白自动补成录像，自动续播只允许下一个可用片段与当前片段严格相接或重叠，不能隐含设置跨空白容差。空白上的事件标记也不能作为可播放依据。

服务端通过 SQLite 日期函数与表达式索引比较真实 UTC 时刻，不直接比较带不同时区偏移的字符串；旧版无时区索引按 UTC 解释，无效时间记录不参与时间轴。日期函数的毫秒精度只用于索引预筛选，随后依据原始时间精确判断交集。实现依据 [SQLite 日期与时间函数](https://www.sqlite.org/lang_datefunc.html) 和 [表达式索引](https://www.sqlite.org/expridx.html)，使用板端 SQLite 已有能力，不依赖新版 `unixepoch('subsec')`。

为限制内存和查询开销，每次查询的录像候选行、事件候选行分别最多 10000 条；事件合并之前就检查上限。实际 UTF-8 JSON 响应还必须不超过 2MiB，与移动客户端读取预算一致；任意一项超限均返回 422，绝不静默截成前 200 条或返回伪完整结果。超过上限时客户端应缩短窗口或明确提示无法加载完整时间轴。查询使用独立只读 WAL 快照，数据库查询执行预算为 2 秒；同时最多处理 2 个时间轴请求，媒体文件检查不占用录像写入锁。

| HTTP 状态 | 含义 |
| --- | --- |
| `200` | 返回完整窗口结果，也可能为空 |
| `400` | 缺失、重复或非法参数，未带时区，时间顺序错误，窗口超过 26 小时 |
| `422` | 录像或事件候选行超过 10000 条，或 UTF-8 JSON 响应超过 2MiB；返回 `{"error":"中文原因"}`，没有部分结果 |
| `503` | 查询名额已满、数据库繁忙或查询超时；返回同一 `error` 字段，可退避重试 |

旧版板端对 `/api/timeline` 返回 `404` 时，客户端应提示需要升级板端，不能回退到 200 条列表后仍将其显示为全天覆盖。接口查询失败时也不能把失败伪装成“全天无录像”。

## 事件类型

`event_type` 用于四类事件筛选；不传表示全部。传入其他值会返回 HTTP 400。

| `event_type` | 含义 |
| --- | --- |
| `person` | 人首次确认出现 |
| `vehicle` | 车首次确认出现，不进行停留检测 |
| `animal` | 动物首次确认出现 |
| `dwell` | 人或动物持续出现达到该通道停留阈值 |

同一条跟踪轨迹的出现事件只保存一次；人和动物超时后再各保存一次长时间停留事件。目标离开、轨迹失效后重新出现会作为新目标处理。检测开关和所选识别类别仍然生效。

事件字段中的 `category` 表示目标类别（`person` / `vehicle` / `animal`），与 `event_type` 分工不同。例如长时间停留事件的 `event_type` 是 `dwell`，`category` 仍是 `person` 或 `animal`。`label` 保留模型识别标签；`dwell_seconds` 用于停留时长展示，新车辆记录固定为0且界面不显示计时。升级前的记录均保留原有停留事件含义并补上 `event_type: "dwell"`，包括旧版车辆停留记录；新规则只影响后续触发。

`created_at` 是带时区的 ISO 8601 时间，客户端转换为当地时间显示。`snapshot_url` 为事件截图；关联录像片段封装完成后才可能出现 `recording_url`。未出现该字段不代表事件失败，录像关闭时也可以保存事件截图。

## 保存配置

先读取最新的完整配置，在其副本上修改需要的字段，然后整体 PUT。必须保留五路通道以及各自 `id`、`source`、`crop` 等字段；接口不支持只提交一个字段的 PATCH。多客户端编辑时采用最后一次保存结果，没有版本冲突检测。

以下示例在由开发板服务打开的网页环境中，将 AHD1 的人和动物停留阈值设为 10 秒：

```javascript
async function saveDwellThreshold(seconds) {
  const response = await fetch('/api/config', {cache: 'no-store'});
  if (!response.ok) throw new Error('读取配置失败');
  const config = await response.json();
  config.channels.find(channel => channel.id === 1)
    .detection.threshold_seconds = seconds;
  const saved = await fetch('/api/config', {
    method: 'PUT',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(config)
  });
  const result = await saved.json();
  if (!saved.ok) throw new Error(result.error || '保存配置失败');
  return result;
}
```

请求体上限为 65536 字节；停留阈值范围是 0.1～3600 秒；录像片段只能选 1、3、5、10 分钟。以 `core/config.py` 的校验和服务返回值为准。普通通道设置由服务自行应用；修改监听地址或端口会返回 `restart_required: true`，当前接口不会替客户端重启服务。

## 连接、媒体与故障处理

- 网页直接从板卡服务加载可保持同源。当前服务没有跨域 CORS 接口，配置 PUT 也会拒绝 `Origin` 与 `Host` 不一致的请求。本地 `file://` HTML 或另一个端口托管的网页不能直接照搬现有调用；原生客户端可使用自己的 HTTP 网络层。不要通过关闭浏览器安全机制解决跨域问题。
- 使用板卡作为基础地址解析返回的媒体地址，例如 `new URL(item.snapshot_url, 'http://192.168.10.172:8080').href`，不要拼接本机文件路径。原生客户端的播放器同样使用完整 HTTP 地址。
- MJPEG 是连续 JPEG 图片流，不是 H.264 录像流。网页可用 `<img>` 显示；原生客户端需读取 multipart 边界并逐帧解码 JPEG。切换页面或关闭窗口时释放长连接，服务当前最多允许 16 个预览连接。
- 无信号的通道会返回 HTTP 503；流中途丢失信号会结束连接。每个通道独立重试，建议从 1 秒逐步延长到 10 秒，不要让某一路阻塞其他画面。
- 状态接口可按 1～2 秒轮询，并保证上一次完成后再发下一次；列表约 5 秒刷新一次即可。请求应有超时、取消和断网重试，不能无限叠加。
- `available: false`、HTTP 404 或无关联录像都要有对应界面提示。循环清理会删除旧媒体，列表加载成功不保证文件随后一直存在。HTTP 503 也可能表示预览或诊断包正在占用服务名额。
- 当前服务用于可信局域网，没有账号认证和 TLS；客户端应按这个部署范围设计。若后续需要公网或多用户访问，需要另行实现认证与传输保护。

</details>
