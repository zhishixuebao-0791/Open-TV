# 实现、构建条件与验证范围

## 当前仓库能做什么

当前仓库包含 Kotlin / Android 源码、资源、JVM 单元测试及 Gradle Wrapper，可自行构建不带直播源和默认订阅的 APK。版本为 `1.6.16-preview`，未提供预编译安装包。

项目基于 [WebViewTvLive](https://github.com/hxh19950701/WebViewTvLive) 历史代码改造，不能把源码公开或署名视为上游额外授权；具体范围见[来源说明](../NOTICE.md)和[许可边界](LICENSING.md)。

## 模块职责

| 模块 | 职责 |
| --- | --- |
| Activity 与原生控件 | 界面状态、频道目录、遥控器事件、焦点管理和触屏操作 |
| PlaylistManager | 用户订阅配置、更新调度、主备获取、有效缓存和状态通知 |
| M3uParser / ChannelIdentity | 频道表校验、分组、名称规范化和多线路合并 |
| HttpFetch | 有大小限制的 HTTP 读取、条件请求与取消处理 |
| StreamResolver / HlsStreamProbe | 媒体类型判断以及部分 HLS 容器和音轨探测 |
| ChannelPlayerView | 原生 / 网页播放路径选择、加载超时、线路回退、画面比例 |
| SettingsManager / BootReceiver | 用户偏好、调台方向以及显式启用后的完整开机自启 |

原生播放使用 Media3 / ExoPlayer；网页路径保留腾讯 X5 WebView。HLS.js 仅在相应网页兼容播放路径中使用。没有实现转播服务或向其他用户分发节目内容的服务端。

无源版不包含 Bugly 依赖及原上游的上报初始化，不向上游项目的崩溃统计账号提交信息。第三方 X5 SDK 自身的网络行为与条款需要单独审阅，不能据此承诺“绝对没有第三方网络请求”。

## 工具链

| 项目 | 当前工程配置 |
| --- | --- |
| 语言 | Kotlin，JVM 字节码目标 1.8 |
| Android 最低版本 | minSdk 21，即 Android 5.0；只是声明的下限，并非逐版本实测结论 |
| 编译 / 目标 API | compileSdk 36 / targetSdk 36 |
| Build Tools | 36.0.0 |
| Gradle Wrapper | 8.11.1 |
| Android Gradle Plugin | 8.10.1 |
| Kotlin 插件 | 2.2.21 |
| 已用于本机构建的 Java | JDK 22 |
| Media3 | 1.1.0 |

不要根据较新的 targetSdk 推断旧版 Media3、第三方 SDK 或每种设备都已经完成完整兼容验证。依赖升级属于后续独立验证工作。

## 从源码构建

先获取代码：

```sh
git clone https://github.com/zhishixuebao-0791/Open-TV.git
cd Open-TV
```

1. 安装兼容的 JDK、Android SDK Platform 36 和 Build Tools 36.0.0。
2. 使用本机环境变量 `JAVA_HOME`、`ANDROID_HOME`，或在不提交的 `local.properties` 中设置 SDK 路径。
3. 使用仓库的 Gradle Wrapper 执行单元测试、Lint 和 Debug 构建。

Windows PowerShell：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

macOS / Linux：

```sh
chmod +x gradlew
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows 的 `local.properties` 盘符冒号需要按 Java Properties 规则转义，例如 `sdk.dir=D\:/Android/SDK`；路径由开发者自行替换。不要提交 SDK、本机代理、签名密钥、调试日志、账号或订阅表。通常构建输出在 `app/build`；本机验证使用了独立的 `build_local` 输出目录。

发布版需要开发者自己的签名和相应的依赖许可审查；本项目不会分发私人签名密钥或沿用其他人的统计账号。无源版使用独立 applicationId `io.github.zhishixuebao0791.opentv`，避免覆盖维护者正在使用的私人安装版本及其偏好。Kotlin namespace 仍为 `com.vasthread.webviewtv`，源目录保留上游历史组织方式。

第一次构建需要联网下载 Gradle 和 Maven 依赖。若本机网络需要代理，请在你自己的用户级 Gradle 配置或命令参数中设置，不要把代理端口、账号或 SDK 路径提交到仓库。

## 验证边界

验证记录需要区分三个对象：

- **历史私人版：** 在有限设备上验证过部分播放、遥控器逻辑及完整系统重启；这些历史记录、截图、日志和 APK 不随此仓库发布。
- **无源版源码：** 单独进行构建、单元测试、Lint 和包内隐私核查；具体结果见[发布状态](RELEASE_STATUS.md)。
- **设备验收：** 本次没有将无源版安装到电视或手机，不把历史私人版的实机记录写作公开版本的实机验收。

自动化按键注入不等同于全部实体遥控器验证；有限设备测试也不代表所有 Android TV、投影设备或长时间运行场景都已覆盖。
