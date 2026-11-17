# 原项目来源与第三方声明

## WebView 电视 / WebViewTvLive

- 原项目：[hxh19950701/WebViewTvLive](https://github.com/hxh19950701/WebViewTvLive)。
- 原作者：**hxh19950701** 及原项目贡献者。
- 原项目说明：[简体中文 README](https://github.com/hxh19950701/WebViewTvLive/blob/main/README_CN.md)。
- 本项目关系：基于其历史 Android / Kotlin / WebView 工程修改和扩展，保留历史 namespace 及代码目录组织。感谢原作者，本项目不是上游官方版本，也不宣称所有代码独立原创。
- 本项目主要改动：直接媒体播放与类型探测、M3U 多线路与缓存、主备订阅更新、遥控器焦点和菜单、开机自启、调台方向设置、无内置源与无默认订阅的公开处理。

原作者及相关权利人的权利和声明保留。未找到适用于整个历史工程的明确标准开源许可证；来源署名不代表额外授权，也没有用本项目原创文档的 MIT 许可覆盖上游代码。详细核查见[许可边界](docs/LICENSING.md)。

## HLS.js 1.5.13

- 项目：[video-dev/hls.js](https://github.com/video-dev/hls.js/tree/v1.5.13)。
- 随附文件：`app/src/main/assets/hls-1.5.13.min.js`。
- 保留原始 Dailymotion 及 Brightcove 等相关版权声明，见[原始声明](app/src/main/assets/hls-LICENSE.txt)。
- 授权为 Apache License 2.0，全文见 [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt)。

## 构建时使用的第三方项目

| 组件 | 本工程中的用途 | 项目或官方资料 |
| --- | --- | --- |
| AndroidX / Media3 | Android 组件与原生媒体播放 | [AndroidX](https://developer.android.com/jetpack/androidx)、[Media3](https://github.com/androidx/media) |
| 腾讯 X5 TBS | WebView 与网页兼容播放 | [腾讯浏览服务](https://x5.tencent.com/) |
| AndroidAutoSize | 电视界面尺寸适配 | [JessYanCoding/AndroidAutoSize](https://github.com/JessYanCoding/AndroidAutoSize) |
| OkHttp / Okio | 网络请求及底层 I/O | [square/okhttp](https://github.com/square/okhttp)、[square/okio](https://github.com/square/okio) |
| Kotlin / Coroutines | 语言与异步任务 | [JetBrains/kotlin](https://github.com/JetBrains/kotlin)、[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| Gson | JSON 相关工具 | [google/gson](https://github.com/google/gson) |
| Apache Commons Lang | 通用工具 | [Apache Commons Lang](https://commons.apache.org/proper/commons-lang/) |
| JUnit / MockWebServer | 单元测试与本地 HTTP 测试 | [JUnit 4](https://github.com/junit-team/junit4)、[OkHttp](https://github.com/square/okhttp) |
| Gradle Wrapper | 获取指定构建工具版本 | [Gradle](https://github.com/gradle/gradle) |

Gradle 依赖未作为二进制包复制进本仓库。各组件的实际使用、修改和再分发范围以相应版本许可为准；此表是来源索引，不为它们统一授予许可，也不表示本项目获得组件作者背书。
