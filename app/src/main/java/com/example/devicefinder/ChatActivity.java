package com.example.devicefinder;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class ChatActivity extends Activity {

    private TextView chatText;
    private ScrollView scrollView;
    private EditText inputMsg;
    private Button btnSend;
    private TextView tvNewMsg; // 【新增】新消息标签

    // [M1] volatile：TrackingService 的 UDP 接收线程读此字段决定是否 sendBroadcast，
    //      若无 volatile，跨线程可见性无保证，可能出现"手机收到消息但聊天页不刷新"
    public static volatile boolean isForeground = false;
    private boolean isAtBottom = true; // 【新增】是否在最底部

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver msgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshHistory(true); // 收到新消息
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        Intent svc = new Intent(this, TrackingService.class);
        svc.setAction("ACTION_RESET_NOTIFICATION");
        // [C1] 防御：Activity 尚未完全可见时，个别 ROM 对 startForegroundService 限制更严，
        //      抛异常会让 Activity 直接崩溃；改为记日志 + 继续，页面仍可打开查看历史
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            android.util.Log.e("ChatActivity", "启动 TrackingService 失败: " + e.getMessage());
        }

        chatText = findViewById(R.id.chatText);
        scrollView = findViewById(R.id.scrollView);
        inputMsg = findViewById(R.id.inputMsg);
        btnSend = findViewById(R.id.btnSend);
        tvNewMsg = findViewById(R.id.tvNewMsg); // 绑定新标签

        // 【新增】监听滚动，判断是否滑到了底部
        scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            int diff = (scrollView.getChildAt(0).getBottom() - (scrollView.getHeight() + scrollView.getScrollY()));
            isAtBottom = diff <= 50; // 留50像素余量
            if (isAtBottom) {
                tvNewMsg.setVisibility(View.GONE);
            }
        });

        // 点击悬浮标签，滑到底部
        tvNewMsg.setOnClickListener(v -> {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN);
            tvNewMsg.setVisibility(View.GONE);
        });

        refreshHistory(false); // 首次进入不弹标签

        btnSend.setOnClickListener(v -> sendMessage());

        inputMsg.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND ||
               (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                sendMessage();
                return true;
            }
            return false;
        });

        // 已有的 API 判断保留，外层加 try/catch 兜底
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(msgReceiver, new IntentFilter("UPDATE_CHAT_UI"), Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(msgReceiver, new IntentFilter("UPDATE_CHAT_UI"));
            }
        } catch (Exception e) {
            android.util.Log.e("ChatActivity", "注册 UPDATE_CHAT_UI 广播失败: " + e.getMessage());
        }
    }

    private void sendMessage() {
        String msg = inputMsg.getText().toString().trim();
        if (!msg.isEmpty()) {
            inputMsg.setText("");

            // 【新增】拦截暗号清空指令
            if ("/clear".equalsIgnoreCase(msg)) {
                SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
                // ★ 2026-11-07 加固：/clear 属低频操作，用 commit() 同步落盘，
                //   避免与 TrackingService 的 UDP 接收线程并发写入时被 apply() 的异步落盘覆盖。
                sp.edit().putString("chat_history", "").commit();
                Toast.makeText(this, "🧹 聊天记录已清空", Toast.LENGTH_SHORT).show();
                // 同步通知后台刷新（虽然 isForeground=true 时本 Activity 自己刷新即可，
                // 但显式广播可保证未来若服务端缓存了聊天快照也能一并清空）
                sendBroadcast(new Intent("UPDATE_CHAT_UI"));
                refreshHistory(false);
                return;
            }

            SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
            String history = sp.getString("chat_history", "");
            String timeStr = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date());
            String newRecord = timeStr + " | [我] " + msg;

            // 本地追加到最后面
            String updatedHistory = history.isEmpty() ? newRecord : history + "\n" + newRecord;
            sp.edit().putString("chat_history", updatedHistory).apply();

            refreshHistory(false); // 自己发的，强制滑到底部

            Intent intent = new Intent(this, TrackingService.class);
            intent.setAction("ACTION_SEND_UDP_CHAT");
            intent.putExtra("message", msg);
            // [C2] 原 startService 在服务被 kill 时会抛 IllegalStateException（Android 8+）；
            //      先按原行为调用，失败再兜底用 startForegroundService 复活服务
            try {
                startService(intent);
            } catch (IllegalStateException e) {
                android.util.Log.w("ChatActivity", "startService 失败，尝试 startForegroundService: " + e.getMessage());
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        throw e; // 老版本不兜底，保持原状
                    }
                } catch (Exception e2) {
                    android.util.Log.e("ChatActivity", "启动 UDP 发送彻底失败: " + e2.getMessage());
                    Toast.makeText(this, "⚠️ 发送失败，请稍后重试", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                android.util.Log.e("ChatActivity", "启动 UDP 发送异常: " + e.getMessage());
                Toast.makeText(this, "⚠️ 发送失败，请稍后重试", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void refreshHistory(boolean isNewMessage) {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        String history = sp.getString("chat_history", "暂无消息...");

        if (!chatText.getText().toString().equals(history)) {
            chatText.setText(history);
            chatText.setTextIsSelectable(true);

            if (!isNewMessage || isAtBottom) {
                // 如果是自己发的，或者当前已经在底部，就自动滑到底部
                scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
                tvNewMsg.setVisibility(View.GONE);
            } else {
                // 如果收到新消息但人在上面看历史，就弹提示
                tvNewMsg.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isForeground = true;
        refreshHistory(false);
    }

@Override
public void onBackPressed() {
    // 按返回键时，关闭界面并从任务列表抹除
    finishAndRemoveTask();
}

@Override
protected void onPause() {
    super.onPause();
    // ★ 2026-11-07 测试补丁：当用户按 Home 键切走，或打开其他应用时
    // 如果界面没有被主动 finish（isFinishing 为 false），强制抹除任务栈卡片
    if (!isFinishing()) {
        finishAndRemoveTask();
    }
}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // [B7] 防御：onCreate 中 registerReceiver 已包 try/catch，
        //      若注册失败或 Activity 生命周期异常未注册就走到 onDestroy，
        //      unregisterReceiver 会抛 IllegalArgumentException 崩溃
        try {
            unregisterReceiver(msgReceiver);
        } catch (Exception ignored) {}
    }
}
