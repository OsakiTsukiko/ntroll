package com.ntroll.app;
final class NativeFirewall {
    static { System.loadLibrary("ntroll"); }
    // One JNI call per configuration evaluation, with no native packet thread.
    static native int blockMask(int network);
    private NativeFirewall() {}
}
