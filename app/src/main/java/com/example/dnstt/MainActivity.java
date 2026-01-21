package com.example.dnstt;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView logView;
    private ScrollView logScrollView;
    private Button btnStart;
    private EditText domainInput, keyInput;
    private DrawerLayout drawerLayout;
    private ListView configListView;
    private ArrayAdapter<String> configAdapter;
    private List<Config> configList = new ArrayList<>();

    // Internal Config Class
    private static class Config {
        String name;
        String domain;
        String key;

        Config(String name, String domain, String key) {
            this.name = name;
            this.domain = domain;
            this.key = key;
        }

        @Override
        public String toString() { return name; } // Used by ListView
    }

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

        // --- Root Layout: DrawerLayout ---
        drawerLayout = new DrawerLayout(this);
        
        // --- Main Content (The UI you see) ---
        LinearLayout mainContent = new LinearLayout(this);
        mainContent.setOrientation(LinearLayout.VERTICAL);
        mainContent.setLayoutParams(new DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.MATCH_PARENT, 
                DrawerLayout.LayoutParams.MATCH_PARENT));

        // 1. Custom Toolbar
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackgroundColor(Color.parseColor("#EEEEEE")); // Light Gray Header
        toolbar.setPadding(20, 20, 20, 20);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);

        ImageButton btnMenu = new ImageButton(this);
        btnMenu.setImageResource(R.drawable.ic_menu);
        btnMenu.setBackgroundColor(Color.TRANSPARENT);
        btnMenu.setOnClickListener(v -> drawerLayout.openDrawer(Gravity.LEFT));

        TextView title = new TextView(this);
        title.setText("DNSTT Runner");
        title.setTextSize(18);
        title.setPadding(30, 0, 0, 0);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        title.setTextColor(Color.BLACK);

        ImageButton btnOptions = new ImageButton(this);
        btnOptions.setImageResource(R.drawable.ic_add); // Using 'Plus' icon for options
        btnOptions.setBackgroundColor(Color.TRANSPARENT);
        btnOptions.setColorFilter(Color.BLACK); // Tint black
        btnMenu.setColorFilter(Color.BLACK);    // Tint black
        btnOptions.setOnClickListener(this::showOptionsMenu);

        toolbar.addView(btnMenu);
        toolbar.addView(title);
        toolbar.addView(btnOptions);

        // 2. Inputs & Logs
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(50, 50, 50, 50);

        domainInput = new EditText(this);
        domainInput.setHint("Domain (e.g. t.example.com)");
        // REMOVED HARDCODED TEXT

        keyInput = new EditText(this);
        keyInput.setHint("Paste Public Key Here");
        
        btnStart = new Button(this);
        btnStart.setText(ProxyService.isRunning ? "Stop Tunnel" : "Start Tunnel");

        logView = new TextView(this);
        logView.setText("Logs:\n" + ProxyService.logBuffer.toString());
        logView.setTextIsSelectable(true);
        
        logScrollView = new ScrollView(this);
        logScrollView.addView(logView);
        
        body.addView(domainInput);
        body.addView(keyInput);
        body.addView(btnStart);
        body.addView(logScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.MATCH_PARENT
        ));

        mainContent.addView(toolbar);
        mainContent.addView(body);

        // --- Drawer Content (The Config List) ---
        LinearLayout drawerContainer = new LinearLayout(this);
        drawerContainer.setOrientation(LinearLayout.VERTICAL);
        drawerContainer.setBackgroundColor(Color.WHITE);
        DrawerLayout.LayoutParams drawerParams = new DrawerLayout.LayoutParams(
                600, // Width in pixels (approx 200dp)
                DrawerLayout.LayoutParams.MATCH_PARENT);
        drawerParams.gravity = Gravity.START;
        drawerContainer.setLayoutParams(drawerParams);
        drawerContainer.setPadding(20, 50, 20, 20);

        TextView drawerTitle = new TextView(this);
        drawerTitle.setText("Saved Configs");
        drawerTitle.setTextSize(20);
        drawerTitle.setPadding(0, 0, 0, 30);
        drawerTitle.setTextColor(Color.BLACK);

        configListView = new ListView(this);
        configAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        configListView.setAdapter(configAdapter);
        
        configListView.setOnItemClickListener((parent, view, position, id) -> {
            loadConfigToInputs(configList.get(position));
            drawerLayout.closeDrawers();
        });
        
        // Long press to delete
        configListView.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDeleteConfig(position);
            return true;
        });

        drawerContainer.addView(drawerTitle);
        drawerContainer.addView(configListView);

        // --- Assemble Root ---
        drawerLayout.addView(mainContent);
        drawerLayout.addView(drawerContainer);

        setContentView(drawerLayout);

        // --- Logic ---
        loadConfigsFromStorage();

        btnStart.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, ProxyService.class);
            if (ProxyService.isRunning) {
                btnStart.setText("Start Tunnel"); 
                serviceIntent.setAction("STOP");
                startService(serviceIntent);
            } else {
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

    // --- Options Menu (Plus Icon) ---
    private void showOptionsMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenu().add("Save Current Config");
        popup.getMenu().add("Import from Clipboard");
        popup.getMenu().add("Export to Clipboard");

        popup.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.equals("Save Current Config")) {
                promptSaveConfig();
            } else if (title.equals("Import from Clipboard")) {
                importFromClipboard();
            } else if (title.equals("Export to Clipboard")) {
                exportToClipboard();
            }
            return true;
        });
        popup.show();
    }

    // --- Config Logic ---

    private void promptSaveConfig() {
        final EditText nameInput = new EditText(this);
        nameInput.setHint("Config Name");

        new AlertDialog.Builder(this)
                .setTitle("Save Config")
                .setView(nameInput)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = nameInput.getText().toString();
                    if (!name.isEmpty()) {
                        saveConfig(new Config(name, domainInput.getText().toString(), keyInput.getText().toString()));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveConfig(Config newConfig) {
        configList.add(newConfig);
        saveConfigsToStorage();
        updateDrawerList();
        Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
    }

    private void confirmDeleteConfig(int position) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Config?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    configList.remove(position);
                    saveConfigsToStorage();
                    updateDrawerList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void loadConfigToInputs(Config c) {
        domainInput.setText(c.domain);
        keyInput.setText(c.key);
    }

    private void updateDrawerList() {
        configAdapter.clear();
        for (Config c : configList) {
            configAdapter.add(c.name);
        }
        configAdapter.notifyDataSetChanged();
    }

    // --- Storage (SharedPreferences + JSON) ---

    private void saveConfigsToStorage() {
        try {
            JSONArray arr = new JSONArray();
            for (Config c : configList) {
                JSONObject obj = new JSONObject();
                obj.put("name", c.name);
                obj.put("domain", c.domain);
                obj.put("key", c.key);
                arr.put(obj);
            }
            SharedPreferences prefs = getSharedPreferences("dnstt_prefs", MODE_PRIVATE);
            prefs.edit().putString("saved_configs", arr.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadConfigsFromStorage() {
        configList.clear();
        try {
            SharedPreferences prefs = getSharedPreferences("dnstt_prefs", MODE_PRIVATE);
            String jsonStr = prefs.getString("saved_configs", "[]");
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                configList.add(new Config(obj.getString("name"), obj.getString("domain"), obj.getString("key")));
            }
            updateDrawerList();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- Clipboard ---

    private void exportToClipboard() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("domain", domainInput.getText().toString());
            obj.put("key", keyInput.getText().toString());
            String json = obj.toString();

            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("DNSTT Config", json);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Copied to Clipboard", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error exporting", Toast.LENGTH_SHORT).show();
        }
    }

    private void importFromClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard.hasPrimaryClip()) {
                String json = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
                JSONObject obj = new JSONObject(json);
                domainInput.setText(obj.getString("domain"));
                keyInput.setText(obj.getString("key"));
                Toast.makeText(this, "Imported!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Clipboard empty", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Invalid Config Format", Toast.LENGTH_SHORT).show();
        }
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
        logView.append(message + "\n");
        logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
