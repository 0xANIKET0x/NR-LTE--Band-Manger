import android.content.Context;
import android.os.Looper;
import android.telephony.RadioAccessSpecifier;
import android.telephony.TelephonyManager;
import android.telephony.AccessNetworkConstants;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class BandLocker {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: BandLocker <type|mode> <bands|modeMask> [subId]");
            System.exit(1);
        }

        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }

        String action = args[0].toLowerCase();
        String param = args[1];
        int subId = -1; // -1 means all subIds
        if (args.length > 2) {
            try { subId = Integer.parseInt(args[2]); } catch (Exception e) {}
        }

        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            Object phoneBinder = getService.invoke(null, "phone");
            Class<?> stubClass = Class.forName("com.android.internal.telephony.ITelephony$Stub");
            Method asInterface = stubClass.getMethod("asInterface", android.os.IBinder.class);
            Object telephony = asInterface.invoke(null, phoneBinder);

            if (action.equals("mode")) {
                long bitmask = Long.parseLong(param);
                boolean success = false;
                
                int startSub = (subId >= 0) ? subId : 0;
                int endSub = (subId >= 0) ? subId : 5;
                
                for (Method m : telephony.getClass().getMethods()) {
                    String name = m.getName();
                    if (name.equals("setAllowedNetworkTypesForReason") || name.equals("setPreferredNetworkTypeBitmask") || name.equals("setAllowedNetworkTypes")) {
                        Class<?>[] pTypes = m.getParameterTypes();
                        for (int s = startSub; s <= endSub; s++) {
                            Object[] invokeArgs = new Object[pTypes.length];
                            for (int i = 0; i < pTypes.length; i++) {
                                if (pTypes[i].equals(int.class)) {
                                    if (i == 0) invokeArgs[i] = s; // subId
                                    else invokeArgs[i] = 0; // reason = 0
                                } else if (pTypes[i].equals(long.class)) {
                                    invokeArgs[i] = bitmask;
                                } else if (pTypes[i].equals(String.class)) {
                                    invokeArgs[i] = "com.android.shell";
                                } else {
                                    invokeArgs[i] = null;
                                }
                            }
                            try {
                                m.invoke(telephony, invokeArgs);
                                success = true;
                            } catch (Exception e) {}
                        }
                    }
                }
                if (success) System.out.println("[✓] Network mode set to " + bitmask + " (subId: " + (subId < 0 ? "ALL" : subId) + ")");
                else System.out.println("[X] Failed to set network mode natively");
                return;
            }

            // Band Locking
            List<RadioAccessSpecifier> specifiers = new ArrayList<>();
            if (!action.equals("clear")) {
                int[] bands = parseBands(param);
                if (action.equals("nr") || action.equals("both")) {
                    specifiers.add(new RadioAccessSpecifier(AccessNetworkConstants.AccessNetworkType.NGRAN, bands, new int[]{}));
                }
                if (action.equals("lte") || action.equals("both")) {
                    specifiers.add(new RadioAccessSpecifier(AccessNetworkConstants.AccessNetworkType.EUTRAN, bands, new int[]{}));
                }
            }

            Method targetMethod = null;
            for (Method m : telephony.getClass().getMethods()) {
                if (m.getName().equals("setSystemSelectionChannels")) {
                    targetMethod = m;
                    break;
                }
            }

            if (targetMethod == null) {
                System.out.println("FAIL: setSystemSelectionChannels not found!");
                System.exit(1);
            }

            Class<?>[] params = targetMethod.getParameterTypes();
            boolean success = false;

            int startSub = (subId >= 0) ? subId : 0;
            int endSub = (subId >= 0) ? subId : 5;

            for (int s = startSub; s <= endSub; s++) {
                Object[] invokeArgs = new Object[params.length];
                for (int i = 0; i < params.length; i++) {
                    if (params[i].isAssignableFrom(List.class)) invokeArgs[i] = specifiers;
                    else if (params[i].equals(int.class)) invokeArgs[i] = s;
                    else if (params[i].equals(String.class)) invokeArgs[i] = "com.android.shell";
                    else invokeArgs[i] = null;
                }
                
                try {
                    targetMethod.invoke(telephony, invokeArgs);
                    if (subId >= 0) {
                        System.out.println("[✓] Applied band lock to SIM subId: " + s);
                    }
                    success = true;
                } catch (Exception e) {}
            }

            if (success) {
                if (subId < 0) System.out.println("[✓] Applied band lock to all SIM slots");
            } else {
                System.out.println("[X] Failed to apply band lock.");
            }

        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
        }
    }

    private static int[] parseBands(String bandsStr) {
        String[] parts = bandsStr.split(",");
        List<Integer> bands = new ArrayList<>();
        for (String p : parts) {
            p = p.trim().replaceAll("[^0-9]", "");
            if (!p.isEmpty()) bands.add(Integer.parseInt(p));
        }
        int[] result = new int[bands.size()];
        for (int i = 0; i < bands.size(); i++) result[i] = bands.get(i);
        return result;
    }
}
