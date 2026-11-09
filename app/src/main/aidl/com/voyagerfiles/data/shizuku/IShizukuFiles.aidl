package com.voyagerfiles.data.shizuku;
import com.voyagerfiles.data.shizuku.IShizukuCommand;
import com.voyagerfiles.data.shizuku.IShizukuRead;
import com.voyagerfiles.data.shizuku.IShizukuWrite;
interface IShizukuFiles {
    IShizukuCommand start(String script) = 1;
    int uid() = 2;
    byte[] stat(String path) = 4;
    boolean exists(String path) = 5;
    String fingerprint(String path) = 7;
    void mkdir(String path) = 8;
    void createFile(String path) = 9;
    IShizukuRead openRead(String path) = 12;
    IShizukuWrite openWrite(String path, String expectedFingerprint, IBinder client) = 13;
    void destroy() = 16777114;
}
