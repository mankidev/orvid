package com.orvid.ui;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.orvid.R;
import com.orvid.cast.Cast;
import com.orvid.cast.CastDevice;
import com.orvid.cast.CastSession;

import java.util.regex.Pattern;

final class DeviceDialog implements Cast.Listener {

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private final Activity activity;
    private final Cast cast;
    private final Dialog dialog;
    private final View root;
    private final LinearLayout list;
    private boolean draggingVolume;

    private DeviceDialog(Activity activity) {
        this.activity = activity;
        cast = Cast.get(activity);
        dialog = Panic.guard(activity, new Dialog(activity));
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        root = activity.getLayoutInflater().inflate(R.layout.dialog_devices, null);
        dialog.setContentView(root);
        list = root.findViewById(R.id.dev_list);
        root.findViewById(R.id.dev_stop).setOnClickListener(v -> {
            cast.end(true);
            dialog.dismiss();
        });
        ImageButton mute = root.findViewById(R.id.dev_mute);
        mute.setOnClickListener(v -> {
            CastSession s = cast.session();
            if (s != null) s.setMuted(!s.muted());
        });
        SeekBar volume = root.findViewById(R.id.dev_volume);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                CastSession s = cast.session();
                if (fromUser && s != null) s.setVolume(progress / 100.0);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                draggingVolume = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                draggingVolume = false;
            }
        });
        EditText ip = root.findViewById(R.id.dev_ip);
        root.findViewById(R.id.dev_add).setOnClickListener(v -> {
            String host = ip.getText().toString().trim();
            if (!valid(host)) {
                ip.setError(activity.getString(R.string.invalid_ip));
                return;
            }
            ip.setText("");
            cast.addManual(host);
        });
        dialog.setOnDismissListener(d -> {
            cast.removeListener(this);
            cast.stopDiscovery();
        });
    }

    static void show(Activity activity) {
        askNotifications(activity);
        new DeviceDialog(activity).open();
    }

    static void askNotifications(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
        }
    }

    private static boolean valid(String host) {
        java.util.regex.Matcher m = IPV4.matcher(host);
        if (!m.matches()) return false;
        for (int i = 1; i <= 4; i++) if (Integer.parseInt(m.group(i)) > 255) return false;
        return true;
    }

    private void open() {
        cast.addListener(this);
        cast.startDiscovery();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.4f);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        render();
        dialog.show();
        TextView hint = root.findViewById(R.id.dev_hint);
        hint.postDelayed(() -> {
            if (dialog.isShowing() && cast.devices().isEmpty()) hint.setText(R.string.no_devices);
        }, 6000);
    }

    @Override
    public void onCastUpdate() {
        if (dialog.isShowing()) render();
    }

    private void render() {
        CastSession s = cast.session();
        boolean active = cast.active();
        TextView title = root.findViewById(R.id.dev_title);
        root.findViewById(R.id.dev_connected).setVisibility(active ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.dev_picker).setVisibility(active ? View.GONE : View.VISIBLE);
        if (active) {
            title.setText(activity.getString(cast.connected() ? R.string.connected_to : R.string.connecting_to, s.device.name));
            SeekBar volume = root.findViewById(R.id.dev_volume);
            if (!draggingVolume) volume.setProgress((int) Math.round(s.volume() * 100));
            ((ImageButton) root.findViewById(R.id.dev_mute)).setImageResource(s.muted() ? R.drawable.ic_volume_x : R.drawable.ic_volume_2);
            return;
        }
        title.setText(R.string.cast_to);
        list.removeAllViews();
        for (CastDevice d : cast.devices()) {
            View row = activity.getLayoutInflater().inflate(R.layout.row_device, list, false);
            ((TextView) row.findViewById(R.id.dev_name)).setText(d.name);
            TextView model = row.findViewById(R.id.dev_model);
            model.setText(d.model.isEmpty() ? d.host : d.model);
            row.setOnClickListener(v -> {
                cast.connect(d);
                dialog.dismiss();
            });
            list.addView(row);
        }
    }
}
