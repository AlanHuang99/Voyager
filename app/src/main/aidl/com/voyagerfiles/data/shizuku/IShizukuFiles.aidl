package com.voyagerfiles.data.shizuku;
import com.voyagerfiles.data.shizuku.IShizukuCommand;
interface IShizukuFiles {
    IShizukuCommand start(String script) = 1;
    int uid() = 2;
    void destroy() = 16777114;
}
