package com.voyagerfiles.data.repository

import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * A [FilterOutputStream] that hands array writes to the wrapped stream in a single call.
 *
 * [FilterOutputStream.write] with an array argument loops over the bytes and calls the
 * single-byte [write] for each one. A wrapper that only adds close-time behavior therefore
 * turns every 64 KiB [StreamTransfer] chunk into tens of thousands of single-byte socket,
 * SFTP packet, or file writes. Provider stream wrappers extend this class instead.
 */
open class ForwardingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
    }
}
