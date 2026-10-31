package com.distractionfree;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private BlockDatabase database;
    private DevicePolicyManager devicePolicyManager;
    private LinearLayout appList;
    private LinearLayout domainList;
    private TextView protectionStatus;
    private TextView deviceOwnerStatus;
    private TextView vpnStatus;
    private List<ApplicationInfo> installedApps = new ArrayList<>();
    private Set<String> blockedApps = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        database = new BlockDatabase(this);
        devicePolicyManager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        appList = findViewById(R.id.app_list);
        domainList = findViewById(R.id.domain_list);
        protectionStatus = findViewById(R.id.protection_status);
        deviceOwnerStatus = findViewById(R.id.device_owner_status);
        vpnStatus = findViewById(R.id.vpn_status);

        findViewById(R.id.add_domain).setOnClickListener(v -> addDomain());
        com.google.android.material.textfield.TextInputEditText appSearch = findViewById(R.id.app_search);
        appSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderApps(s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        loadApps();
        renderDomains();
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (database != null) {
            blockedApps = new HashSet<>(database.getBlockedApps());
            updateStatus();
            renderApps("");
            renderDomains();
            if (BlockVpnService.isConfigured(this) && VpnService.prepare(this) == null
                    && !BlockVpnService.isRunning()) {
                startVpnService();
            }
        }
    }

    private void loadApps() {
        PackageManager manager = getPackageManager();
        installedApps = new ArrayList<>(manager.getInstalledApplications(PackageManager.GET_META_DATA));
        Collections.sort(installedApps, Comparator.comparing(
                info -> manager.getApplicationLabel(info).toString(), String.CASE_INSENSITIVE_ORDER));
        String[] initiallyBlocked = {"com.instagram.android", "com.android.chrome", "com.google.android.youtube"};
        Set<String> installedPackages = new HashSet<>();
        for (ApplicationInfo info : installedApps) {
            installedPackages.add(info.packageName);
        }
        for (String packageName : initiallyBlocked) {
            if (installedPackages.contains(packageName)) {
                database.addDefaultAppIfInstalled(packageName);
            }
        }
        blockedApps = new HashSet<>(database.getBlockedApps());
        renderApps("");
    }

    private void renderApps(String query) {
        appList.removeAllViews();
        String normalized = query.trim().toLowerCase();
        PackageManager manager = getPackageManager();
        for (ApplicationInfo info : installedApps) {
            String label = manager.getApplicationLabel(info).toString();
            if (!normalized.isEmpty() && !label.toLowerCase().contains(normalized)
                    && !info.packageName.toLowerCase().contains(normalized)) {
                continue;
            }
            if (info.packageName.equals(getPackageName())) {
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, 8, 0, 8);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(manager.getApplicationIcon(info));
            row.addView(icon, new LinearLayout.LayoutParams(48, 48));
            TextView labelView = new TextView(this);
            labelView.setText(label);
            labelView.setTextSize(16);
            labelView.setPadding(16, 0, 8, 0);
            row.addView(labelView, new LinearLayout.LayoutParams(0, 56, 1));
            CheckBox checkBox = new CheckBox(this);
            checkBox.setChecked(blockedApps.contains(info.packageName));
            checkBox.setOnClickListener(v -> setAppBlocked(info.packageName, checkBox.isChecked()));
            row.addView(checkBox);
            appList.addView(row);
        }
    }

    private void setAppBlocked(String packageName, boolean blocked) {
        if (!isDeviceOwner()) {
            Toast.makeText(this, "Provision Device Owner before changing app restrictions.", Toast.LENGTH_LONG).show();
            renderApps("");
            return;
        }
        database.setAppBlocked(packageName, blocked);
        blockedApps = new HashSet<>(database.getBlockedApps());
        ComponentName admin = new ComponentName(this, DeviceAdminReceiver.class);
        try {
            boolean applied = devicePolicyManager.setApplicationHidden(admin, packageName, blocked);
            if (!applied && blocked) {
                Toast.makeText(this, "Android could not hide this application.", Toast.LENGTH_LONG).show();
            }
        } catch (SecurityException exception) {
            database.setAppBlocked(packageName, false);
            Toast.makeText(this, "Device Owner enforcement is unavailable.", Toast.LENGTH_LONG).show();
        }
        updateStatus();
    }

    private void addDomain() {
        com.google.android.material.textfield.TextInputEditText input = findViewById(R.id.domain_input);
        String domain = BlockVpnService.normalizeDomain(String.valueOf(input.getText()));
        if (!BlockVpnService.isValidDomain(domain)) {
            input.setError("Enter a domain such as example.com");
            return;
        }
        if (database.addDomain(domain)) {
            input.setText("");
            renderDomains();
            startVpn();
        } else {
            Toast.makeText(this, "That domain is already blocked.", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderDomains() {
        domainList.removeAllViews();
        for (String domain : database.getBlockedDomains()) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView text = new TextView(this);
            text.setText(domain);
            text.setTextSize(16);
            row.addView(text, new LinearLayout.LayoutParams(0, 52, 1));
            com.google.android.material.button.MaterialButton remove =
                    new com.google.android.material.button.MaterialButton(this);
            remove.setText("Remove");
            remove.setTextColor(Color.DKGRAY);
            remove.setOnClickListener(v -> {
                database.removeDomain(domain);
                renderDomains();
                updateStatus();
            });
            row.addView(remove);
            domainList.addView(row);
        }
    }

    private void startVpn() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            startActivityForResult(prepare, 42);
        } else {
            startVpnService();
        }
    }

    private void startVpnService() {
        Intent service = new Intent(this, BlockVpnService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }

    private boolean isDeviceOwner() {
        return devicePolicyManager.isDeviceOwnerApp(getPackageName());
    }

    private void updateStatus() {
        boolean owner = isDeviceOwner();
        boolean vpnPrepared = VpnService.prepare(this) == null;
        deviceOwnerStatus.setText(owner ? "Device Owner: ready" : "Device Owner: not provisioned");
        vpnStatus.setText(BlockVpnService.isRunning() ? "VPN: active" :
                (vpnPrepared ? "VPN: permission granted, service stopped" : "VPN: permission needed"));
        boolean active = owner && BlockVpnService.isRunning();
        protectionStatus.setText(active ? "Protection is active" : "Protection is not active");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 42 && resultCode == RESULT_OK) {
            startVpnService();
        }
        updateStatus();
    }
}
