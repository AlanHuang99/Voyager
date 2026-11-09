package com.voyagerfiles.app

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process

/** Disposable separate-process Binder client for instrumentation death-recipient coverage. */
class ShizukuDeathTokenService : Service() {
    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.FIRST_CALL_TRANSACTION) {
                Process.killProcess(Process.myPid())
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }
}
