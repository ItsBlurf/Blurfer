package com.blurfer.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("SetTextI18n")
public class MainActivity extends Activity {
    private static final int REQUEST_FOLDER = 1001;
    private static final int DEFAULT_PORT = 9021;
    private static final int SOCKET_TIMEOUT_MS = 15000;

    private static final String PREFS = "blurfer_settings";
    private static final String KEY_FOLDER_URI = "folder_uri";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_ORDER_PREFIX = "payload_order_";
    private static final String KEY_DELAY_PREFIX = "payload_delay_";
    private static final String KEY_PAYLOAD_PORT_PREFIX = "payload_port_";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<PayloadItem> payloads = new ArrayList<>();
    private final List<String> logEntries = new ArrayList<>();

    private SharedPreferences prefs;
    private Uri folderUri;
    private EditText hostInput;
    private EditText portInput;
    private TextView folderText;
    private TextView countText;
    private TextView statusText;
    private TextView logText;
    private LinearLayout payloadList;
    private ProgressBar progressBar;
    private Button chooseButton;
    private Button refreshButton;
    private Button injectSelectedButton;
    private Button injectAllButton;
    private boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        configureWindow();
        buildInterface();
        restoreSettings();
        refreshPayloads();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSettings();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri selectedUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(selectedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }

        folderUri = selectedUri;
        saveSettings();
        refreshPayloads();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.parseColor("#F6F7F9"));
        window.setNavigationBarColor(Color.WHITE);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(8));
        root.setBackgroundColor(Color.parseColor("#F6F7F9"));

        TextView title = text("Blurfer", 23, "#172033", true);
        root.addView(title);

        TextView subtitle = text("Choose a folder, review your queue, then inject selected payloads or all payloads.", 13, "#687386", false);
        subtitle.setPadding(0, dp(3), 0, dp(10));
        root.addView(subtitle);

        root.addView(buildTargetCard());
        root.addView(buildFolderCard());

        LinearLayout listHeader = new LinearLayout(this);
        listHeader.setOrientation(LinearLayout.HORIZONTAL);
        listHeader.setGravity(Gravity.CENTER_VERTICAL);
        listHeader.setPadding(0, dp(8), 0, dp(5));

        TextView payloadHeader = text("Payloads", 17, "#172033", true);
        listHeader.addView(payloadHeader, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        countText = text("0", 13, "#687386", false);
        listHeader.addView(countText);
        root.addView(listHeader);

        ScrollView listScroll = new ScrollView(this);
        listScroll.setFillViewport(false);
        payloadList = new LinearLayout(this);
        payloadList.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(payloadList);
        root.addView(listScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        root.addView(buildProgressArea());
        root.addView(buildActionBar());

        setContentView(root);
    }

    private View buildTargetCard() {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);

        TextView label = text("Target", 15, "#172033", true);
        card.addView(label);

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.HORIZONTAL);
        fields.setGravity(Gravity.CENTER_VERTICAL);
        fields.setPadding(0, dp(10), 0, 0);

        hostInput = input("Host / IP");
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        fields.addView(hostInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Space fieldGap = new Space(this);
        fields.addView(fieldGap, new LinearLayout.LayoutParams(dp(10), 1));

        portInput = input("Default port");
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        fields.addView(portInput, new LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(fields);

        return card;
    }

    private View buildFolderCard() {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView label = text("Payload folder", 15, "#172033", true);
        folderText = text("No folder selected", 13, "#687386", false);
        folderText.setPadding(0, dp(4), dp(8), 0);
        folderText.setSingleLine(true);
        folderText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        textColumn.addView(label);
        textColumn.addView(folderText);
        row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);

        chooseButton = button("Choose", "#2563EB", "#FFFFFF");
        chooseButton.setOnClickListener(v -> chooseFolder());
        buttonRow.addView(chooseButton, new LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));

        Space refreshGap = new Space(this);
        buttonRow.addView(refreshGap, new LinearLayout.LayoutParams(dp(6), 1));

        refreshButton = smallButton("Refresh", "#EEF2FF", "#1D4ED8");
        refreshButton.setOnClickListener(v -> refreshPayloads());
        buttonRow.addView(refreshButton, new LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT));

        row.addView(buttonRow);

        card.addView(row);

        return card;
    }

    private View buildProgressArea() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(8), 0, dp(7));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1);
        progressBar.setProgress(0);
        panel.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        statusText = text("Ready", 13, "#687386", false);
        statusText.setPadding(0, dp(6), 0, dp(3));
        panel.addView(statusText);

        logText = text("Ready.", 12, "#172033", false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setPadding(dp(9), dp(7), dp(9), dp(7));
        logText.setBackground(rounded("#FFFFFF", "#D9DEE8", 8));
        logText.setGravity(Gravity.CENTER_VERTICAL);
        logText.setMaxLines(2);
        logText.setMinHeight(dp(46));
        logText.setOnClickListener(v -> showFullLog());
        panel.addView(logText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return panel;
    }

    private View buildActionBar() {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(4), 0, 0);

        injectSelectedButton = button("Selected", "#2563EB", "#FFFFFF");
        injectSelectedButton.setOnClickListener(v -> injectSelected());
        actions.addView(injectSelectedButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Space gapOne = new Space(this);
        actions.addView(gapOne, new LinearLayout.LayoutParams(dp(8), 1));

        injectAllButton = button("Inject All", "#111827", "#FFFFFF");
        injectAllButton.setOnClickListener(v -> injectAll());
        actions.addView(injectAllButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        return actions;
    }

    private void restoreSettings() {
        hostInput.setText(prefs.getString(KEY_HOST, ""));
        portInput.setText(prefs.getString(KEY_PORT, String.valueOf(DEFAULT_PORT)));

        String savedFolder = prefs.getString(KEY_FOLDER_URI, null);
        if (savedFolder != null && !savedFolder.isEmpty()) {
            folderUri = Uri.parse(savedFolder);
        }

        TextWatcher watcher = new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                saveSettings();
            }
        };
        hostInput.addTextChangedListener(watcher);
        portInput.addTextChangedListener(watcher);
    }

    private void saveSettings() {
        SharedPreferences.Editor editor = prefs.edit()
            .putString(KEY_HOST, hostInput.getText().toString().trim())
            .putString(KEY_PORT, portInput.getText().toString().trim());

        if (folderUri != null) {
            editor.putString(KEY_FOLDER_URI, folderUri.toString());
        }
        editor.apply();
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_FOLDER);
    }

    private void refreshPayloads() {
        payloads.clear();
        payloadList.removeAllViews();

        if (folderUri == null) {
            folderText.setText("Choose a folder that contains payload files");
            countText.setText("0 payloads");
            addEmptyState("No folder selected.");
            statusText.setText("Choose a payload folder to begin.");
            return;
        }

        folderText.setText(describeFolder(folderUri));

        try {
            payloads.addAll(applySavedPayloadOrder(loadPayloads(folderUri)));
        } catch (Exception exc) {
            addEmptyState("Could not read this folder. Choose it again.");
            statusText.setText("Folder access failed");
            appendLog("Folder error: " + exc.getMessage());
            return;
        }

        countText.setText(String.format(Locale.US, "%d payload%s", payloads.size(), payloads.size() == 1 ? "" : "s"));
        if (payloads.isEmpty()) {
            addEmptyState("No payload files found in this folder.");
            statusText.setText("No payloads found");
        } else {
            renderPayloadList();
            statusText.setText("Ready");
            appendLog("Loaded " + payloads.size() + " payload" + (payloads.size() == 1 ? "" : "s") + ".");
        }
    }

    private List<PayloadItem> loadPayloads(Uri treeUri) {
        List<PayloadItem> items = new ArrayList<>();
        String parentDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);
        String[] projection = new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };

        try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC")) {
            if (cursor == null) {
                return items;
            }

            int documentIdIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE);
            int modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED);

            while (cursor.moveToNext()) {
                String mimeType = cursor.getString(mimeIndex);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    continue;
                }

                String name = cursor.getString(nameIndex);
                if (name == null || ".gitkeep".equals(name)) {
                    continue;
                }

                String documentId = cursor.getString(documentIdIndex);
                Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                long size = cursor.isNull(sizeIndex) ? -1 : cursor.getLong(sizeIndex);
                long modified = cursor.isNull(modifiedIndex) ? 0 : cursor.getLong(modifiedIndex);
                PayloadItem item = new PayloadItem(name, documentUri, size, modified);
                item.portText = prefs.getString(portPreferenceKey(name), prefs.getString(KEY_PORT, String.valueOf(DEFAULT_PORT)));
                item.delayText = prefs.getString(delayPreferenceKey(name), "0");
                items.add(item);
            }
        }

        return items;
    }

    private List<PayloadItem> applySavedPayloadOrder(List<PayloadItem> discovered) {
        if (folderUri == null) {
            return discovered;
        }

        String savedOrder = prefs.getString(KEY_ORDER_PREFIX + folderUri.toString(), "");
        if (savedOrder.isEmpty()) {
            return discovered;
        }

        List<PayloadItem> ordered = new ArrayList<>();
        List<PayloadItem> remaining = new ArrayList<>(discovered);

        for (String name : savedOrder.split("\\n")) {
            for (int index = 0; index < remaining.size(); index++) {
                PayloadItem item = remaining.get(index);
                if (item.name.equals(name)) {
                    ordered.add(item);
                    remaining.remove(index);
                    break;
                }
            }
        }

        ordered.addAll(remaining);
        return ordered;
    }

    private void savePayloadOrder() {
        if (folderUri == null) {
            return;
        }

        StringBuilder order = new StringBuilder();
        for (PayloadItem item : payloads) {
            if (order.length() > 0) {
                order.append('\n');
            }
            order.append(item.name);
        }

        prefs.edit().putString(KEY_ORDER_PREFIX + folderUri.toString(), order.toString()).apply();
    }

    private void renderPayloadList() {
        payloadList.removeAllViews();
        for (int index = 0; index < payloads.size(); index++) {
            payloadList.addView(payloadRow(payloads.get(index), index));
        }
    }

    private void movePayload(int fromIndex, int toIndex) {
        if (running || fromIndex < 0 || fromIndex >= payloads.size() || toIndex < 0 || toIndex >= payloads.size()) {
            return;
        }

        PayloadItem item = payloads.remove(fromIndex);
        payloads.add(toIndex, item);
        savePayloadOrder();
        renderPayloadList();
    }

    private String delayPreferenceKey(String payloadName) {
        String folder = folderUri == null ? "" : folderUri.toString();
        return KEY_DELAY_PREFIX + folder + "\n" + payloadName;
    }

    private String portPreferenceKey(String payloadName) {
        String folder = folderUri == null ? "" : folderUri.toString();
        return KEY_PAYLOAD_PORT_PREFIX + folder + "\n" + payloadName;
    }

    private void savePayloadDelay(PayloadItem item) {
        prefs.edit().putString(delayPreferenceKey(item.name), item.delayText).apply();
    }

    private void savePayloadPort(PayloadItem item) {
        prefs.edit().putString(portPreferenceKey(item.name), item.portText).apply();
    }

    private View payloadRow(PayloadItem item, int index) {
        LinearLayout row = card();
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(9), dp(8), dp(9), dp(8));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        CheckBox checkBox = new CheckBox(this);
        checkBox.setChecked(item.selected);
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> item.selected = isChecked);
        topRow.addView(checkBox);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);

        TextView name = text(item.name, 15, "#172033", true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView meta = text(formatSize(item.size) + "  " + formatDate(item.modified), 11, "#687386", false);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, dp(2), 0, 0);
        textColumn.addView(name);
        textColumn.addView(meta);
        topRow.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        item.statusView = text(item.status, 11, "#1D4ED8", true);
        item.statusView.setGravity(Gravity.CENTER);
        item.statusView.setPadding(dp(7), dp(4), dp(7), dp(4));
        item.statusView.setBackground(rounded("#EEF2FF", "#D9DEE8", 6));
        topRow.addView(item.statusView);

        row.addView(topRow);

        LinearLayout controlsRow = new LinearLayout(this);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER_VERTICAL);
        controlsRow.setPadding(0, dp(7), 0, 0);

        TextView portLabel = text("Port", 11, "#687386", false);
        controlsRow.addView(portLabel);

        EditText portField = compactInput(String.valueOf(DEFAULT_PORT));
        portField.setInputType(InputType.TYPE_CLASS_NUMBER);
        portField.setText(item.portText);
        portField.setEnabled(!running);
        portField.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                item.portText = s.toString().trim();
                savePayloadPort(item);
            }
        });
        LinearLayout.LayoutParams portParams = new LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT);
        portParams.setMargins(dp(5), 0, dp(8), 0);
        controlsRow.addView(portField, portParams);

        TextView delayLabel = text("Delay", 11, "#687386", false);
        controlsRow.addView(delayLabel);

        EditText delayField = compactInput("0");
        delayField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        delayField.setText(item.delayText);
        delayField.setEnabled(!running);
        delayField.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                item.delayText = s.toString().trim();
                savePayloadDelay(item);
            }
        });
        LinearLayout.LayoutParams delayParams = new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT);
        delayParams.setMargins(dp(5), 0, 0, 0);
        controlsRow.addView(delayField, delayParams);

        Space controlGap = new Space(this);
        controlsRow.addView(controlGap, new LinearLayout.LayoutParams(0, 1, 1));

        Button upButton = smallButton("Up", "#EEF2FF", "#1D4ED8");
        upButton.setEnabled(index > 0 && !running);
        upButton.setOnClickListener(v -> movePayload(index, index - 1));
        controlsRow.addView(upButton, new LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT));

        Button downButton = smallButton("Down", "#EEF2FF", "#1D4ED8");
        downButton.setEnabled(index < payloads.size() - 1 && !running);
        downButton.setOnClickListener(v -> movePayload(index, index + 1));
        LinearLayout.LayoutParams downParams = new LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT);
        downParams.setMargins(dp(4), 0, 0, 0);
        controlsRow.addView(downButton, downParams);

        row.addView(controlsRow);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(params);
        return row;
    }

    private void addEmptyState(String message) {
        TextView empty = text(message, 14, "#687386", false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(16), dp(26), dp(16), dp(26));
        empty.setBackground(rounded("#FFFFFF", "#D9DEE8", 8));
        payloadList.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void injectSelected() {
        List<PayloadItem> selected = new ArrayList<>();
        for (PayloadItem item : payloads) {
            if (item.selected) {
                selected.add(item);
            }
        }

        if (selected.isEmpty()) {
            Toast.makeText(this, "Select one or more payloads first.", Toast.LENGTH_SHORT).show();
            return;
        }
        startInjection(selected);
    }

    private void injectAll() {
        if (payloads.isEmpty()) {
            Toast.makeText(this, "Choose a folder with payload files first.", Toast.LENGTH_SHORT).show();
            return;
        }
        startInjection(new ArrayList<>(payloads));
    }

    private void startInjection(List<PayloadItem> queue) {
        if (running) {
            Toast.makeText(this, "Injection is already running.", Toast.LENGTH_SHORT).show();
            return;
        }

        String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            Toast.makeText(this, "Enter the target host/IP.", Toast.LENGTH_SHORT).show();
            return;
        }

        int defaultPort;
        try {
            defaultPort = parsePort(portInput.getText().toString().trim());
        } catch (NumberFormatException exc) {
            Toast.makeText(this, "Default port must be between 1 and 65535.", Toast.LENGTH_SHORT).show();
            return;
        }

        for (PayloadItem item : queue) {
            try {
                item.portNumber = parsePayloadPort(item, defaultPort);
            } catch (NumberFormatException exc) {
                Toast.makeText(this, "Port for " + item.name + " must be between 1 and 65535.", Toast.LENGTH_LONG).show();
                return;
            }

            try {
                item.delaySeconds = parsePayloadDelay(item);
            } catch (NumberFormatException exc) {
                Toast.makeText(this, "Delay for " + item.name + " must be a non-negative number.", Toast.LENGTH_LONG).show();
                return;
            }
        }

        saveSettings();
        running = true;
        setControlsEnabled(false);
        progressBar.setMax(queue.size());
        progressBar.setProgress(0);

        for (PayloadItem item : queue) {
            setItemStatus(item, "Queued", "#687386", "#F3F4F6");
        }

        executor.execute(() -> runInjection(queue, host));
    }

    private int parsePort(String rawPort) {
        int port = Integer.parseInt(rawPort == null ? "" : rawPort.trim());
        if (port < 1 || port > 65535) {
            throw new NumberFormatException("Port out of range");
        }
        return port;
    }

    private int parsePayloadPort(PayloadItem item, int defaultPort) {
        String rawPort = item.portText == null || item.portText.trim().isEmpty()
            ? String.valueOf(defaultPort)
            : item.portText.trim();
        return parsePort(rawPort);
    }

    private double parsePayloadDelay(PayloadItem item) {
        String rawDelay = item.delayText == null || item.delayText.trim().isEmpty() ? "0" : item.delayText.trim();
        double delaySeconds = Double.parseDouble(rawDelay);
        if (delaySeconds < 0) {
            throw new NumberFormatException("Delay cannot be negative");
        }
        return delaySeconds;
    }

    private void runInjection(List<PayloadItem> queue, String host) {
        int sentCount = 0;
        int total = queue.size();

        for (int index = 0; index < total; index++) {
            PayloadItem item = queue.get(index);

            if (item.delaySeconds > 0) {
                postStatus("Waiting " + (index + 1) + "/" + total);
                postItemStatus(item, "Waiting", "#1D4ED8", "#EEF2FF");
                postLog("Waiting " + trimDelay(item.delaySeconds) + " seconds before " + item.name + "...");
                sleepDelay(item.delaySeconds);
            }

            postStatus("Sending " + (index + 1) + "/" + total);
            postItemStatus(item, "Sending", "#92400E", "#FEF3C7");
            postLog("Sending " + item.name + " to " + host + ":" + item.portNumber);

            try {
                long bytes = sendPayload(item.uri, host, item.portNumber);
                sentCount++;
                postItemStatus(item, "Sent", "#15803D", "#DCFCE7");
                postLog("Sent " + formatSize(bytes) + " from " + item.name);
            } catch (Exception exc) {
                postItemStatus(item, "Failed", "#B42318", "#FFE4E1");
                postLog("Failed " + item.name + ": " + exc.getMessage());
            }

            int progress = index + 1;
            mainHandler.post(() -> progressBar.setProgress(progress));
        }

        int finalSentCount = sentCount;
        mainHandler.post(() -> {
            running = false;
            setControlsEnabled(true);
            statusText.setText("Done: " + finalSentCount + "/" + total + " sent");
            appendLog("Queue finished. " + finalSentCount + "/" + total + " payloads sent.");
        });
    }

    private long sendPayload(Uri uri, String host, int port) throws Exception {
        ContentResolver resolver = getContentResolver();
        long total = 0;

        try (
            InputStream input = resolver.openInputStream(uri);
            Socket socket = new Socket()
        ) {
            if (input == null) {
                throw new IllegalStateException("Could not open payload file");
            }

            socket.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            try (OutputStream output = socket.getOutputStream()) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    total += read;
                }
                output.flush();
            }
        }

        return total;
    }

    private void sleepDelay(double seconds) {
        long end = System.currentTimeMillis() + (long) (seconds * 1000);
        while (System.currentTimeMillis() < end) {
            try {
                Thread.sleep(Math.min(100, end - System.currentTimeMillis()));
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void setControlsEnabled(boolean enabled) {
        chooseButton.setEnabled(enabled);
        refreshButton.setEnabled(enabled);
        injectSelectedButton.setEnabled(enabled);
        injectAllButton.setEnabled(enabled);
        if (!payloads.isEmpty()) {
            renderPayloadList();
        }
    }

    private void postStatus(String status) {
        mainHandler.post(() -> statusText.setText(status));
    }

    private void postLog(String message) {
        mainHandler.post(() -> appendLog(message));
    }

    private void postItemStatus(PayloadItem item, String status, String textColor, String fillColor) {
        mainHandler.post(() -> setItemStatus(item, status, textColor, fillColor));
    }

    private void setItemStatus(PayloadItem item, String status, String textColor, String fillColor) {
        item.status = status;
        if (item.statusView != null) {
            item.statusView.setText(status);
            item.statusView.setTextColor(Color.parseColor(textColor));
            item.statusView.setBackground(rounded(fillColor, "#D9DEE8", 6));
        }
    }

    private void appendLog(String message) {
        String timestamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        String nextLine = "[" + timestamp + "] " + message;

        logEntries.add(nextLine);
        if (logEntries.size() > 200) {
            logEntries.remove(0);
        }

        updateLogPreview();
    }

    private void updateLogPreview() {
        if (logEntries.isEmpty()) {
            logText.setText("Ready.");
            return;
        }

        int start = Math.max(0, logEntries.size() - 2);
        StringBuilder preview = new StringBuilder();
        for (int index = start; index < logEntries.size(); index++) {
            if (preview.length() > 0) {
                preview.append('\n');
            }
            preview.append(logEntries.get(index));
        }
        logText.setText(preview.toString());
    }

    private void showFullLog() {
        ScrollView scrollView = new ScrollView(this);
        TextView fullLogText = text(fullLogText(), 12, "#172033", false);
        fullLogText.setTypeface(Typeface.MONOSPACE);
        fullLogText.setPadding(dp(14), dp(12), dp(14), dp(12));
        scrollView.addView(fullLogText);

        new AlertDialog.Builder(this)
            .setTitle("Activity")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show();
    }

    private String fullLogText() {
        if (logEntries.isEmpty()) {
            return "Ready.";
        }

        StringBuilder fullLog = new StringBuilder();
        for (String entry : logEntries) {
            if (fullLog.length() > 0) {
                fullLog.append('\n');
            }
            fullLog.append(entry);
        }
        return fullLog.toString();
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setBackground(rounded("#FFFFFF", "#D9DEE8", 8));
        view.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(8));
        view.setLayoutParams(params);
        return view;
    }

    private TextView text(String value, int sp, String color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.parseColor(color));
        view.setIncludeFontPadding(true);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private EditText input(String hint) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setSingleLine(true);
        view.setTextSize(14);
        view.setPadding(dp(9), dp(6), dp(9), dp(6));
        view.setMinHeight(dp(38));
        view.setMinimumHeight(dp(38));
        view.setBackground(rounded("#FFFFFF", "#C9D2E3", 7));
        return view;
    }

    private EditText compactInput(String hint) {
        EditText view = input(hint);
        view.setTextSize(13);
        view.setPadding(dp(7), 0, dp(7), 0);
        view.setMinHeight(dp(32));
        view.setMinimumHeight(dp(32));
        return view;
    }

    private Button button(String label, String fillColor, String textColor) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setTextColor(Color.parseColor(textColor));
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setBackground(rounded(fillColor, fillColor, 8));
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setMinHeight(dp(40));
        view.setMinimumHeight(dp(40));
        view.setPadding(dp(10), 0, dp(10), 0);
        return view;
    }

    private Button smallButton(String label, String fillColor, String textColor) {
        Button view = button(label, fillColor, textColor);
        view.setTextSize(11);
        view.setMinHeight(dp(30));
        view.setMinimumHeight(dp(30));
        view.setPadding(dp(4), 0, dp(4), 0);
        return view;
    }

    private GradientDrawable rounded(String fill, String stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(fill));
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), Color.parseColor(stroke));
        return drawable;
    }

    private String describeFolder(Uri uri) {
        try {
            String documentId = DocumentsContract.getTreeDocumentId(uri);
            int colon = documentId.lastIndexOf(':');
            if (colon >= 0 && colon + 1 < documentId.length()) {
                return documentId.substring(colon + 1);
            }
            return documentId;
        } catch (Exception exc) {
            return uri.toString();
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 0) {
            return "Unknown size";
        }

        String[] units = new String[] {"B", "KB", "MB", "GB"};
        double size = bytes;
        int unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }

        if (unit == 0) {
            return String.format(Locale.US, "%d %s", (long) size, units[unit]);
        }
        return String.format(Locale.US, "%.1f %s", size, units[unit]);
    }

    private String formatDate(long timestamp) {
        if (timestamp <= 0) {
            return "";
        }
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(timestamp));
    }

    private String trimDelay(double delay) {
        if (Math.floor(delay) == delay) {
            return String.format(Locale.US, "%.0f", delay);
        }
        return String.format(Locale.US, "%.1f", delay);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class PayloadItem {
        final String name;
        final Uri uri;
        final long size;
        final long modified;
        boolean selected;
        String portText = String.valueOf(DEFAULT_PORT);
        int portNumber = DEFAULT_PORT;
        String delayText = "0";
        double delaySeconds;
        String status = "Ready";
        TextView statusView;

        PayloadItem(String name, Uri uri, long size, long modified) {
            this.name = name;
            this.uri = uri;
            this.size = size;
            this.modified = modified;
        }
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }
}
