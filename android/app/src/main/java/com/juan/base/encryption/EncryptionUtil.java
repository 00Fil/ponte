package com.juan.base.encryption;

/** Cifratura delle credenziali del registratore (libencrypt dell'app originale). */
public class EncryptionUtil {
    static { System.loadLibrary("encrypt"); }
    public static native String nativeDecryptPassword(String str);
    public static native String nativeDecryptVerify(String str);
    public static native String nativeDecryptVerifyByKey(String str, byte[] bArr);
    public static native byte[] nativeDesDecrypt(byte[] bArr, byte[] bArr2);
    public static native byte[] nativeDesEncrypt(byte[] bArr, byte[] bArr2);
    public static native String nativeEncryptPassword(String str);
    public static native String nativeEncryptVerify(String str);
    public static native String nativeRequestDecryptIV();
    public static native String nativeRequestDecryptKey();
    public static native String nativeRequestTransformation(int i);
    public static native byte[] nativeRequestVerifyKeys();
    public static native String nativeSignViaIndex(String[] strArr, int i, int i2);
    public static native String nativeTranslatePwdOfExtraData(String str, int i);
    public static String encryptVerify(String s) { return nativeEncryptVerify(s); }
}
