package com.example.dnstt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TextView logView;
    private ScrollView logScrollView;
    private Button btnStart;
    
    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.dnstt.LOG_UPDATE".equals(intent.getAction())) {
                log(intent.getStringExtra("log"));
            } else if ("com.example.dnstt.STATUS_UPDATE".equals(intent.getAction())) {
                boolean running = intent.getBooleanExtra("running", false);
                btnStart.setText(running ? "Stop Tunnel" : "Start Tunnel");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        EditText domainInput = new EditText(this);
        domainInput.setHint("Domain (e.g. t.mamadoo.shop)");
        domainInput.setText("t.mamadoo.shop"); 

        EditText keyInput = new EditText(this);
        keyInput.setHint("Paste Public Key Here");
        
        btnStart = new Button(this);
        btnStart.setText(ProxyService.isRunning ? "Stop Tunnel" : "Start Tunnel");

        logView = new TextView(this);
        // Load the full history from the Service buffer
        logView.setText("Logs:\n" + ProxyService.logBuffer.toString());
        logView.setTextIsSelectable(true);
        
        logScrollView = new ScrollView(this);
        logScrollView.addView(logView);
        
        layout.addView(domainInput);
        layout.addView(keyInput);
        layout.addView(btnStart);
        layout.addView(logScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(layout);

        btnStart.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, ProxyService.class);
            
            if (ProxyService.isRunning) {
                // Optimistic UI Update: Change text immediately
                btnStart.setText("Start Tunnel"); 
                serviceIntent.setAction("STOP");
                startService(serviceIntent);
            } else {
                // Optimistic UI Update: Change text immediately
                btnStart.setText("Stop Tunnel");
                
                serviceIntent.putExtra("domain", domainInput.getText().toString());
                serviceIntent.putExtra("key", keyInput.getText().toString());
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.example.dnstt.LOG_UPDATE");
        filter.addAction("com.example.dnstt.STATUS_UPDATE");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, filter);
        }
        
        // Refresh state in case it changed while paused
        btnStart.setText(ProxyService.isRunning ? "Stop Tunnel" : "Start Tunnel");
        logView.setText("Logs:\n" + ProxyService.logBuffer.toString());
        logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(logReceiver);
    }

    private void log(String message) {
        if (message == null) return;
        logView.append(message + "\n"); // Append new line
        logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
