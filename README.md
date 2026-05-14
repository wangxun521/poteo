# PhoneCam Monitor

Android 11+ 手机摄像头监控 App。本地分片录制 + 自适应码率 + 满 10GB 循环覆盖 + 局域网浏览器实时预览（HLS）。

## 项目结构

```
app/src/main/java/com/example/phonecam/
├── MainActivity.kt              # UI: 启停 + 显示局域网预览地址
├── service/RecordingService.kt  # 前台服务: 拉起摄像头、HTTP 服务器、HLS
├── recorder/
│   ├── SegmentRecorder.kt       # CameraX VideoCapture, 60s 一片 MP4
│   └── BitrateController.kt     # 根据片大小自动调档 500k-4M
├── storage/
│   └── CircularStorageManager.kt# 超 10GB 删最旧
├── streaming/
│   ├── HlsPackager.kt           # ffmpeg-kit 把 MP4 -> .ts + m3u8
│   └── LocalHttpServer.kt       # NanoHTTPD :8080
└── ../assets/index.html         # 浏览器播放页(hls.js)
```

## 构建

### 方式 A — GitHub Actions（推荐）

1. 把整个 `PhoneCamMonitor/` 目录 push 到 GitHub 仓库。
2. 推送后 `.github/workflows/build.yml` 自动触发：装 JDK17、Android SDK、Gradle 8.4，生成 wrapper 并 `assembleDebug`。
3. 构建完成后在 **Actions → 本次运行 → Artifacts** 下载 `PhoneCamMonitor-debug`，里面是 `app-debug.apk`。
4. 也可以在 Actions 页面点 **Run workflow** 手动触发。

无需把 `gradlew`/`gradle-wrapper.jar` 提交进仓库 —— CI 用 `gradle wrapper` 命令现场生成。

### 方式 B — Android Studio

1. 用 Hedgehog 及以上打开本目录，按提示让 IDE 生成 `gradle-wrapper.jar`、`local.properties`。
2. Build → Build APK。

### ffmpeg-kit 依赖说明

`arthenica/ffmpeg-kit` 项目 2025 年 1 月已归档，但 `6.0-2.LTS` 仍在 Maven Central。如果将来该包被删导致 CI 失败，把 [app/build.gradle.kts](app/build.gradle.kts) 里的依赖切换为注释中的社区 fork（JitPack 仓库已配好）。

## 使用

1. 安装到 Android 11+ 手机，授予摄像头/麦克风/通知权限。
2. 点击 **开始监控**。屏幕可灭，前台服务保活继续录制。
3. 把手机和电脑/手机连到**同一 WiFi**，在浏览器打开屏幕上显示的地址，例如：
   `http://192.168.1.23:8080/`
4. 视频文件位于 `/sdcard/Android/data/com.example.phonecam/files/videos/`，命名 `yyyyMMdd_HHmmss.mp4`，每段 60s。

## 配置参数

代码内常量：

| 位置 | 含义 | 默认 |
|---|---|---|
| `RecordingService.SEGMENT_DURATION_MS` | 单片时长 | 60s |
| `RecordingService.STORAGE_LIMIT_BYTES` | 本地容量上限 | 10 GB |
| `RecordingService.HTTP_PORT` | HTTP/HLS 端口 | 8080 |
| `BitrateController.targetSegmentBytes` | 目标片大小 | 15 MB ≈ 2 Mbps |
| `BitrateController.minBitrate / maxBitrate` | 码率范围 | 500k / 4M |
| `HlsPackager.tsTargetSec / windowSize` | HLS 切片时长 / 直播窗口 | 6s × 6 |

## 已知限制

- HLS 直播延迟 3–6 秒（监控可接受）；要更低延迟需换 WebRTC/RTSP 方案。
- 仅支持局域网。要走公网请把 `LocalHttpServer` 暴露到反向代理 / frp。
- 码率档位切换需要重建 `Recorder`，本版本只在下次启动时生效（避免片间黑屏）。
- `hls.js` 通过 CDN 加载，首次预览需要外网；要纯离线请把 `hls.min.js` 放到 `app/src/main/assets/` 并在 HTML 里改回相对路径。
- 未实现密码鉴权；如需公开网络访问请自行加 token 校验。
