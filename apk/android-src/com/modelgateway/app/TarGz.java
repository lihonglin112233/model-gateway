package com.modelgateway.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * 极简 tar.gz 解包器。
 *
 * 不用系统的 tar 命令：Android 上 toybox 的 tar 对 xz 支持不稳定，
 * 依赖外部命令在车机/定制 ROM 上很容易翻车。这里自己读 tar 头，
 * 支持文件、目录、符号链接、硬链接（按拷贝处理）。
 */
final class TarGz {

    interface Log {
        void log(String line);
    }

    private TarGz() { }

    static void extract(InputStream raw, File dest, Log log) throws IOException {
        File root = dest;
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) {
            throw new IOException("无法创建目录 " + root);
        }
        GZIPInputStream gz = new GZIPInputStream(raw, 65536);
        byte[] header = new byte[512];
        long files = 0;
        // GNU tar 用类型 L / K 的扩展条目存放超长文件名与链接名，
        // 它们本身不带文件内容，真正的内容在下一条目里。
        String pendingName = null;
        String pendingLink = null;
        try {
            while (true) {
                if (!readFully(gz, header, 512)) break;
                if (isZeroBlock(header)) break;

                String name = name(header, 0, 100);
                String prefix = name(header, 345, 155);
                if (prefix.length() > 0) name = prefix + "/" + name;
                long size = octal(header, 124, 12);
                int mode = (int) octal(header, 100, 8);
                char type = (char) (header[156] & 0xFF);
                String link = name(header, 157, 100);

                // 扩展头：读出超长名字，暂存给下一条目
                if (type == 'L' || type == 'K') {
                    byte[] payload = new byte[(int) Math.max(0, size)];
                    if (!readFully(gz, payload, payload.length)) break;
                    String longName = new String(payload).trim();
                    if (type == 'L') pendingName = longName;
                    else pendingLink = longName;
                    long padLen = (512 - (size % 512)) % 512;
                    if (padLen > 0) skip(gz, padLen);
                    continue;
                }
                if (pendingName != null) {
                    name = pendingName;
                    pendingName = null;
                }
                if (pendingLink != null) {
                    link = pendingLink;
                    pendingLink = null;
                }

                if (name.startsWith("/")) name = name.substring(1);
                if (name.length() == 0 || name.contains("..")) {
                    skip(gz, size);
                    continue;
                }

                File out = new File(root, name);
                switch (type) {
                    case '5': {
                        out.mkdirs();
                        applyMode(out, mode);
                        break;
                    }
                    case '2': {
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        deleteQuietly(out);
                        symlink(link, out);
                        break;
                    }
                    case '1': {
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        File src = new File(root, link.startsWith("/") ? link.substring(1) : link);
                        if (src.isFile()) copyFile(src, out);
                        break;
                    }
                    case '0':
                    case '\0':
                    case '7': {
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        writeFile(gz, out, size);
                        applyMode(out, mode);
                        files++;
                        if (files % 2000 == 0 && log != null) {
                            log.log("已解压 " + files + " 个文件…");
                        }
                        // 文件内容按 512 字节对齐，writeFile 已吃掉 padding
                        continue;
                    }
                    default: {
                        // 其他类型（设备节点等）跳过
                        skip(gz, size);
                        break;
                    }
                }
                // 非普通文件没有内容块
                skip(gz, 0);
            }
        } finally {
            try { gz.close(); } catch (Throwable ignore) { }
        }
        if (log != null) log.log("解压完成，共 " + files + " 个文件");
    }

    private static void writeFile(InputStream in, File out, long size) throws IOException {
        FileOutputStream fo = new FileOutputStream(out);
        try {
            byte[] buf = new byte[65536];
            long left = size;
            while (left > 0) {
                int want = (int) Math.min(buf.length, left);
                int n = in.read(buf, 0, want);
                if (n < 0) throw new IOException("tar 数据提前结束：" + out);
                fo.write(buf, 0, n);
                left -= n;
            }
        } finally {
            try { fo.close(); } catch (Throwable ignore) { }
        }
        long pad = (512 - (size % 512)) % 512;
        if (pad > 0) skip(in, pad);
    }

    private static void symlink(String target, File link) {
        // API 26+ 可直接用 nio；低版本退回 ln
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                java.nio.file.Files.createSymbolicLink(
                        link.toPath(), java.nio.file.Paths.get(target));
                return;
            } catch (Throwable ignore) { }
        }
        try {
            new ProcessBuilder("ln", "-s", target, link.getAbsolutePath())
                    .redirectErrorStream(true).start().waitFor();
        } catch (Throwable ignore) { }
    }

    private static void copyFile(File src, File dst) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        try {
            FileOutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                try { out.close(); } catch (Throwable ignore) { }
            }
        } finally {
            try { in.close(); } catch (Throwable ignore) { }
        }
    }

    /**
     * 还原 tar 里的权限位。
     *
     * 这一步不能省：rootfs 里的 /bin/sh、/usr/bin/python3、动态加载器
     * 都靠可执行位才能跑。Java 的 File API 只能表达"属主可执行"，
     * 对 Android 上以应用身份运行自己的文件来说够用。
     */
    private static void applyMode(File f, int mode) {
        if (mode <= 0) return;
        try {
            boolean ownerRead = (mode & 0400) != 0;
            boolean ownerWrite = (mode & 0200) != 0;
            boolean ownerExec = (mode & 0100) != 0;
            f.setReadable(ownerRead, false);
            f.setWritable(ownerWrite, false);
            f.setExecutable(ownerExec, false);
        } catch (Throwable ignore) { }
    }

    private static void deleteQuietly(File f) {
        try { if (f.exists()) f.delete(); } catch (Throwable ignore) { }
    }

    private static void skip(InputStream in, long n) throws IOException {
        long left = n;
        byte[] buf = new byte[8192];
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int r = in.read(buf, 0, want);
            if (r < 0) return;
            left -= r;
        }
    }

    private static boolean readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) return off > 0 ? true : false;
            off += n;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (int i = 0; i < 512; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }

    private static String name(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off);
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = b[i] & 0xFF;
            if (c == 0 || c == ' ') {
                if (v != 0) break;
                continue;
            }
            if (c < '0' || c > '7') continue;
            v = v * 8 + (c - '0');
        }
        return v;
    }
}
