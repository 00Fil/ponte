package com.juan.base.log;

import android.util.Log;

/** Registro chiamato dal motore nativo delle telecamere. */
public class JALog {
    public static final String TAG = "JALog";
    public interface Logger { String getContentMsg(); }
    public static void d(String t, Logger l) {}
    public static void d(String t, String m) {}
    public static void v(String t, Logger l) {}
    public static void dStackTrace(String t, String m) {}
    public static void setDebug(boolean z) {}
    public static void i(String t, String m) { Log.i("cam/" + t, m); }
    public static void w(String t, String m) { Log.w("cam/" + t, m); }
    public static void w(String t, String m, Throwable th) { Log.w("cam/" + t, m, th); }
    public static void w(String t, Throwable th) { Log.w("cam/" + t, th); }
    public static void e(String t, String m) { Log.e("cam/" + t, m); }
    public static void e(String t, Throwable th) { Log.e("cam/" + t, "", th); }
    public static void e(String t, String m, Throwable th) { Log.e("cam/" + t, m, th); }
    public static void p2pLog(String a, String b, String c) {}
    public static void writeP2PLogBytesToFile(byte[] b, int i, int j) {}
    public static void flush() {}
}
