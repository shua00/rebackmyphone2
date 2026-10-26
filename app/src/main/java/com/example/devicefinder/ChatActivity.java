package com.example.devicefinder;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

public class ChatActivity extends Activity {

    private TextView chatText;
    private ScrollView scrollView;
    private EditText inputMsg;
    private Button btnSend;

    public static boolean isForeground = false;

    private final BroadcastReceiver msgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshHistory();
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

        refreshHistory();

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
            // 【核心修复】先本地落盘并瞬间刷新，解决自己发消息延迟
            SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
            String history = sp.getString("chat_history", "");
            String timeStr = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date());
            String updatedHistory = timeStr + " | [我] " + msg + "\n" + history;
            sp.edit().putString("chat_history", updatedHistory).apply();

            inputMsg.setText("");
            refreshHistory(); // 秒回显

            // 再交给后台服务去发 UDP
            Intent intent = new Intent(this, TrackingService.class);
            intent.setAction("ACTION_SEND_UDP_CHAT");
            intent.putExtra("message", msg);
            startService(intent);
        }
    }

    private void refreshHistory() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        String history = sp.getString("chat_history", "暂无消息...");
        chatText.setText(history);
        chatText.setTextIsSelectable(true); 
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    @Override
    protected void onResume() {
        super.onResume();
        isForeground = true;
        refreshHistory();
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
