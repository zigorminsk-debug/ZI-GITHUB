package com.zigit.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.TypedValue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Разные утилиты: форматирование, сохранение в «Загрузки», распаковка APK. */
final class Util {

    private Util() {
    }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    static String humanSize(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < 1024) return bytes + " Б";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f КБ", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f МБ", mb);
        return String.format(Locale.US, "%.2f ГБ", mb / 1024.0);
    }

    private static OffsetDateTime parse(String iso) {
        try {
            return OffsetDateTime.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    /** 2026-09-15T10:00:00Z -> 15.09.2026 13:00 (в часовом поясе телефона) */
    static String dateTime(String iso) {
        OffsetDateTime t = parse(iso);
        if (t == null) return "—";
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.getDefault());
        return t.atZoneSameInstant(ZoneId.systemDefault()).format(f);
    }

    static String timeAgo(String iso) {
        OffsetDateTime t = parse(iso);
        if (t == null) return "";
        long diff = System.currentTimeMillis() - t.toInstant().toEpochMilli();
        if (diff < 0) diff = 0;
        long min = diff / 60000;
        if (min < 1) return "только что";
        if (min < 60) return min + " мин назад";
        long h = min / 60;
        if (h < 24) return h + " ч назад";
        long d = h / 24;
        if (d < 30) return d + " дн назад";
        long mo = d / 30;
        if (mo < 12) return mo + " мес назад";
        return (mo / 12) + " г назад";
    }

    /** MIME-тип по расширению файла. */
    static String mimeFor(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".txt") || n.endsWith(".log") || n.endsWith(".md")) return "text/plain";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".jar") || n.endsWith(".aab")) return "application/java-archive";
        String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(name == null ? "" : name);
        String t = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext == null ? "" : ext.toLowerCase());
        return t == null ? "application/octet-stream" : t;
    }

    /** Каталог приложения со скачанными файлами (доступен установщику и другим приложениям через FileProvider). */
    static File appDownloadDir(Context c) {
        File d = new File(c.getExternalFilesDir(null), FileProviderX.DIR_DOWNLOADS);
        if (!d.exists() && !d.mkdirs()) {
            // запасной вариант — внутренний каталог
            d = new File(c.getFilesDir(), FileProviderX.DIR_DOWNLOADS);
            if (!d.exists()) //noinspection ResultOfMethodCallIgnored
                d.mkdirs();
        }
        return d;
    }

    /**
     * Открыть скачанный файл во внешнем приложении (просмотрщик, архиватор, установщик).
     * @return null при успехе, иначе текст ошибки
     */
    static String fileProviderDir(File f) {
        File p = f == null ? null : f.getParentFile();
        if (p != null && FileProviderX.DIR_APK.equals(p.getName())) return FileProviderX.DIR_APK;
        return FileProviderX.DIR_DOWNLOADS;
    }

    static String openFile(android.app.Activity a, File f) {
        if (f == null || !f.exists()) return "Файл не найден";
        Uri uri = FileProviderX.uriFor(a, fileProviderDir(f), f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mimeFor(f.getName()));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(i);
            return null;
        } catch (android.content.ActivityNotFoundException e) {
            // если для типа нет приложения — предложим выбрать любое
            Intent any = new Intent(Intent.ACTION_VIEW);
            any.setDataAndType(uri, "*/*");
            any.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                a.startActivity(any);
                return null;
            } catch (Exception e2) {
                return "Нет приложения, которое умеет открывать такие файлы";
            }
        } catch (Exception e) {
            return "Не удалось открыть: " + e.getMessage();
        }
    }

    /** Поделиться скачанным файлом (мессенджеры, почта, облако). */
    static String shareFile(android.app.Activity a, File f) {
        if (f == null || !f.exists()) return "Файл не найден";
        Uri uri = FileProviderX.uriFor(a, fileProviderDir(f), f);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mimeFor(f.getName()));
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(i, "Поделиться файлом");
        try {
            a.startActivity(chooser);
            return null;
        } catch (Exception e) {
            return "Не удалось поделиться: " + e.getMessage();
        }
    }

    /** Имя файла без опасных символов. */
    static String safeName(String name) {
        String s = name == null ? "artifact" : name;
        s = s.replaceAll("[^A-Za-z0-9._\\-]", "_");
        if (s.length() > 90) s = s.substring(0, 90);
        return s;
    }

    /** Результат сохранения скачанного файла. */
    static class Saved {
        String publicPath;   // путь для показа пользователю ("Загрузки/имя")
        File appFile;        // копия внутри приложения (для открытия/установки/шаринга)
        String displayName;
    }

    /**
     * Сохраняет скачанный файл в общую папку «Загрузки» и в каталог приложения
     * (второй нужен, чтобы открыть файл или поделиться им через FileProvider).
     */
    static Saved save(Context c, File src, String displayName, String mime) throws IOException {
        Saved res = new Saved();
        res.displayName = displayName;
        res.publicPath = copyToDownloads(c, src, displayName, mime);
        File dir = appDownloadDir(c);
        File out = new File(dir, displayName);
        copyFile(src, out);
        res.appFile = out;
        return res;
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    /**
     * Сохраняет файл в общую папку «Загрузки».
     * Android 10+ — через MediaStore (без разрешений), Android 8–9 — напрямую.
     */
    static String copyToDownloads(Context c, File src, String displayName, String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Не удалось создать файл в «Загрузках»");
            OutputStream os = c.getContentResolver().openOutputStream(uri);
            if (os == null) throw new IOException("Не удалось открыть поток записи");
            try {
                copy(src, os);
            } finally {
                os.close();
            }
            return "Загрузки/" + displayName;
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Нет доступа к папке «Загрузки»");
            File out = new File(dir, displayName);
            try (InputStream in = new java.io.FileInputStream(src);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            touch(c, out);
            return out.getAbsolutePath();
        }
    }

    private static void touch(Context c, File f) {
        try {
            android.media.MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, null, null);
        } catch (Exception ignored) {
        }
    }

    private static void copy(File src, OutputStream os) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    /**
     * Ищет внутри zip-архива первый .apk и извлекает его в outDir.
     * Нужно потому, что артефакты Actions всегда отдаются zip-архивом.
     *
     * @return извлечённый файл или null, если APK внутри нет
     */
    static File extractFirstApk(File zip, File outDir) {
        if (!outDir.exists() && !outDir.mkdirs()) return null;
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new java.io.FileInputStream(zip));
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                    String base = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
                    File out = new File(outDir, safeName(base));
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    return out;
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (zis != null) zis.close();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Открыть установщик APK (нужен content:// URI, поэтому через FileProviderX). */
    static void installApk(android.app.Activity a, File apk) {
        Uri uri = FileProviderX.uriFor(a, fileProviderDir(apk), apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(i);
    }

    static String formatDate(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(millis));
    }
}
