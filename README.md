# PhoneCam Monitor

Android 11+ 手机摄像头监控 App。本地分片录制 + 自适应码率 + 满 10GB 循环覆盖 + 局域网浏览器实时预览（MJPEG）。

## 架构

```
CameraX
 ├─ VideoCapture (H.264 + 可选 AAC) ──→ 本地 60s 分片 MP4
 │                                      ──→ CircularStorageManager (满 10GB 删最旧)
 ├─ Preview ───────────────────────── 手机本机实时画面 (开关)
 └─ ImageAnalysis (YUV → JPEG, ~10fps)
                                       ──→ MjpegStreamer (内存里保留最新帧)
                                              ──→ NanoHTTPD :8080 /stream.mjpeg
                                                     ──→ 浏览器 <img>
```

**录制和直播是两条独立通道**：录像走 H.264+AAC 进 MP4，直播走 MJPEG 走 HTTP。互不影响。

## 项目结构

```
app/src/main/java/com/example/phonecam/
├── MainActivity.kt                   # UI: 启停 + 本机预览开关 + 音频开关 + 显示局域网地址
├── service/RecordingService.kt       # 前台服务: 摄像头、HTTP 服务器、MJPEG
├── recorder/
│   ├── SegmentRecorder.kt            # CameraX VideoCapture + Preview + ImageAnalysis
│   └── BitrateController.kt          # 根据片大小自动调档 500k-4M (作用于录制)
├── storage/
│   └── CircularStorageManager.kt     # 超 10GB 删最旧
├── streaming/
│   ├── MjpegStreamer.kt              # ImageAnalysis Analyzer, YUV→JPEG
│   └── LocalHttpServer.kt            # NanoHTTPD: / /stream.mjpeg /snapshot.jpg
└── ../assets/index.html              # 浏览器播放页 (<img>)
```

## 构建

### 方式 A — GitHub Actions（推荐）

1. push 到 GitHub，[`.github/workflows/build.yml`](.github/workflows/build.yml) 自动跑
2. 完成后从 **Actions → Artifacts** 下载 `PhoneCamMonitor-debug.zip` 解压安装

### 方式 B — Android Studio

打开本目录，Build → Build APK。

## 使用

1. 装到 Android 11+ 手机，授予摄像头/麦克风/通知权限
2. 点击 **开始监控**。屏幕可灭，前台服务保活继续录制
3. 同 WiFi 下浏览器打开屏幕显示的地址，例如 `http://192.168.1.107:8080/`
4. 播放页有两个按钮：
   - **重连** — MJPEG 断流时手动恢复
   - **切到快照模式** — 改为每秒拉一张静态图（更省流量，更稳）
5. 视频保存在 `/sdcard/Android/data/com.example.phonecam/files/videos/`，60s 一片

## 路由表

| URL | 内容 |
|---|---|
| `/` | 播放页 HTML |
| `/stream.mjpeg` | `multipart/x-mixed-replace` 持续 JPEG 帧流 |
| `/snapshot.jpg` | 当前最新一帧 JPEG（用 `<img>` 周期刷新即可做轻量直播） |

## 配置参数

| 位置 | 含义 | 默认 |
|---|---|---|
| `RecordingService.SEGMENT_DURATION_MS` | 单片时长 | 60s |
| `RecordingService.STORAGE_LIMIT_BYTES` | 本地容量上限 | 10 GB |
| `RecordingService.HTTP_PORT` | HTTP 端口 | 8080 |
| `MjpegStreamer.targetFps` | 直播帧率 | 10 fps |
| `MjpegStreamer.quality` | JPEG 质量 | 60 |
| `SegmentRecorder` ImageAnalysis 分辨率 | 直播分辨率 | 640×480 |
| `BitrateController` | 录制码率 | 500k–4M |

## 已知限制

- **MJPEG 直播无音频**（音频仍写入本地 MP4，受界面开关控制）。如需音频直播请改 WebRTC
- 仅支持局域网。要走公网请把 8080 端口反向代理出去（frp / cloudflare tunnel 等）
- 无鉴权 —— 同 WiFi 任何人能看
- ImageAnalysis 分辨率写死 640×480，平衡 CPU 和带宽。要 720p 改 [SegmentRecorder.kt](app/src/main/java/com/example/phonecam/recorder/SegmentRecorder.kt) 里的 `Size(640, 480)`

## 历史踩坑记录

- arthenica `ffmpeg-kit` 2025 年 1 月归档，2025 年中 Maven Central 删除全部产物
- `mobile-ffmpeg` 同步被删
- writingminds 的 `FFmpegAndroid` 0.3.2 还在，但只有 armv7 二进制，老 ffmpeg 3.0.1 转码不稳

所以彻底放弃 HLS + ffmpeg 路线，改用 MJPEG —— 零 native 依赖、任意 ABI、延迟 <300ms。
