package com.voyagerfiles.data.shizuku

import java.security.MessageDigest
import java.security.MessageDigestSpi
import java.security.Provider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Test-only JCA provider pauses the real final hash without changing production transfer APIs. */
class PausingSha256 : MessageDigestSpi() {
    private val delegate = MessageDigest.getInstance("SHA-256", delegateProvider)
    override fun engineUpdate(input: Byte) { pause(); delegate.update(input) }
    override fun engineUpdate(input: ByteArray, offset: Int, length: Int) { pause(); delegate.update(input, offset, length) }
    override fun engineDigest(): ByteArray = delegate.digest()
    override fun engineReset() = delegate.reset()
    override fun engineGetDigestLength(): Int = 32
    private fun pause() {
        if (armed && Thread.currentThread().name == "shizuku-final-commit") {
            armed = false
            entered.countDown()
            check(resume.await(10, TimeUnit.SECONDS)) { "Test did not release the paused digest" }
        }
    }
    companion object {
        lateinit var delegateProvider: Provider
        lateinit var entered: CountDownLatch
        lateinit var resume: CountDownLatch
        @Volatile var armed = false
    }
}
