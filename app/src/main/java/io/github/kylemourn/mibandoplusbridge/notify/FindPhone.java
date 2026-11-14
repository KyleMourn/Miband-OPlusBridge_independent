// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.notify;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import io.github.kylemourn.mibandoplusbridge.BuildConfig;
import io.github.kylemourn.mibandoplusbridge.protocol.BandSystemCommand;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** Rings and vibrates this phone for the band find-device command. No host app is required. */
public final class FindPhone {
    public static final String ACTION = BuildConfig.APPLICATION_ID + ".FIND_PHONE";
    public static final String PERMISSION = BuildConfig.APPLICATION_ID + ".permission.FIND_PHONE";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Ringtone ringtone;
    private static Vibrator vibrator;

    private FindPhone() {}

    public static void onBandCommand(Context context, XiaomiProto.Command command) {
        BandSystemCommand.FindPhone action = BandSystemCommand.findPhone(command);
        if (action == null) return;
        if (action == BandSystemCommand.FindPhone.START) start(context);
        else stop(context);
    }

    public static void start(Context context) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            stop(app);
            try {
                Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                ringtone = RingtoneManager.getRingtone(app, uri);
                if (ringtone != null) {
                    ringtone.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                    ringtone.setLooping(true);
                    ringtone.play();
                }
                vibrator = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null && vibrator.hasVibrator()) {
                    long[] pattern = {0, 800, 600};
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                }
                Log.i("OplusBandBridge", "FIND_PHONE start local");
            } catch (RuntimeException failed) {
                Log.i("OplusBandBridge", "FIND_PHONE local failed " + failed.getClass().getSimpleName());
            }
        });
    }

    public static void stop(Context context) {
        MAIN.post(() -> {
            try { if (ringtone != null && ringtone.isPlaying()) ringtone.stop(); } catch (RuntimeException ignored) { }
            ringtone = null;
            try { if (vibrator != null) vibrator.cancel(); } catch (RuntimeException ignored) { }
            vibrator = null;
            Log.i("OplusBandBridge", "FIND_PHONE stop local");
        });
    }
}
