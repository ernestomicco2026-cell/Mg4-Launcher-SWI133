package com.mg4.launcher.svc;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.lang.reflect.Method;

/**
 * Riconosce il firmware dell'auto e dice se questa versione del launcher è quella giusta.
 * Esistono quattro launcher, uno per ogni software dell'infotainment MG4, non intercambiabili:
 * <ul>
 *   <li>SWI68 (SAIC 1.0+) e SWI133 (MG4 Luxury MY24): app originali com.saicmotor.hmi.*;</li>
 *   <li>SWI69 (Color Radio) e SWI132 (MG4 MY24): app originali com.saicmotor.* e fornitore di configurazione.</li>
 * </ul>
 * Prima fonte: la proprietà di sistema {@code ro.build.mt2712.version} (es. "SWI132-29958-1100R43"), che ha il nome
 * del software. Se manca o non è uno dei quattro, si guarda quali app originali ci sono e in quale cartella di sistema
 * sono installate: le MY24 (SWI132 e SWI133) hanno le app in cartelle "..._my24_...", le altre no.
 * Questa è la versione per la SWI133 (nuova MG4 Luxury): app com.saicmotor.hmi.* in cartelle "..._my24_P",
 * senza fornitore di configurazione.
 */
public final class Firmware {
    public static final String SWI68 = "SWI68", SWI69 = "SWI69", SWI132 = "SWI132", SWI133 = "SWI133", OTHER = "";

    /** Firmware per cui è fatta questa versione del launcher. */
    public static final String THIS = SWI133;

    /** Repository di ogni versione; per un dispositivo non riconosciuto, l'elenco di tutti i repository. */
    public static final String ALL_URL = "https://github.com/ernestomicco2026-cell";

    public static String url(String fw) {
        return fw == null || fw.isEmpty() ? ALL_URL : ALL_URL + "/Mg4-Launcher-" + fw;
    }

    private static final String[] HMI_APPS = {
            "com.saicmotor.hmi.launcher", "com.saicmotor.hmi.systemsettings", "com.saicmotor.hmi.radio",
            "com.saicmotor.hmi.hvac", "com.saicmotor.hmi.btcall", "com.saicmotor.hmi.aroundview",
            "com.saicmotor.hmi.vehiclesettings"};
    private static final String HMI_HOME = "com.saicmotor.hmi.launcher";
    /** Home originale di SWI69 e SWI132. */
    private static final String MG_HOME = "com.saicmotor.launcher";

    private static String kind;

    private Firmware() {}

    /** True se questa versione non è adatta al dispositivo: non si tenta nessun collegamento. */
    public static boolean wrong(Context c) {
        return !THIS.equals(kind(c));
    }

    /** Firmware riconosciuto: SWI68, SWI69, SWI132, SWI133 oppure "" (non è una MG4 conosciuta). */
    /**
     * Telefono o emulatore invece dell'infotainment: restituisce il motivo, oppure null se è un'auto (anche con il
     * firmware dell'altra versione). Su questi dispositivi il launcher non invia log.
     */
    public static String notACar(Context c) {
        String fp = lower(android.os.Build.FINGERPRINT), hw = lower(android.os.Build.HARDWARE), prod = lower(android.os.Build.PRODUCT),
                model = lower(android.os.Build.MODEL), brand = lower(android.os.Build.BRAND), man = lower(android.os.Build.MANUFACTURER);
        String dev = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
        if (fp.startsWith("generic") || fp.contains("emulator") || fp.contains("sdk_gphone") || hw.contains("goldfish")
                || hw.contains("ranchu") || prod.contains("sdk") || prod.contains("emulator") || model.contains("android sdk built for")
                || model.startsWith("generic") || model.contains("emulator"))
            return "emulatore (" + dev + ")";
        if (OTHER.equals(kind(c))) return "firmware non riconosciuto, nessuna app MG (" + dev + ")";
        for (String b : new String[]{"google", "oneplus", "samsung", "xiaomi", "redmi", "huawei", "oppo", "motorola", "sony", "nothing"})
            if (brand.equals(b) || man.equals(b)) return "telefono (" + dev + ")";
        return null;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }

    public static synchronized String kind(Context c) {
        if (kind != null) return kind;
        String prop = prop("ro.build.mt2712.version");
        String fromProp = fromProp(prop);

        PackageManager pm = c.getPackageManager();
        int hmi = 0;
        for (String p : HMI_APPS) if (installed(pm, p)) hmi++;
        // cartella della home MG; se manca, quella della prima app hmi presente (es. home disinstallata o disattivata)
        String hmiDir = dir(pm, HMI_HOME);
        for (int i = 0; hmiDir == null && i < HMI_APPS.length; i++) hmiDir = dir(pm, HMI_APPS[i]);
        String mgDir = dir(pm, MG_HOME);
        boolean provider = CarFeatures.hasFeatureProvider(c);

        String fromApps;
        if (hmi > 0) fromApps = my24(hmiDir) ? SWI133 : SWI68;
        else if (mgDir != null || provider) fromApps = my24(mgDir) ? SWI132 : SWI69;
        else fromApps = OTHER;

        kind = fromProp != null ? fromProp : fromApps;
        Diag.put("firmware", (kind.isEmpty() ? "non riconosciuto" : kind) + (THIS.equals(kind) ? "" : " (versione sbagliata, questa è per " + THIS + ")")
                + " · software " + (prop.isEmpty() ? "?" : prop)
                + " · dalle app " + (fromApps.isEmpty() ? "?" : fromApps)
                + " · app hmi " + hmi + "/" + HMI_APPS.length
                + " · fornitore config " + (provider ? "sì" : "no")
                + " · home MG " + (mgDir != null ? mgDir : hmiDir != null ? hmiDir : "assente"));
        return kind;
    }

    /** Nome del software dalla proprietà di sistema, solo se è uno dei quattro conosciuti. */
    private static String fromProp(String v) {
        if (v == null) return null;
        String u = v.trim().toUpperCase(java.util.Locale.ROOT);
        for (String fw : new String[]{SWI132, SWI133, SWI68, SWI69}) {
            if (u.equals(fw) || u.startsWith(fw + "-") || u.startsWith(fw + "_")) return fw;
        }
        return null;
    }

    /** Cartella delle app MY24 (es. /system/priv-app/Launcher_my24_ll, /system/priv-app/launcher_my24_P). */
    private static boolean my24(String dir) {
        return dir != null && dir.toLowerCase(java.util.Locale.ROOT).contains("_my24");
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Cartella di sistema in cui è installata un'app (null se non c'è). */
    private static String dir(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            String p = ai.sourceDir;
            if (p == null) return "";
            int slash = p.lastIndexOf('/');
            return slash > 0 ? p.substring(0, slash) : p;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            Object v = get.invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Throwable e) {
            return "";
        }
    }
}
