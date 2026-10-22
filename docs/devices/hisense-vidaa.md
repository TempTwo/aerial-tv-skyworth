# 海信 VIDAA（`VIDAA_TV`）

来自用户反馈（#40），我们手上没有这台机器，以下都是报告人的实测。

- Android 11（SDK 30），`RP1A.200720.011`，MediaTek MT9653，arm64-v8a，1920×1080。
- 原桌面：`com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity`（聚好看）。
- 安装的 flavor：`standard`。`adb install` 直接成功；`pm grant`、`appops`、通知监听、屏保接管都正常，`APP_AUTO_START` / `APP_ASSOC_START` 报 `Unknown operation string`（TCL 才有，忽略）。

## HOME 锁定

- `cmd role add-role-holder` 成功，`dumpsys role` 里 HOME 的 holder 也是 Aerial，但按 HOME 时系统用**显式 component** 直接拉起聚好看：`START ... cat=[HOME] cmp=com.jamdeo.tv.vod/... from uid 1000`。
- 桌面守护已绑定，但按 HOME 后收不到聚好看的 `TYPE_WINDOW_STATE_CHANGED`，没有任何 `AerialGuard` 日志。守护「拉回前台」本身是好的（用 `ConfigReceiver` 触发重绑时能把 Aerial 拉回来），问题在事件派发。
- **解决办法**：海信用私有属性 `persist.vt.use_home_pkg` 指定主屏幕包。`adb shell setprop persist.vt.use_home_pkg app.orchard.tv` 之后，`resolve-activity` 返回 Aerial，按 HOME 直接进 Aerial，重启后属性仍在。不需要 root。
- `tools/install.sh`（standard 版）只在这个属性存在时改它，原值存在电视的 `settings global aerial_vt_home_pkg` 里；`tools/restore.sh` 用它还原。

## 开机自启（做不到）

- 开机时海信在 `boot_completed` 之前拦截第三方进程：`[VtInterceptApp] intercept 3rd app.processName: app.orchard.tv(service) before boot_completed`。
- 聚好看自己靠海信私有广播 `android.intent.action.HISENSE_BOOT_COMPLETED` 启动；Aerial 的 `BootReceiver` 收不到 `BOOT_COMPLETED`，通知监听开机时也不会被绑定。
- 试过无效：`settings put system settings_startup_source_custom_pkg_key app.orchard.tv`、`cmd notification` 重绑、在默认应用里选 Aerial。
- 结果：开机先到聚好看，按一次 HOME 进 Aerial（改了上面的属性之后）。
