<div align="center">

# 🍏 EhViewer (Native Compose + Rust)

**极致流畅、符合直觉、极具“Apple 味”的第三方开源阅读器**

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-7F52FF.svg?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4.svg?style=flat-square&logo=android)](https://developer.android.com/jetpack/compose)
[![Rust](https://img.shields.io/badge/Rust-1.75+-000000.svg?style=flat-square&logo=rust)](https://www.rust-lang.org)
[![Platform](https://img.shields.io/badge/Platform-Android_8.0+-3DDC84.svg?style=flat-square&logo=android)](#)
[![License](https://img.shields.io/badge/License-GPL_3.0-blue.svg?style=flat-square)](#)

*本项目已全面重构：抛弃原有 Flutter 架构，拥抱 **Android 原生 (Kotlin + Jetpack Compose)** 与 **Rust** 强劲双擎。*

[特性](#-核心特性) • [架构](#-架构演进) • [编译](#-编译与运行) • [路线图](#-开发路线图) • [贡献](#-参与贡献)

</div>

---

## ✨ 架构演进

EhViewer 经历了彻底的底层革新。我们深知，要达到真正“丝滑跟手”的物理级交互，跨平台框架的渲染层始终存在瓶颈。因此，我们将项目拆解为了两个极致的端点：

1. **🎨 视觉与交互层 (Android Native / Jetpack Compose)**
   完全舍弃 Flutter，采用纯原生 Jetpack Compose 构建极具质感的 UI 界面。从零实现了带物理弹簧反馈 (Spring Physics) 的卡片按压、空间连续性的原生级页面推拽转场、以及细腻的高光材质描边。

2. **⚙️ 性能与逻辑层 (Rust)**
   网络请求、HTML 解析、并发下载引擎、种子/H@H 档案处理等重度计算逻辑全部下沉至 Rust 端。通过 JNI (`jni_bridge`) 直接与 Kotlin 高效通信，保证了极致的解析速度与极低的内存占用。

> **🎯 架构终极目标**: 低功耗、纯物理动画反馈、零多余 GPU 渲染负担、全面适配 Edge-to-Edge 沉浸式。

## 🚀 核心特性

### 🍎 极致果味 UI (Apple-Style UI)
- **弹簧动力学 (Spring Dynamics)**：彻底告别生硬的线性补间动画，所有的按压、滑动、弹出都拥有真实的物理重量感。
- **沉浸式透视排版**：全屏无极瀑布流，画廊封面滑过半透明毛玻璃搜索栏，实现真正的 Edge-to-Edge 视觉享受。
- **空间逻辑转场**：右侧顺滑推入/推出的系统级空间逻辑转场，每一次页面跳转都符合直觉。
- **极致细节打磨**：全圆角药丸状 (Pill-shape) 标签、精调的超大字重对比、以及模拟真实物理高光的悬浮舱边框。

### 📖 超强阅读器体验
- 高性能横向/纵向双向 Pager 丝滑滚动，告别白屏等待。
- 物理光影反射质感的 HUD 悬浮面板，轻触即隐，沉浸阅读。

### 🛠 硬核底层支撑
- **Rust 强力驱动**: 毫秒级的并发页面解析与原生日语标签翻译。
- **纯 `arm64-v8a` 极致优化**: 丢弃无用的多架构冗余，专为现代旗舰芯片压榨性能。
- **Haptic Engine**: 深度集成的系统级微触觉振动反馈网络。

## 📦 编译与运行

本项目采用标准的 `Gradle` + `Cargo` 混合构建系统：

### 1. 环境准备
- 安装 **Android Studio** (推荐最新版)。
- 安装 **Rust 工具链** (`rustup`, `cargo`)。
- 为 Rust 添加 Android 交叉编译目标：
  ```bash
  rustup target add aarch64-linux-android
  ```
- 下载并配置好 Android NDK（确保环境变量正确）。

### 2. 构建工程
在项目根目录执行以下命令，Gradle 脚本会自动触发 Cargo 构建 Rust 动态链接库 (`libehviewer_rust.so`) 并打包至 APK 中：
```bash
cd android
./gradlew assembleRelease
```

### 3. 目标设备要求
- **Min SDK**: Android 8.0 (API 26)+
- **架构支持**: `arm64-v8a` 专属优化

## 🗺 开发路线图

- [x] 核心业务逻辑全面迁移至 Rust 端 (下载引擎、高级搜索、本地化翻译)
- [x] UI 渲染层彻底替换为 Jetpack Compose
- [x] Apple 风格 UI/UX (Spring 动画、毛玻璃、药丸标签) 深度细节打磨
- [ ] 画廊评论区完整交互实现及深度树形回复展示
- [ ] 离线下载管理的进一步优化与断点续传加固
- [ ] 局域网内 H@H 客户端无缝发现与串流阅读

## 🤝 参与贡献

我们非常欢迎来自社区的贡献！无论你是：
- **Compose 魔法师**：对动画曲线和 UI 质感有极高追求。
- **Rust 极客**：热衷于压榨内存和 CPU 的并发性能。

请直接提交 PR！在提交代码前，请确保符合项目的整体设计哲学（极简、原生、高性能）。

## 📄 许可证

本项目基于 [GPL-3.0 License](LICENSE) 协议开源。

---
<div align="center">
  <sub>Made with ❤️ for ACG Enthusiasts</sub>
</div>
