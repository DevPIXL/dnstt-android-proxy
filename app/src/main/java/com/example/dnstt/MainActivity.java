package com.example.dnstt;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
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

        // --- UI SETUP ---
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(30, 30, 30, 30);

        // 1. Button Container (Horizontal)
        LinearLayout buttonLayout = new LinearLayout(this);
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
        
        // Start Button
        actionButton = new Button(this);
        actionButton.setText("START");
        actionButton.setOnClickListener(v -> toggleProcess());
        // improved layout params to share space
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        actionButton.setLayoutParams(btnParams);
        buttonLayout.addView(actionButton);

        // Copy Log Button (NEW)
        Button copyButton = new Button(this);
        copyButton.setText("COPY LOGS");
        copyButton.setOnClickListener(v -> copyLogsToClipboard());
        copyButton.setLayoutParams(btnParams);
        buttonLayout.addView(copyButton);

        mainLayout.addView(buttonLayout);

        // 2. Log Window
        scrollView = new ScrollView(this);
        logView = new TextView(this);
        logView.setText("--- Ready ---\n");
        logView.setTextSize(12);
        // Monospace font makes logs easier to read
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        
        scrollView.addView(logView);
        mainLayout.addView(scrollView);

        setContentView(mainLayout);
        
        // Keep screen awake
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // --- BUTTON ACTIONS ---

    private void copyLogsToClipboard() {
        String logs = logView.getText().toString();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("DNSTT Logs", logs);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show();
    }

    private void toggleProcess() {
        if (isRunning) {
            if (dnsProcess != null) dnsProcess.destroy();
            isRunning = false;
            actionButton.setText("START");
            log("\n--- Stopped by user ---");
        } else {
            startDnstt();
        }
    }

    // --- PROCESS LOGIC ---

    private void startDnstt() {
        logView.setText(""); // Clear old logs
        log("Initializing...");
        actionButton.setEnabled(false);
        
        new Thread(() -> {
            try {
                // A. Setup Binary
                File binFile = new File(getFilesDir(), "dnstt-client");
                if (!binFile.exists()) copyAsset("dnstt-client", binFile, true);

                // B. Setup Key
                File keyFile = new File(getFilesDir(), "pub.key");
                if (!keyFile.exists()) copyAsset("pub.key", keyFile, false);

                // C. Build Command
                String[] cmd = {
                    binFile.getAbsolutePath(),
                    "-udp", "8.8.8.8:53",
                    "-pubkey-file", keyFile.getAbsolutePath(),
                    "t.mamadoo.shop",
                    "127.0.0.1:1080"
                };

                // D. Run
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true); // Crucial: merges Errors into the main log
                dnsProcess = pb.start();
                isRunning = true;

                runOnUiThread(() -> {
                    actionButton.setText("STOP");
                    actionButton.setEnabled(true);
                    log(">>> RUNNING (Port 1080) <<<");
                });

                // E. Read Output Loop
                BufferedReader reader = new BufferedReader(new InputStreamReader(dnsProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    String finalLine = line;
                    runOnUiThread(() -> log(finalLine));
                }

            } catch (Exception e) {
                runOnUiThread(() -> {
                    log("\nCRITICAL ERROR: " + e.getMessage());
                    e.printStackTrace(); // Print full error to internal logcat just in case
                    actionButton.setEnabled(true);
                });
            }
        }).start();
    }

    // --- HELPERS ---

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
        // Auto-scroll to bottom
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }
}
