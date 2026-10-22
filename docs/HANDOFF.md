# 交接说明

写给接手 Aerial 的人或 AI。先读这份，再读 [ARCHITECTURE.md](ARCHITECTURE.md) 和 [devices/](devices/)。

## 现状（2026-10-21）

- 版本 **1.18（versionCode 20）**，2026-10-21 发布（[v1.18](https://github.com/pengchujin/aerial-tv/releases/tag/v1.18)）：触屏、家长密码、检查更新提示、屏保开始时间、照片每天换一张、显示全部 App 名称、天气带城市名，以及酷开 HOME.CC、install.sh 校验守护并加 `repair` 等适配。小米 MiTV-MFTR0（`standard`）和 TCL MT5879 国行（`guard`，多屏互动推送，用 ADB 点的「同意」）装的都是 1.18。酷开、乐视、OPPO K9、小米 ANTL0 / Android 9 相关的改动没有真机，只按用户日志改。海信 VIDAA 的主页键见 [devices/hisense-vidaa.md](devices/hisense-vidaa.md)。主要改动见 Release 说明和 ARCHITECTURE.md。
- 具体 IP、每台电视上改过的设置、户主自己的数据：见 **`local/TVS.md`**（不在版本库，需要单独私下拿到）。
- 开源在 https://github.com/pengchujin/aerial-tv（MIT）。官网在 `site/`，已部署到 https://aerial.qust.me（Cloudflare Worker 静态资源）。还没有自动化测试，以下结论都来自真机操作和日志。

### 真机验证过的功能

| 功能 | 小米 | TCL |
|---|---|---|
| 安装 | `adb install` | 多屏互动推送，电视上点一次「同意」（没有多屏互动时用当贝） |
| 接管 HOME（按 HOME 会闪一下原桌面，约 0.5 秒回到 Aerial） | ✅（1.11 起，adb 测） | 未测 1.11 |
| 从「桌面」磁贴进入原桌面（小米 PatchWall 的爱奇艺、芒果等内容） | ✅（1.11 起） | 未测 |
| 待机唤醒后仍是 Aerial | ✅（靠复活机制） | ✅ |
| 信号源磁贴（HDMI 等加到主屏幕，一键切换） | ✅（1.13） | 未测 |
| 断电重启后仍是 Aerial | ✅（2026-10-20 实测） | ✅（要放行 TCL 的 `APP_AUTO_START`，2026-10-20 实测） |
| ADB 被关后自动打开 | ✅（小米会主动关） | 不需要（不会关） |
| 首页、网格滚动、焦点、长按菜单、移动 | ✅ | ✅（只验证了显示和守护） |
| 小米的应用中心 / 高清播放器 / 米家显示在桌面 | ✅（1.7） | 不适用 |
| 控制中心 | ✅ | ✅（「设置」「网络」1.7 修好） |
| 音量 | ✅ | ✅（需要 `FLAG_SHOW_UI`） |
| 亮度 | ✅ 滑块 | 打开 TCL 图像面板 |
| 每日精选 / 内置壁纸 | ✅ | ✅ |
| 手机扫码上传与选择 | ✅ | 未单独测（代码相同） |
| 航拍屏保 | ✅ | ✅（截图截不到视频，字幕正常） |
| 系统级屏保（Dream） | 不适用（小米不用 Dream） | ✅ |
| 返回键进入屏保 | ✅ | ✅ |
| 焦点样式（高对比度白边 / 标准，控制中心与手机页切换） | ✅ 1.9 | 未装 1.9 |
| 覆盖安装时不打断正在看的信号源 / App | ✅ 1.9（装在 HDMI 播放中） | 未装 1.9 |
| 屏保里左右键切换上一段/下一段（时钟模式除外，其他键退出） | ✅ 航拍（1.7） | 未测 |
| 帧率 | 导航时中位数约 18ms | 未测 |
| 触屏（#60）：点按、长按菜单、拖动网格、控制中心和个性化可点、点屏保唤醒 | ✅ `adb shell input` 模拟（小米没有触摸屏）；移动模式和手势返回在 Android 15 手机模拟器上测过 | 未测 |

**Android 5.0–7.1 的老电视**（1.9 起 minSdk 21，Compose 降到 1.9.5）：小米上装了这一版回归过（首页、焦点、控制中心、壁纸页和手机页、航拍屏保走 HTTPS 正常）。老系统没有真机，在 Android 5.0.2 模拟器（`aerial21`，arm64）上验证过：桌面、焦点、控制中心（音量、亮度）、壁纸页和二维码、手机配置页、守护版接管 HOME。和新系统的差别：

- 界面字体用系统的 Roboto（Inter 的字重靠可变字体，Android 8.0 才支持）；App 图标用 PNG（自适应图标同理）。
- Android 5.0 上无障碍服务接不到 HOME 键，守护版靠「原桌面一出现就拉回」，会闪一下原桌面。
- 老系统不信任 Apple 的根证书，航拍视频的 HTTPS 会失败，屏保会自动改用 HTTP（`res/xml/network_security_config.xml` 只对 `sylvan.apple.com` 放行明文）。模拟器的软件解码器只支持 H.264 Baseline，解不了 Apple 的视频，所以航拍画面本身还没在 5.x 上看到过，要等真机。
- Android 4.4 做不到：Compose 最低要求 Android 5.0。

## 环境

- macOS，JDK 17（`/opt/homebrew/opt/openjdk@17`），Android SDK 在 `~/Library/Android/sdk`，Gradle Wrapper 8.14.3，AGP 8.12，Kotlin 2.2.10。
- **签名**：`local/signing.keystore` 加 `local/signing.properties`，`app/build.gradle.kts` 会优先使用它。电视上的版本都是用这把钥匙签的（SHA-256 `e08962dc…6685`）。没有它也能编译，但会回落到本机的 debug key，装不上电视（签名不一致）。
- **ADB 连电视**需要关闭 Claude Code 的沙箱（局域网访问被拦）。电视第一次连接时要在电视上点「允许 USB 调试」。

## 日常操作

```bash
source local/tvs.env                                    # XIAOMI_TV / TCL_TV（模板：tools/tvs.env.example）
./gradlew assembleStandardRelease assembleGuardRelease  # 两个版本一起编译
BUILD=1 KEEP_ADB=1 TV=$XIAOMI_TV tools/install.sh       # 小米：全自动（BUILD=1 用本地编译；不加则下载 GitHub 最新 Release）
BUILD=1 KEEP_ADB=1 TV=$TCL_TV tools/install.sh guard    # TCL：通过多屏互动推送，在电视上选「同意」
TV=<ip> tools/restore.sh [--uninstall]                  # 撤销所有授权和设置
```

多屏互动推送不要求 versionCode 变大，但回退到当贝时，当贝会隐藏不比已装版本新的 APK。所以发版时照样给 `versionCode` 加 1，两台电视的版本号保持一致。

只改了代码、想快速更新小米时：

```bash
adb -s $XIAOMI_TV:5555 install -r app/build/outputs/apk/standard/release/app-standard-release.apk
adb -s $XIAOMI_TV:5555 shell cmd package compile -m speed -f app.orchard.tv
```

覆盖安装会保留之前的授权；守护服务由 App 启动时自己恢复。

## 发版

1. `app/build.gradle.kts` 里 `versionCode` 加 1，并更新 `versionName`。
2. `./gradlew assembleStandardRelease assembleGuardRelease`（必须有 `local/signing.*`，否则签名不一致，用户无法覆盖安装）。
3. 发布时 APK 要改名为 `aerial-standard.apk` 和 `aerial-guard.apk`，`tools/install.sh` 和官网都按这两个名字从 `releases/latest/download/` 下载：
   ```bash
   cp app/build/outputs/apk/standard/release/app-standard-release.apk /tmp/aerial-standard.apk
   cp app/build/outputs/apk/guard/release/app-guard-release.apk /tmp/aerial-guard.apk
   gh release create vX.Y /tmp/aerial-standard.apk /tmp/aerial-guard.apk --title "Aerial X.Y" --notes "..."
   ```
4. 运行 `tools/deploy-site.sh`（在 main 上、`tools/` 已提交）。国内访问 GitHub 慢或打不开，所以官网自己放一份最新 Release 的 APK 和安装脚本：脚本把 Release 的两个 APK 下载到 `site/download/`，把 `tools/get.sh` 复制成 `site/install.sh`，把 `install.sh`、`restore.sh`、`tcast-push.py` 复制到 `site/tools/`，再部署官网。这些副本不进版本库（见 `.gitignore`）。**每次发版都要跑一次**，否则官网上的 APK 还是旧版。`get.sh`、`install.sh` 先从官网下载，失败再从 GitHub 下载。
5. 官网 `site/` 有中文（`index.html`）和英文（`en/index.html`）两页，共用 `style.css` 和 `app.js`（页面里的 `window.T` 放各自的文案和 Agent 提示词）。页面开头的小脚本按浏览器语言跳转，用户点过语言切换后以用户的选择为准（`localStorage` 的 `aerial-lang`）。改文案时两页都要改，README 也一样（`README.md` 中文、`README.en.md` 英文）。改了 `style.css` 或 `app.js` 要把页面里引用的 `?v=` 加 1。官网是 Cloudflare Worker 静态资源（配置见 `wrangler.jsonc`），绑定在 https://aerial.qust.me 和 https://tv.qust.me。改完官网也用 `tools/deploy-site.sh` 部署（直接 `npx wrangler@4 deploy` 会把官网上的 APK 和脚本删掉）。`site/_headers` 让图片在浏览器里缓存 7 天，所以替换截图时要把 `index.html` 里图片地址的 `?v=` 加 1，否则用户看到的还是旧图。`site/_redirects` 把旧地址 `/download/standard`、`/download/guard` 跳转到 `site/download/` 里的 APK。`site/agent.md`（中文）和 `site/en/agent.md`（英文）是给 Agent 看的安装说明，官网和 README 的 Agent 提示词只让 Agent 去读它；里面的 Windows 逐条步骤照抄了 `tools/install.sh` 和 `tools/restore.sh`，改这两个脚本时要一起改。

## 调试技巧

- **截图**：`adb shell screencap -p /data/local/tmp/x.png && adb pull ...`（小米用 `/data/local/tmp`，TCL 用 `/sdcard`）。用完要删掉。
- **帧率**：`adb shell dumpsys gfxinfo app.orchard.tv reset`，操作几下，再执行 `... framestats`，看 `SwapBuffers→GpuCompleted` 这一段的耗时。
- **守护状态**：`dumpsys accessibility | grep "label=Aerial"`（有输出就是已绑定）；日志 tag 是 `AerialGuard`。
- **壁纸**：日志 tag 是 `AerialWallpaper`。
- **手机配置页**：令牌不写日志，从电视截图里识别二维码即可：`swift tools/qr-from-screenshot.swift shot.png`。
- **立即预览屏保**：`adb shell am start -a app.orchard.tv.PREVIEW_SCREENSAVER -n app.orchard.tv/.MainActivity`；TCL 上测系统屏保：`am start -n com.android.systemui/.Somnambulator`。
- **模拟小米的待机清理**：`adb shell am force-stop app.orchard.tv`，然后看日志里出现 `revived by the notification listener binding`。
- **配置**：`adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez keep_adb true`。
- **磁贴对比图**：`adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --es export_tiles before`，等日志 `AerialTiles` 出现 `Wrote`，再 `adb pull /sdcard/Android/data/app.orchard.tv/files/tiles/before.png`。改 `TileArt` 或 `TileRecipes` 前后各导一次。别的电视上的 App 不用装：把 APK 去掉 `lib/`、`assets/`、`*.dex` 之后推到 `files/tiles/apks/`，会按「装上了」的样子画在后面（名字后带「·」）。这个目录是自己建的，用完可以整个删掉。

## 必须遵守（踩过的坑）

1. **不要停用小米的 `com.mitv.tvhome`**：小米只认系统签名的桌面，停用后电视会卡在黑屏的 FallbackHome。
2. **改 `enabled_accessibility_services` 时要保留别人的服务**（TCL 有两个 `com.tcl.walleve` 服务），用追加的方式写。
3. **在 zsh 里不要写 `"$VAR:xxx"`**：`:a`、`:h`、`:t` 这些会被当成路径修饰符。一律写 `"${VAR}:xxx"`。这个坑把 TCL 的无障碍设置写坏过两次。zsh 也不会按空格拆分 `$VAR`，要循环就用数组。
4. **清理测试数据时，只删自己创建的东西**（按记下来的 id 删），不要删「所有照片」这种。户主在测试期间用手机上传过照片，差点被一起删掉。用户选的壁纸也不要改回去。
5. **在电视上按键操作前先截图确认焦点**，尤其是当贝的安装包管理（左侧有「一键清空」）。
6. **不要把 `local/` 提交到版本库**（里面有 IP 和签名密钥）。
7. 改动系统设置前，先记下原值（写进 `local/TVS.md`），并确保 `restore.sh` 能还原。
8. 每次改完都在真机上验证，不要只看编译通过。电视上的行为和模拟器差别很大。
9. **不要升级 Compose BOM 到 2025.12 及以后**（Compose 1.10 要求 Android 6.0），也不要升级到要求 minSdk 23 的 AndroidX 版本（比如 `lifecycle` 2.10 起）。升级前看 AAR 里 `AndroidManifest.xml` 的 `minSdkVersion`。

## 重新生成航拍目录

```bash
curl -O https://sylvan.apple.com/Aerials/resources-16.tar
tar xf resources-16.tar entries.json TVIdleScreenStrings.bundle/zh_CN.lproj TVIdleScreenStrings.bundle/en.lproj
plutil -convert json -o zh.json TVIdleScreenStrings.bundle/zh_CN.lproj/Localizable.nocache.strings
plutil -convert json -o en.json TVIdleScreenStrings.bundle/en.lproj/Localizable.nocache.strings
```

然后把每个 asset 转成 `{id,label,category,url(=url-1080-H264),hevc(=url-1080-SDR),captions:[[秒,中文,英文],...]}` 写进 `app/src/main/assets/aerials.json`。captions 由 `pointsOfInterest` 的 key 去两个 strings 文件里查出来；category 由 `categories[0]` 对应 `localizedNameKey` 去掉 `AerialCategory` 前缀再转小写得到。

## 已知问题与限制

- TCL 每次升级都要在电视上点一次「同意」（多屏互动的提示框）。
- 小米只有停在桌面上时才由 Aerial 接管屏保；在别的 App 里空闲，小米自己的屏保仍会出现。
- TCL 调音量时会同时弹出 TCL 自己的音量条。
- HDMI 设备名只能显示「已连接」，读真实 CEC 名称需要系统权限。
- 航拍和每日精选都需要联网，航拍视频没有本地缓存。
- 手机配置页是局域网 HTTP，只靠随机令牌保护。
- 签名用的是 debug key。想换成正式密钥，必须先在两台电视上卸载重装（会丢掉壁纸、照片和图标排序），TCL 还要走一遍当贝流程。
- 海信等其他品牌没有在真机上验证过。

## 可以继续做的事（按价值排序）

1. **小米上在任意 App 空闲时也用 Aerial 屏保**：守护服务能看到 `com.xiaomi.mitv.hyper.screensaver` 的窗口出现，届时打开 Aerial 的屏保 Activity 替换它（需要把屏保做成独立 Activity）。
2. **TCL 升级完全免手动**：让桌面守护（无障碍服务）在多屏互动的推送提示框出现、而且推送的是 Aerial 时，自动点「同意」。
3. **自动化测试**：`LayoutStore.move`、`Aerial.captionAt`、`TileArtFactory` 的图标分类、`SystemProbe` 的信号源映射都适合写单元测试。
4. **航拍预缓存**：夜间缓存几段视频，离线也能播。
5. **手机页增强**：调整 App 排序、隐藏 App、屏保等待时间。
6. **新机型**：参照 `docs/devices/` 的格式补充海信等机型的笔记，`SystemState.DeviceProfile` 里加对应的修正。
7. **tvOS 的「顶部陈列内容」**：国内 App 基本不提供 TvProvider 的预览节目，可以考虑为常用 App 做精选内容（工作量大，要有数据来源）。

## 参考

- tv-vibehack / CleanYourTV（https://github.com/Gathin23/tv-vibehack）：有很多机型的去广告列表，以及 MFTR0 的信号源笔记，Aerial 的小米信号源路由就参考了它。
- Apple 航拍资源：`https://sylvan.apple.com/Aerials/resources-16.tar`。
