package com.voyagerfiles.data.shizuku;
import android.os.ParcelFileDescriptor;
interface IShizukuCommand {
    ParcelFileDescriptor input();
    ParcelFileDescriptor output();
    ParcelFileDescriptor error();
    boolean awaitExit(long timeoutMillis);
    int exitValue();
    void terminate();
}
