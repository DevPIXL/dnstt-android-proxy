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
    
    // Listen for logs and status updates from the Service
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

        // --- UI Setup ---
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        EditText domainInput = new EditText(this);
        domainInput.setHint("Domain (e.g. t.mamadoo.shop)");
        domainInput.setText("t.mamadoo.shop"); 

        EditText keyInput = new EditText(this);
        keyInput.setHint("Paste Public Key Here");
        
        btnStart = new Button(this);
        // Check if service is already running to set button text correctly
        btnStart.setText(ProxyService.isRunning ? "Stop Tunnel" : "Start Tunnel");

        logView = new TextView(this);
        // Restore logs if service was already running
        logView.setText("Logs:\n" + ProxyService.lastLog);
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

        // --- Button Logic ---
        btnStart.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, ProxyService.class);
            
            if (ProxyService.isRunning) {
                // If running, send STOP command
                serviceIntent.setAction("STOP");
                startService(serviceIntent);
            } else {
                // If stopped, send START command with data
                serviceIntent.putExtra("domain", domainInput.getText().toString());
                serviceIntent.putExtra("key", keyInput.getText().toString());
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                btnStart.setText("Stop Tunnel");
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Register receiver to get updates while screen is on
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.example.dnstt.LOG_UPDATE");
        filter.addAction("com.example.dnstt.STATUS_UPDATE");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, filter);
        }
        
        // Sync button state
        btnStart.setText(ProxyService.isRunning ? "Stop Tunnel" : "Start Tunnel");
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Unregister to save battery when app is minimized (Service keeps running)
        unregisterReceiver(logReceiver);
    }

    private void log(String message) {
        if (message == null) return;
        logView.append("\n" + message);
        logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
