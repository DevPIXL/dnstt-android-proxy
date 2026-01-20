package com.example.dnstt;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

public class MainActivity extends AppCompatActivity {

    private Process proxyProcess;
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Simple Layout Programmatically (to avoid needing XML layout files)
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        EditText domainInput = new EditText(this);
        domainInput.setHint("Domain (e.g. t.mamadoo.shop)");
        domainInput.setText("t.mamadoo.shop"); // Default

        EditText keyInput = new EditText(this);
        keyInput.setHint("Paste Public Key Here");
        
        Button btnStart = new Button(this);
        btnStart.setText("Start Tunnel (1080)");

        logView = new TextView(this);
        logView.setText("Logs will appear here...");

        layout.addView(domainInput);
        layout.addView(keyInput);
        layout.addView(btnStart);
        layout.addView(logView);
        setContentView(layout);

        btnStart.setOnClickListener(v -> {
            if (proxyProcess != null) {
                proxyProcess.destroy();
                proxyProcess = null;
            }
            startTunnel(domainInput.getText().toString(), keyInput.getText().toString());
        });
    }

    private void startTunnel(String domain, String pubKeyContent) {
        new Thread(() -> {
            try {
                // 1. Write the Key to a temporary file
                File keyFile = new File(getCacheDir(), "pub.key");
                FileOutputStream fos = new FileOutputStream(keyFile);
                fos.write(pubKeyContent.getBytes());
                fos.close();

                // 2. Locate the Binary (libdnstt.so)
                String binaryPath = getApplicationInfo().nativeLibraryDir + "/libdnstt.so";
                
                // 3. Build Command
                // ./dnstt-client -udp 8.8.8.8:53 -pubkey-file [FILE] [DOMAIN] 127.0.0.1:1080
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

                runOnUiThread(() -> logView.setText("Starting..."));

                BufferedReader reader = new BufferedReader(new InputStreamReader(proxyProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    String finalLine = line;
                    runOnUiThread(() -> logView.append("\n" + finalLine));
                }

            } catch (Exception e) {
                runOnUiThread(() -> logView.setText("Error: " + e.getMessage()));
            }
        }).start();
    }
}
