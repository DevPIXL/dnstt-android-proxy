package com.devpixl.dnstt;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
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
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int VPN_REQUEST_CODE = 0x0F;

    private TextView logView;
    private ScrollView logScrollView;
    private ImageButton btnStart;
    private TextView statusView;
    private TextInputEditText domainInput, keyInput, dnsInput;
    private DrawerLayout drawerLayout;
    private ListView configListView;
    private ArrayAdapter<String> configAdapter;
    private List<Config> configList = new ArrayList<>();

    // Status Logic Variables
    private boolean isLogsVisible = false;
    private boolean isTestingPhase = false;
    private int logBatchCount = 0;
    private int streamLogCount = 0;

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
                String logMsg = intent.getStringExtra("log");
                log(logMsg);
                updateStatusLogic(logMsg);
            } else if ("com.devpixl.dnstt.STATUS_UPDATE".equals(intent.getAction())) {
                boolean running = intent.getBooleanExtra("running", false);
                if (!running) {
                    updateUIState("Disconnected");
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        drawerLayout = new DrawerLayout(this);

        // --- Main Content Container ---
        LinearLayout mainContent = new LinearLayout(this);
        mainContent.setOrientation(LinearLayout.VERTICAL);
        mainContent.setLayoutParams(new DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.MATCH_PARENT,
                DrawerLayout.LayoutParams.MATCH_PARENT));

        // 1. Toolbar
        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("");
        toolbar.setElevation(8f);

        // Title
        TextView toolbarTitle = new TextView(this);
        toolbarTitle.setText("DNSTT Runner");
        toolbarTitle.setTextSize(20);
        toolbarTitle.setTypeface(null, Typeface.BOLD);

        Toolbar.LayoutParams titleParams = new Toolbar.LayoutParams(
                Toolbar.LayoutParams.WRAP_CONTENT, Toolbar.LayoutParams.WRAP_CONTENT);
        titleParams.gravity = Gravity.CENTER;
        toolbarTitle.setLayoutParams(titleParams);
        toolbar.addView(toolbarTitle);

        mainContent.addView(toolbar);
        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_menu);
        }

        // 2. Body Container
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // --- Inputs Section ---
        LinearLayout inputsContainer = new LinearLayout(this);
        inputsContainer.setOrientation(LinearLayout.VERTICAL);
        inputsContainer.setPadding(50, 50, 50, 20);

        TextInputLayout domainLayout = createInputLayout("Domain (e.g. t.example.com)");
        domainInput = (TextInputEditText) domainLayout.getEditText();

        TextInputLayout keyLayout = createInputLayout("Paste Public Key Here");
        keyInput = (TextInputEditText) keyLayout.getEditText();

        TextInputLayout dnsLayout = createInputLayout("UDP DNS (e.g. 8.8.8.8:53)");
        dnsInput = (TextInputEditText) dnsLayout.getEditText();
        dnsInput.setText("8.8.8.8:53");

        inputsContainer.addView(domainLayout);
        inputsContainer.addView(keyLayout);
        inputsContainer.addView(dnsLayout);

        body.addView(inputsContainer);

        // --- Spacer ---
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        body.addView(spacer);

        // --- Logs Section ---
        logView = new TextView(this);
        logView.setText("Logs:\n");
        logView.setTextIsSelectable(true);
        logView.setPadding(20, 20, 20, 20);

        logScrollView = new ScrollView(this);
        logScrollView.setId(View.generateViewId());
        logScrollView.addView(logView);

        TypedValue logBgValue = new TypedValue();
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurfaceVariant, logBgValue, true);
        logScrollView.setBackgroundColor(logBgValue.data);

        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 400);
        logParams.setMargins(20, 0, 20, 20);
        logScrollView.setLayoutParams(logParams);
        logScrollView.setVisibility(View.GONE);

        body.addView(logScrollView);

        // --- Bottom Area ---
        LinearLayout bottomArea = new LinearLayout(this);
        bottomArea.setOrientation(LinearLayout.VERTICAL);
        bottomArea.setGravity(Gravity.CENTER_HORIZONTAL);
        bottomArea.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Connect Button
        btnStart = new ImageButton(this);
        btnStart.setImageResource(R.drawable.ic_app_icon);
        btnStart.setScaleType(ImageView.ScaleType.FIT_CENTER);

        GradientDrawable buttonBg = new GradientDrawable();
        buttonBg.setShape(GradientDrawable.OVAL);
        buttonBg.setColor(Color.parseColor("#333333"));

        btnStart.setBackground(buttonBg);
        btnStart.setPadding(20, 20, 20, 20);
        btnStart.setElevation(20f);

        int btnSize = 280;
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(btnSize, btnSize);
        btnParams.setMargins(0, 0, 0, -100);
        btnStart.setLayoutParams(btnParams);

        bottomArea.addView(btnStart);

        // Status Bar
        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTypeface(null, Typeface.BOLD);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 120, 0, 40);
        statusView.setTextSize(18);

        statusView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bottomArea.addView(statusView);
        body.addView(bottomArea);

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
                750, DrawerLayout.LayoutParams.MATCH_PARENT);
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

        configListView.setOnItemClickListener((parent, view, position, id) -> {
            loadConfigToInputs(configList.get(position));
            drawerLayout.closeDrawers();
        });

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

        updateUIState("Disconnected");

        btnStart.setOnClickListener(v -> {
            if (!statusView.getText().equals("Disconnected")) {
                Intent serviceIntent = new Intent(this, DnsttVpnService.class);
                serviceIntent.setAction("STOP");
                startService(serviceIntent);
            } else {
                Intent intent = VpnService.prepare(this);
                if (intent != null) {
                    startActivityForResult(intent, VPN_REQUEST_CODE);
                } else {
                    startVpnService();
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpnService();
        } else if (requestCode == VPN_REQUEST_CODE) {
            Toast.makeText(this, "VPN Permission Denied", Toast.LENGTH_SHORT).show();
        }
    }

    private void startVpnService() {
        Intent serviceIntent = new Intent(this, DnsttVpnService.class);
        serviceIntent.putExtra("domain", domainInput.getText().toString());
        serviceIntent.putExtra("key", keyInput.getText().toString());
        serviceIntent.putExtra("dns", dnsInput.getText().toString());

        isTestingPhase = true;
        logBatchCount = 0;
        streamLogCount = 0;
        updateUIState("Testing...");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void updateUIState(String status) {
        statusView.setText(status);

        if ("Disconnected".equals(status)) {
            statusView.setBackgroundColor(Color.GRAY);
            btnStart.setImageResource(R.drawable.ic_app_icon_gray);
            btnStart.clearColorFilter();
        }
        else if ("Timed-out".equals(status)) {
            statusView.setBackgroundColor(Color.RED);
            btnStart.setImageResource(R.drawable.ic_app_icon_red);
            btnStart.clearColorFilter();
        }
        else {
            int brandBlue = ContextCompat.getColor(this, R.color.brand_blue);
            statusView.setBackgroundColor(brandBlue);
            btnStart.setImageResource(R.drawable.ic_app_icon);
            btnStart.clearColorFilter();
        }
    }

    private void updateStatusLogic(String message) {
        if (message == null) return;

        boolean isSessionStart = message.contains("begin session");
        boolean isStreamLog = message.contains("begin stream") || message.contains("end stream");

        if (isTestingPhase) {
            if (!statusView.getText().equals("Testing...")) updateUIState("Testing...");

            if (isSessionStart || isStreamLog) {
                isTestingPhase = false;
                logBatchCount = 0;
                streamLogCount = 0;

                if (isStreamLog) {
                    logBatchCount = 1;
                    streamLogCount = 1;
                }
            }
            return;
        }

        logBatchCount++;

        if (isStreamLog) {
            streamLogCount++;
        }

        if (streamLogCount >= 3) {
            updateUIState("Connected");
            logBatchCount = 0;
            streamLogCount = 0;
            return;
        }

        if (logBatchCount >= 5) {
            if (streamLogCount >= 3) {
                updateUIState("Connected");
            } else {
                updateUIState("Timed-out");
            }
            logBatchCount = 0;
            streamLogCount = 0;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

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
        } else if (id == R.id.action_toggle_logs) {
            isLogsVisible = !isLogsVisible;
            logScrollView.setVisibility(isLogsVisible ? View.VISIBLE : View.GONE);
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
                        saveConfig(new Config(name, domainInput.getText().toString(), keyInput.getText().toString(), dnsInput.getText().toString()));
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

    private void exportConfigToClipboard(Config c) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("name", c.name);
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

    private void importFromClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                String json = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
                JSONObject obj = new JSONObject(json);
                String domain = obj.getString("domain");
                String key = obj.getString("key");
                String dns = obj.has("dns") ? obj.getString("dns") : "8.8.8.8:53";
                domainInput.setText(domain);
                keyInput.setText(key);
                dnsInput.setText(dns);
                String name;
                if (obj.has("name") && !obj.getString("name").trim().isEmpty()) {
                    name = obj.getString("name");
                } else {
                    name = "Imported Config " + (configList.size() + 1);
                }
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

        // [FIX] Use DnsttVpnService instead of ProxyService
        if (DnsttVpnService.isServiceRunning) {
            if (statusView.getText().equals("Disconnected")) {
                updateUIState("Testing...");
            }
        } else {
            updateUIState("Disconnected");
        }

        // [FIX] Use DnsttVpnService log buffer
        logView.setText("Logs:\n" + DnsttVpnService.logBuffer.toString());
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
        if (isLogsVisible) {
            logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }
}
