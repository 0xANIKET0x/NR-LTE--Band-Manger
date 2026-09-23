import java.lang.reflect.Method;

public class FindTelephony {
    public static void main(String[] args) {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            Object phoneBinder = getService.invoke(null, "phone");
            
            Class<?> stubClass = Class.forName("com.android.internal.telephony.ITelephony$Stub");
            Method asInterface = stubClass.getMethod("asInterface", android.os.IBinder.class);
            Object telephony = asInterface.invoke(null, phoneBinder);
            
            System.out.println("ITelephony methods:");
            for (Method m : telephony.getClass().getMethods()) {
                if (m.getName().toLowerCase().contains("selection") || 
                    m.getName().toLowerCase().contains("band") || 
                    m.getName().toLowerCase().contains("network")) {
                    System.out.print("  " + m.getName() + "(");
                    Class<?>[] params = m.getParameterTypes();
                    for (int i = 0; i < params.length; i++) {
                        System.out.print(params[i].getName());
                        if (i < params.length - 1) System.out.print(", ");
                    }
                    System.out.println(")");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
