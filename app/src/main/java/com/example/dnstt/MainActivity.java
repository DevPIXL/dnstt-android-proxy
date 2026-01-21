package com.example.dnstt;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

public class MainActivity extends AppCompatActivity {

    private Process proxyProcess;
    private TextView logView;
    private ScrollView logScrollView;
    private boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Main Layout
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        // Inputs
        EditText domainInput = new EditText(this);
        domainInput.setHint("Domain (e.g. t.example.com)");
        domainInput.setText("t.mamadoo.shop"); 

        EditText keyInput = new EditText(this);
        keyInput.setHint("Paste Public Key Here");
        
        Button btnStart = new Button(this);
        btnStart.setText("Start Tunnel");

        // Log Area (Scrollable & Selectable)
        logView = new TextView(this);
        logView.setText("Logs will appear here...");
        logView.setTextIsSelectable(true); // <--- MAKES IT COPIABLE
        
        logScrollView = new ScrollView(this);
        logScrollView.addView(logView);
        
        // Add to layout (Inputs at top, logs take remaining space)
        layout.addView(domainInput);
        layout.addView(keyInput);
        layout.addView(btnStart);
        layout.addView(logScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(layout);

        btnStart.setOnClickListener(v -> {
            if (isRunning) {
                killProcess();
                btnStart.setText("Start Tunnel");
                log("\n--- Stopped ---");
            } else {
                startTunnel(domainInput.getText().toString(), keyInput.getText().toString());
                btnStart.setText("Stop Tunnel");
            }
        });
    }

    private void startTunnel(String domain, String pubKeyContent) {
        isRunning = true;
        new Thread(() -> {
            try {
                // 1. Write the Key to a temporary file
                File keyFile = new File(getCacheDir(), "pub.key");
                FileOutputStream fos = new FileOutputStream(keyFile);
                fos.write(pubKeyContent.getBytes());
                fos.close();

                // 2. Locate the Binary
                String binaryPath = getApplicationInfo().nativeLibraryDir + "/libdnstt.so";
                
                // 3. Build Command
                String[] cmd = {
                    binaryPath,
                    "-udp", "8.8.8.8:53",
                    "-pubkey-file", keyFile.getAbsolutePath(),
                    domain,
                    "127.0.0.1:1080"
                };

                // 4. Run
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                proxyProcess = pb.start();

                runOnUiThread(() -> log("Starting..."));

                BufferedReader reader = new BufferedReader(new InputStreamReader(proxyProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    String finalLine = line;
                    runOnUiThread(() -> log(finalLine));
                }

            } catch (Exception e) {
                runOnUiThread(() -> log("Error: " + e.getMessage()));
            } finally {
                isRunning = false;
            }
        }).start();
    }

    private void killProcess() {
        if (proxyProcess != null) {
            proxyProcess.destroy();
            proxyProcess = null;
        }
        isRunning = false;
    }

    private void log(String message) {
        logView.append("\n" + message);
        // Auto-scroll to bottom
        logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
