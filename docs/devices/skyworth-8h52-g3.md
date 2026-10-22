# 创维 8H52_G3（Hi3751V551，Android 5.1）

- 创维 `8H52_G3`，SoC `Hi3751V551`（bigfish），**Android 5.1.1（SDK 22 / API 22）**，32 位 ARM（armeabi-v7a），zygote32。
- 1920×1080。系统分辨率 1920×1080，Aerial 的 960×540dp 画布缩放后正好两倍，字重和间距都合适。
- 屏幕时间不对：厂商的 `/system/etc/init.bigfish.sh` 开机第一件事是 `busybox date -s 201701010000.00`，
  所以刚开机的几分钟里桌面上的时钟是错的（1 月 1 日 08:00）。联网之后系统自己会校回来（实测约 1～2 分钟）。
  桌面显示的就是系统时间，所以刚开机那会儿会看到 8:00。
- 没有自适应图标：API 22 不支持，磁贴用 PNG 兜底，没有图标的 App 显示成纯色块。
- 原桌面：厂商的 `com.oversea.aslauncher`（AS Launcher），被 `sky_launcher` 这个系统服务按配置拉起。

## 安装：adb install 直接过

没有 TCL 那样的安装校验链，`adb install` / `pm install` 都能装上（APK 声明了 MAIN + HOME 也照装）。
装的是 **`standard`** flavor（带 HOME intent-filter）。

## 接管 HOME：不走无障碍守护，直接改厂商配置

这台电视的 HOME 不由系统决定，而是厂商自己的 `sky_launcher` 服务读配置：

```
/system/pcfg/8H52_G3/config/general_config.xml
  <config name="DEFAULT_HOMEPAGE" value="包名/类名"/>
```

开机时 `sky_launcher` 解析这一行并 `am start` 对应的 Activity，同时覆盖
`persist.service.homepage.pkg` / `cls`。所以：

1. 装 `standard` 版；
2. 把 `DEFAULT_HOMEPAGE` 改成 `app.orchard.tv/app.orchard.tv.MainActivity`；
3. **同时**把 `/data/ccos/general_config.xml`（运行时副本）一起改掉，否则某些路径会读旧值。

日志里能看到接管过程：

```
I/sky_launcher: ----parser---- DEFAULT_HOMEPAGE:app.orchard.tv/app.orchard.tv.MainActivity
I/sky_launcher: oemIntent:Intent { cmp=app.orchard.tv/.MainActivity }
I/ActivityManager: START u0 { flg=0x10000000 cmp=app.orchard.tv/.MainActivity} from uid 0 on display 0
```

配置文件在 `/system` 里，写它需要 root。这台电视的 root 通道见
[../../../../skyworth-8h52-root](https://github.com/TempTwo/skyworth-8h52-root)：
`/system/xbin/guardctl` 连 `coocaa_guard` 的 socket，`tvroot` 起一个 root telnetd。

**不需要 Home Guard**：既然配置直接指到 Aerial，按 HOME 键就是 Aerial；
开无障碍服务反而多一份常驻权限。这台电视上没开 `enabled_accessibility_services`。

## 权限 / appops

API 22 上只有安装期权限，没有运行时权限，所以 `pm grant` 基本用不上：

```sh
pm grant app.orchard.tv android.permission.ACCESS_FINE_LOCATION
# Operation not allowed: ... It is required by the application
```

（这句话的意思是"这是安装期权限，已经给了，不用再 grant" ✓。）

`WRITE_SECURE_SETTINGS` 是 signature 权限，API 22 上 `pm grant` 改不了。**这台电视不需要它** ——
它只服务于 Home Guard 和"把 ADB 打开"，我们两样都没用。

appops 只有这几个有效（`APP_AUTO_START` / `APP_ASSOC_START` 在 API 22 上不存在，报
`Unknown operation string`，无害）：

```sh
appops set app.orchard.tv SYSTEM_ALERT_WINDOW allow
appops set app.orchard.tv WRITE_SETTINGS allow
```

## 屏保

把 Aerial 设为系统屏保：

```sh
settings put secure screensaver_components app.orchard.tv/app.orchard.tv.dream.AerialDream
```

这台电视的厂商屏保开关是 `SUPPORT_SCREENSAVER=false`（默认关），但 Aerial 自己在桌面空闲时
也会拉起 Dream，所以航拍屏保照样能看（实测能播，中文字幕正常，Media3 + H.264 硬解没问题）。

## 实测结论（2026-10-21，1.18 / versionCode 20）

| 项 | 结果 |
| --- | --- |
| `adb install` | ✅ 直接装上，没有校验链 |
| 开机自动接管 HOME | ✅ 重启后 `mResumedActivity = app.orchard.tv/.MainActivity` |
| 首页渲染 | ✅ 时钟、日期、天气、壁纸、应用网格都正常 |
| 遥控器焦点 | ✅ 方向键移动正常，滚下去时钟收进状态栏 |
| 壁纸（必应每日精选） | ✅ 能联网取图 |
| 航拍屏保 | ✅ 能播，带中文字幕 |
| 崩溃 | ✅ 无（logcat 里只有 `Unknown permission REQUEST_DELETE_PACKAGES / QUERY_ALL_PACKAGES` 两条警告，API 22 不认识这两个权限，无害） |
| 天气城市 | 显示 Tokyo —— 来自公网 IP 定位，不是写死的；可以在「个性化 → 天气」里改成别的城市 |
