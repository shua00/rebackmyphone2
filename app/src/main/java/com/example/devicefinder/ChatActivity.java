package com.example.devicefinder;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class ChatActivity extends Activity {

    private TextView chatText;
    private ScrollView scrollView;
    private EditText inputMsg;

    // 静态变量，用来告诉 TrackingService：聊天界面是否开着
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

        chatText = findViewById(R.id.chatText);
        scrollView = findViewById(R.id.scrollView);
        inputMsg = findViewById(R.id.inputMsg);
        Button btnSend = findViewById(R.id.btnSend);

        refreshHistory();

        btnSend.setOnClickListener(v -> {
            String msg = inputMsg.getText().toString().trim();
            if (!msg.isEmpty()) {
                Intent intent = new Intent(this, TrackingService.class);
                intent.setAction("ACTION_SEND_UDP_CHAT");
                intent.putExtra("message", msg);
                startService(intent);
                inputMsg.setText("");
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(msgReceiver, new IntentFilter("UPDATE_CHAT_UI"), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(msgReceiver, new IntentFilter("UPDATE_CHAT_UI"));
        }
    }

    private void refreshHistory() {
        SharedPreferences sp = getSharedPreferences("device_finder_prefs", MODE_PRIVATE);
        String history = sp.getString("msg_history", "暂无消息...");
        chatText.setText(history);
        // 滚动到底部
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
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(msgReceiver);
    }
}
