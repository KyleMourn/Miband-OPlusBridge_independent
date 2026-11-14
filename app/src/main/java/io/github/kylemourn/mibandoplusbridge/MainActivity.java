// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.DialogInterface;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import io.github.kylemourn.mibandoplusbridge.data.BandCatalog;
import io.github.kylemourn.mibandoplusbridge.data.BandStateRepository;
import io.github.kylemourn.mibandoplusbridge.data.BindingStore;
import io.github.kylemourn.mibandoplusbridge.data.HealthRecordStore;
import io.github.kylemourn.mibandoplusbridge.data.LocalPrefs;
import io.github.kylemourn.mibandoplusbridge.data.SessionLog;
import io.github.kylemourn.mibandoplusbridge.data.TransportObservation;
import io.github.kylemourn.mibandoplusbridge.hook.ClockHostProfile;
import io.github.kylemourn.mibandoplusbridge.integration.AlarmLinkage;
import io.github.kylemourn.mibandoplusbridge.integration.BandDetailsActivity;
import io.github.kylemourn.mibandoplusbridge.integration.CredentialProvider;
import io.github.kylemourn.mibandoplusbridge.integration.DeviceCardProvider;
import io.github.kylemourn.mibandoplusbridge.integration.HealthQueueProvider;
import io.github.kylemourn.mibandoplusbridge.notify.BandNotificationListener;
import io.github.kylemourn.mibandoplusbridge.notify.SleepMusic;
import io.github.kylemourn.mibandoplusbridge.protocol.BandNotificationCommand;
import io.github.kylemourn.mibandoplusbridge.protocol.SppDiagnosticClient;
import io.github.kylemourn.mibandoplusbridge.service.BandLiveService;
import io.github.kylemourn.mibandoplusbridge.service.OwnershipController;
import io.github.kylemourn.mibandoplusbridge.ui.BridgeScreen;
import io.github.kylemourn.mibandoplusbridge.ui.Copy;
import io.github.kylemourn.mibandoplusbridge.ui.SetupProgress;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.json.JSONObject;

public final class MainActivity extends AppCompatActivity {
   private final ExecutorService worker = Executors.newSingleThreadExecutor();
   private final Handler main = new Handler(Looper.getMainLooper());
   private static final int BLUETOOTH_PERMISSION = 1;
   private static final int CALL_PERMISSIONS = 2;
   private BridgeScreen screen;
   private TextView deviceName;
   private TextView statusChip;
   private TextView connectionHint;
   private TextView metricsLine;
   private TextView healthLine;
   private TextView ownershipStatus;
   private TextView clockStatus;
   private Button homePrimary;
   private LinearLayout setupCard;
   private TextView setupTitle;
   private TextView stepRoot;
   private TextView stepImport;
   private TextView stepAdd;
   private TextView primaryHint;
   private Button openKsu;
   private LinearLayout nearbyNotice;
   private LinearLayout notifyNotice;
   private LinearLayout healthAccessNotice;
   private LinearLayout listenerNotice;
   private TextView manageHeading;
   private LinearLayout manageCard;
   private Button remove;
   private Button restore;
   private LinearLayout bonded;
   private TextView status;
   private Button open;
   private Button advancedRestore;
   private String selectedAddress = "";
   private String databaseImportNote = "";
   private boolean hostSupported;
   private boolean callPermissionsAsked;
   private boolean changingOwnership;
   private boolean launchHostAfterOpen;
   private boolean profileAfterRestore;
   private boolean rootGranted;
   private boolean probingRoot;
   private Change pendingPermission;
   private final ContentObserver deviceObserver;
   private final ContentObserver observer;
   private final Runnable expireRefresh;

   public MainActivity() {
      this.deviceObserver = new ContentObserver(this.main) {
         public void onChange(boolean selfChange) {
            MainActivity.this.refreshDevice();
         }
      };
      this.observer = new ContentObserver(this.main) {
         public void onChange(boolean selfChange) {
            MainActivity.this.refresh();
            MainActivity.this.refreshDevice();
         }
      };
      this.expireRefresh = this::refresh;
   }

   static boolean openProfileAfterRestore(Change change, boolean restored) {
      return restored && change == MainActivity.Change.RESTORE;
   }

   public void onCreate(Bundle state) {
      super.onCreate(state);
      this.screen = BridgeScreen.attach(this, "小米手环桥接", false);
      this.buildStatusCard();
      this.buildSetupCard();
      this.buildNotices();
      this.buildFeatures();
      this.buildHelp();
      this.buildManagement();
      this.buildAdvanced();
      this.getContentResolver().registerContentObserver(CredentialProvider.URI, false, this.observer);
      this.getContentResolver().registerContentObserver(DeviceCardProvider.URI, false, this.deviceObserver);
      this.getContentResolver().registerContentObserver(HealthQueueProvider.URI, false, this.deviceObserver);
      this.refreshDevice();
      this.requestRoot();
   }

   private void buildStatusCard() {
      LinearLayout card = this.screen.card();
      this.deviceName = this.screen.title(card, "小米手环");
      this.statusChip = this.screen.chip(card, "正在读取", BridgeScreen.Tone.NEUTRAL);
      this.connectionHint = this.screen.caption(card, "");
      this.metricsLine = this.screen.caption(card, "");
      this.healthLine = this.screen.caption(card, "");
      this.ownershipStatus = this.screen.bodyText(card, "");
      this.ownershipStatus.setTextSize(14.0F);
      this.homePrimary = this.screen.filled(card, Copy.setupLabel(SetupProgress.Step.ROOT), this::runHomePrimary);
      this.connectionHint.setVisibility(8);
      this.metricsLine.setVisibility(8);
      this.healthLine.setVisibility(8);
   }

   private void buildSetupCard() {
      this.setupCard = this.screen.card();
      this.setupTitle = this.screen.overline(this.setupCard, "首次设置");
      this.stepRoot = this.screen.stepRow(this.setupCard, 1, "授权 Root", BridgeScreen.StepState.CURRENT);
      this.stepImport = this.screen.stepRow(this.setupCard, 2, "导入绑定", BridgeScreen.StepState.LATER);
      this.stepAdd = this.screen.stepRow(this.setupCard, 3, "添加到桥接", BridgeScreen.StepState.LATER);
      this.primaryHint = this.screen.caption(this.setupCard, "");
      this.openKsu = this.screen.outlined(this.setupCard, "打开 KernelSU", this::openKernelSu);
      LinearLayout prereq = this.screen.expander(this.setupCard, "开始前准备", (Runnable)null);
      this.screen.caption(prereq, "1. 在系统蓝牙里配对手环。\n2. 在 LSPosed 启用本模块，勾选设备空间、小米运动健康，然后强停这两个应用一次。\n3. 在 KernelSU 中允许本应用使用超级用户。");
   }

   private void buildNotices() {
      this.nearbyNotice = this.screen.noticeCard(BridgeScreen.Tone.ATTENTION);
      this.screen.bodyText(this.nearbyNotice, "授权附近设备后即可连接手环。");
      this.screen.filled(this.nearbyNotice, "授权附近设备", () -> {
         this.pendingPermission = null;
         this.requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"}, 1);
      });
      this.notifyNotice = this.screen.noticeCard(BridgeScreen.Tone.NEUTRAL);
      this.screen.bodyText(this.notifyNotice, "开启通知后，可在通知栏看到手环连接状态。");
      this.screen.outlined(this.notifyNotice, "开启通知", this::openAppNotificationSettings);
      this.healthAccessNotice = this.screen.noticeCard(BridgeScreen.Tone.ACTIVE);
      this.screen.bodyText(this.healthAccessNotice, "在系统设置里把「小米手环桥接」的通知使用权关掉再打开一次，手机通知就能转到手环。");
      this.screen.outlined(this.healthAccessNotice, "打开通知使用权", this::openHealthListenerSettings);
      this.listenerNotice = this.screen.noticeCard(BridgeScreen.Tone.ATTENTION);
      this.screen.bodyText(this.listenerNotice, "开启「小米手环桥接」的通知使用权后，微信、短信、来电等通知才会转发到手环。");
      this.screen.outlined(this.listenerNotice, "开启通知使用权", this::openBridgeListenerSettings);
      setShown(this.nearbyNotice, false);
      setShown(this.notifyNotice, false);
      setShown(this.healthAccessNotice, false);
      setShown(this.listenerNotice, false);
   }

   private void buildFeatures() {
      this.screen.heading("功能");
      LinearLayout links = this.screen.card();
      this.screen.navRow(links, "设备详情", () -> this.startActivity(new Intent(this, BandDetailsActivity.class)));
      this.screen.navRow(links, "健康数据", () -> this.startActivity(new Intent(this, HealthActivity.class)));
      this.screen.navRow(links, "天气同步", () -> this.startActivity(new Intent(this, WeatherActivity.class)));
      this.screen.navRow(links, "通知转发应用", () -> this.startActivity(new Intent(this, NotificationAppsActivity.class)));
      this.screen.caption(links, "在控制中心添加「手环」磁贴，下拉或点按即可连接。");
      LinearLayout sleepCard = this.screen.card();
      MaterialSwitch sleepPause = this.screen.switchRow(sleepCard, "入睡后暂停音乐", "手环报告入睡时暂停正在播放的音乐，醒来后保持暂停。打开开关时如果已经入睡，会在下一次入睡时生效。通过睡眠记录报告入睡的手环，会在记录同步后暂停，通常需要几分钟。");
      boolean[] writingSleepPause = new boolean[]{false};
      sleepPause.setChecked(SleepMusic.enabled(this));
      sleepPause.setOnCheckedChangeListener((button, checked) -> {
         if (!writingSleepPause[0]) {
            if (SleepMusic.setEnabled(this, checked)) {
               BandLiveService.sleepPauseChanged(this);
            } else {
               writingSleepPause[0] = true;
               button.setChecked(!checked);
               writingSleepPause[0] = false;
               this.ownershipStatus.setText("开关状态保存中断，再拨一次即可。");
            }
         }
      });
      LinearLayout pushCard = this.screen.card();
      SharedPreferences noticePrefs = this.getSharedPreferences("notification-settings", 0);
      MaterialSwitch messages = this.screen.switchRow(pushCard, "转发通知消息", "微信、短信、来电等通知在手机上出现时转发到手环。需要先开启通知使用权。");
      messages.setChecked(noticePrefs.getBoolean("enabled", true));
      messages.setOnCheckedChangeListener((button, checked) -> noticePrefs.edit().putBoolean("enabled", checked).apply());
      MaterialSwitch calls = this.screen.switchRow(pushCard, "来电提醒", "手环显示来电和通话状态，可直接在手机上接听；需要电话权限。");
      calls.setChecked(noticePrefs.getBoolean("callsEnabled", false));
      calls.setOnCheckedChangeListener((button, checked) -> noticePrefs.edit().putBoolean("callsEnabled", checked).apply());
      MaterialSwitch screenOff = this.screen.switchRow(pushCard, "仅在息屏时转发", "默认只在息屏时转发通知。关闭该项后，亮屏也转发。");
      screenOff.setChecked(noticePrefs.getBoolean("screenOffOnly", true));
      screenOff.setOnCheckedChangeListener((button, checked) -> noticePrefs.edit().putBoolean("screenOffOnly", checked).apply());
      MaterialSwitch bandScreenOn = this.screen.switchRow(pushCard, "手表收到通知时亮屏", "转发的通知到达手环时点亮手环屏幕，是否生效取决于手环固件。");
      bandScreenOn.setChecked(noticePrefs.getBoolean("bandScreenOn", true));
      bandScreenOn.setOnCheckedChangeListener((button, checked) -> {
         noticePrefs.edit().putBoolean("bandScreenOn", checked).apply();
         this.sendBandScreenOn(checked);
      });
      this.screen.outlined(pushCard, "发送预览通知", this::sendPreviewNotification);
      LinearLayout alarmCard = this.screen.card();
      this.screen.title(alarmCard, "手机闹钟");
      this.screen.bodyText(alarmCard, "手机响铃时在手环提醒；在手环上关闭或稍后提醒，会回传手机上的闹钟。");
      MaterialSwitch alarmLink = this.screen.switchRow(alarmCard, "手机闹钟联动", "关掉后闹钟只按通知转发到手环。");
      alarmLink.setChecked(AlarmLinkage.enabled(this));
      alarmLink.setOnCheckedChangeListener((button, checked) -> {
         AlarmLinkage.setEnabled(this, checked);
         this.refreshClockStatus();
      });
      this.clockStatus = this.screen.caption(alarmCard, "");
      if (!Settings.canDrawOverlays(this)) {
         this.screen.navRow(pushCard, "授予悬浮窗权限（手环关闭闹钟需要）", () -> {
            Intent intent = new Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION", Uri.parse("package:" + this.getPackageName()));

            try {
               this.startActivity(intent);
            } catch (RuntimeException var3) {
               this.ownershipStatus.setText("请到系统设置 → 应用 → 特殊权限中开启悬浮窗。");
            }

         });
      }

   }

   private void sendBandScreenOn(boolean enabled) {
      try {
         BandLiveService.sendSessionCommand(BandNotificationCommand.screenOn(enabled)).whenComplete((ignored, error) -> this.runOnUiThread(() -> this.ownershipStatus.setText(error == null ? "手表亮屏设置已同步。" : "手表亮屏设置需要重试：" + error.getMessage())));
      } catch (RuntimeException failure) {
         this.ownershipStatus.setText("手表亮屏设置需要重试：" + failure.getMessage());
      }

   }

   private void sendPreviewNotification() {
      try {
         String key = "bridge-preview-" + System.currentTimeMillis();
         XiaomiProto.Command command = BandNotificationCommand.post(this.getPackageName(), "小米手环桥接", key, (int)(System.currentTimeMillis() & 2147483647L), "小米手环桥接", "这是一条预览通知：微信消息会这样显示。", Instant.now(), ZoneId.systemDefault());
         BandLiveService.sendSessionCommand(command).whenComplete((ignored, error) -> this.runOnUiThread(() -> this.ownershipStatus.setText(error == null ? "预览通知已发送到手环。" : "预览通知稍后重试：" + error.getMessage())));
      } catch (RuntimeException failure) {
         this.ownershipStatus.setText("预览通知稍后重试：" + failure.getMessage());
      }

   }

   private void buildHelp() {
      this.screen.heading("帮助");
      LinearLayout help = this.screen.card();
      this.screen.navRow(help, "使用说明", this::showSetupGuide);
      this.screen.navRow(help, "系统权限设置", () -> this.startActivity(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS", Uri.parse("package:" + this.getPackageName()))));
      this.screen.navRow(help, "发送调试日志", this::shareDebugLog);
   }

   private void buildManagement() {
      this.manageHeading = this.screen.heading("管理");
      this.manageCard = this.screen.card();
      this.restore = this.screen.outlined(this.manageCard, "恢复小米运动健康", () -> this.changeOwnership(MainActivity.Change.RESTORE));
      this.remove = this.screen.dangerOutlined(this.manageCard, "从健康移除", () -> (new MaterialAlertDialogBuilder(this)).setTitle("从健康移除这只手环？").setMessage("桥接会断开连接，把手环交还小米运动健康。健康历史和加密绑定都会保留，之后可随时重新添加。").setNegativeButton("取消", (DialogInterface.OnClickListener)null).setPositiveButton("移除", (dialog, which) -> this.changeOwnership(MainActivity.Change.REMOVE)).show());
      this.screen.caption(this.manageCard, "绑定和健康历史始终保存在本机。");
   }

   private void buildAdvanced() {
      LinearLayout advanced = this.screen.expander(this.screen.body, "高级设置", this::refreshBonded);
      LinearLayout importCard = this.screen.card(advanced);
      this.screen.overline(importCard, "绑定导入");
      this.screen.caption(importCard, "首页的「导入绑定」直接读取小米运动健康的本地数据库。这里保留旧式导入窗口：选择已配对设备，在 120 秒内由官方应用重新读取绑定。");
      this.bonded = new LinearLayout(this);
      this.bonded.setOrientation(1);
      importCard.addView(this.bonded);
      String preselected = this.getIntent().getStringExtra("deviceAddress");
      if (preselected != null && preselected.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) {
         this.selectedAddress = preselected;
      }

      this.open = this.screen.filled(importCard, "开启导入窗口（120 秒）", () -> {
         if (this.hostSupported) {
            if (this.selectedAddress.isBlank()) {
               this.status.setText("先在上方选择一只已配对设备。");
            } else {
               Bundle selected = new Bundle();
               selected.putString("address", this.selectedAddress);
               this.action("openWindow", selected);
            }
         }
      });
      this.open.setEnabled(false);
      this.screen.outlined(importCard, "关闭导入和采集窗口", () -> this.action("closeWindow", (Bundle)null));
      this.screen.outlined(importCard, "打开小米运动健康", this::launchMiHealth);
      this.advancedRestore = this.screen.outlined(importCard, "恢复官方管理", () -> this.changeOwnership(MainActivity.Change.RESTORE));
      this.status = this.screen.bodyText(importCard, "正在检查小米运动健康…");
      this.status.setTextSize(14.0F);
      LinearLayout debug = this.screen.card(advanced);
      this.screen.overline(debug, "调试");
      this.screen.navRow(debug, "协议测试", () -> this.startActivity(new Intent(this, LabActivity.class)));
      if (BuildConfig.DEBUG) {
         this.screen.navRow(debug, "独立连接诊断", () -> this.startActivity(new Intent(this, DiagnosticActivity.class)));
         this.screen.outlined(debug, "采集连接参数（120 秒）", this::startProfileCapture);
         this.screen.outlined(debug, "采集协议消息（120 秒）", () -> (new MaterialAlertDialogBuilder(this)).setTitle("采集所选手环的协议消息").setMessage("消息可能包含通知和健康数据。原始内容保存在本机私有目录，用于本次协议分析。").setNegativeButton("取消", (DialogInterface.OnClickListener)null).setPositiveButton("开始采集", (dialog, which) -> this.action("openCapture", (Bundle)null)).show());
      }

   }

   private static void setShown(LinearLayout cardInner, boolean shown) {
      View frame = (View)cardInner.getParent();
      ((View)(frame == null ? cardInner : frame)).setVisibility(shown ? 0 : 8);
   }

   private void refreshClockStatus() {
      if (this.clockStatus != null) {
         if (!AlarmLinkage.enabled(this)) {
            this.clockStatus.setText("联动已关：闹钟只按通知转发到手环。");
         } else {
            ClockHostProfile.Presence presence = ClockHostProfile.presence(this);
            if (!presence.installed()) {
               this.clockStatus.setText("装好手机闹钟后，联动随即生效；当前按通知转发。");
            } else if (!presence.disableCapable()) {
               this.clockStatus.setText("已识别手机闹钟：手环关闭时先做到停响，闹钟开关保持原样。");
            } else {
               this.clockStatus.setText("已识别手机闹钟：手环关闭只响一次的闹钟时，手机上的开关一起变灰。");
            }

         }
      }
   }

   protected void onResume() {
      super.onResume();
      this.refreshNotices();
      this.refreshClockStatus();
      if (!this.rootGranted) {
         this.requestRoot();
      } else {
         this.continueInit();
      }

   }

   private void continueInit() {
      this.refreshOwnership();
      this.refreshDevice();
      this.refreshBonded();
      if (this.hostSupported) {
         this.refresh();
      } else {
         this.worker.execute(() -> {
            boolean supported = HostIdentity.installed(this, "com.mi.health");
            this.main.post(() -> {
               if (!this.isDestroyed()) {
                  this.hostSupported = supported;
                  this.open.setEnabled(supported && this.rootGranted);
                  if (supported) {
                     this.refresh();
                  } else {
                     this.status.setText("安装小米运动健康并配对手环后，即可使用导入窗口。");
                  }

               }
            });
         });
      }

      this.ensureCallPermissions();
   }

   protected void onDestroy() {
      this.getContentResolver().unregisterContentObserver(this.observer);
      this.getContentResolver().unregisterContentObserver(this.deviceObserver);
      this.main.removeCallbacks(this.expireRefresh);
      this.worker.shutdown();
      super.onDestroy();
   }

   private void refreshNotices() {
      if (this.nearbyNotice != null) {
         setShown(this.nearbyNotice, !this.hasBluetoothPermission());
         setShown(this.notifyNotice, !((NotificationManager)this.getSystemService(NotificationManager.class)).areNotificationsEnabled());
         LocalPrefs prefs = LocalPrefs.open(this, "notification-settings");
         boolean healthAccess = prefs.getBoolean("healthSecure", false) && !prefs.getBoolean("healthApproved", true) && prefs.getBoolean("healthGrantFailed", false);
         setShown(this.healthAccessNotice, healthAccess);
         setShown(this.listenerNotice, !BandNotificationListener.accessGranted(this));
      }
   }

   private void openAppNotificationSettings() {
      Intent intent = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
      intent.putExtra("android.provider.extra.APP_PACKAGE", this.getPackageName());

      try {
         this.startActivity(intent);
      } catch (RuntimeException var3) {
         this.startActivity(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS", Uri.parse("package:" + this.getPackageName())));
      }

   }

   private void openHealthListenerSettings() {
      this.openListenerAccess();
   }

   private void openBridgeListenerSettings() {
      this.openListenerAccess();
   }

   /** 依次尝试各家系统的「通知使用权」页面，落到本应用那一项。 */
   private void openListenerAccess() {
      ComponentName own = new ComponentName(this, BandNotificationListener.class);
      String flat = own.flattenToString();
      List<Intent> targets = new ArrayList<>();
      targets.add(this.listenerDetail(flat));
      targets.add(this.listenerDetail(null));
      String[] detailPages = new String[]{"com.android.settings.Settings$NotificationAccessDetailsActivity", "com.android.settings.Settings$NotificationAccessSettingsActivity", "com.android.settings.Settings$NotificationListenerSettingsActivity", "com.android.settings.Settings$SecNotificationAccessSettingsActivity", "com.coloros.settings.Settings$NotificationAccessSettingsActivity", "com.oplus.settings.Settings$NotificationAccessDetailsActivity"};

      for (int i = 0; i < detailPages.length; ++i) {
         targets.add(this.listenerDetail(flat).setComponent(new ComponentName("com.android.settings", detailPages[i])));
      }

      targets.add(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
      String[] listPages = new String[]{"com.android.settings.Settings$NotificationAccessSettingsActivity", "com.android.settings.Settings$NotificationListenerSettingsActivity"};

      for (int i = 0; i < listPages.length; ++i) {
         targets.add((new Intent()).setComponent(new ComponentName("com.android.settings", listPages[i])));
      }

      targets.add(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + this.getPackageName())));

      for (Intent intent : targets) {
         if (intent.resolveActivity(this.getPackageManager()) != null) {
            try {
               this.startActivity(intent);
               return;
            } catch (RuntimeException ignored) {
            }
         }
      }

      this.ownershipStatus.setText("在系统设置 → 通知与状态栏 → 通知使用权中找到「小米手环桥接」。");
   }

   private Intent listenerDetail(String flat) {
      Intent intent = new Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS");
      String component = flat != null ? flat : new ComponentName(this, BandNotificationListener.class).flattenToString();
      intent.putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", component);
      return intent;
   }

   private void showSetupGuide() {
      (new MaterialAlertDialogBuilder(this)).setTitle("使用说明").setMessage("1. 检查 Root：在 KernelSU 中允许本应用，回来点「检查 Root」。\n2. 导入绑定：从小米运动健康的本地数据库读取手环密钥。\n3. 添加到桥接：桥接接管手环，设备空间里随即出现这只手环。\n\n日常使用：在控制中心添加「手环」磁贴，下拉或点按即可连接。\n\n开始前准备\n1. 在系统蓝牙里配对手环。\n2. 在 LSPosed 启用本模块，勾选设备空间、小米运动健康，然后强停这两个应用一次。\n3. 在 KernelSU 中允许本应用使用超级用户。").setPositiveButton("好的", (DialogInterface.OnClickListener)null).show();
   }

   private void shareDebugLog() {
      this.worker.execute(() -> {
         try {
            File file = SessionLog.export(this);
            Uri uri = FileProvider.getUriForFile(this, this.getPackageName() + ".debuglog", file);
            Intent send = new Intent("android.intent.action.SEND");
            send.setType("text/plain");
            send.putExtra("android.intent.extra.STREAM", uri);
            send.putExtra("android.intent.extra.SUBJECT", "小米手环桥接调试日志");
            send.addFlags(1);
            Intent qq = new Intent(send);
            qq.setPackage("com.tencent.mobileqq");
            this.main.post(() -> {
               if (!this.isDestroyed()) {
                  if (qq.resolveActivity(this.getPackageManager()) != null) {
                     this.startActivity(qq);
                  } else {
                     this.startActivity(Intent.createChooser(send, "发送调试日志"));
                  }

               }
            });
         } catch (Exception var5) {
            this.main.post(() -> {
               if (!this.isDestroyed()) {
                  Toast.makeText(this, "调试日志生成中断，稍后再试一次", 0).show();
               }

            });
         }

      });
   }

   private void requestRoot() {
      if (!this.worker.isShutdown() && !this.probingRoot && !this.changingOwnership) {
         this.probingRoot = true;
         this.ownershipStatus.setText("正在检查 KernelSU 授权…");
         this.worker.execute(() -> {
            boolean granted = (new OwnershipController(this)).probeRoot();
            this.main.post(() -> {
               if (!this.isDestroyed()) {
                  this.probingRoot = false;
                  this.rootGranted = granted;
                  this.ownershipStatus.setText(granted ? "KernelSU 已授权。" : Copy.setupHint(SetupProgress.Step.ROOT));
                  this.updateControls();
                  this.refreshDevice();
                  if (granted) {
                     this.continueInit();
                  }

               }
            });
         });
      }
   }

   private void openKernelSu() {
      Intent launch = this.getPackageManager().getLaunchIntentForPackage("me.weishu.kernelsu");
      if (launch == null) {
         this.ownershipStatus.setText("安装 KernelSU 管理器后，为本应用打开超级用户。");
      } else {
         this.startActivity(launch);
      }
   }

   private void refreshOwnership() {
      if (!this.worker.isShutdown() && !this.changingOwnership && this.rootGranted) {
         this.worker.execute(() -> {
            if (this.isUnlocked()) {
               OwnershipController owner = new OwnershipController(this);
               BandStateRepository repository = new BandStateRepository(this);

               String message;
               try {
                  boolean registered = repository.isRegistered();
                  boolean ready = owner.nativeReady();
                  if (!registered) {
                     BandLiveService.stop(this);
                     if (!SppDiagnosticClient.stopAllAndWait()) {
                        throw new OwnershipController.Failure("DIAGNOSTIC_SOCKET_STILL_ACTIVE");
                     }

                     repository.disconnected();
                  }

                  if (registered && ready && this.hasBluetoothPermission()) {
                     BandLiveService.start(this);
                  }

                  message = !registered ? (this.needsOfficialRestore() ? "小米运动健康仍在暂停。可以添加设备，或在「管理」中恢复小米运动健康。" : "") : ("NATIVE".equals(owner.mode()) ? "由桥接管理，连接状态以手环实际响应为准。" : "当前由小米运动健康管理，点「立即同步」重新接管。");
               } catch (Exception var6) {
                  message = repository.isRegistered() ? "设备登记已保留。点「立即同步」再连接一次，或恢复官方管理。" : "点「恢复小米运动健康」再试一次。";
               }

               final String decided = message;
               this.main.post(() -> {
                  if (!this.isDestroyed()) {
                     this.ownershipStatus.setText(this.databaseImportNote.isEmpty() ? decided : this.databaseImportNote);
                     this.updateControls();
                  }
               });
            }
         });
      }
   }

   private void changeOwnership(Change change) {
      if (!this.worker.isShutdown() && !this.changingOwnership) {
         if (!this.rootGranted) {
            this.requestRoot();
         } else if (!this.isUnlocked()) {
            this.ownershipStatus.setText("解锁手机后再管理设备。");
         } else {
            this.changingOwnership = true;
            this.updateControls();
            this.ownershipStatus.setText("正在处理，请稍候…");
            this.worker.execute(() -> {
               OwnershipController owner = new OwnershipController(this);
               BandStateRepository repository = new BandStateRepository(this);
               boolean registrationSaved = false;
               boolean removalSaved = false;
               boolean officialRestored = false;

               String message;
               try {
                  if (change != MainActivity.Change.ADD && change != MainActivity.Change.RECONNECT) {
                     if (change == MainActivity.Change.REMOVE) {
                        repository.unregisterDevice();
                        removalSaved = true;
                        this.main.post(this::refreshDevice);
                     }

                     BandLiveService.stop(this);
                     if (!SppDiagnosticClient.stopAllAndWait()) {
                        throw new OwnershipController.Failure("DIAGNOSTIC_SOCKET_STILL_ACTIVE");
                     }

                     repository.disconnected();
                     owner.restoreOfficial();
                     officialRestored = change == MainActivity.Change.RESTORE;
                     message = change == MainActivity.Change.REMOVE ? "已移除，小米运动健康已恢复。绑定和健康历史都已保留。" : "小米运动健康已恢复。绑定和健康历史都已保留。";
                  } else {
                     if (!this.isUnlocked()) {
                        throw new IllegalStateException("USER_LOCKED");
                     }

                     if (!owner.probeRoot()) {
                        throw new OwnershipController.Failure("ROOT_REQUIRED");
                     }

                     if (!this.hasBluetoothPermission()) {
                        throw new IllegalStateException("NEARBY_PERMISSION_REQUIRED");
                     }

                     if (change == MainActivity.Change.ADD) {
                        repository.registerDevice();
                        registrationSaved = true;
                     }

                     if (!repository.isRegistered()) {
                        throw new IllegalStateException("DEVICE_NOT_REGISTERED");
                     }

                     if (!owner.nativeReady()) {
                        owner.takeOver();
                     }

                     if (!repository.isRegistered() || !owner.nativeReady()) {
                        throw new IllegalStateException("NATIVE_OWNERSHIP_REQUIRED");
                     }

                     BandLiveService.start(this);
                     BandLiveService.requestSync(this);
                     message = "设备已登记，正在连接。";
                  }
               } catch (Exception failure) {
                  String hint = Copy.ownershipFailure(failureCode(failure));
                  if (!removalSaved && (change != MainActivity.Change.RESTORE || repository.isRegistered())) {
                     if (change == MainActivity.Change.REMOVE) {
                        message = "设备仍在登记中，再点一次「从健康移除」。";
                     } else if (!registrationSaved && (change != MainActivity.Change.RECONNECT || !repository.isRegistered())) {
                        message = hint;
                     } else {
                        message = "设备登记已保留，连接稍后继续。" + hint;
                     }
                  } else {
                     message = "已移除，小米运动健康待恢复。" + hint;
                  }
               }

               final String decided = message;
               boolean followProfile = openProfileAfterRestore(change, officialRestored);
               this.main.post(() -> {
                  if (!this.isDestroyed()) {
                     this.changingOwnership = false;
                     this.ownershipStatus.setText(decided);
                     this.updateControls();
                     this.refreshDevice();
                     if (this.profileAfterRestore && followProfile) {
                        this.profileAfterRestore = false;
                        this.startProfileWindow();
                     }

                  }
               });
            });
         }
      }
   }

   private static String failureCode(Exception failure) {
      return failure instanceof OwnershipController.Failure ? ((OwnershipController.Failure)failure).code : failure.getMessage();
   }

   private void requestNative(Change change) {
      if (!this.changingOwnership) {
         if (!this.rootGranted) {
            this.requestRoot();
         } else if (!this.isUnlocked()) {
            this.ownershipStatus.setText("解锁手机后再管理设备。");
         } else if (!this.hasBluetoothPermission()) {
            this.pendingPermission = change;
            this.requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"}, 1);
         } else if (change == MainActivity.Change.RECONNECT) {
            this.changeOwnership(change);
         } else {
            (new MaterialAlertDialogBuilder(this)).setTitle("添加到桥接并接管？").setMessage("小米运动健康会暂停，由桥接接管手环。开始前请结束表盘安装、OTA 升级和 NFC 操作。绑定和健康历史都会保留。").setNegativeButton("取消", (DialogInterface.OnClickListener)null).setPositiveButton("添加并接管", (dialog, which) -> this.changeOwnership(change)).show();
         }
      }
   }

   public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
      super.onRequestPermissionsResult(requestCode, permissions, results);
      if (requestCode == 2) {
         BandLiveService.refreshNotificationSettings(this);
      } else if (requestCode == 1) {
         Change requested = this.pendingPermission;
         this.pendingPermission = null;
         this.refreshNotices();
         this.refreshDevice();
         this.refreshBonded();
         if (this.hasBluetoothPermission()) {
            if (requested != null) {
               this.requestNative(requested);
            } else {
               this.refreshOwnership();
            }
         } else {
            this.ownershipStatus.setText("授权附近设备后即可添加和连接，已有登记保持原样。");
         }

      }
   }

   private void ensureCallPermissions() {
      if (!this.callPermissionsAsked && this.isUnlocked() && (new BandStateRepository(this)).isRegistered()) {
         boolean phone = this.checkSelfPermission("android.permission.READ_PHONE_STATE") == 0;
         boolean hangup = this.checkSelfPermission("android.permission.ANSWER_PHONE_CALLS") == 0;
         if (!phone || !hangup) {
            this.callPermissionsAsked = true;
            if (!phone && !hangup) {
               this.requestPermissions(new String[]{"android.permission.READ_PHONE_STATE", "android.permission.ANSWER_PHONE_CALLS"}, 2);
            } else if (!phone) {
               this.requestPermissions(new String[]{"android.permission.READ_PHONE_STATE"}, 2);
            } else {
               this.requestPermissions(new String[]{"android.permission.ANSWER_PHONE_CALLS"}, 2);
            }

         }
      }
   }

   private boolean isUnlocked() {
      return ((UserManager)this.getSystemService(UserManager.class)).isUserUnlocked() && !((KeyguardManager)this.getSystemService(KeyguardManager.class)).isDeviceLocked();
   }

   private boolean hasBluetoothPermission() {
      return this.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == 0;
   }

   private boolean needsOfficialRestore() {
      LocalPrefs ownership = LocalPrefs.open(this, "ownership");
      return ownership.getBoolean("ownsDisable", false) || ownership.getBoolean("transitionPending", false);
   }

   private void updateControls() {
      boolean registered = (new BandStateRepository(this)).isRegistered();
      SetupProgress progress = this.readProgress();
      if (this.setupCard != null) {
         setShown(this.setupCard, progress.showChecklist());
         if (progress.showChecklist()) {
            this.bindChecklist(progress);
         }
      }

      if (this.homePrimary != null) {
         this.homePrimary.setText(progress.primaryLabel());
         this.homePrimary.setEnabled(!this.changingOwnership && !this.probingRoot);
      }

      if (this.openKsu != null) {
         this.openKsu.setVisibility(this.rootGranted ? 8 : 0);
         this.openKsu.setEnabled(!this.probingRoot);
      }

      boolean officialPaused = this.needsOfficialRestore();
      boolean manage = this.rootGranted && (registered || officialPaused);
      this.manageHeading.setVisibility(manage ? 0 : 8);
      setShown(this.manageCard, manage);
      this.remove.setVisibility(registered ? 0 : 8);
      this.remove.setEnabled(!this.changingOwnership);
      this.restore.setVisibility(officialPaused ? 0 : 8);
      this.restore.setEnabled(!this.changingOwnership);
      this.advancedRestore.setEnabled(!this.changingOwnership && this.rootGranted);
   }

   private SetupProgress readProgress() {
      boolean registered = (new BandStateRepository(this)).isRegistered();
      boolean hasBinding = false;
      String model = "";

      try {
         JSONObject binding = (new BindingStore(this)).read();
         hasBinding = binding != null;
         if (binding != null) {
            model = binding.optString("model", "");
         }
      } catch (Exception var10) {
      }

      boolean hasProfile = hasBinding && TransportObservation.supportsLive(TransportObservation.liveProfile(this, model), model);
      boolean nativeOwned = false;

      try {
         nativeOwned = (new OwnershipController(this)).nativeReady();
      } catch (RuntimeException var9) {
      }

      boolean accountConfirmed = false;

      try {
         accountConfirmed = (new HealthRecordStore(this)).confirmedAccountHash() != null;
      } catch (Exception var8) {
      }

      return new SetupProgress(this.rootGranted, hasBinding, hasProfile, registered, nativeOwned, accountConfirmed);
   }

   private void bindChecklist(SetupProgress progress) {
      this.setupTitle.setText("首次设置 · 第 " + progress.visibleIndex() + "/3 步");
      int current = progress.visibleIndex();
      this.screen.setStep(this.stepRoot, 1, "授权 Root", stepState(1, current));
      this.screen.setStep(this.stepImport, 2, "导入绑定", stepState(2, current));
      this.screen.setStep(this.stepAdd, 3, "添加到桥接", stepState(3, current));
      String hint = progress.primaryHint();
      this.primaryHint.setText(hint);
      this.primaryHint.setVisibility(hint.isEmpty() ? 8 : 0);
   }

   private static BridgeScreen.StepState stepState(int index, int current) {
      if (index < current) {
         return BridgeScreen.StepState.DONE;
      } else {
         return index == current ? BridgeScreen.StepState.CURRENT : BridgeScreen.StepState.LATER;
      }
   }

   private void runHomePrimary() {
      if (!this.changingOwnership && !this.probingRoot) {
         switch (this.readProgress().current()) {
            case ROOT -> this.requestRoot();
            case IMPORT -> this.startBindingImport();
            case PROFILE -> this.startProfileCapture();
            case ADD -> this.requestNative(MainActivity.Change.ADD);
            case DONE -> this.requestNative(MainActivity.Change.RECONNECT);
         }

      }
   }

   private void refreshDevice() {
      if (!this.isDestroyed() && !this.worker.isShutdown()) {
         this.updateControls();
         this.refreshNotices();
         if (!this.isUnlocked()) {
            this.deviceName.setText("小米手环");
            this.screen.setChip(this.statusChip, "解锁后显示", BridgeScreen.Tone.NEUTRAL);
            setOptionalCaption(this.connectionHint, "解锁手机后显示手环信息。");
            this.metricsLine.setVisibility(8);
            this.healthLine.setVisibility(8);
         } else {
            LocalPrefs state = LocalPrefs.open(this, "band-state");
            if ((new BandStateRepository(this)).isRegistered()) {
               this.deviceName.setText(safeIdentity(BandCatalog.displayName(state.getString("modelId", ""), state.getString("name", "")), "已登记手环"));
               boolean connected = state.getBoolean("connected", false);
               if (connected) {
                  this.screen.setChip(this.statusChip, "已连接", BridgeScreen.Tone.POSITIVE);
                  setOptionalCaption(this.connectionHint, "");
               } else {
                  this.screen.setChip(this.statusChip, "等待连接", BridgeScreen.Tone.NEUTRAL);
                  setOptionalCaption(this.connectionHint, Copy.offlineReason(this.liveStatus()));
               }

               setOptionalCaption(this.metricsLine, metricsText(state));
               setOptionalCaption(this.healthLine, this.healthExtras());
            } else {
               this.deviceName.setText("小米手环");
               this.screen.setChip(this.statusChip, "待添加", BridgeScreen.Tone.NEUTRAL);
               setOptionalCaption(this.connectionHint, "");
               this.healthLine.setVisibility(8);

               try {
                  JSONObject binding = (new BindingStore(this)).read();
                  setOptionalCaption(this.metricsLine, binding == null ? "导入绑定后即可添加到桥接。" : "绑定已导入，点「添加到桥接」即可连接。");
               } catch (Exception var3) {
                  setOptionalCaption(this.metricsLine, "解锁手机后显示绑定信息，已有数据都在。");
               }

            }
         }
      }
   }

   private static String metricsText(LocalPrefs state) {
      int battery = state.getInt("battery", -1);
      long lastSync = state.getLong("lastSyncAtMs", 0L);
      String batteryText = battery >= 0 && battery <= 100 ? "电量 " + battery + "%" : "";
      String syncText = lastSync > 0L ? "同步于 " + DateFormat.getDateTimeInstance(2, 3).format(new Date(lastSync)) : "";
      if (batteryText.isEmpty()) {
         return syncText;
      } else {
         return syncText.isEmpty() ? batteryText : batteryText + " · " + syncText;
      }
   }

   private String liveStatus() {
      File file = new File(this.getNoBackupFilesDir(), "live-status.txt");
      if (file.isFile() && file.length() > 0L && file.length() < 256L) {
         try {
            FileInputStream in = new FileInputStream(file);

            String var5;
            label56: {
               try {
                  byte[] bytes = new byte[(int)file.length()];
                  int n = in.read(bytes);
                  if (n > 0) {
                     var5 = (new String(bytes, 0, n, StandardCharsets.UTF_8)).trim();
                     break label56;
                  }
               } catch (Throwable var7) {
                  try {
                     in.close();
                  } catch (Throwable var6) {
                     var7.addSuppressed(var6);
                  }

                  throw var7;
               }

               in.close();
               return "";
            }

            in.close();
            return var5;
         } catch (Exception var8) {
         }
      }

      return "";
   }

   private String healthExtras() {
      SharedPreferences live = this.getSharedPreferences("live-service", 0);
      String host = Copy.healthHost(live.getString("healthHostStatus", ""));
      String collection = Copy.healthCollection(live.getString("healthCollectionStatus", ""));
      if (collection.isEmpty()) {
         return host;
      } else {
         return host.isEmpty() ? collection : host + "\n" + collection;
      }
   }

   private static void setOptionalCaption(TextView view, String value) {
      boolean show = value != null && !value.isBlank();
      view.setVisibility(show ? 0 : 8);
      view.setText(show ? value : "");
   }

   private static String safeIdentity(String value, String fallback) {
      String clean = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
      return !clean.isEmpty() && clean.length() <= 80 ? clean : fallback;
   }

   private void action(String method, Bundle arguments) {
      if (!this.isDestroyed() && !this.worker.isShutdown()) {
         this.worker.execute(() -> {
            try {
               Bundle response = this.getContentResolver().call(CredentialProvider.URI, method, (String)null, arguments);
               this.main.post(() -> {
                  if (!this.isDestroyed()) {
                     this.show(response);
                     if ("openWindow".equals(method) || "openDiagnostics".equals(method) || "openCapture".equals(method)) {
                        this.main.removeCallbacks(this.expireRefresh);
                        this.main.postDelayed(this.expireRefresh, 120100L);
                     }

                  }
               });
            } catch (RuntimeException var4) {
               this.main.post(() -> {
                  if (!this.isDestroyed()) {
                     this.status.setText("解锁手机、核对所选设备后再点一次。");
                  }

               });
            }

         });
      }
   }

   private void refresh() {
      this.action("status", (Bundle)null);
   }

   private void show(Bundle response) {
      if (response == null) {
         this.status.setText("导入服务正在启动，稍后再点一次。");
      } else {
         String code = response.getString("status", "CREDENTIAL_STORAGE_FAILED");
         String text = Copy.importResult(code, response.getString("model", ""), response.getString("productId", ""), response.getString("firmware", ""));
         String observation = response.getString("transportObservation", "");
         if (!observation.isBlank()) {
            text = text + "\n连接参数（仅观察）：\n" + observation;
         }

         if (response.getBoolean("diagnosticOpen", false)) {
            text = text + "\n连接参数采集窗口开启中。";
         }

         if (response.getBoolean("captureOpen", false)) {
            text = text + "\n协议消息采集窗口开启中。";
         }

         String captureFile = response.getString("captureFile", "");
         if (!captureFile.isBlank()) {
            text = text + "\n采集记录：" + response.getInt("captureRecords") + "，字节：" + response.getLong("captureBytes") + "\n文件：" + captureFile;
         }

         String captureError = response.getString("captureError", "");
         if (!captureError.isBlank()) {
            text = text + "\n采集中断：" + captureError;
         }

         this.status.setText(text);
         if ("IMPORT_WINDOW_OPEN".equals(code) || "DIAGNOSTIC_WINDOW_OPEN".equals(code)) {
            this.ownershipStatus.setText(text);
            if (this.launchHostAfterOpen) {
               this.launchHostAfterOpen = false;
               if (this.hostSupported) {
                  this.launchMiHealth();
               }
            }
         }

      }
   }

   private void launchMiHealth() {
      Intent launch = this.getPackageManager().getLaunchIntentForPackage("com.mi.health");
      if (launch == null) {
         String text = "在桌面打开小米运动健康，确认它已安装并启用。";
         this.status.setText(text);
         this.ownershipStatus.setText(text);
      } else {
         this.startActivity(launch);
      }
   }

   private void refreshBonded() {
      if (this.bonded != null) {
         this.bonded.removeAllViews();
         if (!this.hasBluetoothPermission()) {
            this.screen.caption(this.bonded, "授权附近设备后，这里会列出已配对设备。");
         } else {
            BluetoothAdapter adapter = ((BluetoothManager)this.getSystemService(BluetoothManager.class)).getAdapter();
            if (adapter == null) {
               this.screen.caption(this.bonded, "这台手机需要蓝牙才能连接手环。");
            } else {
               Set<BluetoothDevice> devices;
               try {
                  devices = adapter.getBondedDevices();
               } catch (SecurityException var10) {
                  this.screen.caption(this.bonded, "授权附近设备后，这里会列出已配对设备。");
                  return;
               }

               if (devices != null && !devices.isEmpty()) {
                  this.screen.caption(this.bonded, "选择已配对设备");
                  List<BluetoothDevice> listed = sortedBonded(devices);
                  this.preferXiaomiBand(listed);

                  for(BluetoothDevice device : listed) {
                     String mac = device.getAddress() == null ? "" : device.getAddress().toUpperCase(Locale.ROOT);
                     String name = BandCatalog.displayName((String)null, device.getName());
                     boolean chosen = !mac.isBlank() && mac.equals(this.selectedAddress);
                     Button choice = this.screen.outlined(this.bonded, (chosen ? "✓ " : "") + name, () -> {
                        this.selectedAddress = mac;
                        this.refreshBonded();
                     });
                     choice.setEnabled(!chosen);
                  }

               } else {
                  this.screen.caption(this.bonded, "在系统蓝牙设置中配对手环后，它会出现在这里。");
               }
            }
         }
      }
   }

   private static List<BluetoothDevice> sortedBonded(Set<BluetoothDevice> devices) {
      ArrayList<BluetoothDevice> listed = new ArrayList(devices);
      listed.sort(Comparator.comparing((BluetoothDevice device) -> !BandCatalog.looksLikeBand(device.getName()))
              .thenComparing((BluetoothDevice device) -> BandCatalog.displayName(null, device.getName()),
                      String.CASE_INSENSITIVE_ORDER));
      return listed;
   }

   private void preferXiaomiBand(List<BluetoothDevice> devices) {
      if (this.selectedAddress.isBlank()) {
         String onlyBand = null;
         int bands = 0;

         for(BluetoothDevice device : devices) {
            if (BandCatalog.looksLikeBand(device.getName())) {
               String mac = device.getAddress();
               if (mac != null && !mac.isBlank()) {
                  ++bands;
                  onlyBand = mac.toUpperCase(Locale.ROOT);
               }
            }
         }

         if (bands == 1) {
            this.selectedAddress = onlyBand;
         } else if (devices.size() == 1) {
            String only = ((BluetoothDevice)devices.get(0)).getAddress();
            if (only != null) {
               this.selectedAddress = only.toUpperCase(Locale.ROOT);
            }

         }
      }
   }

   private void startBindingImport() {
      if (!this.changingOwnership) {
         if (!this.rootGranted) {
            this.requestRoot();
         } else if (!this.isUnlocked()) {
            this.ownershipStatus.setText("解锁手机后再导入绑定。");
         } else {
            (new MaterialAlertDialogBuilder(this)).setTitle("导入绑定？").setMessage("用 root 读取小米运动健康本地数据库中的手环密钥。全程在本机完成，密钥保存在本应用的私有目录里。").setNegativeButton("取消", (DialogInterface.OnClickListener)null).setPositiveButton("读取", (dialog, which) -> this.importFromDatabase(this.selectedAddress)).show();
         }
      }
   }

   private void importFromDatabase(String address) {
      if (!this.worker.isShutdown()) {
         this.databaseImportNote = "";
         this.ownershipStatus.setText("正在读取本地数据库…");
         this.worker.execute(() -> {
            Bundle selected = new Bundle();
            if (address != null && !address.isBlank()) {
               selected.putString("address", address);
            }

            Bundle response;
            try {
               response = this.getContentResolver().call(CredentialProvider.URI, "importFromDatabase", (String)null, selected);
            } catch (RuntimeException var5) {
               response = null;
            }

            final Bundle answer = response;
            this.main.post(() -> {
               if (!this.isDestroyed()) {
                  if (answer != null && "MULTIPLE_BANDS".equals(answer.getString("status"))) {
                     this.chooseDatabaseBand(answer.getStringArray("choices"));
                  } else {
                     this.show(answer);
                     this.databaseImportNote = this.status.getText().toString();
                     this.ownershipStatus.setText(this.databaseImportNote);
                     this.updateControls();
                     this.refreshDevice();
                  }
               }
            });
         });
      }
   }

   private void chooseDatabaseBand(String[] choices) {
      if (choices != null && choices.length != 0) {
         String[] labels = new String[choices.length];
         String[] addresses = new String[choices.length];

         for(int index = 0; index < choices.length; ++index) {
            String[] parts = choices[index].split("\t", 3);
            addresses[index] = parts.length > 0 ? parts[0] : "";
            String model = parts.length > 1 ? parts[1] : "";
            String name = parts.length > 2 ? parts[2] : "";
            labels[index] = BandCatalog.displayName(model, name);
         }

         (new MaterialAlertDialogBuilder(this)).setTitle("选择要导入的手环").setItems(labels, (dialog, which) -> this.importFromDatabase(addresses[which])).show();
      } else {
         this.ownershipStatus.setText(Copy.importResult("NO_BAND_KEY", "", "", ""));
      }
   }

   private void startProfileCapture() {
      if (!this.changingOwnership) {
         if (!this.rootGranted) {
            this.requestRoot();
         } else if (!this.isUnlocked()) {
            this.ownershipStatus.setText("解锁手机后再采集连接参数。");
         } else if (!this.hostSupported) {
            this.ownershipStatus.setText("安装小米运动健康并配对手环后，即可采集连接参数。");
         } else if (this.needsOfficialRestore()) {
            (new MaterialAlertDialogBuilder(this)).setTitle("先恢复官方管理").setMessage("采集连接参数时要用到小米运动健康。先恢复官方管理，随后会打开采集窗口。").setNegativeButton("取消", (DialogInterface.OnClickListener)null).setPositiveButton("恢复", (dialog, which) -> {
               this.profileAfterRestore = true;
               this.changeOwnership(MainActivity.Change.RESTORE);
            }).show();
         } else {
            (new MaterialAlertDialogBuilder(this)).setTitle("采集连接参数？").setMessage("将开启 120 秒采集窗口，随后打开小米运动健康。点开这只手环，让它重新连上。").setNegativeButton("取消", (DialogInterface.OnClickListener)null).setPositiveButton("开始采集", (dialog, which) -> this.startProfileWindow()).show();
         }
      }
   }

   private void startProfileWindow() {
      this.launchHostAfterOpen = true;
      this.action("openDiagnostics", (Bundle)null);
   }

   static enum Change {
      ADD,
      RECONNECT,
      REMOVE,
      RESTORE;

      // $FF: synthetic method
      private static Change[] $values() {
         return new Change[]{ADD, RECONNECT, REMOVE, RESTORE};
      }
   }
}
