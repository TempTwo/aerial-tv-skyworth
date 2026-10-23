# Aerial 电视桌面 · 创维 8H52_G3 二次修改版

> 本仓库是 [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv) 的**二次修改版**，
> 原作者为 **pengchujin**，本仓库保留其 MIT 许可证与全部版权声明（见 [LICENSE](LICENSE)）。
>
> 二次修改由 **TempTwo** 完成，仅为让 Aerial 在**创维 8H52_G3** 这台老电视上真正好用。

---

## 这是什么

[Aerial](https://github.com/pengchujin/aerial-tv) 是一款参照 tvOS 设计的 Android TV 桌面：
没有广告、没有推荐流，只有你的 App、你的壁纸和航拍屏保。

原版 Aerial 面向较新的国产电视（小米、TCL 等）。本仓库把它适配到了
**创维 8H52_G3（Hi3751V551 / Android 5.1.1 / API 22 / 2016 年机器）**，
并修复了原版在老设备上的若干问题。

## 下载

Releases 页提供可直接安装的 APK：

| 版本 | 说明 |
| --- | --- |
| `aerial-skyworth-8h52-v1.20.apk` | 最新版：和风天气、联网校时、创维背光控制，**已在创维 8H52_G3 实测** |
| `aerial-skyworth-8h52-v1.19.apk` | 上一版：移除新版本检测、信号源改用厂商 provider |

- 包名 `app.orchard.tv`，版本 `1.20`（versionCode 22）
- `minSdk 21` / `targetSdk 34`，原生 ABI：arm64-v8a、armeabi-v7a、x86、x86_64
- 与上游同为**同一个签名 key**（上游 `local/` 目录里的 key），因此可以用 `adb install -r` 覆盖升级

## 相对上游改了什么

### 1. 适配 Android 5.1（API 22）

上游 Aerial 默认面向 Android 6.0+。在 API 22 的老设备上会直接崩溃或无法安装，改动包括：

| 项 | 上游 | 本仓库 |
| --- | --- | --- |
| `minSdk` | 23 | **21**（覆盖到 Android 5.0） |
| Compose BOM | 2025.x | **降级到支持 API 21 的版本**（Compose 1.10 把 minSdk 提到了 23） |
| Media3 | 1.9+ | **1.8.0**（1.9 起要求 API 23） |
| 其他 | — | 关闭脱糖（desugaring），因为老设备不需要 |

### 2. 个性化面板：二维码缩小、壁纸完整显示

原版个性化面板里，二维码卡片（176dp）和壁纸缩略图（168dp）尺寸过大，
在 1080p 的小屏上**二维码与左侧内容重叠**、壁纸被裁掉。

- 二维码卡片：`176dp → 124dp`，容器 `220dp → 156dp`
- 壁纸缩略图：`168dp → 160dp`，间距 `28dp → 20dp`
- 给壁纸容器加 `clipToBounds`，四张壁纸完整显示
- 缩小后二维码仍可被 OpenCV 正常解码（内容为 `http://<电视IP>:8790/<token>/`）

### 3. 右键菜单「应用信息」改为自绘面板

原版的「应用信息」调用 `ACTION_APPLICATION_DETAILS_SETTINGS`，
但这台电视**没有 AOSP 的 Settings 应用**，点击无反应。

新增 `ui/home/AppInfoSheet.kt`，自绘一个应用信息面板，直接显示：
应用名、版本号、安装大小、安装时间，并提供「启动」「卸载」「关闭」操作。

### 4. 其他

- 适配 `armeabi-v7a`（本机 `ro.product.cpu.abilist` 只有 32 位）
- 修正硬解音频解码器选择（`OMX.hisi.audio.decoder.mp3`）

### 5. 信号源：改用厂商 TV SDK provider（修复列表为空）

原版从 `TvInputManager` 读信号源。**这台电视的固件没有 `tv_input` 服务**
（`TvInputManager` 无任何输出），所以设置面板里的信号源一直是空的。

改为走创维自己的 TV SDK provider：

| 项 | 说明 |
| --- | --- |
| 列表 | `content://com.skyworth.tv.sdk.provider/system/source_list` |
| 读取的列 | `sourceName` / `sourceIndex` / `displayName` / `signalState` / `sourceId` |
| 切源 | `call("METHOD_SWITCH_SOURCE", …)`，extras 带 `sourceId` / `sourceName` / `sourceIndex` |

> ⚠️ 这个 provider 的 `query` **必须传非空 projection**，否则直接返回 `null`
>（内部判断是 `if (columns == null \|\| columns.length <= 0) return null;`）。

实测列出 6 个信号源：4K HDMI1、4K HDMI2、视频、模拟电视、有线数字、地面数字。
`sources()` 的查找顺序为 framework → skyworth → tcl，没有厂商 provider 时自动回退。

### 6. 移除新版本检测

原版会去检查上游有没有新版本，并在设置面板里提示「新版本」。
本仓库**移除了这套机制**（`Updates.kt` 及相关入口），因为本仓库的版本号
与上游不同，检测结果没有意义，只会干扰使用。

### 7. 和风天气（Key 不写进代码）

原版天气依赖上游的接口，在这台电视上不可用。1.20 改为和风天气 v1：

- API Host 和 Key 在**手机设置页**填写，存在电视的 SharedPreferences 里，
  代码和仓库中**不保存任何 Key**
- 内置省、市、区县列表（34 省 / 392 市 / 3577 区县），手机页可直接选择
- **没填 Host/Key 时，桌面和手机页一律显示 `-- --`**，不会显示假数据或报错
- 天气数据按 10 分钟缓存，请求失败保留上一次结果

### 8. 联网校时

电视的 RTC 不走时，开机后系统时间会停在 2017 年，TLS 证书会因「尚未生效」校验失败。

- 启动时请求阿里源 HTTP `Date` 头，计算与本机时钟的偏移
- 桌面时间直接显示校正后的时间，不依赖系统时间
- `ClockTls` 在校正生效时按校正时间校验证书，并内置 ISRG Root X1，
  保证系统时间错误时 HTTPS 仍然可用

### 9. 创维背光控制（亮度键真正生效）

这台电视没有 `lights.*.so`，`Settings.System.SCREEN_BRIGHTNESS` 写进去没有任何效果。
实际上背光由创维 `com.tianci.provider` 的 `SKY_CFG_TV_MANUAL_BACKLIGHT_ADJUST` 控制：

- 通过 `ContentResolver.call("content://com.tianci.provider", …)` 读写
- `SET_CONFIG` 需要 Bundle 里带 `TCSetData` / `TCRangeSetData`，APK 内内置了
  这两个类的序列化镜像（固定 `serialVersionUID`），并在 R8 规则里保留
- 读取后按 `min/max` 映射到 0..1，步长为 `span/20`
- 写入后回读校验；值没变就返回失败，控制中心退回「打开图像设置」，
  避免控件显示成功但电视没反应

### 10. 农历与节日

桌面时间下方显示农历、干支、节日；数据在 `time/Lunar.kt`、`time/Festivals.kt`，
不依赖网络。

## 实测环境

| 项 | 值 |
| --- | --- |
| 电视 | 创维 8H52_G3 |
| SoC | Hi3751V551（bigfish） |
| Android | 5.1.1（API 22） |
| 分辨率 | 1920×1080 |
| 内存 | 1.5 GB |

已实测通过：首页、歌单、搜索、逐行歌词、全屏播放器、硬解 MP3 播放，
右键菜单应用信息面板正常显示，常驻内存约 190 MB。

## 自己编译

```bash
git clone https://github.com/TempTwo/aerial-tv-skyworth.git
cd aerial-tv-skyworth
./gradlew :app:assembleStandardRelease
# 产物: app/build/outputs/apk/standard/release/app-standard-release.apk
```

> ⚠️ 与上游一样，**签名 key 不在版本控制里**（见 `docs/HANDOFF.md`）。
> 若要生成可覆盖升级的 APK，需要自备签名 key 并配置到 `local/`。

## 安装

```bash
adb install -r aerial-skyworth-8h52-v1.20.apk
```

装完把电视的默认桌面指向 Aerial：

```xml
<!-- /system/pcfg/8H52_G3/config/general_config.xml -->
<config name="DEFAULT_HOMEPAGE" value="app.orchard.tv/app.orchard.tv.MainActivity" />
```

## 许可证与致谢

- 代码基于 [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv)，MIT 许可证
- 原作者：**pengchujin** — 感谢写出这么棒的电视桌面
- 字体 `FONT-LICENSE-OFL.txt` 同样来自上游
- 本仓库的适配与修复改动由 TempTwo 完成
