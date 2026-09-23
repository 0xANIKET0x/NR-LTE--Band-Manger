import android.telephony.TelephonyManager;

public class TestBitmasks {
    public static void main(String[] args) {
        System.out.println("NR: " + TelephonyManager.NETWORK_TYPE_BITMASK_NR);
        System.out.println("LTE: " + TelephonyManager.NETWORK_TYPE_BITMASK_LTE);
        System.out.println("LTE_CA: " + TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA);
    }
}
