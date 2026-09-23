import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.webkit.JavascriptInterface;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;

public class TestSims {
    public static void main(String[] args) {
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", "content query --uri content://telephony/siminfo --projection _id:sim_id:display_name");
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while((line = r.readLine()) != null) {
                System.out.println(line);
            }
        } catch (Exception e) {}
    }
}
