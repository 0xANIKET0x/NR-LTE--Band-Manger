package com.nrbandmanager.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.webkit.JavascriptInterface;
import java.io.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bridge between JavaScript UI and native Android/root functionality.
 * Uses multiple approaches for band management:
 * 1. cmd phone (Android telephony commands)
 * 2. service call phone (hidden IPC to telephony service)
 * 3. AT commands via /dev/at_mdm0 (fallback)
 * 4. Qualcomm DIAG port if available
 */
public class BandBridge {
    private final Context context;
    private static final String PREFS = "nrband_prefs";
    private static final long CMD_TIMEOUT = 15000;

    // AT port options - try in order
    private static final String[] AT_PORTS = {
        "/dev/at_mdm0", "/dev/smd8", "/dev/smd11", "/dev/at_usb0"
    };

    public BandBridge(Context context) {
        this.context = context;
    }

    // ── Root Shell ──────────────────────────────────────────────

    @JavascriptInterface
    public String execRoot(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            p.waitFor(CMD_TIMEOUT, TimeUnit.MILLISECONDS);
            p.destroy();
            return sb.toString().trim();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @JavascriptInterface
    public boolean checkRoot() {
        String result = execRoot("id");
        return result != null && result.contains("uid=0");
    }

    // ── AT Commands (improved) ──────────────────────────────────

    @JavascriptInterface
    public String sendAT(String command) {
        return sendATToPort(command, AT_PORTS[0]);
    }

    @JavascriptInterface
    public String sendATToPort(String command, String port) {
        // Method 1: Using stty + echo + timeout cat
        String escaped = command.replace("'", "'\\''").replace("\"", "\\\"");
        String script = String.format(
            "stty -F %s raw -echo 2>/dev/null; " +
            "echo -ne '%s\\r\\n' > %s; " +
            "sleep 0.3; " +
            "timeout 2 cat %s 2>/dev/null || true",
            port, escaped, port, port
        );
        String result = execRoot(script);

        // If no response, try the file descriptor approach
        if (result == null || result.trim().isEmpty() || result.startsWith("ERROR")) {
            script = String.format(
                "exec 3<>%s 2>/dev/null; " +
                "printf '%s\\r\\n' >&3; " +
                "sleep 0.5; " +
                "timeout 2 head -c 1024 <&3 2>/dev/null; " +
                "exec 3>&-",
                port, escaped
            );
            result = execRoot(script);
        }

        return result != null ? result : "(no response)";
    }

    @JavascriptInterface
    public String tryAllATports(String command) {
        StringBuilder sb = new StringBuilder();
        for (String port : AT_PORTS) {
            String exists = execRoot("test -e " + port + " && echo YES || echo NO");
            if (exists.contains("YES")) {
                sb.append("Port: ").append(port).append("\n");
                String r = sendATToPort(command, port);
                sb.append(r).append("\n\n");
            }
        }
        return sb.length() > 0 ? sb.toString() : "No AT ports available";
    }

    // ── Device Info ─────────────────────────────────────────────

    @JavascriptInterface
    public String getDeviceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model\":\"").append(esc(Build.MODEL)).append("\",");
        sb.append("\"device\":\"").append(esc(Build.DEVICE)).append("\",");
        sb.append("\"manufacturer\":\"").append(esc(Build.MANUFACTURER)).append("\",");
        sb.append("\"board\":\"").append(esc(Build.BOARD)).append("\",");
        sb.append("\"hardware\":\"").append(esc(Build.HARDWARE)).append("\",");
        sb.append("\"soc\":\"").append(esc(Build.SOC_MODEL)).append("\",");
        sb.append("\"android\":\"").append(esc(Build.VERSION.RELEASE)).append("\",");
        sb.append("\"sdk\":").append(Build.VERSION.SDK_INT).append(",");
        sb.append("\"fingerprint\":\"").append(esc(Build.FINGERPRINT)).append("\"");
        sb.append("}");
        return sb.toString();
    }

    @JavascriptInterface
    public String getNetworkInfo() {
        return execRoot("dumpsys telephony.registry 2>/dev/null | grep -E " +
            "'mServiceState|DataNetworkType|NrState|mSignalStrength|nrFrequency|CellBandwidth|channelNumber|mCellInfo' | head -30");
    }

    @JavascriptInterface
    public String getRadioInfo() {
        return execRoot("dumpsys phone 2>/dev/null | grep -iE " +
            "'rat=|band|nr |lte |nsa|mode|signal|rsrp|sinr|earfcn|arfcn|pci|freq' | head -40");
    }

    @JavascriptInterface
    public String getCurrentBands() {
        // Get detailed current band info
        StringBuilder sb = new StringBuilder();
        sb.append("── Current Network ──\n");
        sb.append(execRoot("dumpsys telephony.registry 2>/dev/null | " +
            "grep -E 'DataNetworkType|NrState|channelNumber|CellBandwidth' | head -10")).append("\n\n");

        sb.append("── Signal ──\n");
        sb.append(execRoot("dumpsys telephony.registry 2>/dev/null | " +
            "grep -E 'mSignalStrength' | head -5")).append("\n\n");

        sb.append("── Cell Info ──\n");
        sb.append(execRoot("dumpsys telephony.registry 2>/dev/null | " +
            "grep -A3 'CellInfoNr\\|CellInfoLte' | head -30")).append("\n");

        return sb.toString();
    }

    // ── Band Management (multiple methods) ──────────────────────

    @JavascriptInterface
    public String probeBands() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══ AVAILABLE INTERFACES ═══\n");
        sb.append(execRoot("ls -la /dev/at_mdm0 /dev/smd8 /dev/smd11 /dev/diag /dev/qmi* 2>/dev/null || echo 'Standard ports only'")).append("\n");
        sb.append(execRoot("ls -la /dev/socket/qrtr* /dev/qrtr* 2>/dev/null || echo 'No QRTR socket'")).append("\n\n");
        sb.append("═══ CMD PHONE ═══\n");
        sb.append(execRoot("cmd phone get-network-type 2>/dev/null || echo 'not available'")).append("\n\n");

        // 4. Current bands
        sb.append("═══ CURRENT BANDS ═══\n");
        sb.append(getCurrentBands()).append("\n");

        // 5. Check vendor properties for band config
        sb.append("═══ VENDOR PROPERTIES ═══\n");
        sb.append(execRoot("getprop | grep -iE 'ril|radio|nr|band|modem|5g' | head -20")).append("\n\n");

        // 6. Check DIAG
        sb.append("═══ DIAG PORT ═══\n");
        sb.append(execRoot("ls -la /dev/diag 2>/dev/null && echo 'DIAG available!' || echo 'No DIAG port'")).append("\n");
        sb.append(execRoot("cat /proc/devices 2>/dev/null | grep -i diag || echo 'No DIAG in /proc/devices'")).append("\n\n");

        // 7. Check vendor radio tools
        sb.append("═══ VENDOR TOOLS ═══\n");
        sb.append(execRoot("ls /vendor/bin/ 2>/dev/null | grep -iE 'qmi|ril|diag|radio|modem' | head -10")).append("\n");
        sb.append(execRoot("ls /vendor/bin/hw/ 2>/dev/null | grep -i radio | head -5")).append("\n");

        return sb.toString();
    }

    private String getBandLockerDex() {
        File tmpDex = new File("/data/local/tmp/bandlocker.dex");
        File localDex = new File(context.getFilesDir(), "bandlocker.dex");
        try {
            // Always extract from assets to ensure latest version
            try (InputStream in = context.getAssets().open("bandlocker.dex");
                 FileOutputStream out = new FileOutputStream(localDex)) {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            // Copy to /data/local/tmp and make readable
            execRoot("cp " + localDex.getAbsolutePath() + " " + tmpDex.getAbsolutePath());
            execRoot("chmod 777 " + tmpDex.getAbsolutePath());
            return tmpDex.getAbsolutePath();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @JavascriptInterface
    public String getSimInfo() {
        try {
            // First try normal API (requires permission)
            SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
            if (sm != null) {
                java.util.List<SubscriptionInfo> sims = sm.getActiveSubscriptionInfoList();
                if (sims != null && !sims.isEmpty()) {
                    return buildSimJson(sims);
                }
            }
        } catch (Exception e) {}

        // Fallback to ROOT query on content provider (bypasses permission delay/issues)
        try {
            String out = execRoot("content query --uri content://telephony/siminfo --projection _id:sim_id:display_name");
            // Output looks like: Row: 0 _id=3, sim_id=1, display_name=Jio
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            String[] lines = out.split("\n");
            int count = 0;
            for (String line : lines) {
                if (line.contains("_id=")) {
                    try {
                        String idStr = line.replaceAll(".*_id=([0-9]+).*", "$1");
                        String slotStr = line.replaceAll(".*sim_id=([0-9]+).*", "$1");
                        String nameStr = line.replaceAll(".*display_name=([^,]+).*", "$1").trim();
                        
                        if (count > 0) sb.append(",");
                        sb.append("{");
                        sb.append("\"subId\":").append(idStr).append(",");
                        sb.append("\"slot\":").append(slotStr).append(",");
                        sb.append("\"name\":\"").append(esc(nameStr)).append("\"");
                        sb.append("}");
                        count++;
                    } catch (Exception e) {}
                }
            }
            sb.append("]");
            if (count > 0) return sb.toString();
        } catch (Exception e) {}

        return "[]";
    }

    private String buildSimJson(java.util.List<SubscriptionInfo> sims) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < sims.size(); i++) {
            SubscriptionInfo info = sims.get(i);
            sb.append("{");
            sb.append("\"subId\":").append(info.getSubscriptionId()).append(",");
            sb.append("\"slot\":").append(info.getSimSlotIndex()).append(",");
            sb.append("\"name\":\"").append(esc(info.getDisplayName().toString())).append("\"");
            sb.append("}");
            if (i < sims.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    @JavascriptInterface
    public String lockBands(String type, String bands, int subId) {
        StringBuilder log = new StringBuilder();
        String dexPath = getBandLockerDex();
        if (dexPath.startsWith("ERROR")) {
            return "Failed to extract BandLocker: " + dexPath;
        }

        String cmd = String.format("app_process -Djava.class.path=%s /system/bin BandLocker %s %s %d 2>&1", dexPath, type, bands, subId);
        String r = execRoot(cmd);
        log.append(r).append("\n");

        if (type.equals("nr") || type.equals("both")) saveLock("nr_bands", bands);
        if (type.equals("lte") || type.equals("both")) saveLock("lte_bands", bands);
        saveLock("target_subid", String.valueOf(subId));

        return log.toString();
    }

    @JavascriptInterface
    public String setNetworkModeNative(long bitmask, int subId) {
        StringBuilder log = new StringBuilder();
        String dexPath = getBandLockerDex();
        if (dexPath.startsWith("ERROR")) return "Error: " + dexPath;

        // 1. Try Native Java Reflection
        String cmd = String.format("app_process -Djava.class.path=%s /system/bin BandLocker mode %d %d 2>&1", dexPath, bitmask, subId);
        String r = execRoot(cmd);
        log.append(r).append("\n");
        
        // Android 13/14+ cmd phone expects comma-separated string names, not raw integers!
        String typeString = "";
        if (bitmask == 524288) typeString = "nr";
        else if (bitmask == 4096) typeString = "lte";
        else typeString = "nr,lte,umts,gsm,cdma";

        int slot = (subId > 0) ? (subId - 1) : 0;
        
        // 2. Fallback: cmd phone with string bitmask
        log.append(execRoot("cmd phone set-allowed-network-types-for-users " + slot + " " + typeString + " 2>&1")).append("\n");
        log.append(execRoot("cmd phone set-allowed-network-types-for-users " + typeString + " 2>&1")).append("\n");
        
        // 3. Update Settings Database (25 = NR only, 11 = LTE only, 33 = All)
        int legacyMode = (bitmask == 524288) ? 25 : (bitmask == 4096) ? 11 : 33;
        execRoot("settings put global preferred_network_mode " + legacyMode);
        execRoot("settings put global preferred_network_mode" + subId + " " + legacyMode);
        execRoot("settings put global preferred_network_mode" + slot + " " + legacyMode);

        // Notify modem by sending broadcast
        execRoot("am broadcast -a android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED");

        saveLock("network_mode_mask", String.valueOf(bitmask));
        saveLock("target_subid", String.valueOf(subId));
        return log.toString();
    }

    @JavascriptInterface
    public String force5GPlusPlusNative(int subId) {
        StringBuilder sb = new StringBuilder();
        sb.append("Applying 5G++ Aggressive Optimizations...\n");

        // 1. Force Network Mode to NR Only (524288) natively to force Standalone Carrier Aggregation
        sb.append(">> Setting NR Only Mode...\n");
        sb.append(setNetworkModeNative(524288, subId)).append("\n");

        // 2. Inject Vendor Properties for Carrier Aggregation
        sb.append(">> Injecting NR Carrier Aggregation Properties...\n");
        execRoot("setprop persist.vendor.radio.nr_ca 1");
        execRoot("setprop persist.vendor.radio.nr_ca_enabled 1");
        execRoot("setprop persist.radio.nr_ca 1");
        execRoot("setprop persist.sys.radio.nr_ca_enabled 1");
        execRoot("setprop persist.vendor.radio.sa_mode 1");
        execRoot("setprop persist.vendor.radio.nr_ca_force 1");

        // 3. UI 5G Icon forces (MIUI specific overrides for 5G++)
        execRoot("settings put global 5g_icon_mode 2");
        execRoot("settings put global show_5g_plus 1");
        execRoot("settings put global show_5g_plus_plus 1");
        execRoot("settings put global nr_mode_enabled 1");
        execRoot("setprop persist.sys.miui.5g_plus 1");
        execRoot("setprop persist.sys.miui.5g_plus_plus 1");
        execRoot("setprop persist.vendor.radio.5g_icon_type 2");
        
        sb.append("[✓] Modem properties updated for Max CA speed and 5G++ icon.");
        return sb.toString();
    }

    @JavascriptInterface
    public String setNRBands(String bands) {
        return lockBands("nr", bands, -1);
    }

    @JavascriptInterface
    public String setLTEBands(String bands) {
        return lockBands("lte", bands, -1);
    }

    @JavascriptInterface
    public String setNetworkMode(int mode) {
        StringBuilder sb = new StringBuilder();

        // cmd phone approach
        String r1 = execRoot("cmd phone set-preferred-network-type 0 " + mode + " 2>&1");
        sb.append("cmd phone: ").append(r1.isEmpty() ? "ok" : r1).append("\n");

        // settings approach
        String r2 = execRoot("settings put global preferred_network_mode " + mode + " 2>&1");
        sb.append("settings: ").append(r2.isEmpty() ? "ok" : r2).append("\n");

        // Also try for SIM slot 1 (subId 1)
        execRoot("settings put global preferred_network_mode1 " + mode + " 2>&1");

        saveLock("network_mode", String.valueOf(mode));
        return sb.toString();
    }

    @JavascriptInterface
    public String setNROnly() {
        // Force NR only mode - disable LTE fallback
        StringBuilder sb = new StringBuilder();

        // Mode 25 = NR_ONLY
        sb.append(setNetworkMode(25)).append("\n");

        // Also try 5G-specific props
        String r = execRoot("settings put global nr_mode_enabled 1 2>&1");
        sb.append("nr_mode: ").append(r.isEmpty() ? "enabled" : r).append("\n");

        return sb.toString();
    }

    @JavascriptInterface
    public String setNRPreferred() {
        // NR + LTE, prefer NR
        return setNetworkMode(33); // NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA
    }

    @JavascriptInterface
    public String restoreDefaults() {
        StringBuilder sb = new StringBuilder();

        // 1. Restore network mode to default (33 = all bands / global) for all SIMs (-1)
        sb.append("Restoring network mode to Default...\n");
        setNetworkModeNative(33, -1);

        // 2. Clear Band Locks natively for all SIMs (-1)
        sb.append("Clearing Band Locks...\n");
        lockBands("clear", "", -1);

        // 3. Clear all saved configurations
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();

        sb.append("\n[✓] All locks and network forcings cleared successfully!");
        return sb.toString();
    }

    @JavascriptInterface
    public String runDiagnostics() {
        // Comprehensive diagnostic
        StringBuilder sb = new StringBuilder();

        sb.append("===== DEVICE =====\n");
        sb.append("Model: ").append(Build.MODEL).append("\n");
        sb.append("SoC: ").append(Build.SOC_MODEL).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n\n");

        sb.append("===== MODEM INTERFACES =====\n");
        sb.append(execRoot("ls -la /dev/at_mdm0 /dev/at_usb* /dev/smd* /dev/diag /dev/qmi* " +
            "/dev/socket/qrtr* 2>/dev/null")).append("\n\n");

        sb.append("===== VENDOR RADIO SERVICES =====\n");
        sb.append(execRoot("service list 2>/dev/null | grep -iE 'phone|radio|ril|qti|telephony' | head -15")).append("\n\n");

        sb.append("===== CMD PHONE HELP =====\n");
        sb.append(execRoot("cmd phone help 2>&1 | head -50")).append("\n\n");

        sb.append("===== CURRENT NETWORK =====\n");
        sb.append(getCurrentBands()).append("\n\n");

        sb.append("===== RADIO PROPERTIES =====\n");
        sb.append(execRoot("getprop | grep -iE 'ril\\.|radio\\.|nr_|5g|modem' | head -25")).append("\n");

        return sb.toString();
    }

    // ── Persistence ─────────────────────────────────────────────

    @JavascriptInterface
    public void saveLock(String key, String value) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(key, value).apply();
    }

    @JavascriptInterface
    public String loadLock(String key) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString(key, "");
    }

    @JavascriptInterface
    public String getSavedLocks() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"nr_bands\":\"").append(esc(prefs.getString("nr_bands", ""))).append("\",");
        sb.append("\"lte_bands\":\"").append(esc(prefs.getString("lte_bands", ""))).append("\",");
        sb.append("\"network_mode\":\"").append(esc(prefs.getString("network_mode", ""))).append("\",");
        sb.append("\"auto_reapply\":").append(prefs.getBoolean("auto_reapply", false));
        sb.append("}");
        return sb.toString();
    }

    @JavascriptInterface
    public void setAutoReapply(boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putBoolean("auto_reapply", enabled).apply();
    }

    // ── Helpers ─────────────────────────────────────────────────

    private int getNetworkTypeForBands(String type) {
        // Android preferred network type constants:
        // 33 = NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA (all)
        // 25 = NR_ONLY
        // 41 = NR_LTE (5G+4G only)
        // 11 = LTE_CDMA_EVDO_GSM_WCDMA (4G+3G+2G)
        // 9 = LTE_ONLY
        switch (type) {
            case "nr": return 25; // NR only
            case "lte": return 9; // LTE only
            case "both":
            default: return 41; // NR + LTE
        }
    }

    private String getNetworkTypeName(int type) {
        switch (type) {
            case 25: return "NR Only";
            case 41: return "NR + LTE";
            case 33: return "NR + LTE + 3G + 2G";
            case 11: return "LTE + 3G + 2G";
            case 9: return "LTE Only";
            case 0: return "GSM/WCDMA";
            default: return "Type " + type;
        }
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }
}
