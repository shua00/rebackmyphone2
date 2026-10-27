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

    public static boolean isForeground = false;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { startForegroundService(svc); }
        else { startService(svc); }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(msgReceiver, new IntentFilter("UPDATE_CHAT_UI"), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(msgReceiver, new IntentFilter("UPDATE_CHAT_UI"));
        }
    }

    private void sendMessage() {
        String msg = inputMsg.getText().toString().trim();
        if (!msg.isEmpty()) {
            inputMsg.setText("");
            
            // 【新增】拦截暗号清空指令
            if ("/clear".equalsIgnoreCase(msg)) {
                SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
                sp.edit().putString("chat_history", "").apply();
                Toast.makeText(this, "🧹 聊天记录已清空", Toast.LENGTH_SHORT).show();
                refreshHistory(false);
                return; // 直接返回，不发送
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
            startService(intent);
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
    protected void onPause() {
        super.onPause();
        isForeground = false;
    }
    
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(msgReceiver);
    }
}
