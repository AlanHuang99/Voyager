package com.voyagerfiles.data.shizuku;
import android.os.ParcelFileDescriptor;
/** Bytes produced by the service. finish() returns the count written, or throws when production failed. */
interface IShizukuRead {
    ParcelFileDescriptor output();
    long finish();
}
