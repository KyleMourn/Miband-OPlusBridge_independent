# 测试环境与验收清单

## 构建环境

| 项 | 值 |
| --- | --- |
| JDK | 21（Android Studio 自带 jbr 若是 25 不可用，AGP 8.13.2 不接受） |
| Android SDK | compileSdk 37、buildTools 36.0.0、minSdk 36 |
| Gradle | wrapper 8.13（`gradle-8.13-bin`，可 `--offline`） |
| AGP | 8.13.2，protobuf 插件 0.9.5 |

```bash
./gradlew :app:testDebugUnitTest          # 403 项单测
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease            # 需下面 4 个环境变量，否则 release 不签名
```

签名相关的环境变量：`OPLUSBAND_KEYSTORE`、`OPLUSBAND_STORE_PASSWORD`、`OPLUSBAND_KEY_ALIAS`、`OPLUSBAND_KEY_PASSWORD`。

## 实测环境

| 项 | 值 |
| --- | --- |
| 手机 | 一加 15（CPH2747EEA），Android 16，OxygenOS 16 / `CPH2747_16.0.5.700(EX01)`，EUEA 区域 |
| Root | KernelSU Ultra + Zygisk LSPosed（libxposed API 102） |
| 时钟 | 欧版 `com.oneplus.deskclock`（本机没有 `com.coloros.alarmclock`，代码里保留候选） |
| 手环 | 小米手环 10 陶瓷版（SPP V2） |
| 设备空间 | `com.heytap.mydevices` |
| OPPO 健康 | **未安装**：本版不依赖它 |

## 装完/升级后的固定动作

```bash
adb install -r app-debug.apk
adb shell am force-stop com.oneplus.deskclock     # 让新代码重新注入
adb logcat -c
adb logcat -s OplusBandBridge
```

注入失败的判断：会话日志里没有 `CLOCK_HOST_RESOLVED` / `CLOCK_HOOK_INSTALLED`。反复 `install -r` 后 LSPosed 偶尔会缓存旧模块，重启手机可恢复。

## 验收清单

**闹钟（一次性）**

1. 设一条 2 分钟后的闹钟，等待响铃：手环收到提醒（`tx spp2 type=17 subtype=16`）。
2. 在手环上点关闭：手机停止响铃、手机闹钟开关变灰、**不出现 5 分钟稍后倒计时**。
3. 若什么都不做，手环提醒到期后手机只停响，同样不应出现倒计时。

**闹钟（重复）**：手环关闭只停响，开关保持开启，次日继续响。

**闹钟（稍后）**：在手环上点稍后，5 分钟后手环与手机再次响铃；再关闭一次同样生效。

**来电**：接听、挂断、对方挂断、拒接、静音、通知栏挂断，任意一种操作后手环来电界面都应立即消失，且不出现「通话中」界面。手机本身处于静音模式时，来电仍应正常显示在手环上。

**通知与音乐**：已开启通知使用权时，微信/短信通知转发到手环；音乐控制可用。这两项依赖通知使用权，闹钟与来电不依赖。

## 日志关键字

| 关键字 | 含义 |
| --- | --- |
| `CLOCK_HOST_RESOLVED` | 时钟宿主探测结果（`ring=` / `stop=` / `disable=` 决定能力） |
| `CLOCK_HOOK_INSTALLED` | hook 装载成功 |
| `CLOCK_CHANNEL_REGISTERED` | 时钟进程与模块进程的通道建立（`ring=1` 表示能收到响铃事件） |
| `CLOCK_RING` | 起响事件 |
| `CLOSE` / `CLOSE_DISMISS` | 手环关闭时写表与调用时钟自身关闭入口的结果 |
| `CLOCK_DISABLE_NATIVE` | 时钟自己把开关关掉了（不需要我们补写） |
| `SNOOZE_CARD_CLEARED` / `SNOOZE_REFIRE` | 稍后卡片被清 / 拦下「已关闭又复响」 |
| `CALL_PHASE` / `CALL_RING_CLEARED` / `CALL_SILENCED` / `CALL_SUBMITTED_GONE` | 来电链路各阶段 |

会话日志文件（debug 包可直接读）：

```bash
adb shell run-as io.github.kylemourn.mibandoplusbridge.independent \
  grep -nE 'CLOCK_|CALL_|ALARM_' no_backup/session-log.txt
```
