package com.zigit.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Достаёт APK из скачанного артефакта GitHub Actions.
 * Штатный ZipInputStream часто падает на этих архивах (data descriptor, gzip, STORED).
 */
final class ZipExtract {

    private ZipExtract() {
    }

    static File apkFrom(File src, File outDir) throws IOException {
        if (src == null || !src.exists()) throw new IOException("Нет скачанного файла");
        if (outDir == null) throw new IOException("Нет каталога для APK");
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("Не создать каталог " + outDir);
        if (src.length() < 4) throw new IOException("Файл пустой (" + src.length() + " байт)");

        File work = unwrapGzip(src);
        if (Util.looksLikeJson(work)) {
            throw new IOException("GitHub вернул JSON, а не архив. Нужен токен.\n" + headText(work));
        }

        File apk = fromZipFile(work, outDir);
        if (valid(apk)) return apk;
        apk = fromStream(work, outDir);
        if (valid(apk)) return apk;
        apk = fromCentralDirectory(work, outDir);
        if (valid(apk)) return apk;

        if (hasManifest(work)) {
            File copy = new File(outDir, "download.apk");
            copyFile(work, copy);
            if (valid(copy)) return copy;
        }

        throw new IOException("Не удалось извлечь APK из архива.\n"
                + "Размер: " + work.length() + " байт\n"
                + "Начало: " + hexHead(work));
    }

    static String hexHead(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] m = new byte[16];
            int n = in.read(m);
            if (n <= 0) return "(пусто)";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(' ');
                sb.append(String.format(Locale.US, "%02x", m[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    private static String headText(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] m = new byte[180];
            int n = in.read(m);
            if (n <= 0) return "";
            return new String(m, 0, n, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean valid(File f) {
        return f != null && f.isFile() && f.length() > 100 && Util.looksLikeZip(f);
    }

    private static File unwrapGzip(File src) throws IOException {
        if (!isGzip(src)) return src;
        File out = new File(src.getParentFile(), src.getName() + ".ungz");
        try (GZIPInputStream gz = new GZIPInputStream(new FileInputStream(src));
             FileOutputStream os = new FileOutputStream(out)) {
            copy(gz, os);
        }
        return out;
    }

    private static boolean isGzip(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            return in.read() == 0x1f && in.read() == 0x8b;
        } catch (Exception e) {
            return false;
        }
    }

    private static String baseName(String name) {
        String n = name == null ? "" : name.replace('\\', '/');
        int i = n.lastIndexOf('/');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    private static File fromZipFile(File zip, File outDir) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(zip);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String base = baseName(e.getName());
                if (!base.toLowerCase(Locale.US).endsWith(".apk")) continue;
                File out = new File(outDir, Util.safeName(base));
                try (InputStream in = zf.getInputStream(e);
                     FileOutputStream os = new FileOutputStream(out)) {
                    copy(in, os);
                }
                if (valid(out)) return out;
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (zf != null) zf.close();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static File fromStream(File zip, File outDir) {
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new FileInputStream(zip));
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String base = baseName(e.getName());
                if (!base.toLowerCase(Locale.US).endsWith(".apk")) continue;
                File out = new File(outDir, Util.safeName(base));
                try (FileOutputStream os = new FileOutputStream(out)) {
                    copy(zis, os);
                }
                if (valid(out)) return out;
                //noinspection ResultOfMethodCallIgnored
                out.delete();
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

    /** Ручной разбор ZIP: не зависит от багов ZipInputStream на архивах GitHub. */
    private static File fromCentralDirectory(File zip, File outDir) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(zip, "r");
            long len = raf.length();
            if (len < 22) return null;
            int scan = (int) Math.min(len, 22 + 65535);
            byte[] tail = new byte[scan];
            raf.seek(len - scan);
            raf.readFully(tail);
            int eocd = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if (tail[i] == 'P' && tail[i + 1] == 'K' && tail[i + 2] == 5 && tail[i + 3] == 6) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) return null;
            int nEnt = le16(tail, eocd + 10);
            long cdOff = le32(tail, eocd + 16);
            if (nEnt <= 0 || cdOff < 0 || cdOff >= len) return null;

            long pos = cdOff;
            for (int i = 0; i < nEnt && i < 500; i++) {
                raf.seek(pos);
                byte[] hdr = new byte[46];
                if (raf.read(hdr) < 46) break;
                if (hdr[0] != 'P' || hdr[1] != 'K' || hdr[2] != 1 || hdr[3] != 2) break;
                int method = le16(hdr, 10);
                long compSize = le32(hdr, 20);
                long uncompSize = le32(hdr, 24);
                int nameLen = le16(hdr, 28);
                int extraLen = le16(hdr, 30);
                int commentLen = le16(hdr, 32);
                long localOff = le32(hdr, 42);
                byte[] nameBytes = new byte[nameLen];
                if (nameLen > 0) raf.readFully(nameBytes);
                String name = new String(nameBytes, "UTF-8");
                pos += 46L + nameLen + extraLen + commentLen;

                String base = baseName(name);
                if (!base.toLowerCase(Locale.US).endsWith(".apk")) continue;
                if (method != 0 && method != 8) continue;

                raf.seek(localOff);
                byte[] loc = new byte[30];
                if (raf.read(loc) < 30) continue;
                if (loc[0] != 'P' || loc[1] != 'K' || loc[2] != 3 || loc[3] != 4) continue;
                int locName = le16(loc, 26);
                int locExtra = le16(loc, 28);
                long dataOff = localOff + 30L + locName + locExtra;
                int locMethod = le16(loc, 8);
                if (locMethod == 0 || locMethod == 8) method = locMethod;

                File out = new File(outDir, Util.safeName(base));
                raf.seek(dataOff);
                if (method == 0) {
                    copyFrom(raf, out, uncompSize > 0 ? uncompSize : compSize);
                } else {
                    inflateFrom(raf, out, compSize, uncompSize);
                }
                if (valid(out)) return out;
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (raf != null) raf.close();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static void copyFrom(RandomAccessFile raf, File out, long size) throws IOException {
        try (FileOutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            long left = size;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                os.write(buf, 0, n);
                left -= n;
            }
        }
    }

    private static void inflateFrom(RandomAccessFile raf, File out, long compSize, long uncompSize)
            throws IOException {
        byte[] compressed = new byte[(int) Math.min(compSize, Integer.MAX_VALUE - 8)];
        int got = 0;
        while (got < compressed.length) {
            int n = raf.read(compressed, got, compressed.length - got);
            if (n < 0) break;
            got += n;
        }
        Inflater inf = new Inflater(true);
        try (FileOutputStream os = new FileOutputStream(out)) {
            inf.setInput(compressed, 0, got);
            byte[] buf = new byte[65536];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput()) break;
                    if (inf.needsDictionary()) break;
                }
                if (n > 0) os.write(buf, 0, n);
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw (e instanceof IOException) ? (IOException) e : new IOException(e);
        } finally {
            inf.end();
        }
        if (uncompSize > 0 && out.length() == 0) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    private static boolean hasManifest(File zip) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(zip);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                if ("AndroidManifest.xml".equalsIgnoreCase(baseName(en.nextElement().getName()))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (zf != null) zf.close();
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream os = new FileOutputStream(dst)) {
            copy(in, os);
        }
    }

    private static void copy(InputStream in, OutputStream os) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n > 0) os.write(buf, 0, n);
        }
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static long le32(byte[] b, int off) {
        return (b[off] & 0xffL)
                | ((b[off + 1] & 0xffL) << 8)
                | ((b[off + 2] & 0xffL) << 16)
                | ((b[off + 3] & 0xffL) << 24);
    }
}
