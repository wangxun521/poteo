# PhoneCam Monitor

Android 11+ 手机摄像头监控 App。本地分片录制 + 自适应码率 + 满 10GB 循环覆盖 + 局域网浏览器实时预览（MJPEG）+ 远程切摄像头/调参/回放。

## 主要功能

- **多摄像头**：通过 Camera2 `CameraManager.cameraIdList` 枚举所有物理摄像头 ID 并尝试绑定（覆盖 MIUI 把广角/长焦藏为独立 ID 的情况），运行时切换 + 同摄像头内变焦
- **MJPEG 实时预览**：浏览器打开就能看，延迟 <300ms
- **录像存储**：H.264 + 可选 AAC，60s 一片 MP4，满 10GB 删最旧
- **远程调参**：浏览器调整直播分辨率、帧率、JPEG 质量、录制档位、码率、音频开关
- **远程回放**：浏览器列出所有录像，按日期/时间范围筛选，点击播放（支持拖进度条，HTTP Range）
- **直播水印**：MJPEG 帧底部烧入 `yyyy-MM-dd HH:mm:ss`（可开关）
- **温度监控**：读取 `/sys/class/thermal/thermal_zone*` 和电池温度，网页顶部徽章显示 CPU/电池温度，>45°C 黄、>55°C 红
- **播放页**：旋转、全屏、MJPEG/快照切换
- **手机端**：摄像头选择、本机实时画面开关、音频开关

## 架构

```
CameraX
 ├─ VideoCapture (H.264 + 可选 AAC) ──→ 60s MP4 分片
 │                                      ──→ CircularStorageManager
 ├─ Preview ───────────────────────── 手机本机实时画面 (开关)
 └─ ImageAnalysis (YUV → JPEG)
                                       ──→ MjpegStreamer
                                              ──→ NanoHTTPD :8080
                                                     ├─ /stream.mjpeg
                                                     ├─ /snapshot.jpg
                                                     ├─ /cameras  /switch
                                                     ├─ /config (GET/POST)
                                                     └─ /recordings  /recordings/<name>
```

录制 / 直播 / 回放是三条独立通道，**任何一条卡住不影响其它**。

## HTTP API

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/` | 播放页 HTML |
| GET | `/stream.mjpeg` | multipart/x-mixed-replace JPEG 流 |
| GET | `/snapshot.jpg` | 当前最新一帧（轻量直播） |
| GET | `/cameras` | `{"current":"C0","cameras":[{"id":"C0","label":"后置 广角 (5.4mm)","facing":"后置"}, ...]}` |
| POST | `/switch?id=C2` | 切换摄像头，鉴权 = 同网段 |
| GET | `/config` | 返回当前 StreamConfig |
| POST | `/config?streamWidth=640&streamHeight=480&streamFps=10&jpegQuality=60&recQuality=HD&recBitrate=2000000&saveAudio=true&cameraId=C0` | 改参数；任意子集即可，省略字段保持当前值 |
| GET | `/recordings` | 录像列表 `{"items":[{"name":"...","size":..,"mtime":..}]}` |
| GET | `/recordings/<name>` | 播放/下载 MP4，支持 `Range:` 拖进度条 |
| GET | `/thermal` | `{"cpuC":45.2,"batteryC":32.1,"zones":[{"name":"cpu-0-0-usr","tempC":45.2},...]}` |
| GET | `/storage` | `{"usedBytes":...,"limitBytes":...,"deviceFreeBytes":...,"fileCount":...}` |
| POST | `/storage?limitGb=10` | 调整本地容量上限（0.1–200 GB） |
| GET | `/zoom` | `{"available":true,"min":0.6,"max":10.0,"current":1.0}` |
| POST | `/zoom?ratio=2.5` | 设置变焦倍率（含跨物理镜头切换） |

## 项目结构

```
app/src/main/java/com/example/phonecam/
├── MainActivity.kt
├── service/RecordingService.kt
├── recorder/
│   ├── CameraRegistry.kt        # 枚举摄像头，焦距标签
│   ├── StreamConfig.kt          # 配置数据类 + 持久化
│   ├── SegmentRecorder.kt       # CameraX 绑定 + 优雅 rebuild
│   └── BitrateController.kt
├── storage/CircularStorageManager.kt
└── streaming/
    ├── MjpegStreamer.kt
    ├── LocalHttpServer.kt
    └── BoundedRafStream.kt      # Range 请求的有界 InputStream
```

## 构建

push 到 GitHub → Actions 自动跑 → Artifacts 下载 `PhoneCamMonitor-debug.zip` → 装机。

## 使用

1. 装到 Android 11+ 手机，授权
2. 点 **开始监控**，屏幕显示 `http://192.168.1.107:8080/`
3. 浏览器打开该地址，三个 Tab：
   - **实时** — 直播 + 摄像头按钮 + 旋转/全屏
   - **设置** — 滑杆调直播分辨率/帧率/JPEG质量；下拉/滑杆调录制档位/码率/音频
   - **回放** — 录像列表，点击播放，可拖进度条
4. 视频文件位于 `/sdcard/Android/data/com.example.phonecam/files/videos/`，60s 一片

## 已知限制

- MJPEG 直播无音频（音频仍写入本地 MP4）。要音频直播请改 WebRTC
- 仅局域网。要走公网请反代 8080
- **无鉴权** — 同 WiFi 任何人可看可改参数。准备公开访问前请加 token 校验
- 切摄像头/改参数 = 当前段录制立刻结束 + 重建管线，会丢约 1-2 秒画面
- 部分手机厂商把多摄合并为单一逻辑摄像头（如 Pixel）；这种情况只能看到前/后 2 个，要用 zoom 切换镜头
- 直播分辨率不能超过 ImageAnalysis 支持的范围；超出会自动 fallback 到最近档
- **本地 MP4 录制目前没有水印**。CameraX 1.3 没有官方"硬件叠加"API；要给录像加水印得引入 `androidx.camera:camera-effects` 实验包或自己写 OpenGL 管线，工作量较大。当前水印只在 MJPEG 直播流上
- **CameraX 不可用的摄像头**：CameraRegistry 会枚举所有 Camera2 ID，但有些被 OEM 标记为 "不可被 CameraX 绑定"。这种条目标签后会显示 `(CameraX 不可用)`，点击会切换失败 —— 这是 CameraX 设计限制，绕不过去
- **录制帧率**：通过 `VideoCapture.Builder().setTargetFrameRate()` 提示编码器，**实际帧率受光照/曝光影响**；暗光环境下系统会降帧。设到 60fps 不保证拿到 60fps，更像是上限
- **温度**：Android 没公开 CPU 温度 API，靠读 `/sys/class/thermal/`。部分厂商（华为、近年三星）SELinux 锁死该路径，会显示"系统未暴露任何热区"；电池温度走 `BatteryManager`，几乎所有机型都能拿到

## 历史决策

早期版本走 HLS + ffmpeg，因 ffmpeg-kit / mobile-ffmpeg 2025 年都从 Maven Central 下架，writingminds 老版本在部分机器跑不起来，**最终拆掉所有 native 依赖，改 MJPEG**。延迟从 3-6s 降到 <300ms，APK 也小了 ~30MB。
