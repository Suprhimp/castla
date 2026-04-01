<p align="center">
  <img src="docs/images/app-icon.png" width="120" alt="Castla App Icon">
  <h1 align="center">Castla</h1>
  <p align="center">
    <strong>特斯拉的终极Android Auto替代方案。在特斯拉浏览器中直接投射Waze、Google地图和手机屏幕。</strong>
  </p>
  <p align="center">
    <a href="https://github.com/Suprhimp/castla/releases/latest"><img src="https://img.shields.io/github/v/release/Suprhimp/castla?style=flat-square" alt="Latest Release"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square" alt="License"></a>
    <a href="https://ko-fi.com/suprhimp"><img src="https://img.shields.io/badge/Ko--fi-Support-ff5e5b?style=flat-square&logo=ko-fi&logoColor=white" alt="Ko-fi"></a>
  </p>
  <p align="center">
    <a href="README.md">English</a> · <a href="README.ko.md">한국어</a> · <a href="README.ja.md">日本語</a> · <a href="README.de.md">Deutsch</a> · <a href="README.es.md">Español</a> · <a href="README.fr.md">Français</a>
  </p>
</p>

<p align="center">
  <img src="docs/images/hero.jpg" width="700" alt="Castla - 将Android投射到Tesla屏幕">
</p>

---

## 什么是Castla？

特斯拉没有**Android Auto**让你烦恼？想在大屏幕上使用**Waze**、Google地图？

Castla是一款免费开源解决方案，通过本地WiFi网络将安卓手机屏幕直接串流到特斯拉内置浏览器。无需互联网、无需昂贵的配件、无需云服务器、无需订阅 — 一切都在手机和车辆之间快速、安全地运行。

**核心亮点：**

- **Android Auto体验** — 在特斯拉屏幕上使用你最爱的导航和音乐应用
- **实时投屏** — H.264硬件编码 + WebSocket串流，超低延迟
- **完整触控** — 直接在特斯拉屏幕上点触、滑动、操控手机（通过Shizuku）
- **音频串流** — 将设备音频直接传输到特斯拉扬声器（Android 10+）
- **100%本地且私密** — 所有数据仅在WiFi/热点内传输
- **完全免费** — 无广告、无付费墙。GPL-3.0开源

## 功能

| 功能 | 详情 |
|------|------|
| **大屏导航** | **Waze**、Google地图等流畅运行，最高1080p @ 60fps |
| **触控输入** | 通过Shizuku完整触控注入。从车载屏幕操控手机 |
| **分屏浏览** | 双面板多任务。左边Waze，右边YouTube！ |
| **虚拟显示器** | 无需亮屏即可在特斯拉上独立运行应用 |
| **音频** | 系统音频捕获（Android 10+，实验性） |
| **特斯拉自动检测** | BLE + 热点客户端检测自动连接 |
| **自动热点** | 投屏开始/停止时自动开关热点 |
| **OTT浏览器** | 内置DRM内容浏览器（YouTube、Netflix等） |
| **温控保护** | 设备过热时自动降低画质保护电池 |
| **9种语言** | EN, KO, DE, ES, FR, JA, NL, NO, ZH |

## 系统要求

- Android 8.0+（API 26）
- 触控和高级功能需要 [Shizuku](https://shizuku.rikka.app/)
- 配备浏览器的特斯拉车辆
- 手机和特斯拉在同一WiFi网络（或使用手机热点）

## 安装

1. 前往 [Releases](https://github.com/Suprhimp/castla/releases/latest)
2. 下载最新 `.apk` 文件
3. 安装到安卓设备

## 快速开始

1. **安装Shizuku** — 打开Castla，点击"安装Shizuku"
2. **启动Shizuku** — 开发者选项 → 无线调试 → 打开Shizuku → "通过无线调试启动"
3. **授权** — 允许Castla使用Shizuku
4. **连接** — 确保手机和特斯拉在同一WiFi
5. **开始投屏** — 在Castla中点击"开始投屏"
6. **在特斯拉打开** — 在特斯拉浏览器中输入显示的URL

## 贡献

欢迎贡献！详情请阅读 [贡献指南](CONTRIBUTING.md)。

## 隐私

Castla **不收集任何数据**。详情请查看 [隐私政策](PRIVACY.md)。

## 支持独立开发者

<a href="https://ko-fi.com/suprhimp"><img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="在Ko-fi上支持"></a>

## 许可证

[GNU General Public License v3.0](LICENSE)
