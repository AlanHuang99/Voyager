package com.voyagerfiles.playback

interface PlaybackRandomAccessSource : AutoCloseable {
    fun read(offset: Long, byteCount: Int): Result<ByteArray>
}
