// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.notify;

import android.content.Context;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** Routes band media keys through the local media-session controller. */
public final class NativeMusic {
    public static final String ACTION = "io.github.kylemourn.mibandoplusbridge.MUSIC";

    private NativeMusic() {}

    public static void onBandCommand(Context context, XiaomiProto.Command command) {
        if (command == null || command.getType() != 18) return;
        int subtype = command.getSubtype();
        if (subtype == 0) {
            PhoneMusic.handle(0, 0, true);
        } else if (subtype == 2 && command.hasMusic() && command.getMusic().hasMediaKey()) {
            var key = command.getMusic().getMediaKey();
            PhoneMusic.handle(key.getKey(), key.getVolume(), false);
        }
    }

    public static void requestRefresh(Context context) {
        PhoneMusic.handle(0, 0, true);
    }

    /** Same pause key the band sends. */
    public static void pause(Context context) {
        PhoneMusic.handle(1, 0, false);
    }
}
