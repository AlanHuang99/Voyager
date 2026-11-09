package com.voyagerfiles.data.shizuku;
import android.os.ParcelFileDescriptor;
/** A staged replacement. Closing input() is not a commit; only commit() replaces the target. */
interface IShizukuWrite {
    ParcelFileDescriptor input();
    void commit();
    void abort();
}
