package com.devpixl.dnstt;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView logView;
    private ScrollView logScrollView;
    private MaterialButton btnStart;
    private TextInputEditText domainInput, keyInput, dnsInput;
    private DrawerLayout drawerLayout;
    private ListView configListView;
    private ArrayAdapter<String> configAdapter;
    private List<Config> configList = new ArrayList<>();

    private static class Config {
        String name;
        String domain;
        String key;
        String dns;

        Config(String name, String domain, String key, String dns) {
            this.name = name;
            this.domain = domain;
            this.key = key;
            this.dns = dns;
        }
    }

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.devpixl.dnstt.LOG_UPDATE".equals(intent.getAction())) {
                log(intent.getStringExtra("log"));
            } else if ("com.devpixl.dnstt.STATUS_UPDATE".equals(intent.getAction())) {
                boolean running = intent.getBooleanExtra("running", false);
                btnStart.setText(running ? "Stop Tunnel" : "Start Tunnel");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        drawerLayout = new DrawerLayout(this);

        // --- Main Content ---
        LinearLayout mainContent = new LinearLayout(this);
        mainContent.setOrientation(LinearLayout.VERTICAL);
        mainContent.setLayoutParams(new DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.MATCH_PARENT,
                DrawerLayout.LayoutParams.MATCH_PARENT));

        // 1. Toolbar
        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("DNSTT Runner");
        toolbar.setElevation(8f);

        mainContent.addView(toolbar);
        setSupportActionBar(toolbar);

        // Enable the Hamburger Menu icon
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_menu);
        }

        // 2. Body
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(50, 50, 50, 50);

        TextInputLayout domainLayout = createInputLayout("Domain (e.g. t.example.com)");
        domainInput = (TextInputEditText) domainLayout.getEditText();

        TextInputLayout keyLayout = createInputLayout("Paste Public Key Here");
        keyInput = (TextInputEditText) keyLayout.getEditText();

        TextInputLayout dnsLayout = createInputLayout("UDP DNS (e.g. 8.8.8.8:53)");
        dnsInput = (TextInputEditText) dnsLayout.getEditText();
        dnsInput.setText("8.8.8.8:53");

        body.addView(domainLayout);
        body.addView(keyLayout);
        body.addView(dnsLayout);

        btnStart = new MaterialButton(this);
        btnStart.setText(ProxyService.isRunning ? "Stop Tunnel" : "Start Tunnel");

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(0, 20, 0, 20);
        btnStart.setLayoutParams(btnParams);

        body.addView(btnStart);

        logView = new TextView(this);
        logView.setText("Logs:\n" + ProxyService.logBuffer.toString());
        logView.setTextIsSelectable(true);

        logScrollView = new ScrollView(this);
        logScrollView.setId(View.generateViewId());
        logScrollView.addView(logView);

        body.addView(logScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));

        mainContent.addView(body);

        // --- Drawer Content ---
        LinearLayout drawerContainer = new LinearLayout(this);
        drawerContainer.setOrientation(LinearLayout.VERTICAL);

        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true);
        drawerContainer.setBackgroundColor(typedValue.data);

        drawerContainer.setClickable(true);
        drawerContainer.setFocusable(true);

        DrawerLayout.LayoutParams drawerParams = new DrawerLayout.LayoutParams(
                750,
                DrawerLayout.LayoutParams.MATCH_PARENT);
        drawerParams.gravity = Gravity.START;
        drawerContainer.setLayoutParams(drawerParams);
        drawerContainer.setPadding(40, 60, 40, 40);

        TextView drawerTitle = new TextView(this);
        drawerTitle.setText("Saved Configs");
        drawerTitle.setTextSize(22);
        drawerTitle.setPadding(0, 0, 0, 40);

        configListView = new ListView(this);
        configAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        configListView.setAdapter(configAdapter);

        // Click to Load
        configListView.setOnItemClickListener((parent, view, position, id) -> {
            loadConfigToInputs(configList.get(position));
            drawerLayout.closeDrawers();
        });

        // Long Click to show Options Menu
        configListView.setOnItemLongClickListener((parent, view, position, id) -> {
            showConfigOptionsMenu(view, position);
            return true;
        });

        drawerContainer.addView(drawerTitle);
        drawerContainer.addView(configListView);

        drawerLayout.addView(mainContent);
        drawerLayout.addView(drawerContainer);

        setContentView(drawerLayout);

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
                serviceIntent.putExtra("dns", dnsInput.getText().toString());

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        // Handle Hamburger Menu Click
        if (id == android.R.id.home) {
            drawerLayout.openDrawer(Gravity.LEFT);
            return true;
        }

        if (id == R.id.action_save) {
            promptSaveConfig();
            return true;
        } else if (id == R.id.action_import) {
            importFromClipboard();
            return true;
        } else if (id == R.id.action_export) {
            exportToClipboard();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void showConfigOptionsMenu(View view, int position) {
        PopupMenu popup = new PopupMenu(this, view);
        getMenuInflater().inflate(R.menu.config_item_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.action_delete_config) {
                confirmDeleteConfig(position);
                return true;
            } else if (itemId == R.id.action_share_config) {
                exportConfigToClipboard(configList.get(position));
                return true;
            }
            return false;
        });
        popup.show();
    }

    private TextInputLayout createInputLayout(String hint) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(hint);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 30);
        layout.setLayoutParams(params);

        TextInputEditText editText = new TextInputEditText(layout.getContext());
        layout.addView(editText);
        return layout;
    }

    private void promptSaveConfig() {
        final EditText nameInput = new EditText(this);
        nameInput.setHint("Config Name");

        new MaterialAlertDialogBuilder(this)
                .setTitle("Save Config")
                .setView(nameInput)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = nameInput.getText().toString();
                    if (!name.isEmpty()) {
                        saveConfig(new Config(
                            name,
                            domainInput.getText().toString(),
                            keyInput.getText().toString(),
                            dnsInput.getText().toString()
                        ));
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
        new MaterialAlertDialogBuilder(this)
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
        dnsInput.setText(c.dns);
    }

    private void updateDrawerList() {
        configAdapter.clear();
        for (Config c : configList) {
            configAdapter.add(c.name);
        }
        configAdapter.notifyDataSetChanged();
    }

    private void saveConfigsToStorage() {
        try {
            JSONArray arr = new JSONArray();
            for (Config c : configList) {
                JSONObject obj = new JSONObject();
                obj.put("name", c.name);
                obj.put("domain", c.domain);
                obj.put("key", c.key);
                obj.put("dns", c.dns);
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
                String dns = obj.has("dns") ? obj.getString("dns") : "8.8.8.8:53";
                configList.add(new Config(obj.getString("name"), obj.getString("domain"), obj.getString("key"), dns));
            }
            updateDrawerList();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Exports inputs from UI (cannot export name as it's not on screen)
    private void exportToClipboard() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("domain", domainInput.getText().toString());
            obj.put("key", keyInput.getText().toString());
            obj.put("dns", dnsInput.getText().toString());
            String json = obj.toString();
            copyToClipboard(json);
        } catch (Exception e) {
            Toast.makeText(this, "Error exporting", Toast.LENGTH_SHORT).show();
        }
    }

    // [CHANGE] Now includes "name" in export
    private void exportConfigToClipboard(Config c) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("name", c.name); // Export name too
            obj.put("domain", c.domain);
            obj.put("key", c.key);
            obj.put("dns", c.dns);
            String json = obj.toString();
            copyToClipboard(json);
        } catch (Exception e) {
            Toast.makeText(this, "Error exporting", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("DNSTT Config", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Copied to Clipboard", Toast.LENGTH_SHORT).show();
    }

    // [CHANGE] Auto-saves imported config and reads "name"
    private void importFromClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                String json = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
                JSONObject obj = new JSONObject(json);

                String domain = obj.getString("domain");
                String key = obj.getString("key");
                String dns = obj.has("dns") ? obj.getString("dns") : "8.8.8.8:53";

                // Populate UI
                domainInput.setText(domain);
                keyInput.setText(key);
                dnsInput.setText(dns);

                // Determine Name
                String name;
                if (obj.has("name") && !obj.getString("name").trim().isEmpty()) {
                    name = obj.getString("name");
                } else {
                    name = "Imported Config " + (configList.size() + 1);
                }

                // Auto-Save
                saveConfig(new Config(name, domain, key, dns));

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
        filter.addAction("com.devpixl.dnstt.LOG_UPDATE");
        filter.addAction("com.devpixl.dnstt.STATUS_UPDATE");
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
