package com.modelgateway.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Model Gateway 的 Android 外壳。
 *
 * 结构：
 *   assets/rootfs.tar.xz  —— Alpine + Python 3.12 + fastapi 的完整运行环境
 *   assets/proot          —— proot 主程序（arm64）
 *   assets/proot-loader   —— proot 的 ld 预加载组件（--link2symlink 需要）
 *   assets/app.tar.xz     —— 网关本体（gateway + admin）
 *
 * 启动流程：首次启动把 rootfs 解压到内部存储 → 用 proot 拉起 uvicorn →
 * 轮询 /health 直到就绪 → 在 WebView 里打开管理后台。
 */
public class MainActivity extends Activity {

    private static final int DEFAULT_PORT = 8080;

    /** 运行状态。 */
    private TextView statusText;
    private TextView urlText;
    private TextView logText;
    private Button startBtn;
    private Button stopBtn;
    private Button openBtn;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private GatewayRunner runner;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        runner = new GatewayRunner(this, new GatewayRunner.Callback() {
            @Override public void onLog(final String line) {
                ui.post(new Runnable() {
                    @Override public void run() { appendLog(line); }
                });
            }
            @Override public void onState(final String state) {
                ui.post(new Runnable() {
                    @Override public void run() { setStatus(state); }
                });
            }
            @Override public void onReady(final int port) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        refreshButtons();
                        setStatus("运行中 · http://127.0.0.1:" + port);
                        urlText.setText(baseUrl(port));
                    }
                });
            }
        });
        setStatus("未启动");
        urlText.setText("");
        refreshButtons();
    }

    // ---------------- 界面 ----------------

    private void buildUi() {
        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Model Gateway");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("OpenAI 兼容中转站 · 多账号池 · 自动签到");
        sub.setTextSize(13);
        sub.setPadding(0, 0, 0, dp(12));
        root.addView(sub);

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setPadding(0, dp(6), 0, dp(6));
        root.addView(statusText);

        urlText = new TextView(this);
        urlText.setTextSize(13);
        urlText.setPadding(0, 0, 0, dp(12));
        urlText.setTextIsSelectable(true);
        root.addView(urlText);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        startBtn = new Button(this);
        startBtn.setText("启动");
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doStart(); }
        });
        stopBtn = new Button(this);
        stopBtn.setText("停止");
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doStop(); }
        });
        openBtn = new Button(this);
        openBtn.setText("打开后台");
        openBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAdmin(); }
        });
        row1.addView(startBtn, weight());
        row1.addView(stopBtn, weight());
        row1.addView(openBtn, weight());
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button tokenBtn = new Button(this);
        tokenBtn.setText("设置口令");
        tokenBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editSetting(KEY_ADMIN_TOKEN, "后台登录口令", true); }
        });
        Button keyBtn = new Button(this);
        keyBtn.setText("设置 API Key");
        keyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editSetting(KEY_API_KEY, "客户端 API Key", true); }
        });
        Button portBtn = new Button(this);
        portBtn.setText("端口");
        portBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editPort(); }
        });
        row2.addView(tokenBtn, weight());
        row2.addView(keyBtn, weight());
        row2.addView(portBtn, weight());
        root.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        Button copyBtn = new Button(this);
        copyBtn.setText("复制接入地址");
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyBaseUrl(); }
        });
        Button reinstallBtn = new Button(this);
        reinstallBtn.setText("重建运行时");
        reinstallBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmReinstall(); }
        });
        row3.addView(copyBtn, weight());
        row3.addView(reinstallBtn, weight());
        root.addView(row3);

        TextView logTitle = new TextView(this);
        logTitle.setText("日志");
        logTitle.setTextSize(13);
        logTitle.setPadding(0, dp(14), 0, dp(4));
        root.addView(logTitle);

        logText = new TextView(this);
        logText.setTextSize(11);
        logText.setPadding(dp(8), dp(8), dp(8), dp(8));
        logText.setTextIsSelectable(true);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setBackgroundColor(0xFF101418);
        logText.setTextColor(0xFFB8C4CE);
        logText.setMinHeight(dp(200));
        root.addView(logText);

        setContentView(scroll);
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void setStatus(String s) {
        statusText.setText("状态：" + s);
    }

    private void appendLog(String line) {
        String cur = logText.getText().toString();
        String next = cur.length() == 0 ? line : cur + "\n" + line;
        if (next.length() > 20000) next = next.substring(next.length() - 20000);
        logText.setText(next);
    }

    private void refreshButtons() {
        startBtn.setEnabled(!busy && !runner.isRunning());
        stopBtn.setEnabled(runner.isRunning());
        openBtn.setEnabled(runner.isRunning());
    }

    // ---------------- 动作 ----------------

    private static final String KEY_ADMIN_TOKEN = "admin_token";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_PORT = "port";

    private SharedPreferences prefs() {
        return getSharedPreferences("gateway", MODE_PRIVATE);
    }

    private int port() {
        return prefs().getInt(KEY_PORT, DEFAULT_PORT);
    }

    private String baseUrl(int port) {
        return "http://127.0.0.1:" + port;
    }

    private void doStart() {
        if (busy || runner.isRunning()) return;
        busy = true;
        refreshButtons();
        final int port = port();
        runner.start(port);
    }

    private void doStop() {
        runner.stop();
        setStatus("已停止");
        busy = false;
        refreshButtons();
    }

    private void openAdmin() {
        Intent i = new Intent(this, AdminWebActivity.class);
        i.putExtra("url", baseUrl(port()) + "/admin");
        startActivity(i);
    }

    private void copyBaseUrl() {
        String u = baseUrl(port()) + "/v1";
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("base_url", u));
        Toast.makeText(this, "已复制 " + u, Toast.LENGTH_SHORT).show();
    }

    private void editSetting(final String key, String hint, boolean secret) {
        final EditText et = new EditText(this);
        et.setHint(hint);
        if (secret) et.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setText(prefs().getString(key, ""));
        new AlertDialog.Builder(this)
                .setTitle(hint)
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    prefs().edit().putString(key, et.getText().toString().trim()).apply();
                    Toast.makeText(this, "已保存，重启服务后生效", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void editPort() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(port()));
        new AlertDialog.Builder(this)
                .setTitle("监听端口")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        int p = Integer.parseInt(et.getText().toString().trim());
                        if (p < 1024 || p > 65535) throw new NumberFormatException();
                        prefs().edit().putInt(KEY_PORT, p).apply();
                        Toast.makeText(this, "重启服务后生效", Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        Toast.makeText(this, "端口需 1024-65535", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmReinstall() {
        new AlertDialog.Builder(this)
                .setTitle("重建运行时")
                .setMessage("会删除已解压的运行时并重新释放（数据库与凭据保留）。服务需重启。")
                .setPositiveButton("重建", (d, w) -> {
                    doStop();
                    runner.deleteRuntime();
                    setStatus("运行时已清除，重启后重新解压");
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
