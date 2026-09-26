package com.zigit.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Простейший FileProvider: отдаёт наружу файлы из своих каталогов —
 * apk/ (собранные приложения для установки) и downloads/ (скачанные артефакты и релизы).
 * Формат URI: content://com.zigit.app.fileprovider/<каталог>/<имя файла>
 */
public class FileProviderX extends ContentProvider {

    static final String AUTHORITY = "com.zigit.app.fileprovider";
    static final String DIR_APK = "apk";
    static final String DIR_DOWNLOADS = "downloads";

    public static Uri uriFor(Context c, String dir, File f) {
        return Uri.parse("content://" + AUTHORITY + "/" + dir + "/" + Uri.encode(f.getName()));
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> segs = uri.getPathSegments();
        if (segs.isEmpty()) throw new FileNotFoundException("Пустой путь");
        Context c = getContext();

        if (segs.size() >= 2) {
            String dir = segs.get(0);
            String name = segs.get(segs.size() - 1);
            if (!DIR_APK.equals(dir) && !DIR_DOWNLOADS.equals(dir)) {
                throw new FileNotFoundException("Неизвестный каталог");
            }
            if (name.contains("..") || name.contains("/") || name.contains("\\")) {
                throw new FileNotFoundException("Некорректное имя файла");
            }
            File ext = c.getExternalFilesDir(null);
            if (ext != null) {
                File f = new File(new File(ext, dir), name);
                if (f.exists()) return f;
            }
            File internal = new File(new File(c.getFilesDir(), dir), name);
            if (internal.exists()) return internal;
            File legacy = new File("/storage/emulated/0/Android/data/com.ghloader/files/apk", name);
            if (legacy.exists()) return legacy;
            throw new FileNotFoundException(name);
        }
        // совместимость: content://authority/<имя> — ищем в apk/, затем в downloads/
        String name = segs.get(0);
        for (String dir : new String[]{DIR_APK, DIR_DOWNLOADS}) {
            File f = new File(new File(c.getExternalFilesDir(null), dir), name);
            if (f.exists()) return f;
        }
        throw new FileNotFoundException(name);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        try {
            return Util.mimeFor(resolve(uri).getName());
        } catch (FileNotFoundException e) {
            return "application/octet-stream";
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            String[] cols = projection != null ? projection
                    : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor c = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
                else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
                else row[i] = null;
            }
            c.addRow(row);
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
