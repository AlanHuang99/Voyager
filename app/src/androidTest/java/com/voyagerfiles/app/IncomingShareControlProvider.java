package com.voyagerfiles.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Controls disposable sender fixtures without granting access to their source URIs. */
public final class IncomingShareControlProvider extends ContentProvider {
    public static final String AUTHORITY = "com.voyagerfiles.test.sharecontrol";
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String sessionId, Bundle extras) {
        if ("reset".equals(method)) IncomingShareSourceProvider.sessions.put(sessionId, new IncomingShareSourceProvider.Session());
        IncomingShareSourceProvider.Session session = IncomingShareSourceProvider.sessions.get(sessionId);
        if (session == null) throw new IllegalArgumentException("Missing fixture session");
        if ("release".equals(method) || "clear".equals(method)) session.release.countDown();
        if ("revoke".equals(method)) getContext().revokeUriPermission(IncomingShareSourceProvider.uri(sessionId, "second.bin"), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if ("clear".equals(method)) IncomingShareSourceProvider.sessions.remove(sessionId);
        Bundle result = new Bundle();
        result.putBoolean("blocked", session.blocked);
        result.putInt("firstOpens", session.firstOpens.get());
        result.putInt("secondOpens", session.secondOpens.get());
        return result;
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
