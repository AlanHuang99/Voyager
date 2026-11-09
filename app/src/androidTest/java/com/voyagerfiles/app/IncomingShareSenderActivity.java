package com.voyagerfiles.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;

/** Sends from the test APK's UID so Activity and Service grants are exercised by Android. */
public final class IncomingShareSenderActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String session = getIntent().getStringExtra("session");
        ArrayList<Uri> sources = new ArrayList<>();
        for (String name : new String[]{"first.bin", "second.bin", "empty.bin"}) sources.add(IncomingShareSourceProvider.uri(session, name));
        Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE)
            .setClassName("com.voyagerfiles.debug", "com.voyagerfiles.app.MainActivity")
            .setType("application/octet-stream")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, sources)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clip = ClipData.newRawUri("Share fixtures", sources.get(0));
        for (int i = 1; i < sources.size(); i++) clip.addItem(new ClipData.Item(sources.get(i)));
        share.setClipData(clip);
        startActivity(share);
        finish();
    }
}
