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
| `aerial-skyworth-8h52-v1.21.apk` | 最新版：无 Key 多源天气（移植 Quiet TV）、时钟直取系统时间，**已在创维 8H52_G3 实测** |
| `aerial-skyworth-8h52-v1.20.apk` | 上一版：和风天气、联网校时、创维背光控制 |

- 包名 `app.orchard.tv`，版本 `1.21`（versionCode 23）
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

### 7. 天气：无 Key 多源（移植自 Quiet TV）

原版天气依赖上游的接口，在这台电视上不可用。1.20 曾改为和风天气 v1，但还要在手机页填
API Host 和 Key；**1.21 从 [BeatsAD/quiet-tv](https://github.com/BeatsAD/quiet-tv) 移植了
无 Key 的多源方案，零配置即用**：

- 主源 [UAPI](https://uapis.cn)（uapis.cn，免费无 Key）：带所选城市的 `adcode` 时按区划查，
  否则按网络地址自动定位；城市、区划码和天气一个请求同时返回
- 兜底 [Open-Meteo](https://open-meteo.com)（同样无 Key，按坐标查询，自带白天/夜晚标志）
- 内置省、市、区县列表（34 省 / 392 市 / 3577 区县），手机页选择后按 adcode 查询；
  「改回自动定位」恢复按网络定位，定位结果缓存 24 小时
- **手机页的和风 API Host / Key 配置已移除**，天气不再需要任何账号
- 每 30 分钟刷新一次（Quiet TV 的节奏），失败时保留上一次结果显示，超过 3 小时不再展示

### 8. 时钟直接取系统时间

- 开机头一两分钟显示的是系统自己的时间（校准前的 8:00），系统校准后一直准确
- 校时相关的明文 HTTP 域名白名单一并清理；`ClockTls` 按 `TimeSync.nowMs()`（即系统时间）
  校验证书，并内置 ISRG Root X1，保证 Android 5 上 HTTPS 仍然可用

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
adb install -r aerial-skyworth-8h52-v1.21.apk
```

装完把电视的默认桌面指向 Aerial（**写 `/system` 配置需要 root**，见下一节；
仅有 ADB 时两条 `setprop` 只是临时生效，冷启动会被打回）：

```xml
<!-- /system/pcfg/8H52_G3/config/general_config.xml 与 /data/ccos/general_config.xml，两处都要改 -->
<config name="DEFAULT_HOMEPAGE" value="app.orchard.tv/app.orchard.tv.MainActivity" />
```

```sh
setprop persist.service.homepage.pkg app.orchard.tv
setprop persist.service.homepage.cls app.orchard.tv.MainActivity
setprop persist.service.starthomepage 1
```

## 装完被重置 / 被自动卸载？（酷开系统守护机制的应对）

这是 [issues #1](https://github.com/TempTwo/aerial-tv-skyworth/issues/1)、
[#2](https://github.com/TempTwo/aerial-tv-skyworth/issues/2) 里反馈的两类问题，
本质都是**创维酷开（天赐）系统的自愈/守护机制**在起作用。
仅靠 ADB（未 root）能缓解、不能根治；**根本解法都需要 root**。
root 的免解锁做法、能力边界、变砖风险与救砖方法，见
👉 **[TempTwo/skyworth-8h52-root](https://github.com/TempTwo/skyworth-8h52-root)**
（⚠️ ROOT 有风险，仓库 README 置顶有完整声明；下面的一切都以先拿到 root 为前提）。

### 问题一：冷启动后默认桌面被还原（issue #2）

**根因不是属性失效，而是配置被重读。** `persist.service.homepage.pkg/cls`
只是厂商 `sky_launcher` 的派生缓存——每次**冷启动**（拔电重开）都会从
`general_config.xml` 的 `DEFAULT_HOMEPAGE` 重新生成。热重启只改属性所以"有效"，
冷启动读的还是厂商配置，于是打回 `com.tianci.movieplatform`。

**Root 后的修法（改配置文件，不是循环 setprop）**：

1. **两处配置都要改**（缺一不可）：
   * `/system/pcfg/8H52_G3/config/general_config.xml`（出厂配置源）
   * `/data/ccos/general_config.xml`（运行时副本，`sky_launcher` 实际读它）
2. 改完执行三条 `setprop`（上一节），立即生效，不用重启；
3. **不需要常驻"守护脚本"循环覆盖**（DeepSeek 给的方案是在拿不到配置写权时的
   权宜——有了 root 直接改持久文件即可；循环覆盖仍会有一瞬厂商桌面闪现）；
4. 保险丝：把写配置做成 U 盘 payload 任务，**每次开机自动再写一遍**，
   厂商 OTA 重刷 `/system` 后也会自愈（工具箱的 `10_set_homepage.sh` 就是这个）。

实测：2026-10-21 起在这台 8H52_G3 上，配置改完后**多次拔电冷启动**，
Aerial 每次都是开机默认桌面，无一次被打回。

### 问题二：开机或打开设置后 Aerial 被自动卸载（issue #1）

**根因**：酷开的多层自愈——root 守护进程 `coocaa_guard`、开机推送/自装
（`SkyPushService` / `SkyAutoInstaller`）、疑似存在 `/system/etc/skysecurity.list`
的白名单清剿，以及云端"快照"恢复（连恢复出厂设置都会拖回快照）。
未 root 时这台机器 adb 拿不到写权，**基本无解**（快照连你打开 ADB 的开关都会还原）。

**Root 后的操作序列**（对照工具箱的 `payload/tasks/`）：

1. **先拿 root**：U 盘 boot-shell 通道（V017/V020 固件实测，不改 boot 分区、
   不拆机；`setprop ctl.start boot-shell` 不重启电视即可触发）；
2. **砍掉自愈的"命脉"**：
   * `pm uninstall com.coocaa.skycast.tool` ——它内置 `EmergencyUpgradeEngine`，
     会在后台偷偷重装酷开桌面（纯 `/data` 应用，可彻底删）；
   * 屏蔽它拉包用的域名：`/system/etc/hosts` 加
     `0.0.0.0 apk-sky-fs.coocaa.ottcn.com`；
   * 冻结（或卸载）`SkyPushService` / `SkyAutoInstaller`：这些是"开机把用户
     装的东西清回去"的执行者，`pm disable-user --user 0 <pkg>` 即可；
3. **如果系统里有 `/system/etc/skysecurity.list`**（issue #1 提到的疑似白名单
   清剿）：root 后直接编辑它，把 `app.orchard.tv` 加入白名单；本机该文件
   未出现异常行为（有可能在 V020 上已失效），若你的机器遇到"装了就消失"，
   优先排查这个文件；
4. **配置按问题一处理**，让厂商自己的 HOME 流程把 Aerial 当成"合法默认桌面"
   ——守护机制也就不再与你互相拉扯了。

实测：2026-10-21 起本机执行完上述 1/2/4 后，Aerial 常驻至今，多次冷启动、
打开过系统设置，**再未出现"开机被卸载"或"打开设置就消失"**。

> 再次提醒：以上需要在 `/system` 写配置/删包，全部依赖 root；
> root 有失去保修与变砖风险，动手前先看工具箱仓库的救砖章节。

## 许可证与致谢

- 代码基于 [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv)，MIT 许可证
- 原作者：**pengchujin** — 感谢写出这么棒的电视桌面
- 字体 `FONT-LICENSE-OFL.txt` 同样来自上游
- 本仓库的适配与修复改动由 TempTwo 完成
