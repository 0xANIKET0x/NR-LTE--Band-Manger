package com.nrbandmanager.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.util.concurrent.TimeUnit;

/**
 * Reapplies band locks after device boot.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!"android.intent.action.BOOT_COMPLETED".equals(action)) return;

        SharedPreferences prefs = context.getSharedPreferences("nrband_prefs", Context.MODE_PRIVATE);
        boolean autoReapply = prefs.getBoolean("auto_reapply", false);
        if (!autoReapply) return;

        // Delay 45 seconds after boot for modem to initialize
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            new Thread(() -> reapplyLock(prefs, context)).start();
        }, 45000);
    }

    private void reapplyLock(SharedPreferences prefs, Context context) {
        String nrBands = prefs.getString("nr_bands", "");
        String lteBands = prefs.getString("lte_bands", "");
        String targetSubId = prefs.getString("target_subid", "-1");
        String networkMode = prefs.getString("network_mode_mask", "");

        File dexFile = new File(context.getFilesDir(), "bandlocker.dex");
        if (!dexFile.exists()) return;
        String dexPath = dexFile.getAbsolutePath();

        try {
            if (!networkMode.isEmpty()) {
                String cmd = String.format("app_process -Djava.class.path=%s /system/bin BandLocker mode %s %s", dexPath, networkMode, targetSubId);
                sendRootCommand(cmd);
            }
            if (!nrBands.isEmpty()) {
                String cmd = String.format("app_process -Djava.class.path=%s /system/bin BandLocker nr %s %s", dexPath, nrBands, targetSubId);
                sendRootCommand(cmd);
            }
            if (!lteBands.isEmpty()) {
                String cmd = String.format("app_process -Djava.class.path=%s /system/bin BandLocker lte %s %s", dexPath, lteBands, targetSubId);
                sendRootCommand(cmd);
            }
        } catch (Exception e) {
            // Silently fail on boot
        }
    }

    private String sendRootCommand(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            p.waitFor(10, TimeUnit.SECONDS);
            p.destroy();
            return sb.toString();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
}
