package com.app.jagles.connect;

import com.app.jagles.listener.VideoEncodeCallback;

/** Ponte verso il motore Eseecloud (libJAVideo): stessi nomi e firme dell'app originale, gli eventi vanno a [sink]. */
public final class JAConnectorV2 {
    public interface Sink {
        void status(String key, int status, int index);
        void frame(String key, int w, int h, byte[] data, int len, int channel, long ptr);
        void audio(String key, byte[] data);
        void vcon(String key, String json, int index);
        void command(int code, String key, int status, int index);
        void stream(String key, int status, int channel);
        void record(String key, int a, int b, int c, int d, int e, int f);
        void osd(String key, int a, int b);
    }
    public static volatile Sink sink;
    private static JAConnectorV2 sInstance;
    static { System.loadLibrary("JAVideo"); }
    public static JAConnectorV2 getInstance() { return get(); }
    public static synchronized JAConnectorV2 get() { if (sInstance == null) sInstance = new JAConnectorV2(); return sInstance; }
    private JAConnectorV2() { nativeInitManager("", false); }

    public static native long decodeVideo(String str, int i, Object obj);
    public static native String getKp2pVersion();
    public static native void nativeAiSrEnable(boolean z);
    public static native void nativeAiSrPath(String str, String str2);
    public static native void nativeAudioTempoEnable(boolean z);
    public native int nativeCall(long j, int i, int i2);
    public native int nativeCancelCaptureImage(long j, int i, int i2);
    public native int nativeCloseStream(long j, int i, int i2, int i3);
    public static native void nativeConfigExtMediaPlayerContext(Object obj);
    public native int nativeConnect(long j, String str, String str2, String str3, int i, int i2, int i3, int i4, boolean z);
    public native long nativeCreateConnector(String str, boolean z);
    public static native long nativeCreateMediaPlayer(Object obj);
    public native int nativeDestroyConnector(long j);
    public native void nativeDisconnect(long j, int i);
    public static native void nativeEnableAudio(boolean z);
    public native int nativeEnableDecodeIFrameOnly(long j, boolean z, int i);
    public native boolean nativeEnableHardwareDecoder(long j, boolean z, int i, int i2, int i3);
    public static native void nativeEnqueueAudioBuffer(byte[] bArr, int i, int i2);
    public native int nativeGetAllStreamSpeed(long j);
    public native long nativeGetConnectionContext(long j, int i);
    public native int nativeGetStreamSpeed(long j, int i);
    public static native int nativeGetTutkOnlineStatus(Object obj, int i, String str, int i2);
    public native int nativeHangUp(long j, int i);
    public native void nativeInitManager(String str, boolean z);
    public static native boolean nativeMediaPlayerOnBusy(long j);
    public static native void nativeNightVisionEnable(boolean z);
    public native int nativeOpenStream(long j, int i, int i2, int i3, int i4, boolean z);
    public native int nativePTZControl(long j, int i, int i2, int i3, int i4, int i5, int i6);
    public native int nativePausePlayback(long j, int i, int i2);
    public static native void nativePlayerCancelCaptureImage(long j, int i);
    public static native void nativePlayerCapture(long j, String str, int i, int i2, int i3);
    public static native void nativePlayerChangeReplayMode(long j, int i);
    public static native void nativePlayerEnableHardware(long j, boolean z, int i, int i2);
    public static native void nativePlayerPause(long j);
    public static native void nativePlayerResume(long j);
    public static native void nativePlayerSeekFile(long j, int i);
    public static native void nativePlayerSetChannelCount(long j, int i);
    public static native boolean nativePlayerStart(int i, long j, String str, String str2, int i2, int i3, boolean z, boolean z2);
    public static native boolean nativePlayerStartRecord(long j, String str, int i, int i2, int i3, float f, float f2);
    public static native void nativePlayerStop(long j);
    public static native void nativePlayerStopRecord(long j);
    public static native int nativeRecSnapshotFind(long j, String str, int i, int i2, int i3, int i4, int i5, int i6, int i7);
    public static native int nativeRecSnapshotQueryStart(long j, String str, int i, int i2, int i3, int i4, int i5, int i6, int i7);
    public static native int nativeRecSnapshotQueryStop(long j, String str, int i);
    public static native void nativeReleaseExtMediaPlayerContext();
    public static native void nativeReleaseMediaPlayer(long j);
    public native int nativeResetAiSr(long j, String str);
    public static native int nativeResetTransfer();
    public native int nativeResumePlayback(long j, int i, int i2);
    public native int nativeSearchRecord(long j, int i, int i2, int i3, int i4, int i5, int i6);
    public native int nativeSendAudioPacket(long j, byte[] bArr, int i, long j2, String str, int i2, int i3, int i4, float f, int i5);
    public native int nativeSendData(long j, byte[] bArr, int i, int i2, int i3, int i4);
    public native int nativeSendMsgPushEvent(long j, int i, int i2);
    public native int nativeSendVconData(long j, int[] iArr, byte[] bArr, int i, int i2);
    public native int nativeSendVideoPacket(long j, byte[] bArr, int i, long j2, String str, int i2, int i3, int i4, boolean z, int i5);
    public native int nativeSetOSDFormat(long j, int i, int i2);
    public static native int nativeSetPlayAudioIndex(int i);
    public native int nativeSetPlayMode(long j, int i);
    public native int nativeSetTimezone(long j, int i, int i2);
    public native int nativeStartBackup(long j, int i, int i2, int i3, String str, int i4, int i5, int i6, int i7, int i8, int i9, int i10, int i11, float f, float f2);
    public native int nativeStartCaptureImage(long j, String str, int i, int i2, int i3, int i4, int i5);
    public native int nativeStartPlayback(long j, int i, int i2, boolean z, int i3, int i4, int i5);
    public native int nativeStartRecord(long j, String str, int i, int i2, int i3, int i4, float f, float f2);
    public native int nativeStopBackup(long j, int i, int i2);
    public native int nativeStopPlayback(long j, int i, int i2);
    public native int nativeStopRecord(long j, int i);
    public static native void nativeSwitchSoftEncoder(boolean z);
    public static native int stopDecodeVideo(long j);
    public native long configVideoEncoder(String str, int i, int i2, int i3, int i4, int i5, VideoEncodeCallback videoEncodeCallback);
    public native byte[] covertYUV420_888ToNV21(int i, int i2, byte[] bArr, byte[] bArr2, byte[] bArr3, int i3, int i4, int i5, int i6);
    public native int encodeVideo(long j, byte[] bArr, int i, int i2, int i3);
    public native int flushEncodeBuffer(long j);
    public native int forceIFrame(long j);
    public native void nativeDestroyManager();
    public native int releaseVideoEncoder(long j);
    public native int testEncodeFile(long j, String str, int i, int i2);

    public void onLogUpload(String str) {  }
    public void onCommandStatus(int i, String str, int i2, int i3) { Sink s = sink; if (s != null) s.command(i, str, i2, i3); }
    public void OnKp2pErrorStatus(String str, int i, int i2, int i3, int i4) {  }
    public void onKp2pConnectResult(String str, int i, String str2, int i2) {  }
    public void onConnect2(String str, int i, String str2, int i2) {  }
    public void onNgwDomain(String str, String str2, int i, boolean z) {  }
    public void onConnectStatus(String str, int i, int i2, int i3) { Sink s = sink; if (s != null) s.status(str, i, i3); }
    public void onServerErrCallback(String str, int i, int i2, int i3) {  }
    public boolean onP2PDisconnectCallback(String str, int i, int i2) {  return false; }
    public void onStreamOpenStatus(String str, int i, int i2) { Sink s = sink; if (s != null) s.stream(str, i, i2); }
    public void onAiSrStatus(String str, int i, int i2, boolean z, int i3) {  }
    public void onNightVisionStatus(String str, boolean z, int i) {  }
    public void onVconDataAvailable(String str, byte[] bArr, int i) { Sink s = sink; if (s == null || bArr == null) return; int n = bArr.length; while (n > 0 && bArr[n - 1] == 0) n--; s.vcon(str, new String(bArr, 0, n, java.nio.charset.StandardCharsets.UTF_8), i); }
    public void onVconDataResend(String str, int i, int i2, int i3, int i4, int i5, int i6) {  }
    public void onAudioFrameAvailable(String str, byte[] bArr) { Sink s = sink; if (s != null) s.audio(str, bArr); }
    public void onTextureAvailable(String str, int i, int i2, float f, byte[] bArr, long j, int i3, int i4, int i5, int i6, int i7, long j2) { Sink s = sink; if (s != null) s.frame(str, i, i2, bArr, i3, i7, j); }
    public void onOSDTextureAvailable(String str, int i, int i2, long j, int i3, int i4, long j2) {  }
    public void onPlaybackOSDAvailable(String str, int i, int i2) { Sink s = sink; if (s != null) s.osd(str, i, i2); }
    public void onRecordVideoDurationAvailable(String str, long j, int i) {  }
    public void OnPTZControlCallback(String str, int i, int i2) {  }
    public void onRecordAvailable(String str, int i, int i2, int i3, int i4, int i5, int i6) { Sink s = sink; if (s != null) s.record(str, i, i2, i3, i4, i5, i6); }
    public void onOOBParamAvailable(String str, int i, int i2) {  }
    public void onFishParamAvailable(String str, float f, float f2, float f3, float f4, float f5, float f6, byte[] bArr, int i, int i2) {  }
    public void onGSensorParamAvailable(String str, long j, double d, double d2, double d3) {  }
    public void onSplicingParamAvailable(String str, int i, int i2, int[] iArr, int[] iArr2, int i3) {  }
    public void onPushMsgParamAvailable(String str, int i, int i2) {  }
    public void onPrivacyModeEvent(String str, int i) {  }
    public void onCaptureResult(String str, int i, int i2, int i3) {  }
    public void onTUTKOnlineStatusAvailable(int i, int i2) {  }
    public void OnVideoBackupCallback(String str, int i, int i2) {  }
    public void OnRecSnapshot(String str, int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8, int i9, int i10, int i11, int i12, int i13, int i14, int i15, byte[] bArr) {  }
    public void OnRecSnapshotFind(String str, int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8, int i9, int i10, int i11, int i12, int i13) {  }
    public void OnPixelInterpolation(String str, int i, int i2, int i3, String str2) {  }
    public void onPlayProgress(int i, int i2) {  }
}
