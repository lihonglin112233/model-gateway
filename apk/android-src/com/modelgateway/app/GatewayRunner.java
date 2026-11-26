package com.modelgateway.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 负责在后台把 rootfs 解压出来，并用 proot 启动 uvicorn。
 *
 * proot 参数照搬已在同设备验证过的组合（--kill-on-exit --link2symlink
 * --sysvipc -L -0 --rootfs=...），挂载 /proc /dev /sys 等必要目录，
 * Android 上 /dev/shm 不可用，因此单独 bind 一个目录进去。
 */
final class GatewayRunner {

    interface Callback {
        void onLog(String line);
        void onState(String state);
        void onReady(int port);
    }

    private static final String ROOTFS = "rootfs";
    private static final String APP = "app";
    /** 仅作为低版本系统回退时从 assets 取 proot 的副本名。 */
    private static final String PROOT = "proot";
    private static final String PROOT_LOADER = "proot-loader";

    private final Context ctx;
    private final Callback cb;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Process proc;
    private int port;

    GatewayRunner(Context ctx, Callback cb) {
        this.ctx = ctx;
        this.cb = cb;
    }

    boolean isRunning() {
        return running.get();
    }

    File runtimeDir() {
        return new File(ctx.getFilesDir(), "runtime");
    }

    private File rootfsDir() {
        return new File(runtimeDir(), ROOTFS);
    }

    void deleteRuntime() {
        deleteTree(runtimeDir());
    }

    /** 后台线程完成：解压 → 启动 → 等健康检查。 */
    void start(final int port) {
        this.port = port;
        running.set(true);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    prepare();
                    launch(port);
                } catch (Throwable t) {
                    log("启动失败：" + t);
                    running.set(false);
                    cb.onState("启动失败");
                }
            }
        }, "gateway-start").start();
    }

    void stop() {
        running.set(false);
        Process p = proc;
        proc = null;
        if (p != null) {
            try {
                // proot 会转发 SIGTERM 给客体内的 uvicorn
                p.destroy();
            } catch (Throwable ignore) { }
        }
    }

    // ---------------- 准备运行时 ----------------

    private void prepare() throws Exception {
        File runtime = runtimeDir();
        File rootfs = rootfsDir();
        File stamp = new File(runtime, ".ready");

        if (!stamp.isFile() || !rootfs.isDirectory()) {
            cb.onState("解压运行时（首次约需 1 分钟）");
            deleteTree(runtime);
            if (!runtime.mkdirs() && !runtime.isDirectory()) {
                throw new IOException("无法创建 " + runtime);
            }
            log("解压 rootfs…");
            extract("rootfs.tar.gz", runtime);
            log("解压应用…");
            extract("app.tar.gz", new File(rootfs, "root"));
            // proot 首选 nativeLibraryDir（安装时由系统解压，可执行）。
            // 同时保留一份 assets 副本，供极少数不解压 .so 的旧系统回退。
            try {
                new FileOutputStream(stamp).close();
            } catch (Throwable ignore) { }
            try {
                copyAsset(PROOT, new File(runtime, PROOT), true);
                copyAsset(PROOT_LOADER, new File(runtime, PROOT_LOADER), true);
            } catch (Throwable ignore) { }
            log("运行时就绪");
        } else {
            log("运行时已存在，跳过解压");
        }
        ensureGuestDirs();
    }

    /** 客体内需要的目录（/dev/shm、/tmp 等）在 Android 上不存在，先建好。 */
    private void ensureGuestDirs() {
        File root = rootfsDir();
        String[] dirs = {"dev/shm", "tmp", "proc", "sys", "root/app/data", "var/log"};
        for (String d : dirs) {
            File f = new File(root, d);
            if (!f.isDirectory()) f.mkdirs();
        }
    }

    private void extract(String asset, File dest) throws Exception {
        InputStream in = ctx.getAssets().open(asset);
        try {
            TarGz.extract(in, dest, new TarGz.Log() {
                @Override public void log(String line) { GatewayRunner.this.log(line); }
            });
        } finally {
            try { in.close(); } catch (Throwable ignore) { }
        }
    }

    private void copyAsset(String name, File dest, boolean exec) throws Exception {
        AssetManager am = ctx.getAssets();
        InputStream in = am.open(name);
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        FileOutputStream out = new FileOutputStream(dest);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { out.close(); } catch (Throwable ignore) { }
            try { in.close(); } catch (Throwable ignore) { }
        }
        if (exec) dest.setExecutable(true, false);
    }

    /**
     * proot 可执行文件的位置。
     *
     * Android 10 起应用私有 data 目录里的文件不允许 execve，
     * 只有 APK 的 lib/<abi>/ 会被系统解压到可执行的 nativeLibraryDir。
     * 所以 proot 必须以 libproot.so 的形式随 APK 安装，而不是放 assets。
     */
    private File prootBinary() {
        String dir = ctx.getApplicationInfo().nativeLibraryDir;
        File p = new File(dir, "libproot.so");
        if (p.isFile()) return p;
        // 极少数 ROM 不按 .so 解压，退回 assets 里的副本（低版本系统可用）
        File fallback = new File(runtimeDir(), PROOT);
        if (fallback.isFile()) return fallback;
        return p;
    }

    // ---------------- 启动 ----------------

    private void launch(int port) throws Exception {
        File runtime = runtimeDir();
        File rootfs = rootfsDir();
        File proot = prootBinary();

        SharedPreferences sp = ctx.getSharedPreferences("gateway", Context.MODE_PRIVATE);
        String adminToken = sp.getString("admin_token", "");
        String apiKey = sp.getString("api_key", "");

        List<String> cmd = new ArrayList<String>();
        cmd.add(proot.getAbsolutePath());
        cmd.add("--kill-on-exit");
        cmd.add("--link2symlink");
        cmd.add("--sysvipc");
        cmd.add("-L");
        cmd.add("-0");
        cmd.add("--rootfs=" + rootfs.getAbsolutePath());
        cmd.add("--cwd=/root/app");
        cmd.add("-b");
        cmd.add(proot.getAbsolutePath() + ":/opt/proot");
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-b");
        cmd.add("/system");
        cmd.add("-b");
        cmd.add("/apex");
        cmd.add("-b");
        cmd.add("/storage/emulated/0:/sdcard");
        cmd.add("-b");
        cmd.add(new File(rootfs, "dev/shm").getAbsolutePath() + ":/dev/shm");
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        cmd.add("HOME=/root");
        cmd.add("USER=root");
        cmd.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        cmd.add("LANG=C.UTF-8");
        cmd.add("TMPDIR=/tmp");
        cmd.add("PYTHONPATH=/root/app");
        cmd.add("PYTHONHOME=/usr");
        cmd.add("PYTHONUNBUFFERED=1");
        cmd.add("GATEWAY_HOST=127.0.0.1");
        cmd.add("GATEWAY_PORT=" + port);
        cmd.add("GATEWAY_DATA_DIR=/root/app/data");
        cmd.add("GATEWAY_UPSTREAM=mock");
        if (adminToken.length() > 0) cmd.add("GATEWAY_ADMIN_TOKEN=" + adminToken);
        if (apiKey.length() > 0) cmd.add("GATEWAY_API_KEY=" + apiKey);
        cmd.add("/usr/bin/python3");
        cmd.add("-m");
        cmd.add("uvicorn");
        cmd.add("gateway.app:app");
        cmd.add("--host");
        cmd.add("127.0.0.1");
        cmd.add("--port");
        cmd.add(String.valueOf(port));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        // proot 需要能找到自己依赖的 libtalloc 等（rootfs 内的库在 guest 生效，
        // 宿主机侧这些 .so 已在 rootfs/lib 里，用 LD_LIBRARY_PATH 指过去）
        // proot 自己依赖 libtalloc / libandroid-shmem，它们在 nativeLibraryDir；
        // rootfs 里的 musl 库是给客体用的，不能混进来，否则 proot 会加载失败。
        pb.environment().put("LD_LIBRARY_PATH",
                ctx.getApplicationInfo().nativeLibraryDir);
        pb.environment().put("PROOT_TMP_DIR", new File(runtime, "tmp").getAbsolutePath());
        new File(runtime, "tmp").mkdirs();

        // proot 的 --link2symlink 依赖一个独立的 loader 可执行文件，
        // 默认路径编死在 Termux 目录下，必须显式指过去，否则 guest 里
        // 所有符号链接都会失败（rootfs 里有 567 个）。
        File loader = new File(runtime, PROOT_LOADER);
        if (loader.isFile()) {
            loader.setExecutable(true, false);
            pb.environment().put("PROOT_LOADER", loader.getAbsolutePath());
        } else {
            log("警告：缺少 proot-loader，符号链接可能失效");
        }

        cb.onState("启动中…");
        log("启动 uvicorn :" + port);
        proc = pb.start();
        final Process p = proc;
        new Thread(new Runnable() {
            @Override public void run() { drain(p.getInputStream(), "gateway"); }
        }, "gateway-log").start();

        waitHealthy(port);
    }

    /** 轮询 /health，最长约 90 秒。 */
    private void waitHealthy(int port) throws Exception {
        String url = "http://127.0.0.1:" + port + "/health";
        long deadline = System.currentTimeMillis() + 90_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!running.get()) return;
            Process p = proc;
            if (p == null) return;
            // 进程已退出（启动失败）就别再等了
            if (!stillAlive(p)) {
                cb.onState("进程已退出，请查看日志");
                running.set(false);
                return;
            }
            if (httpOk(url)) {
                cb.onState("运行中");
                cb.onReady(port);
                return;
            }
            Thread.sleep(1200L);
        }
        cb.onState("等待就绪超时，请查看日志");
    }

    private boolean stillAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean httpOk(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            c.setRequestMethod("GET");
            return c.getResponseCode() == 200;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void drain(InputStream in, String tag) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line = r.readLine()) != null) {
                log("[" + tag + "] " + line);
            }
        } catch (Throwable ignore) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignore) { }
        }
    }

    private void log(String s) {
        cb.onLog(s);
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        f.delete();
    }
}
