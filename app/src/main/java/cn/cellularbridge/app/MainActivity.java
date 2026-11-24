package cn.cellularbridge.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xFFF5F8F5;
    private static final int INK = 0xFF162C27;
    private static final int SECONDARY = 0xFF49625A;
    private static final int GREEN = 0xFF176658;
    private static final int BORDER = 0xFFD7E4DD;
    private static final int PERMISSION_REQUEST = 1;
    private static final String PENDING_PERMISSION = "pending_permission";
    private static final String WIFI_SSID = "wifi_ssid";
    private static final String WIFI_SECURITY = "wifi_security";

    private final Consumer<BridgeService.State> observer = this::render;
    private TextView endpoint;
    private TextView cellular;
    private TextView active;
    private Button copy;
    private Button serviceButton;
    private EditText wifiSsid;
    private Spinner wifiSecurity;
    private EditText wifiPassword;
    private EditText username;
    private EditText password;
    private EditText listenPort;
    private EditText targetPorts;
    private CheckBox showPassword;
    private BridgeService.State state;
    private boolean pendingPermission;

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        pendingPermission = savedState != null && savedState.getBoolean(PENDING_PERMISSION);
        BridgeConfig config = BridgeConfig.load(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout content = column();
        content.setPadding(dp(24), dp(22), dp(24), dp(24));
        scroll.addView(content);
        setContentView(scroll);
        content.setFocusableInTouchMode(true);
        content.requestFocus();

        add(content, text(getString(R.string.app_name), 32, INK, true), 0);
        add(content, text(getString(R.string.subtitle), 15, SECONDARY, false), 4);

        LinearLayout statusPanel = panel();
        add(content, statusPanel, 19);
        add(statusPanel, text(getString(R.string.proxy_address), 13, SECONDARY, true), 0);
        endpoint = text("", 19, INK, true);
        add(statusPanel, endpoint, 4);
        endpoint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        copy = button(R.string.copy_address, false);
        add(statusPanel, copy, 7);
        copy.setOnClickListener(view -> copyAddress());

        LinearLayout statusRow = new LinearLayout(this);
        add(statusPanel, statusRow, 11);
        cellular = metric(statusRow, R.string.cellular_status);
        active = metric(statusRow, R.string.active_tunnels);

        serviceButton = button(R.string.start_proxy, true);
        add(content, serviceButton, 14);
        serviceButton.setOnClickListener(view -> {
            if (state.running()) {
                BridgeService.stop(this);
            } else {
                startProxy();
            }
        });

        var preferences = BridgeConfig.preferences(this);
        LinearLayout wifiSettings = panel();
        add(content, wifiSettings, 20);
        add(wifiSettings, text(getString(R.string.wifi_settings), 20, INK, true), 0);
        add(wifiSettings, text(getString(R.string.wifi_settings_hint), 13, SECONDARY, false), 5);
        wifiSsid = input(wifiSettings, R.id.wifi_ssid, R.string.wifi_ssid,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                preferences.getString(WIFI_SSID, ""));
        wifiSecurity = wifiSecuritySpinner(wifiSettings, preferences.getInt(WIFI_SECURITY, 0));
        wifiPassword = input(wifiSettings, R.id.wifi_password, R.string.wifi_password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "");
        wifiPassword.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        wifiPassword.setSaveEnabled(false);
        wifiSecurity.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateWifiPasswordField();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateWifiPasswordField();
            }
        });

        LinearLayout settings = panel();
        add(content, settings, 20);
        add(settings, text(getString(R.string.proxy_settings), 20, INK, true), 0);
        add(settings, text(getString(R.string.settings_hint), 13, SECONDARY, false), 5);
        username = input(settings, R.id.username, R.string.username,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, config.username);
        password = input(settings, R.id.password, R.string.password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, config.password);
        password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        showPassword = new CheckBox(this);
        showPassword.setText(R.string.show_password);
        showPassword.setTextColor(SECONDARY);
        add(settings, showPassword, 0);
        showPassword.setOnCheckedChangeListener((view, checked) -> {
            password.setTransformationMethod(checked ? null : PasswordTransformationMethod.getInstance());
            password.setSelection(password.length());
        });
        listenPort = input(settings, R.id.listen_port, R.string.listen_port, InputType.TYPE_CLASS_NUMBER,
                Integer.toString(config.listenPort));
        targetPorts = input(settings, R.id.target_ports, R.string.target_ports,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                BridgeConfig.preferences(this).getString("target_ports", "443"));
    }

    @Override
    protected void onStart() {
        super.onStart();
        BridgeService.observe(observer);
    }

    @Override
    protected void onStop() {
        BridgeService.removeObserver(observer);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(PENDING_PERMISSION, pendingPermission);
    }

    private void render(BridgeService.State state) {
        this.state = state;
        String address = state.address();
        endpoint.setText(address == null ? getString(state.wifiText()) : address);
        cellular.setText(state.cellularText() == 0 ? R.string.value_unavailable : state.cellularText());
        active.setText(getString(R.string.active_count, state.activeCount()));
        copy.setEnabled(address != null);
        serviceButton.setText(state.running() ? R.string.stop_proxy : R.string.start_proxy);
        wifiSsid.setEnabled(!state.running());
        wifiSecurity.setEnabled(!state.running());
        updateWifiPasswordField();
        username.setEnabled(!state.running());
        password.setEnabled(!state.running());
        listenPort.setEnabled(!state.running());
        targetPorts.setEnabled(!state.running());
    }

    private void copyAddress() {
        if (state.address() != null) {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.proxy_address), state.address()));
        }
    }

    private void startProxy() {
        String ssid = wifiSsid.getText().toString();
        if (!LocalWifi.validSsid(ssid)) {
            validationError(wifiSsid, R.string.error_wifi_ssid);
            return;
        }
        int security = wifiSecurity.getSelectedItemPosition();
        String networkPassword = wifiPassword.getText().toString();
        if (LocalWifi.needsPassword(security) && !LocalWifi.validPassword(networkPassword)) {
            validationError(wifiPassword, R.string.error_wifi_password);
            return;
        }
        String user = username.getText().toString();
        String secret = password.getText().toString();
        if (!BridgeConfig.validCredential(user) || user.contains(":")) {
            validationError(username, R.string.error_username);
            return;
        }
        if (!BridgeConfig.validCredential(secret)) {
            validationError(password, R.string.error_password);
            return;
        }
        int port;
        try {
            port = BridgeConfig.parsePort(listenPort.getText().toString(), 1024);
        } catch (IllegalArgumentException error) {
            validationError(listenPort, R.string.error_listen_port);
            return;
        }
        String allowed = targetPorts.getText().toString();
        Set<Integer> ports;
        try {
            ports = BridgeConfig.parseTargetPorts(allowed);
        } catch (IllegalArgumentException error) {
            validationError(targetPorts, R.string.error_target_ports);
            return;
        }
        new BridgeConfig(user, secret, port, ports).save(this, allowed);
        BridgeConfig.preferences(this).edit().putString(WIFI_SSID, ssid).putInt(WIFI_SECURITY, security).apply();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(serviceButton.getWindowToken(), 0);
        showPassword.setChecked(false);
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !BridgeConfig.preferences(this).getBoolean("asked_for_notifications", false)) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            BridgeConfig.preferences(this).edit().putBoolean("asked_for_notifications", true).apply();
        }
        if (!permissions.isEmpty()) {
            pendingPermission = true;
            requestPermissions(permissions.toArray(new String[0]), PERMISSION_REQUEST);
            return;
        }
        launchService(LocalWifi.request(ssid, security, networkPassword));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST && pendingPermission) {
            pendingPermission = false;
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) {
                startProxy();
            } else {
                Toast.makeText(this, R.string.error_wifi_permission, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void launchService(NetworkRequest wifiRequest) {
        if (!BridgeService.start(this, wifiRequest)) {
            Toast.makeText(this, R.string.error_start, Toast.LENGTH_LONG).show();
        }
    }

    private void validationError(EditText input, int message) {
        input.requestFocus();
        input.setError(getString(message));
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout panel() {
        LinearLayout layout = column();
        layout.setBackground(shape(0xFFFFFFFF, BORDER, 16));
        layout.setPadding(dp(16), dp(14), dp(16), dp(14));
        return layout;
    }

    private TextView metric(LinearLayout row, int label) {
        LinearLayout group = column();
        row.addView(group, new LinearLayout.LayoutParams(0, -2, 1f));
        add(group, text(getString(label), 13, SECONDARY, true), 0);
        TextView value = text("", 15, INK, false);
        add(group, value, 2);
        return value;
    }

    private Spinner wifiSecuritySpinner(LinearLayout parent, int selected) {
        TextView title = text(getString(R.string.wifi_security), 13, SECONDARY, true);
        add(parent, title, 12);
        Spinner spinner = new Spinner(this);
        spinner.setId(R.id.wifi_security);
        title.setLabelFor(spinner.getId());
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.wifi_security_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
        spinner.setMinimumHeight(dp(48));
        add(parent, spinner, 3);
        return spinner;
    }

    private void updateWifiPasswordField() {
        wifiPassword.setEnabled((state == null || !state.running())
                && LocalWifi.needsPassword(wifiSecurity.getSelectedItemPosition()));
    }

    private EditText input(LinearLayout parent, int id, int label, int type, String value) {
        TextView title = text(getString(label), 13, SECONDARY, true);
        add(parent, title, 12);
        EditText field = new EditText(this);
        field.setId(id);
        title.setLabelFor(field.getId());
        field.setSingleLine(true);
        field.setInputType(type);
        field.setText(value);
        field.setTextSize(16);
        field.setTextColor(INK);
        field.setSelectAllOnFocus(true);
        field.setBackground(shape(BACKGROUND, BORDER, 8));
        field.setPadding(dp(10), dp(8), dp(10), dp(8));
        field.setMinHeight(dp(48));
        add(parent, field, 3);
        return field;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private Button button(int label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setTextColor(primary ? 0xFFFFFFFF : GREEN);
        button.setBackgroundTintList(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                new int[]{0xFFEDF2EE, primary ? GREEN : 0xFFE1EFE7}));
        return button;
    }

    private GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setStroke(dp(1), stroke);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private void add(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMargin);
        parent.addView(child, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
