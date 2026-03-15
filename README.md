# SyncWatch - 同步观影应用

<div align="center">

![SyncWatch](https://img.shields.io/badge/SyncWatch-v1.0-blue)
![Android](https://img.shields.io/badge/Android-24%2B-green)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-purple)
![License](https://img.shields.io/badge/License-MIT-yellow)

**一个支持多人实时同步观影的 Android 应用，让朋友们可以在不同设备上同时观看同一部视频。**

[功能](#功能) • [快速开始](#快速开始) • [架构](#架构) • [常见问题](#常见问题)

</div>

---

## 📱 功能

### 核心功能
- **🎬 实时同步播放** - 多个用户可以在不同设备上同时观看同一部视频，播放进度实时同步
- **👥 房间管理** - 创建观影房间，邀请朋友加入，支持最多 10 人同时观看
- **🎮 播放控制权** - 房主可以授予其他用户播放控制权，支持暂停、播放、快进等操作
- **⚡ 低延迟同步** - 基于 WebSocket 的实时通信，确保多设备间的同步精度
- **🎚️ 倍速播放** - 支持 0.5x 到 3.0x 的播放倍速调整
- **📊 多格式支持** - 支持 HLS、DASH、MP4 等多种视频格式

### 高级功能
- **🔄 自动重连** - 网络波动时自动重新连接
- **📍 播放进度校准** - 加入房间时自动同步当前播放进度
- **🎯 控制权管理** - 灵活的控制权请求和授予机制
- **💬 实时成员列表** - 显示房间内所有成员及其状态
- **🔐 房间密码保护** - 每个房间都有独立的密码，防止未授权访问

---

## 🚀 快速开始

### 系统要求
- **Android 7.0+** (API 24+)
- **Node.js 14+** (服务器)
- **Java 17+** (编译)

### 安装步骤

#### 1. 克隆项目
```bash
git clone https://github.com/yourusername/syncwatch.git
cd syncwatch
```

#### 2. 启动服务器

```bash
cd server
npm install
npm start
```

服务器将在 `ws://localhost:8080` 启动

**Docker 部署（可选）**
```bash
cd server
docker-compose up -d
```

#### 3. 编译 Android 应用

```bash
cd app
./gradlew assembleDebug
```

或使用 Android Studio：
1. 打开 `app` 文件夹
2. 点击 "Run" 或按 `Shift + F10`

#### 4. 配置服务器地址

在 `MainActivity.kt` 中配置服务器地址：
```kotlin
private val serverUrl = "ws://your-server-ip:8080"
```

### 使用流程

#### 房主创建房间
1. 打开应用
2. 输入昵称
3. 选择视频文件或输入视频 URL
4. 点击"创建房间"
5. 分享房间号和密码给朋友

#### 用户加入房间
1. 打开应用
2. 输入昵称
3. 输入房间号和密码
4. 点击"加入房间"
5. 等待房主开始播放

---

## 🏗️ 架构

### 系统架构图

```
┌─────────────────────────────────────────────────────────┐
│                    SyncWatch 系统架构                      │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  ┌──────────────┐         ┌──────────────┐              │
│  │  Android App │         │  Android App │              │
│  │   (Device 1) │         │   (Device 2) │              │
│  └──────┬───────┘         └──────┬───────┘              │
│         │                        │                       │
│         │      WebSocket         │                       │
│         └────────────┬───────────┘                       │
│                      │                                   │
│              ┌───────▼────────┐                          │
│              │  Signaling     │                          │
│              │  Server        │                          │
│              │  (Node.js)     │                          │
│              └────────────────┘                          │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

### 项目结构

```
syncwatch/
├── app/                              # Android 应用
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/syncwatch/app/
│   │   │   │   ├── MainActivity.kt              # 主界面
│   │   │   │   ├── PlayerActivity.kt            # 播放器界面
│   │   │   │   ├── SyncWatchApplication.kt      # 应用类
│   │   │   │   ├── network/
│   │   │   │   │   └── SignalingClient.kt       # WebSocket 客户端
│   │   │   │   ├── sync/
│   │   │   │   │   └── SyncEngine.kt            # 同步引擎
│   │   │   │   └── dlna/
│   │   │   │       └── DlnaReceiverService.kt   # DLNA 服务
│   │   │   └── res/                             # 资源文件
│   │   └── build.gradle                         # 依赖配置
│   └── settings.gradle
│
├── server/                           # Node.js 服务器
│   ├── index.js                      # 主服务器文件
│   ├── package.json                  # 依赖配置
│   ├── Dockerfile                    # Docker 配置
│   └── docker-compose.yml            # Docker Compose 配置
│
└── README.md                          # 本文件
```

### 核心模块说明

#### SignalingClient (网络通信)
负责与服务器的 WebSocket 通信，处理：
- 房间创建和加入
- 播放状态同步
- 控制权管理
- 成员列表更新

```kotlin
// 创建房间
signalingClient.createRoom(nickname, videoUrl)

// 加入房间
signalingClient.joinRoom(roomId, password, nickname)

// 发送同步信息
signalingClient.sendSync(action, position, speed, videoUrl)
```

#### SyncEngine (同步引擎)
管理播放器状态同步，处理：
- 播放进度同步
- 播放速度同步
- 自动校准
- 延迟补偿

```kotlin
// 应用同步状态
syncEngine.applySyncState(syncState, isManual = false)

// 校准播放进度
syncEngine.calibrate()
```

#### 服务器 (Node.js)
WebSocket 服务器，负责：
- 房间管理
- 消息转发
- 成员管理
- 状态维护

---

## 📡 通信协议

### WebSocket 消息格式

所有消息都是 JSON 格式，包含 `type` 字段标识消息类型。

#### 创建房间
```json
{
  "type": "create_room",
  "nickname": "房主昵称",
  "videoUrl": "https://example.com/video.m3u8"
}
```

#### 加入房间
```json
{
  "type": "join_room",
  "roomId": "12345678",
  "password": "123456",
  "nickname": "用户昵称"
}
```

#### 同步播放状态
```json
{
  "type": "sync",
  "action": "play",
  "position": 12345,
  "speed": 1.0,
  "videoUrl": "https://example.com/video.m3u8",
  "timestamp": 1234567890
}
```

#### 请求控制权
```json
{
  "type": "control",
  "action": "request"
}
```

#### 授予控制权
```json
{
  "type": "control",
  "action": "grant",
  "targetId": "client-id-123"
}
```

更多消息类型详见 [PROTOCOL.md](./PROTOCOL.md)

---

## 🔧 配置

### 服务器配置

编辑 `server/index.js`：

```javascript
const PORT = process.env.PORT || 8080;
const MAX_ROOM_SIZE = 10;
const HEARTBEAT_INTERVAL = 30000;
const HEARTBEAT_TIMEOUT = 60000;
```

### Android 应用配置

编辑 `app/app/src/main/java/com/syncwatch/app/MainActivity.kt`：

```kotlin
// 服务器地址
private val serverUrl = "ws://your-server-ip:8080"

// 视频源配置
private val defaultVideoUrl = "https://example.com/video.m3u8"
```

### 环境变量

**服务器**
```bash
PORT=8080              # 服务器端口
NODE_ENV=production    # 运行环境
```

---

## 🐛 常见问题

### Q: 加入的用户播放视频时出现 HTTP 402 错误？

**A:** 这是 CDN 授权限制导致的。视频 URL 中的 `auth_key` 参数通常有以下限制：
- ⏱️ 时间限制：通常 5-30 分钟后过期
- 🌐 IP 绑定：只允许从请求它的 IP 地址访问
- 📱 设备限制：只允许在原始设备上使用

**解决方案：**
1. 使用公开视频 URL（不需要授权）进行测试
2. 实现后端代理，为每个用户单独获取授权 URL
3. 联系 CDN 提供商，申请支持多设备的授权令牌

### Q: 如何在局域网内使用？

**A:** 
1. 确保手机和服务器在同一网络
2. 获取服务器的局域网 IP（如 `192.168.1.100`）
3. 在应用中配置：`ws://192.168.1.100:8080`

### Q: 支持哪些视频格式？

**A:** 支持以下格式：
- **流媒体格式**：HLS (.m3u8)、DASH (.mpd)
- **渐进式格式**：MP4、WebM、MKV
- **直播流**：RTMP、HLS 直播

### Q: 如何部署到生产环境？

**A:** 
1. 使用 Docker 部署服务器
2. 配置 HTTPS/WSS（WebSocket Secure）
3. 使用反向代理（Nginx）
4. 配置防火墙规则

详见 [部署指南](./DEPLOYMENT.md)

### Q: 支持的最大用户数是多少？

**A:** 默认配置支持每个房间最多 10 人。可以在服务器配置中修改：

```javascript
if (room.members.size >= 10) {
  return sendTo(ws, { type: 'error', message: '房间已满' });
}
```

### Q: 如何调试网络问题？

**A:** 查看日志：

```bash
# Android 日志
adb logcat -s SignalingClient:*

# 服务器日志
node index.js  # 直接运行查看控制台输出
```

---

## 📊 性能指标

| 指标 | 数值 |
|------|------|
| 同步延迟 | < 500ms |
| 支持最大用户数 | 10 人/房间 |
| 最大房间数 | 无限制 |
| 网络带宽 | ~10KB/s |
| 内存占用 | ~50MB (Android) |

---

## 🔐 安全性

- ✅ 房间密码保护
- ✅ WebSocket 连接验证
- ✅ 自动断线重连
- ✅ 心跳检测（防止僵尸连接）
- ⚠️ 建议在生产环境使用 WSS (WebSocket Secure)

---

## 📝 许可证

本项目采用 MIT 许可证。详见 [LICENSE](./LICENSE) 文件。

---

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

### 贡献步骤
1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

---

## 📧 联系方式

- 📮 提交 Issue：[GitHub Issues](https://github.com/yourusername/syncwatch/issues)
- 💬 讨论：[GitHub Discussions](https://github.com/yourusername/syncwatch/discussions)

---

## 🙏 致谢

感谢以下开源项目的支持：
- [ExoPlayer (Media3)](https://developer.android.com/guide/topics/media/media3)
- [OkHttp](https://square.github.io/okhttp/)
- [WebSocket (ws)](https://github.com/websockets/ws)
- [Gson](https://github.com/google/gson)

---

## 📚 相关文档

- [快速开始指南](./docs/GETTING_STARTED.md)
- [通信协议文档](./docs/PROTOCOL.md)
- [部署指南](./docs/DEPLOYMENT.md)
- [故障排查](./docs/TROUBLESHOOTING.md)
- [开发指南](./docs/DEVELOPMENT.md)

---

<div align="center">

**[⬆ 回到顶部](#syncwatch---同步观影应用)**

Made with ❤️ by SyncWatch Team

</div>
