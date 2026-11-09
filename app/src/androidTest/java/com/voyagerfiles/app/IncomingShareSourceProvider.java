package com.voyagerfiles.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Private source in the separate instrumentation APK UID, accessible only by URI grants. */
public final class IncomingShareSourceProvider extends ContentProvider {
    public static final String AUTHORITY = "com.voyagerfiles.test.shares";
    static final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    static final class Session {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger firstOpens = new AtomicInteger();
        final AtomicInteger secondOpens = new AtomicInteger();
        volatile boolean blocked;
    }

    public static Uri uri(String session, String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(session).appendPath(name).build();
    }

    public static byte[] payload(String name) {
        byte[] bytes = new byte["empty.bin".equals(name) ? 0 : 128 * 1024];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) ((i * 31 + name.hashCode()) % 251);
        return bytes;
    }

    private Session session(Uri uri) throws FileNotFoundException {
        Session value = sessions.get(uri.getPathSegments().get(0));
        if (value == null) throw new FileNotFoundException("Missing share fixture");
        return value;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = uri.getLastPathSegment();
            if (OpenableColumns.SIZE.equals(columns[i]) && "empty.bin".equals(uri.getLastPathSegment())) row[i] = 0L;
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        Session session = session(uri);
        String name = uri.getLastPathSegment();
        boolean first = "first.bin".equals(name);
        if (first) session.firstOpens.incrementAndGet();
        if ("second.bin".equals(name)) session.secondOpens.incrementAndGet();
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            new Thread(() -> {
                try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    byte[] bytes = payload(name);
                    if (first) {
                        output.write(bytes, 0, 4096);
                        session.blocked = true;
                        if (!session.release.await(30, TimeUnit.SECONDS)) throw new IOException("Share fixture timed out");
                        output.write(bytes, 4096, bytes.length - 4096);
                    } else {
                        output.write(bytes);
                    }
                } catch (IOException | InterruptedException expected) {
                    try { pipe[1].closeWithError("Sender closed"); } catch (IOException ignored) { }
                }
            }, "share-fixture-writer").start();
            return pipe[0];
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
