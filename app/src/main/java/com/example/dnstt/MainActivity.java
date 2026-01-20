package com.example.dnstt;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;

public class MainActivity extends Activity {

    private TextView logView;
    private Button actionButton;
    private ScrollView scrollView;
    private Process dnsProcess;
    private boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Simple UI Setup
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(30, 30, 30, 30);

        actionButton = new Button(this);
        actionButton.setText("START DNSTT");
        actionButton.setOnClickListener(v -> toggleProcess());
        layout.addView(actionButton);

        scrollView = new ScrollView(this);
        logView = new TextView(this);
        logView.setText("Ready. Press Start.\n");
        logView.setTextSize(14);
        scrollView.addView(logView);
        layout.addView(scrollView);

        setContentView(layout);
    }

    private void toggleProcess() {
        if (isRunning) {
            if (dnsProcess != null) dnsProcess.destroy();
            isRunning = false;
            actionButton.setText("START DNSTT");
            log("Stopped.");
        } else {
            startDnstt();
        }
    }

    private void startDnstt() {
        log("Initializing...");
        actionButton.setEnabled(false);
        
        new Thread(() -> {
            try {
                // 1. Setup Binary
                File binFile = new File(getFilesDir(), "dnstt-client");
                if (!binFile.exists()) copyAsset("dnstt-client", binFile, true);

                // 2. Setup Key
                File keyFile = new File(getFilesDir(), "pub.key");
                if (!keyFile.exists()) copyAsset("pub.key", keyFile, false);

                // 3. Build Command
                String[] cmd = {
                    binFile.getAbsolutePath(),
                    "-udp", "8.8.8.8:53",
                    "-pubkey-file", keyFile.getAbsolutePath(),
                    "t.mamadoo.shop",
                    "127.0.0.1:1080"
                };

                // 4. Run
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                dnsProcess = pb.start();
                isRunning = true;

                runOnUiThread(() -> {
                    actionButton.setText("STOP");
                    actionButton.setEnabled(true);
                    log(">>> RUNNING <<<");
                });

                // 5. Log Loop
                BufferedReader reader = new BufferedReader(new InputStreamReader(dnsProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    String finalLine = line;
                    runOnUiThread(() -> log(finalLine));
                }

            } catch (Exception e) {
                runOnUiThread(() -> {
                    log("Error: " + e.getMessage());
                    actionButton.setEnabled(true);
                });
            }
        }).start();
    }

    private void copyAsset(String name, File dest, boolean executable) throws IOException {
        try (InputStream in = getAssets().open(name);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        if (executable) dest.setExecutable(true);
    }

    private void log(String text) {
        logView.append(text + "\n");
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }
}
