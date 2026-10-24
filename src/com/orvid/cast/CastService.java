package com.orvid.cast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

import com.orvid.R;
import com.orvid.ui.PlayerActivity;

public final class CastService extends Service implements Cast.Listener {

    private static final String CHANNEL = "casting";
    private static final int ID = 1;
    private static final String ACTION_TOGGLE = "com.orvid.CAST_TOGGLE";
    private static final String ACTION_STOP = "com.orvid.CAST_STOP";

    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private String shown;

    static void start(Context context) {
        context.startForegroundService(new Intent(context, CastService.class));
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, CastService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.casting_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
        startForeground(ID, build());
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "orvid:cast");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "orvid:cast");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        Cast.get(this).addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Cast cast = Cast.get(this);
        String action = intent == null ? null : intent.getAction();
        CastSession s = cast.session();
        if (ACTION_TOGGLE.equals(action) && s != null) {
            if (s.playing()) s.pause();
            else s.play();
        } else if (ACTION_STOP.equals(action)) {
            cast.end(true);
            return START_NOT_STICKY;
        }
        if (!cast.active()) stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public void onCastUpdate() {
        Cast cast = Cast.get(this);
        if (!cast.active()) {
            stopSelf();
            return;
        }
        Notification n = build();
        String key = n.extras.getString(Notification.EXTRA_TITLE) + "|" + n.extras.getString(Notification.EXTRA_TEXT)
                + "|" + (cast.session().playing());
        if (key.equals(shown)) return;
        shown = key;
        getSystemService(NotificationManager.class).notify(ID, n);
    }

    private Notification build() {
        Cast cast = Cast.get(this);
        CastSession s = cast.session();
        CastItem m = cast.media();
        String device = s == null ? "" : s.device.name;
        Intent open = new Intent(this, PlayerActivity.class)
                .putExtra(PlayerActivity.EXTRA_URL, m == null ? null : m.page)
                .putExtra(PlayerActivity.EXTRA_TITLE, m == null ? null : m.title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_cast_white)
                .setContentTitle(m != null && !m.title.isEmpty() ? m.title : getString(R.string.casting_to, device))
                .setContentText(getString(R.string.casting_to, device))
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (m != null && m.page != null) b.setContentIntent(PendingIntent.getActivity(this, 0, open, flags));
        boolean playing = s != null && s.playing();
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, playing ? R.drawable.ic_pause_white : R.drawable.ic_play_white),
                getString(playing ? R.string.pause : R.string.play),
                PendingIntent.getService(this, 1, new Intent(this, CastService.class).setAction(ACTION_TOGGLE), flags)).build());
        b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_x_white),
                getString(R.string.stop_casting),
                PendingIntent.getService(this, 2, new Intent(this, CastService.class).setAction(ACTION_STOP), flags)).build());
        b.setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1));
        return b.build();
    }

    @Override
    public void onDestroy() {
        Cast.get(this).removeListener(this);
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
