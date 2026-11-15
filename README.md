# 小米手环桥接 · 独立版

把小米手环直接接到手机上，**不需要 OPPO 健康**。

- 不依赖 `com.heytap.health`：本版不注入健康进程、不写健康数据库。手环数据存在本机，用应用自己的页面查看。
- 可选接入 ColorOS「设备空间」：装了 `com.heytap.mydevices` 就多一张手环卡片；不装也能用应用内页面。
- 直接连手环：SPP V2、SPP V1（8 Pro）、纯 BLE（fe95）三套连接方式，按导入时采集的 profile 选择。
- 功能：连接与电量、步数 / 心率 / 睡眠 / 静息心率 / 步行平均心率、历史同步与日汇总核对、天气同步、通知转发、来电提醒、手机闹钟镜像到手环并支持手环回控、音乐控制、入睡后暂停播放、查找手机。

包名 `io.github.kylemourn.mibandoplusbridge`（applicationId `…independent`），可与上游原版同时安装，数据互不影响。

## 仓库

https://github.com/KyleMourn/Miband-OPlusBridge_independent

## 与上游的关系

本项目由 [MiaM1ku/Miband-OPlusBridge](https://github.com/MiaM1ku/Miband-OPlusBridge) 二次开发，许可证同为 AGPL-3.0-or-later。

### 本版改动

| 方面 | 上游 | 本版 |
| --- | --- | --- |
| OPPO 健康 | 注入 `com.heytap.health` 进程、写入健康数据库 | 移除健康侧 hook 与写入；记录存本机（`data/HealthRecordStore`，独立模式账号为空），应用内「健康数据」页查看；健康存在时可经 `HealthQueueProvider` 读取，不存在也不影响 |
| 包名 | `io.github.miam1ku.mibandoplusbridge` | `io.github.kylemourn.mibandoplusbridge`，独立 applicationId，可与原版共存 |
| 闹钟 | hook 时钟日志字符串锚点；读私有 `alarms.db` 映射；1.5 秒时间窗去重回环；只认 ColorOS 包名与 authority | 重写：after hook `AlarmService.onStartCommand`；类名/方法/authority 运行时探测；事件标识 `alarmId:ringEpochSec` 幂等；不读私有数据库 |
| 手环关闭闹钟 | 只停响，开关不变灰；保留行会导致 5 分钟后自动稍后 | 走时钟自己的关闭入口（反射 `w(boolean)`），一次性闹钟同时把 `alarm` 与 `schedules` 的 `enabled` 置 0、阻止「用完即删」；重复闹钟只停响。先写表再停响，避免行被删 |
| 手环提醒超时 | 当作「稍后提醒」转发，5 分钟后手机再响一次 | 按「没做决定」处理：只停响、不产生稍后倒计时；手动「稍后提醒」仍完整复刻（两者协议上同一条命令，用 45 秒窗口区分） |
| 来电 | 接听/挂断后手环停在来电界面；另推「通话中」界面 | 接听、挂断、对方挂断、拒接、静音、通知栏挂断都会让手环界面立即消失；不再推「通话中」；静音判定带基线，系统本来就静音时不误清 |
| 时钟通道 | Messenger 只宣告一次，模块进程重启后命令丢失 | 自愈：时钟侧收到请求后重新宣告；模块侧排队未发命令并按 2 秒间隔重试 |
| 测试 | 存在引用已删健康类的失效测试 | 删除失效测试、补齐断言；403 项单测全绿 |

## 安装

1. 装支持 libxposed API 102 的 LSPosed，启用本模块，作用域勾：`com.oneplus.deskclock`（或 `com.coloros.alarmclock`）、`com.heytap.mydevices`、`com.mi.health`（仅导入绑定需要）。**不需要 `com.heytap.health`**。
2. 建议在 KernelSU 里授权 root：用于开机自启动、关联启动与后台拉起；不授权也能用，只是被系统冻结后需要手动打开应用。
3. 打开应用：检查 Root → 导入绑定（从本地数据库读密钥）→ 添加到桥接。
4. 首次安装或升级后，强停一次时钟应用（或重启手机），让新代码注入。
5. 通知使用权只影响「通知转发」和「音乐控制」，闹钟与来电不依赖它。

## 已知限制

- 闹钟回控依赖时钟 App 的内部实现。探测不到宿主时退回「只停响 + 通知转发」，日志里能看到 `CLOCK_HOST_UNRESOLVED`。
- 已在 OxygenOS 16 / `com.oneplus.deskclock` 实机验证；ColorOS、其他 ROM 未验证。
- 手环提醒超时与手动「稍后提醒」在协议上是同一条 `op=2` 命令，用时间窗口区分，属于近似判断。

## 构建

需要 JDK 21、Android SDK（compileSdk 37）。热重载开发时换一个更大的 versionCode：

```bash
./gradlew assembleDebug
./gradlew assembleDebug -PhotReloadVersionCode=<更大的数>
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

调试日志：`adb logcat -s OplusBandBridge`，或应用内「帮助 → 发送调试日志」。

打 tag（`{versionCode}-{versionName}`，如 `251-2.0.9`）会触发 GitHub Actions 构建并发布 Release；需要先配好签名与 PAT 的 Secrets，见 `.github/workflows/release.yml`。

## 鸣谢

- [MiaM1ku/Miband-OPlusBridge](https://github.com/MiaM1ku/Miband-OPlusBridge)：本项目的上游。
- [Gadgetbridge](https://gadgetbridge.org/)：[AstroBox-NG](https://github.com/AstralSightStudios/AstroBox-NG)：协议对照。

## 许可证

AGPL-3.0-or-later，见 `LICENSE`。
