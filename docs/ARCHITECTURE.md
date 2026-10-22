# Aerial 架构

单模块 Android 应用（`app/`），Kotlin + Jetpack Compose（没有用 androidx.tv 和 Material，全部自绘）。包名 `app.orchard.tv`（产品名是 Aerial，包名沿用旧名 Orchard：改包名会让电视把它当成新 App，丢掉户主数据和全部授权），minSdk 21（Android 5.0），targetSdk 34，compileSdk 36。Compose 固定在 1.9.x（BOM 2025.11.01）：1.10 起要求 Android 6.0。比 Android 5.0 新的接口都要按 `Build.VERSION.SDK_INT` 判断，`./gradlew lintStandardRelease` 的 NewApi 检查能找出漏掉的地方。

## 目录一览

```
app/src/main/java/app/orchard/tv/
├── MainActivity.kt          HOME Activity：窗口设置、960×540 画布密度、广播/网络监听、空闲计时与桌面内屏保、启动 App
├── HomeViewModel.kt         首页状态：App 列表、浮层（Overlay）、系统状态（网络/音量/亮度/信号源/桌面状态）、移动模式
├── apps/
│   ├── Apps.kt              AppCatalog：枚举 LEANBACK_LAUNCHER + LAUNCHER，按包去重，缓存磁贴图（按 lastUpdateTime）
│   ├── TileArt.kt           磁贴图生成：横幅 / 自适应图标双层 / 铺满底板 / 同色系底色；主色；每 App 的 64×36 环境光背景
│   ├── TileRecipes.kt       精选图标库：按包名给 App 指定重绘方式（品牌色、白色 logo、用图标代替横幅、自绘图标）
│   ├── TileSheet.kt         把所有磁贴拼成一张图，用来对比改动前后（ADB 触发，见 HANDOFF「调试」）
│   ├── LayoutStore.kt       用户排序与隐藏（SharedPreferences "layout"），首次运行的默认排序
│   └── CustomIcons.kt       用户在手机页给 App 换的磁贴图（files/icons/<包名>.png，16:9）
├── ui/
│   ├── theme/Theme.kt       Palette（tvOS 深色系统色）、Motion（缓动曲线）、Inter 字体（opsz 14/32）、Type
│   ├── theme/Glyphs.kt      自绘 SF Symbols 风格图标（Canvas 线条）
│   ├── components/Pressable.kt  遥控器可按控件：按下缩放、只在「本控件上开始的按压」抬起时触发
│   ├── home/HomeScreen.kt   首页布局：网格几何、滚动、焦点管理、各浮层挂载、返回键
│   ├── home/AppTile.kt      单个磁贴：聚焦弹簧、阴影、柔光、视差、边缘回弹、长按、移动抖动
│   ├── home/HomeChrome.kt   背景（AmbientBackground）、大时钟、右上角状态胶囊、时间格式
│   ├── home/Overlays.kt     长按菜单、隐藏的 App 面板、MenuRow、MiniTile
│   ├── home/PinPad.kt       家长密码键盘（设置 / 解锁，遥控器数字键或屏幕键盘）
│   ├── home/Screensaver.kt  屏保 UI：航拍（Media3 ExoPlayer，见 wallpaper/AerialStream.kt）/ 照片（Ken Burns）/ 时钟
│   ├── control/ControlCenter.kt  控制中心：按钮、音量/亮度滑块、「个性化」「隐藏的 App」两个入口、有新版本时的提示和升级说明页、底部版本号；信号源页（只负责切换）和信号源设置页（显示在主屏幕、开机时打开）
│   └── wallpaper/WallpaperSheet.kt  个性化面板（壁纸、屏保和开始时间、焦点样式、App 名称、家长密码、天气）+ 手机扫码卡片
├── wallpaper/
│   ├── Wallpapers.kt        壁纸单例：来源（每日/内置/照片/照片每天换一张/跟随App）、选择、首页背景烘焙、缩略图、上传
│   ├── Gradients.kt         8 组内置渐变的参数与渲染
│   ├── Images.kt            解码裁剪、首页压暗烘焙、抖动、box blur
│   ├── Aerials.kt           读取 assets/aerials.json（Apple 航拍目录）
│   └── PhoneServer.kt       NanoHTTPD 手机配置页 + 二维码生成
├── settings/
│   └── AerialSettings.kt    Aerial 自己的偏好（SharedPreferences "settings"）：焦点样式 FocusStyle（默认高对比度）、固定到主屏幕的信号源、开机信号源、屏保开始时间、是否显示全部 App 名称、家长密码（SHA-256，解锁 10 分钟或按 HOME 重新上锁）
├── update/
│   └── Updates.kt           每天最多一次读官网 download/version.txt，有新版时控制中心提示；不在后台下载安装
├── weather/
│   └── Weather.kt           时钟旁的天气：Open-Meteo 实时天气，城市来自手机页或 IP 定位
├── system/
│   ├── SystemState.kt       SystemProbe：网络、音量、桌面状态、设置入口、信号源（TvInputManager + 小米路由 + 机型修正）
│   └── Brightness.kt        亮度：小米 picture_backlight / 标准 screen_brightness / TCL 走面板
├── guard/
│   ├── HomeGuardService.kt  无障碍「桌面守护」：原桌面出现就切回、从磁贴进原桌面时临时拦 HOME、亮屏检查、ADB 保持
│   ├── GuardSetup.kt        用 WRITE_SECURE_SETTINGS 重新开启守护 / 保持 ADB / 「已启用未绑定」时强制重绑
│   ├── RevivalListener.kt   通知监听服务：被强杀后由系统重绑，借此复活并重开守护；10 分钟内复活 3 次以上时，看不到前台就不再抢 HOME（乐视 #63）
│   ├── BootReceiver.kt      真正重启后的兜底
│   └── ConfigReceiver.kt    ADB 专用配置入口（keep_adb、own_screensaver、clear_pin、export_tiles），要求发送方持有 WRITE_SECURE_SETTINGS
└── dream/AerialDream.kt    系统级屏保（DreamService + Compose）

app/src/main/assets/aerials.json   Apple 航拍目录（114 条：地址 + 中英文地名 + 时间点说明）
app/src/main/assets/web/index.html 手机配置页（单文件，iOS 风格，深浅色）
app/src/standard/AndroidManifest.xml  只给 standard 版本加 HOME intent-filter
tools/install.sh / restore.sh       部署与还原
```

## 两个构建版本（flavor）

| flavor | HOME 声明 | 用途 |
|---|---|---|
| `standard` | 有（`src/standard/AndroidManifest.xml` 合并进去） | 能装桌面类 APK 的电视（小米、原生 Android TV 等） |
| `guard` | 无 | 拒绝安装第三方桌面的固件（TCL 的 `LauncherStrategy`），只靠桌面守护接管 |

两者代码完全相同，只差 manifest。

## 首页

- **画布**：`MainActivity` 用 `LocalDensity` 把内容缩放到 960×540dp 的设计画布（`density = 屏幕宽 / 960`）。不同厂商的 1080p 屏幕 dpi 不同（小米 320，TCL 240），这样排版完全一致。Compose 里所有 dp/sp 都按这个画布理解。
- **分辨率**：系统给 App 的界面多大，就按多大画（`ui/theme/Theme.kt` 的 `canvasDensity`：1080p 界面是 2，4K 界面是 4）。预先画好的位图也按它算：磁贴图是「聚焦放大后的磁贴」的实际像素（1080p 时 352×198，4K 时 704×396），壁纸按屏幕像素解码，界面超过 1080p 时每日精选下载必应的 UHD 图。大部分 4K 电视的界面其实是 1080p，由硬件放大（Android TV 的 `config_maxUiWidth` = 1920，Google TV 模拟器和家里三台电视都是这样）；只有厂商放开这个限制的电视才会拿到 4K 界面。4K 路径在 Android 15 的 4K 模拟器（`Aerial4KUI`，非 TV 镜像，没有这个限制）上验证过。
- **刷新率**：Android 12 起，如果面板能无黑屏地切到更高的刷新率（`Display.Mode.getAlternativeRefreshRates`），首页会选最高的那个（最高 120Hz），见 `MainActivity.preferSmoothestRefreshRate`。会黑屏的切换一律不做：桌面每次回来都黑一下是不可接受的。
- **网格**（`HomeScreen.GridMetrics`）：5 列，16:9 磁贴，边距 56dp、间距 28dp，第一行在屏幕高 55.5% 处。磁贴用绝对 `offset` 定位，没有用 Row，所以移动 App 时组件不会重建、焦点不会丢，位置变化由 `animateIntOffsetAsState` 做重排动画。
- **滚动**：不用 `verticalScroll`（它会自动 bringIntoView），而是一个 `Animatable` 加内容 `offset`，目标由聚焦行决定（`scrollFor`），用弹簧动画以便连按时平滑改变目标。
- **焦点**：每个磁贴一个 `FocusRequester`（按包名存在 map 里）。方向键由磁贴自己处理：`moveFocus` 失败时触发边缘回弹。浮层打开时磁贴 `focusable(enabled=false)`，焦点不会逃出浮层；浮层关闭后恢复到原磁贴。HOME 键重置通过 `homeResetPending` 加「窗口重新获得焦点」两个条件触发（在别的 App 在前台时请求焦点会丢）。
- **返回键**：网格里 → 回第一行；第一行 → 启动屏保（tvOS 行为）。
- **焦点样式**：`AppTile` 聚焦时放大 1.15 倍，加阴影、顶部高光和加粗的名字。`FocusStyle.HighContrast`（默认）时，另在磁贴外 5dp 处画 3dp 白色描边（外加一圈淡黑边，保证在亮壁纸上也看得见）。描边画在一个不裁剪的独立图层上，和磁贴共用放大、倾斜、推移、抖动这些变换。起因：国产电视都是方向键遥控器，没有 Siri 遥控器那种跟着手指的倾斜，只靠放大的话用户找不到焦点。
- **背景**（`HomeChrome.HomeBackground`）：`Wallpapers.backdrop`（选中壁纸，已烘焙压暗），否则（「跟随 App」模式）用聚焦 App 的 `TileArt.backdrop`。和 tvOS 一样分两种状态：焦点在第一排时显示清晰的壁纸；往下进入网格后，整屏换成模糊、压暗的壁纸。模糊版（`Wallpapers.frosted`，320×180）由 `Images.frost` 用压暗之前的原图预先做好，网格的压暗也预先做在里面，不做实时模糊或颜色滤镜。切换跟着滚动的弹簧走，没有单独的动画；两张全屏图只在滚动的中段（进度 30%–70%）交叉淡化，其余时间每帧只画一张。小米 MFTR0 实测：第一排 14ms、网格 18ms、上下切换 90 分位 20ms，和改之前一样；全程交叉淡化时切换的 90 分位是 30ms。试过 tvOS 26 那样在第一排下面垫一块玻璃底板，但聚焦时要给 App 名字留位置，上下边距只能不对称，还会压到第二排，所以没有保留。

### 性能约束（重要）

电视 GPU 很弱，这里的瓶颈是全屏填充率。第一版在小米上帧耗时中位数 65ms，改完后约 18ms。
- 首页每帧只允许一次全屏绘制：壁纸和压暗层在 CPU 上预先合成；窗口背景设为 null。
- 空闲时不能有任何持续动画（测量结果是 0 帧）。
- 模糊（`BlurEffect`）只在控制中心 / 隐藏 App 面板打开时用。
- 加任何全屏效果前，先测：`adb shell dumpsys gfxinfo app.orchard.tv framestats`。
- 一定要装 release 版，并执行 `cmd package compile -m speed -f app.orchard.tv`。debug 版的 Compose 在电视上明显卡顿。

## 磁贴图（TileArt）

优先级：

1. **固件自带的 App**（设置、相册、天气、投屏、应用商店、日历、媒体中心、安全中心、浏览器）用 Aerial 自己画的磁贴：渐变底色加白色线条图标（`SystemTile` + `Glyphs.kt`），像 tvOS 的系统 App。这些 App 在小米和 TCL 上都没有声明标准的 App 分类，所以按包名列在 `AppCatalog.SYSTEM_TILES`，每个都注明在哪台机器上核对过；声明了标准分类（`CATEGORY_APP_GALLERY` 等）而且没有横幅的系统 App 也走这条。
2. **精选图标库**（`TileRecipes`）：按包名写的「重绘配方」，只决定怎么用 App 自己的图标，不打包别家的图：
   - `top`/`bottom`：磁贴用品牌色渐变，图标的底板去掉，只留 logo（`cutOut`）；
   - `white`：logo 画成白色，只适合单色 logo 配另一种颜色的底板（WPS、夸克、小米「桌面」）；和 `top`/`bottom` 一起用在整张图片式的图标上时，只按亮度取出图里的白色 logo（Z视介：蓝色晶格上的白色 Z）；
   - `useIcon`：有横幅也不用，横幅上文字太多的 App（当贝市场、阿里云盘TV）；
   - `glyph`：完全用 Aerial 自己画的线条图标，给图标和横幅都只有名字的 App（我的电视）；
   - `keepColors`：不走下面的统一风格，保留图标原来的颜色（当贝市场的浮雕字、T惠购的红丝带）。
   每条都注明在哪台电视的 App 上核对过。改完用 `TileSheet` 出图对比。
3. **TV 横幅**。不够宽的「横幅」（有的 App 把方形图标再声明一次，比如遥遥电视助手）不算横幅。
4. **自适应图标**：背景层铺满，前景层单独保存，倾斜时做视差。前景超出安全区，或背景层自己有图形（Google 时钟的蓝色圆盘），就当整张图标处理。
5. **满底色的方形图标**（大多数国内 App）：底色铺满磁贴，图标边缘渐隐进底色。底色取上下边的中位数，并要求 6 成以上的边缘像素接近它，所以角上的小标（WPS 的「TV」）、碰到边的 logo 不影响判断。上下颜色差太多的（小米视频头条：上白下红）不算底板。
6. **老式图标**（认不出底板的）：放在同色系的斜向渐变上（取图标主色，灰色图标用石墨色），和系统 App 的磁贴一个风格。实心方块（含圆角方块）的图标多半是一整张图（视频头条、HiFi音乐），放大 1.25 倍，让里面的 logo 和别的一样大；方块上下边各自基本是一种颜色、两者又差得不太远（`edgeColors`，渐变可以，黑配橙不行）时，把这两种颜色铺满磁贴，图标边缘的 18% 渐隐进去，看起来是整块磁贴而不是贴在上面的图标（HiFi音乐、华数TV、开唛K歌、Z视介）；否则裁成圆角放在同色系渐变上。1.16 之前垫的是图标自己放大模糊的毛玻璃，看起来像手机图标贴在一块污渍上，已去掉。

**统一风格**（`TileArtFactory.unified`，默认开）：3、5、6 三类从图标生成的磁贴，和系统 App 一样画成「App 颜色的斜向渐变 + 白色 logo」（`unify`），这样磁贴之间不会一块白底一块彩底、logo 有大有小。底板本身是彩色的，就用底板的颜色，但把饱和度和亮度拉到同一个范围；底板是白、黑、灰的，就用 logo 自己的颜色。多色 logo（Geekbench 眼镜上的柱子、阿里云盘气泡里的星星、时尚画报）变白会糊成一团，`multiColor` 按色相分组，有两组以上、每组超过 8% 的就保留原样；和底板同色系的像素是 logo 边缘的抗锯齿，不算一种颜色（小米红底上的白「mi」）。TV 横幅是 App 为电视设计的图，不改。

Logo 的大小统一：先在 256×256 的采样里找出 Logo 的范围，再按面积缩放（同面积正方形的边长 = 磁贴高的 0.5），高不超过 0.6、宽不超过磁贴宽的 0.62，放在正中。这样宽的文字标志和圆形图标看起来一样重，也都留在安全区里，聚焦放大和视差不会碰到边。图标直接按最终尺寸画（不缩放位图），画完再量一次，因为有的 `layer-list` 用的是固定 dp 尺寸，不随 bounds 缩放。图标和横幅都取 App 自带的最高密度（`getDrawableForDensity(XXXHIGH)`），否则 TCL 240dpi 上拿到的是 72px 的图标。

前景层会裁到只剩可见像素，记下它在磁贴里的位置（`TileArt.foreAt`）：磁贴每帧都要画，电视 GPU 的填充率很紧。同时算出主色和 64×36 的环境光背景。所有位图都在 `Dispatchers.Default` 上生成，按包名、`lastUpdateTime` 和磁贴像素宽度缓存。

## 个性化：壁纸、屏保、焦点样式、天气

控制中心的「个性化」打开 `WallpaperSheet`，按「看得到的效果」从上到下分四段：壁纸、屏幕保护程序、焦点样式、天气（城市在手机页设置）。右边是手机扫码卡片。焦点移到最后一段可聚焦的「焦点样式」时，面板滚到底，让下面的天气也露出来。

- `Wallpapers` 是 Compose 状态单例，只在主线程写。来源：
  - `daily:<date>`：必应 HPImageArchive，`cn.bing.com`，最近 8 张，存在 `files/daily/`；
  - `daily:latest`：一直跟随最新一张；
  - `grad:<key>`：内置渐变；
  - `photo:<id>`：手机上传的照片，存在 `files/photos/`，id 是毫秒时间戳的 36 进制；
  - `auto`：跟随 App。
- 首页背景按屏幕实际像素解码，压暗层已经烘焙进去（`Images.shadeForHome`）；网格用的模糊版见「首页」一节。
- 屏保有两条路径：
  1. **桌面内**：`MainActivity` 空闲计时 5 分钟，或在第一行按返回、点「立即开始」→ `vm.screensaverVisible`，同时加 `FLAG_KEEP_SCREEN_ON`，用来压住厂商屏保（小米靠这条）。
  2. **系统级**：`AerialDream`，由 `install.sh` 在使用标准屏保（`screensaver_enabled=1`）的电视上写入 `screensaver_components`（TCL 靠这条）。
- 航拍：`assets/aerials.json` 只含地址和地名，影片实时从 `sylvan.apple.com` 播放（1080p H.264），用 Media3 ExoPlayer（#46，`AerialStream`）：全部影片放进一个播放列表，下一段提前加载；连接 5 秒、读取 8 秒超时，只重试一次。`sylvan.apple.com` 的证书链到 Apple Root CA，Android 自带的根证书里没有，`network_security_config.xml` 只对这个域名信任打包的 `raw/apple_root_ca.pem`；Android 5、6 不读这个配置，一开始就走 HTTP。8 秒没画面就换下一段，失败 3 次换照片屏保；这次屏保一帧都没播出来时，2 次就换。播放时把下一次屏保第一段影片的开头和索引预取到磁盘（缓存上限 48MB），下次几乎秒开。小米 MFTR0 实测冷启动 1.3 秒出第一帧。

## 手机配置（PhoneServer）

NanoHTTPD 监听 8790 端口，路径前缀是 8 位随机令牌（每次打开面板都重新生成，二维码里带着）。只在壁纸面板打开时运行，关闭后再保留 5 分钟。接口：`GET /` 页面、`GET api/state`、`GET thumb/<id>`、`POST api/wallpaper|screensaver|delete`（表单）、`POST api/upload`（multipart，可多张）；`GET tile/<包名>`（磁贴现在的样子）、`POST api/icon`（multipart：`app` 加一张图片）、`POST api/icon/reset`、`POST api/weather/city`（`name`）、`POST api/weather/auto`。页面在 `assets/web/index.html`。

- **自定义图标**：图片裁成 16:9、1280×720 存成 PNG，有这张图的 App 直接当横幅画（`AppCatalog.entry`，排在系统 App 自绘磁贴前面）。磁贴缓存的键里带着图片的修改时间；`CustomIcons.version` 变化时 `HomeViewModel` 重新加载 App。电视上的长按菜单里有「恢复默认图标」。包名必须是已安装的 App，同时也保证它是一个合法的文件名。
- **天气**（`Weather`）：Open-Meteo 的实时天气接口和地名查询接口都不需要 Key，国内能访问。城市优先用手机页里设置的；没设置时用 ipinfo.io 按 IP 定位，每天最多一次。整个家庭网络走代理时 IP 定位会定到别的国家（家里的网络就会定到新加坡），所以手机页会显示定位结果，让用户改。回到主页时刷新，最多每小时一次；最近一次结果缓存 3 小时，开机后先显示缓存。老系统不信任 Let's Encrypt 的根证书，HTTPS 失败时改用 HTTP（`network_security_config.xml` 只对这几个域名放行明文）。

## 开机信号源

控制中心 → 信号源 → 信号源设置 →「开机时打开」（`AerialSettings.bootSource`，issue #25）。国内电视的「开机」大多是从待机唤醒，所以三条路径都会触发 `MainActivity.openBootSource`：真重启后第一次启动（按 `Settings.Global.BOOT_COUNT` 每次开机只开一次）；守护在亮屏、复活或开机后把 Aerial 拉回前台时带上 `EXTRA_WOKE`（Aerial 被杀掉的小米）；Aerial 本来就在前台时自己收到的亮屏广播（TCL）。15 秒内重复触发只开一次。如果唤醒时 CEC 已经切到了某个信号源，守护不会拉回 Aerial，这一次也就不再打开设定的信号源。

## 遥控器按键

- 方向键、确认键由磁贴自己处理；长按确认键打开长按菜单。
- 菜单键：焦点在 App 磁贴上时打开这个 App 的长按菜单（老遥控器长按确认键识别不出来，issue #28），其他时候开关控制中心。和长按一样，只在本磁贴上按下、抬起时才触发，抬起不会误选菜单的第一项。
- `KEYCODE_TV_INPUT` 打开控制中心的信号源页。

## 触屏和鼠标（issue #60）

给带触摸屏的商用大屏和平板用，遥控器的逻辑不变。

- `Pressable` 和 `AppTile` 用 `detectTapGestures`：点一下先把焦点移过去再触发；磁贴长按打开长按菜单。触摸事件只发给按下时所在的控件，所以长按后抬起手指不会选中菜单第一项，和遥控器「只在本控件上开始的按压」一致。拖动一旦变成滚动，点按就取消。
- 网格用 `draggable` 拖动 `HomeScreen` 里同一个 `scroll`（不用 `verticalScroll`/`scrollable`，它们会响应焦点的 bringIntoView），松手按速度惯性滑动。拖过之后 `dragged` 为真，滚动不再跟随焦点行；按任意遥控器键（返回键除外）恢复。返回键在拖过的网格上先回第一排。
- 浮层：面板加 `consumeTaps()`，外面的遮罩加 `dismissOnTap()`，点外面等于按返回。没显示的浮层不能挂 pointerInput，否则会挡住首页的所有触摸。
- 移动模式下点某个磁贴把 App 移到那里，点这个 App 本身或空白处完成。提示文字按 `Configuration.touchscreen` 显示带触屏的版本。
- 音量、亮度滑块：左右拖动按步数调（音量按最大值分步，亮度 20 步）。小米 MFTR0 接 HDMI ARC 功放时，音量每一步都要等功放回应，快速拖动会丢步。
- `MainActivity.dispatchTouchEvent` 重置空闲计时；屏保显示时，这次触摸从按下到抬起只用来关屏保。

## 桌面接管（guard/）

层次和原因见 `docs/devices/`。数据流：

```
安装：install.sh 授权（WRITE_SECURE_SETTINGS、通知监听、SAW、WRITE_SETTINGS）
  → MainActivity.onStart → GuardSetup.ensureEnabled()（把守护写进 enabled_accessibility_services）
  → 5 秒后 rebindIfStalled()（已启用但没运行 → 先移除、1.5 秒后加回，触发重绑）
开机：守护、RevivalListener、BootReceiver 都声明了 directBootAware（#45），用户解锁之前就绑定
  - 锁定期间只记窗口，不启动界面、不拦 HOME；凭据加密存储里的偏好（keep_adb、屏保）解锁后再读
  - ACTION_USER_UNLOCKED → 1.5 秒后按下面的规则接管。TCL 75S365C 上从解锁到 Aerial 7.6 秒 → 约 2 秒
运行：HomeGuardService
  - onKeyEvent(HOME) → 不是默认桌面时吞掉按键，打开 Aerial（Android 11 及以下一直开着按键过滤，见 filterKeys）
  - TYPE_WINDOW_STATE_CHANGED → 原桌面的 HOME Activity 到前台 → 打开 Aerial
  - 亮屏、开机、复活、解锁 → 等 1.5 秒，前台还是原桌面（或者不知道）才打开 Aerial（takeOverOnceSettled）。
    只有 Activity 窗口算前台，浮层和对话框不算（TCL 语音助手）。前台是信号源时不动：CEC 切过去的，
    或者电视自己「开机播放上次的信号源」（小米 PatchWall 的 autoPlay），都以电视为准
  - adb_enabled 的 ContentObserver → keep_adb 时写回 1
被杀（小米待机 killAllThirdApp）：系统移除无障碍授权
  → 通知监听绑定断开 → 系统约 0.5 秒后重绑 RevivalListener（进程复活）
  → GuardSetup.ensureEnabled() → 守护重新启用 → onServiceConnected 判断为「刚被复活」→ 打开 Aerial
```

`allowStockLauncher`：用户从网格里主动打开原桌面时，1 小时内不拦截。

## 系统能力（system/）

- **信号源**：`TvInputManager` 列出直通输入和调谐器。装了 `com.xiaomi.mitv.tvplayer` 的走小米的 Intent（HDMI 的 `input = HW 编号 + 18`，AV=2，ATV/DTMB 各自的 Activity），否则走 `TvContract` 的通用 URI。CEC 子输入的名字用于显示设备名，`Playback_1` 这类通用名会被过滤。`DeviceProfile` 存放按机型的修正（MFTR0 隐藏不存在的 HW8，并重命名调谐器）。
- **音量**：`AudioManager` 的 STREAM_MUSIC；TCL 必须加 `FLAG_SHOW_UI`（否则不会写入功放）。
- **亮度**：见 `Brightness.kt` 的注释（小米 / 标准 / TCL 面板三种）。
- **设置入口**：优先厂商自己的设置（小米 `com.xiaomi.mitv.settings`）。

## 资源与第三方

- 字体：Inter 可变字体（OFL，许可证在 `FONT-LICENSE-OFL.txt`）。
- 依赖：Compose BOM 2026.02.01、activity-compose、lifecycle-viewmodel-compose、core-ktx、NanoHTTPD 2.3.1、ZXing core 3.5.3。
- 航拍目录由 Apple 的 `resources-16.tar` 生成（生成方法见 HANDOFF.md）。
