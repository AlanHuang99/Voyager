package com.voyagerfiles.app;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

/** The separate test process uses only SDK classes because Kotlin lives in the target APK. */
public final class ShizukuDeathTokenService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == IBinder.FIRST_CALL_TRANSACTION) {
                    Process.killProcess(Process.myPid());
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
    }
}
