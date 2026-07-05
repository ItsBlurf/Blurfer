package com.blurfer.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

@SuppressLint("SetTextI18n")
public class MainActivity extends Activity {
    private static final int REQUEST_FOLDER = 1001;
    private static final int DEFAULT_PORT = 9021;
    private static final int SOCKET_TIMEOUT_MS = 15000;
    private static final int FLAG_VIRTUAL_DOCUMENT_COMPAT = 1 << 9;
    private static final int FLAG_PARTIAL_COMPAT = 1 << 13;
    private static final int FLAG_SUPPORTS_RESTORE_COMPAT = 1 << 17;
    private static final String COLUMN_ORIGINAL_RELATIVE_PATH_COMPAT = "original_relative_path";

    private static final String[] DEFAULT_REPOS = {
        "ItsBlurf/BFpilot",
        "drakmor/ShadowMountPlus",
        "EchoStretch/kstuff-lite",
        "juma-sayeh/PS5-Game-Compressor",
        "seregonwar/zftpd"
    };

    private static final String PREFS = "blurfer_settings";
    private static final String KEY_FOLDER_URI = "folder_uri";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_ORDER_PREFIX = "payload_order_";
    private static final String KEY_DELAY_PREFIX = "payload_delay_";
    private static final String KEY_PAYLOAD_PORT_PREFIX = "payload_port_";

    // Dark slate design tokens
    private static final String COLOR_BG = "#0F172A";       // Slate 900
    private static final String COLOR_CARD = "#1E293B";     // Slate 800
    private static final String COLOR_BORDER = "#334155";   // Slate 700
    private static final String COLOR_TEXT = "#F8FAFC";     // Slate 50
    private static final String COLOR_MUTED = "#94A3B8";    // Slate 400
    private static final String COLOR_ACCENT = "#2563EB";   // Blue 600
    private static final String COLOR_ACCENT_DARK = "#1D4ED8";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<PayloadItem> payloads = new ArrayList<>();
    private final List<String> logEntries = new ArrayList<>();
    private final List<String> repositories = new ArrayList<>();

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
    private ContentObserver folderObserver;
    private int ignoredFileCount;
    private boolean running;

    // View Switcher Tabs
    private LinearLayout injectLayout;
    private LinearLayout downloadLayout;

    // Download Hub views
    private LinearLayout repoChipsContainer;
    private TextView repoTitleText;
    private Spinner versionSpinner;
    private TextView releaseNotesText;
    private LinearLayout assetsList;
    private ProgressBar downloadProgressBar;
    private TextView downloadStatusText;
    private Button downloadButton;
    private EditText githubTokenInput;

    private String selectedRepo = "";
    private JSONArray fetchedReleases = null;
    private boolean isDownloading = false;

    private final Runnable folderRefreshRunnable = () -> {
        if (!running && folderUri != null) {
            refreshPayloads();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        configureWindow();
        restoreRepositories();
        buildInterface();
        restoreSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!running) {
            refreshPayloads();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSettings();
    }

    @Override
    protected void onDestroy() {
        unregisterFolderObserver();
        mainHandler.removeCallbacks(folderRefreshRunnable);
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
        registerFolderObserver();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.parseColor(COLOR_BG));
        window.setNavigationBarColor(Color.parseColor(COLOR_BG));
        // Reset light status bar flags since we're using a dark status bar
        window.getDecorView().setSystemUiVisibility(0);
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(COLOR_BG));

        // Use WindowInsets to add padding matching status and navigation bars dynamically
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(dp(16), top + dp(12), dp(16), bottom + dp(8));
            return insets;
        });

        // Header Navigation Tab bar
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(0, 0, 0, dp(12));

        Button btnInject = tabButton("Inject Payloads", true);
        Button btnDownload = tabButton("Download Hub", false);

        btnInject.setOnClickListener(v -> selectTab(true, btnInject, btnDownload));
        btnDownload.setOnClickListener(v -> selectTab(false, btnInject, btnDownload));

        tabBar.addView(btnInject, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Space tabGap = new Space(this);
        tabBar.addView(tabGap, new LinearLayout.LayoutParams(dp(12), 1));
        tabBar.addView(btnDownload, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(tabBar);

        // Frame switcher
        FrameLayout frameLayout = new FrameLayout(this);

        injectLayout = buildInjectLayout();
        downloadLayout = buildDownloadLayout();

        frameLayout.addView(injectLayout);
        frameLayout.addView(downloadLayout);

        root.addView(frameLayout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);

        // Default state selection
        selectTab(true, btnInject, btnDownload);
        renderRepoChips();
        if (!selectedRepo.isEmpty()) {
            onRepoChipSelected(selectedRepo);
        }
    }

    private Button tabButton(String label, boolean active) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(14);
        btn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        btn.setMinWidth(0);
        btn.setMinHeight(dp(44));
        btn.setMinimumHeight(dp(44));
        btn.setPadding(dp(16), 0, dp(16), 0);
        updateTabButtonStyle(btn, active);
        return btn;
    }

    private void updateTabButtonStyle(Button btn, boolean active) {
        if (active) {
            btn.setTextColor(Color.parseColor(COLOR_TEXT));
            btn.setBackground(rounded(COLOR_ACCENT, COLOR_ACCENT, 8));
        } else {
            btn.setTextColor(Color.parseColor(COLOR_MUTED));
            btn.setBackground(rounded(COLOR_CARD, COLOR_BORDER, 8));
        }
    }

    private void selectTab(boolean isInject, Button btnInject, Button btnDownload) {
        if (isInject) {
            injectLayout.setVisibility(View.VISIBLE);
            downloadLayout.setVisibility(View.GONE);
            updateTabButtonStyle(btnInject, true);
            updateTabButtonStyle(btnDownload, false);
            // Refresh local payloads when returning to injector tab
            refreshPayloads();
        } else {
            injectLayout.setVisibility(View.GONE);
            downloadLayout.setVisibility(View.VISIBLE);
            updateTabButtonStyle(btnInject, false);
            updateTabButtonStyle(btnDownload, true);
        }
    }

    private LinearLayout buildInjectLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        layout.addView(buildTargetCard());
        layout.addView(buildFolderCard());

        LinearLayout listHeader = new LinearLayout(this);
        listHeader.setOrientation(LinearLayout.HORIZONTAL);
        listHeader.setGravity(Gravity.CENTER_VERTICAL);
        listHeader.setPadding(0, dp(4), 0, dp(6));

        TextView payloadHeader = text("Payload Queue", 15, COLOR_TEXT, true);
        listHeader.addView(payloadHeader, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        countText = text("0 payloads", 12, COLOR_MUTED, false);
        listHeader.addView(countText);
        layout.addView(listHeader);

        ScrollView listScroll = new ScrollView(this);
        listScroll.setFillViewport(false);
        payloadList = new LinearLayout(this);
        payloadList.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(payloadList);
        layout.addView(listScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        layout.addView(buildProgressArea());
        layout.addView(buildActionBar());

        return layout;
    }

    private LinearLayout buildDownloadLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        // Card 1: Repo Select and Manager
        LinearLayout cardRepos = card();
        cardRepos.setOrientation(LinearLayout.VERTICAL);

        LinearLayout reposHeader = new LinearLayout(this);
        reposHeader.setOrientation(LinearLayout.HORIZONTAL);
        reposHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView reposTitle = text("Homebrew Repositories", 13, COLOR_MUTED, true);
        reposHeader.addView(reposTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button btnAdd = smallButton("+", COLOR_ACCENT, "#FFFFFF");
        btnAdd.setOnClickListener(v -> showAddRepoDialog());
        reposHeader.addView(btnAdd, new LinearLayout.LayoutParams(dp(36), dp(36)));

        Space gapAdd = new Space(this);
        reposHeader.addView(gapAdd, new LinearLayout.LayoutParams(dp(8), 1));

        Button btnDel = smallButton("-", "#991B1B", "#FFFFFF");
        btnDel.setOnClickListener(v -> removeRepository());
        reposHeader.addView(btnDel, new LinearLayout.LayoutParams(dp(36), dp(36)));

        cardRepos.addView(reposHeader);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setPadding(0, dp(8), 0, 0);
        scroll.setHorizontalScrollBarEnabled(false);
        repoChipsContainer = new LinearLayout(this);
        repoChipsContainer.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(repoChipsContainer);
        cardRepos.addView(scroll);

        layout.addView(cardRepos);

        // Card 2: Selected Repo version dropdown & Assets list
        LinearLayout cardDetails = card();
        cardDetails.setOrientation(LinearLayout.VERTICAL);

        LinearLayout detailsHeader = new LinearLayout(this);
        detailsHeader.setOrientation(LinearLayout.HORIZONTAL);
        detailsHeader.setGravity(Gravity.CENTER_VERTICAL);
        detailsHeader.setPadding(0, 0, 0, dp(8));

        repoTitleText = text("Select a Repo", 15, COLOR_TEXT, true);
        detailsHeader.addView(repoTitleText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView verLabel = text("Version:", 12, COLOR_MUTED, false);
        verLabel.setPadding(0, 0, dp(6), 0);
        detailsHeader.addView(verLabel);

        versionSpinner = new Spinner(this);
        // Minimum width to prevent spinner layout glitching
        versionSpinner.setMinimumWidth(dp(120));
        detailsHeader.addView(versionSpinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cardDetails.addView(detailsHeader);

        // Scrollable list of files
        ScrollView assetsScroll = new ScrollView(this);
        LinearLayout.LayoutParams assetsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130));
        assetsScroll.setLayoutParams(assetsParams);
        assetsScroll.setBackground(rounded("#15ffffff", COLOR_BORDER, 6));

        assetsList = new LinearLayout(this);
        assetsList.setOrientation(LinearLayout.VERTICAL);
        assetsScroll.addView(assetsList);
        cardDetails.addView(assetsScroll);

        // Download progress bar and labels
        downloadProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        downloadProgressBar.setMax(100);
        downloadProgressBar.setProgress(0);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        progressParams.setMargins(0, dp(12), 0, 0);
        cardDetails.addView(downloadProgressBar, progressParams);

        downloadStatusText = text("Idle", 12, COLOR_MUTED, false);
        downloadStatusText.setPadding(0, dp(4), 0, 0);
        cardDetails.addView(downloadStatusText);

        layout.addView(cardDetails);

        // Card 3: Release Notes
        LinearLayout cardNotes = card();
        cardNotes.setOrientation(LinearLayout.VERTICAL);
        cardNotes.addView(text("Release Notes", 13, COLOR_MUTED, true));

        ScrollView notesScroll = new ScrollView(this);
        LinearLayout.LayoutParams notesParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100));
        notesScroll.setLayoutParams(notesParams);
        notesScroll.setPadding(0, dp(6), 0, 0);

        releaseNotesText = text("No repository selected.", 12, COLOR_TEXT, false);
        releaseNotesText.setLineSpacing(0, 1.15f);
        notesScroll.addView(releaseNotesText);

        cardNotes.addView(notesScroll);
        layout.addView(cardNotes);

        // Card 4: GitHub API Token config
        LinearLayout cardToken = card();
        cardToken.setOrientation(LinearLayout.VERTICAL);
        cardToken.setPadding(dp(10), dp(8), dp(10), dp(8));

        LinearLayout tokenRow = new LinearLayout(this);
        tokenRow.setOrientation(LinearLayout.HORIZONTAL);
        tokenRow.setGravity(Gravity.CENTER_VERTICAL);

        tokenRow.addView(text("GitHub Token (Optional):", 12, COLOR_MUTED, false));
        Space tokenGap = new Space(this);
        tokenRow.addView(tokenGap, new LinearLayout.LayoutParams(dp(8), 1));

        githubTokenInput = compactInput("Paste access token");
        githubTokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        githubTokenInput.setText(prefs.getString("github_token", ""));
        githubTokenInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                prefs.edit().putString("github_token", s.toString().trim()).apply();
            }
        });
        tokenRow.addView(githubTokenInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        cardToken.addView(tokenRow);
        layout.addView(cardToken);

        return layout;
    }

    private View buildTargetCard() {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);

        TextView label = text("Target PS5", 14, COLOR_TEXT, true);
        card.addView(label);

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.HORIZONTAL);
        fields.setGravity(Gravity.CENTER_VERTICAL);
        fields.setPadding(0, dp(8), 0, 0);

        hostInput = input("Host / IP");
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        fields.addView(hostInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Space fieldGap = new Space(this);
        fields.addView(fieldGap, new LinearLayout.LayoutParams(dp(10), 1));

        portInput = input("Port");
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
        TextView label = text("Payload Folder", 14, COLOR_TEXT, true);
        folderText = text("No folder selected", 12, COLOR_MUTED, false);
        folderText.setPadding(0, dp(4), dp(8), 0);
        folderText.setSingleLine(true);
        folderText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        textColumn.addView(label);
        textColumn.addView(folderText);
        row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);

        chooseButton = button("Choose", COLOR_ACCENT, "#FFFFFF");
        chooseButton.setOnClickListener(v -> chooseFolder());
        buttonRow.addView(chooseButton, new LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));

        Space refreshGap = new Space(this);
        buttonRow.addView(refreshGap, new LinearLayout.LayoutParams(dp(6), 1));

        refreshButton = smallButton("Refresh", COLOR_CARD, COLOR_TEXT);
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

        statusText = text("Ready", 12, COLOR_MUTED, false);
        statusText.setPadding(0, dp(6), 0, dp(3));
        panel.addView(statusText);

        logText = text("Ready.", 12, COLOR_TEXT, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setPadding(dp(9), dp(7), dp(9), dp(7));
        logText.setBackground(rounded(COLOR_CARD, COLOR_BORDER, 8));
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

        injectSelectedButton = button("Inject Selected", COLOR_ACCENT, "#FFFFFF");
        injectSelectedButton.setOnClickListener(v -> injectSelected());
        actions.addView(injectSelectedButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Space gapOne = new Space(this);
        actions.addView(gapOne, new LinearLayout.LayoutParams(dp(8), 1));

        injectAllButton = button("Inject All", COLOR_CARD, COLOR_TEXT);
        // Style Inject All with a clear accent border
        injectAllButton.setBackground(rounded(COLOR_CARD, COLOR_ACCENT, 8));
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
            registerFolderObserver();
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

    private void restoreRepositories() {
        String saved = prefs.getString("repositories_list", "");
        repositories.clear();
        if (saved.isEmpty()) {
            repositories.addAll(Arrays.asList(DEFAULT_REPOS));
        } else {
            repositories.addAll(Arrays.asList(saved.split("\n")));
        }
        selectedRepo = prefs.getString("selected_repository", repositories.get(0));
    }

    private void saveRepositories() {
        StringBuilder sb = new StringBuilder();
        for (String r : repositories) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(r);
        }
        prefs.edit()
            .putString("repositories_list", sb.toString())
            .putString("selected_repository", selectedRepo)
            .apply();
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
            savePayloadOrder();
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
            if (ignoredFileCount > 0) {
                appendLog(ignoredSummary());
            }
        } else {
            renderPayloadList();
            statusText.setText("Ready");
            String message = "Loaded " + payloads.size() + " payload" + (payloads.size() == 1 ? "" : "s") + ".";
            if (ignoredFileCount > 0) {
                message += " " + ignoredSummary();
            }
            appendLog(message);
        }
    }

    private List<PayloadItem> loadPayloads(Uri treeUri) {
        List<PayloadItem> items = new ArrayList<>();
        Set<String> seenDocumentIds = new HashSet<>();
        ignoredFileCount = 0;
        String parentDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);

        try (Cursor cursor = queryPayloadDocuments(childrenUri)) {
            if (cursor == null) {
                return items;
            }

            int documentIdIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE);
            int modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            int flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS);
            int originalPathIndex = cursor.getColumnIndex(COLUMN_ORIGINAL_RELATIVE_PATH_COMPAT);

            while (cursor.moveToNext()) {
                String mimeType = cursor.getString(mimeIndex);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    continue;
                }

                String name = cursor.getString(nameIndex);
                String documentId = cursor.getString(documentIdIndex);
                int flags = flagsIndex >= 0 && !cursor.isNull(flagsIndex) ? cursor.getInt(flagsIndex) : 0;
                String originalPath = originalPathIndex >= 0 && !cursor.isNull(originalPathIndex)
                    ? cursor.getString(originalPathIndex)
                    : null;

                if (!isPayloadFileName(name)
                    || documentId == null
                    || !seenDocumentIds.add(documentId)
                    || isUnavailableOrTrashed(flags, originalPath)) {
                    ignoredFileCount++;
                    continue;
                }

                Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                if (!isReadableDocument(documentUri)) {
                    ignoredFileCount++;
                    continue;
                }

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

    private Cursor queryPayloadDocuments(Uri childrenUri) {
        String[] baseProjection = new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        };
        String[] extendedProjection = new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            COLUMN_ORIGINAL_RELATIVE_PATH_COMPAT
        };

        try {
            return getContentResolver().query(
                childrenUri,
                extendedProjection,
                null,
                null,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC"
            );
        } catch (RuntimeException extendedQueryFailure) {
            try {
                return getContentResolver().query(
                    childrenUri,
                    baseProjection,
                    null,
                    null,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC"
                );
            } catch (RuntimeException baseQueryFailure) {
                baseQueryFailure.addSuppressed(extendedQueryFailure);
                throw baseQueryFailure;
            }
        }
    }

    private boolean isPayloadFileName(String name) {
        if (name == null || name.isEmpty() || name.startsWith(".")) {
            return false;
        }

        String lowerName = name.toLowerCase(Locale.ROOT);
        if (lowerName.startsWith("trashed-")
            || lowerName.startsWith("deleted-")
            || lowerName.startsWith("recycled-")) {
            return false;
        }

        // Restrict local list strictly to .elf, .bin, and .js files
        return lowerName.endsWith(".elf") || lowerName.endsWith(".bin") || lowerName.endsWith(".js");
    }

    private boolean isUnavailableOrTrashed(int flags, String originalPath) {
        int unavailableFlags = FLAG_PARTIAL_COMPAT
            | FLAG_VIRTUAL_DOCUMENT_COMPAT
            | FLAG_SUPPORTS_RESTORE_COMPAT;
        return (flags & unavailableFlags) != 0 || (originalPath != null && !originalPath.isEmpty());
    }

    private boolean isReadableDocument(Uri documentUri) {
        try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(documentUri, "r")) {
            return descriptor != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String ignoredSummary() {
        return "Ignored " + ignoredFileCount
            + " hidden, trashed, duplicate, incomplete, or unreadable file"
            + (ignoredFileCount == 1 ? "." : "s.");
    }

    private void registerFolderObserver() {
        unregisterFolderObserver();
        if (folderUri == null) {
            return;
        }

        String documentId;
        try {
            documentId = DocumentsContract.getTreeDocumentId(folderUri);
        } catch (RuntimeException ignored) {
            folderObserver = null;
            return;
        }

        folderObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                mainHandler.removeCallbacks(folderRefreshRunnable);
                mainHandler.postDelayed(folderRefreshRunnable, 250);
            }
        };

        boolean registered = registerFolderObserverUri(
            DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, documentId)
        );
        String authority = folderUri.getAuthority();
        if (authority != null) {
            registered |= registerFolderObserverUri(
                DocumentsContract.buildChildDocumentsUri(authority, documentId)
            );
        }

        if (!registered) {
            folderObserver = null;
        }
    }

    private boolean registerFolderObserverUri(Uri uri) {
        try {
            getContentResolver().registerContentObserver(uri, false, folderObserver);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void unregisterFolderObserver() {
        mainHandler.removeCallbacks(folderRefreshRunnable);
        if (folderObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(folderObserver);
            } catch (Exception ignored) {
            }
        }
        folderObserver = null;
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
        row.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        CheckBox checkBox = new CheckBox(this);
        checkBox.setChecked(item.selected);
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> item.selected = isChecked);
        topRow.addView(checkBox);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);

        TextView name = text(item.name, 14, COLOR_TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView meta = text(formatSize(item.size) + "  " + formatDate(item.modified), 11, COLOR_MUTED, false);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, dp(2), 0, 0);
        textColumn.addView(name);
        textColumn.addView(meta);
        topRow.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        item.statusView = text(item.status, 11, "#2563EB", true);
        item.statusView.setGravity(Gravity.CENTER);
        item.statusView.setPadding(dp(7), dp(4), dp(7), dp(4));
        item.statusView.setBackground(rounded(COLOR_BG, COLOR_BORDER, 6));
        topRow.addView(item.statusView);

        row.addView(topRow);

        LinearLayout inputsRow = new LinearLayout(this);
        inputsRow.setOrientation(LinearLayout.HORIZONTAL);
        inputsRow.setGravity(Gravity.CENTER_VERTICAL);
        inputsRow.setPadding(0, dp(8), 0, 0);

        TextView portLabel = text("Port", 11, COLOR_MUTED, false);
        inputsRow.addView(portLabel);

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
        LinearLayout.LayoutParams portParams = new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT);
        portParams.setMargins(dp(6), 0, dp(16), 0);
        inputsRow.addView(portField, portParams);

        TextView delayLabel = text("Delay", 11, COLOR_MUTED, false);
        inputsRow.addView(delayLabel);

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
        LinearLayout.LayoutParams delayParams = new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT);
        delayParams.setMargins(dp(6), 0, 0, 0);
        inputsRow.addView(delayField, delayParams);

        row.addView(inputsRow);

        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);
        actionsRow.setPadding(0, dp(10), 0, 0);

        Space actionSpacer = new Space(this);
        actionsRow.addView(actionSpacer, new LinearLayout.LayoutParams(0, 1, 1));

        Button upButton = smallButton("Up", COLOR_BG, COLOR_TEXT);
        upButton.setEnabled(index > 0 && !running);
        upButton.setOnClickListener(v -> movePayload(index, index - 1));
        actionsRow.addView(upButton, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));

        Button downButton = smallButton("Down", COLOR_BG, COLOR_TEXT);
        downButton.setEnabled(index < payloads.size() - 1 && !running);
        downButton.setOnClickListener(v -> movePayload(index, index + 1));
        LinearLayout.LayoutParams downParams = new LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT);
        downParams.setMargins(dp(6), 0, 0, 0);
        actionsRow.addView(downButton, downParams);

        Button deleteButton = smallButton("Delete", "#991B1B", "#FFFFFF");
        deleteButton.setEnabled(!running);
        deleteButton.setOnClickListener(v -> deletePayload(item));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT);
        deleteParams.setMargins(dp(6), 0, 0, 0);
        actionsRow.addView(deleteButton, deleteParams);

        row.addView(actionsRow);
        return row;
    }

    private void deletePayload(PayloadItem item) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Payload")
            .setMessage("Are you sure you want to permanently delete " + item.name + " from storage?")
            .setPositiveButton("Delete", (dialog, which) -> {
                try {
                    boolean deleted = DocumentsContract.deleteDocument(getContentResolver(), item.uri);
                    if (deleted) {
                        Toast.makeText(this, "Deleted " + item.name, Toast.LENGTH_SHORT).show();
                        appendLog("Deleted " + item.name + " from folder.");
                        refreshPayloads();
                    } else {
                        throw new Exception("Document deletion returned false.");
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Failed to delete: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    appendLog("Failed to delete " + item.name + ": " + e.getMessage());
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void addEmptyState(String message) {
        TextView empty = text(message, 14, COLOR_MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(16), dp(26), dp(16), dp(26));
        empty.setBackground(rounded(COLOR_CARD, COLOR_BORDER, 8));
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
            setItemStatus(item, "Queued", COLOR_MUTED, COLOR_CARD);
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
                postItemStatus(item, "Waiting", COLOR_ACCENT, COLOR_BG);
                postLog("Waiting " + trimDelay(item.delaySeconds) + " seconds before " + item.name + "...");
                sleepDelay(item.delaySeconds);
            }

            postStatus("Sending " + (index + 1) + "/" + total);
            postItemStatus(item, "Sending", "#D97706", "#FEF3C7"); // Amber
            postLog("Sending " + item.name + " to " + host + ":" + item.portNumber);

            try {
                long bytes = sendPayload(item.uri, host, item.portNumber);
                sentCount++;
                postItemStatus(item, "Sent", "#16A34A", "#DCFCE7"); // Green
                postLog("Sent " + formatSize(bytes) + " from " + item.name);
            } catch (Exception exc) {
                postItemStatus(item, "Failed", "#DC2626", "#FEE2E2"); // Red
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
            item.statusView.setBackground(rounded(fillColor, COLOR_BORDER, 6));
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
        TextView fullLogText = text(fullLogText(), 12, COLOR_TEXT, false);
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

    // ----------------- DOWNLOADING & GITHUB API MODULE -----------------
    private void renderRepoChips() {
        repoChipsContainer.removeAllViews();
        for (String repo : repositories) {
            boolean isSelected = repo.equals(selectedRepo);
            TextView chip = text(repo, 12, isSelected ? "#FFFFFF" : COLOR_MUTED, true);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            chip.setBackground(rounded(isSelected ? COLOR_ACCENT : COLOR_CARD, COLOR_BORDER, 16));
            chip.setGravity(Gravity.CENTER);
            chip.setOnClickListener(v -> onRepoChipSelected(repo));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, dp(8), 0);
            chip.setLayoutParams(params);

            repoChipsContainer.addView(chip);
        }
    }

    private void onRepoChipSelected(String repo) {
        if (isDownloading) {
            Toast.makeText(this, "Please wait for the current download to finish first.", Toast.LENGTH_SHORT).show();
            return;
        }
        selectedRepo = repo;
        saveRepositories();
        renderRepoChips();
        repoTitleText.setText(repo);
        fetchReleasesAsync(repo);
    }

    private void fetchReleasesAsync(String repo) {
        // Clear spinner & notes first
        ArrayAdapter<String> loadingAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"Loading..."});
        versionSpinner.setAdapter(loadingAdapter);
        releaseNotesText.setText("Loading release details from GitHub...");
        assetsList.removeAllViews();

        executor.execute(() -> {
            try {
                String token = prefs.getString("github_token", "").trim();
                String urlStr = "https://api.github.com/repos/" + repo + "/releases";
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Blurfer-Android");
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(12000);
                if (!token.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        JSONArray releases = new JSONArray(sb.toString());
                        mainHandler.post(() -> onReleasesFetched(repo, releases, null));
                    }
                } else {
                    String error = "HTTP Error " + code;
                    if (code == 404) {
                        error = "Repository not found.";
                    } else if (code == 403) {
                        error = "API rate limit exceeded. Please configure a GitHub Token.";
                    }
                    final String finalError = error;
                    mainHandler.post(() -> onReleasesFetched(repo, null, finalError));
                }
            } catch (Exception e) {
                final String finalError = "Connection error: " + e.getMessage();
                mainHandler.post(() -> onReleasesFetched(repo, null, finalError));
            }
        });
    }

    private void onReleasesFetched(String repo, JSONArray releases, String error) {
        if (!repo.equals(selectedRepo)) {
            return;
        }

        if (error != null) {
            releaseNotesText.setText("Failed to load releases:\n" + error);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"Error"});
            versionSpinner.setAdapter(adapter);
            assetsList.removeAllViews();
            return;
        }

        this.fetchedReleases = releases;
        if (releases.length() == 0) {
            releaseNotesText.setText("This repository has no releases.");
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"No Releases"});
            versionSpinner.setAdapter(adapter);
            assetsList.removeAllViews();
            return;
        }

        List<String> tags = new ArrayList<>();
        for (int i = 0; i < releases.length(); i++) {
            try {
                tags.add(releases.getJSONObject(i).getString("tag_name"));
            } catch (Exception ignored) {
            }
        }

        setupSpinner(tags);
    }

    private void setupSpinner(List<String> items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(Color.parseColor(COLOR_TEXT));
                    ((TextView) v).setTextSize(13);
                    ((TextView) v).setPadding(dp(6), dp(6), dp(6), dp(6));
                }
                return v;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(Color.parseColor(COLOR_TEXT));
                    ((TextView) v).setBackgroundColor(Color.parseColor(COLOR_CARD));
                    ((TextView) v).setPadding(dp(12), dp(12), dp(12), dp(12));
                }
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        versionSpinner.setAdapter(adapter);
        versionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onReleaseSelected(items.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        if (!items.isEmpty()) {
            onReleaseSelected(items.get(0));
        }
    }

    private void onReleaseSelected(String tagName) {
        if (fetchedReleases == null) return;

        JSONObject selectedRelease = null;
        for (int i = 0; i < fetchedReleases.length(); i++) {
            try {
                JSONObject r = fetchedReleases.getJSONObject(i);
                if (r.getString("tag_name").equals(tagName)) {
                    selectedRelease = r;
                    break;
                }
            } catch (Exception ignored) {
            }
        }

        if (selectedRelease == null) return;

        try {
            String body = selectedRelease.optString("body", "No description provided.");
            releaseNotesText.setText(body);

            assetsList.removeAllViews();
            JSONArray assets = selectedRelease.getJSONArray("assets");
            List<JSONObject> filteredAssets = new ArrayList<>();
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.getString("name");
                String lower = name.toLowerCase(Locale.ROOT);
                // Exclude .zip/other formats, only list payload-related formats
                if (lower.endsWith(".elf") || lower.endsWith(".bin") || lower.endsWith(".js")) {
                    filteredAssets.add(asset);
                }
            }

            if (filteredAssets.isEmpty()) {
                TextView empty = text("No assets (.elf/.bin/.js) in this release.", 12, COLOR_MUTED, false);
                empty.setPadding(dp(8), dp(12), dp(8), dp(12));
                assetsList.addView(empty);
            } else {
                for (int i = 0; i < filteredAssets.size(); i++) {
                    assetsList.addView(buildAssetRow(filteredAssets.get(i), i));
                }
            }
        } catch (Exception e) {
            releaseNotesText.setText("Error loading assets: " + e.getMessage());
        }
    }

    private View buildAssetRow(JSONObject asset, int index) throws Exception {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));

        if (index % 2 != 0) {
            row.setBackgroundColor(Color.parseColor("#08ffffff")); // Zebra striping
        }

        String name = asset.getString("name");
        long size = asset.getLong("size");
        String url = asset.getString("browser_download_url");

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView nameTxt = text(name, 13, COLOR_TEXT, true);
        nameTxt.setSingleLine(true);
        nameTxt.setEllipsize(TextUtils.TruncateAt.END);

        TextView sizeTxt = text(formatSize(size), 10, COLOR_MUTED, false);

        textCol.addView(nameTxt);
        textCol.addView(sizeTxt);

        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button downloadBtn = smallButton("Get", COLOR_ACCENT, "#FFFFFF");
        downloadBtn.setOnClickListener(v -> startAssetDownload(name, url, size));
        row.addView(downloadBtn, new LinearLayout.LayoutParams(dp(56), dp(36)));

        return row;
    }

    private void setDownloadControlsEnabled(boolean enabled) {
        versionSpinner.setEnabled(enabled);
        for (int i = 0; i < assetsList.getChildCount(); i++) {
            View child = assetsList.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) child;
                View btn = row.getChildAt(row.getChildCount() - 1);
                if (btn instanceof Button) {
                    btn.setEnabled(enabled);
                }
            }
        }
    }

    private void startAssetDownload(String name, String urlStr, long size) {
        if (isDownloading) {
            Toast.makeText(this, "A download is already in progress.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (folderUri == null) {
            Toast.makeText(this, "Choose a payload folder in the 'Inject Payloads' tab first.", Toast.LENGTH_LONG).show();
            return;
        }

        isDownloading = true;
        setDownloadControlsEnabled(false);
        downloadProgressBar.setProgress(0);
        downloadStatusText.setText("Connecting...");

        executor.execute(() -> runDownload(name, urlStr, size));
    }

    private void runDownload(String name, String urlStr, long size) {
        HttpURLConnection conn = null;
        try {
            String currentUrl = urlStr;
            int redirectCount = 0;
            while (redirectCount < 5) {
                URL url = new URL(currentUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Blurfer-Android");
                String token = prefs.getString("github_token", "").trim();
                if (!token.isEmpty() && currentUrl.contains("api.github.com")) {
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                }
                conn.setInstanceFollowRedirects(true);
                int status = conn.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_TEMP
                    || status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == 307 || status == 308) {
                    currentUrl = conn.getHeaderField("Location");
                    conn.disconnect();
                    redirectCount++;
                } else {
                    break;
                }
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                ContentResolver resolver = getContentResolver();
                deleteDocumentIfExists(folderUri, name);
                Uri parentDocUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, DocumentsContract.getTreeDocumentId(folderUri));
                Uri docUri = DocumentsContract.createDocument(resolver, parentDocUri, "application/octet-stream", name);
                if (docUri == null) {
                    throw new Exception("Could not create document in the folder.");
                }

                try (
                    InputStream input = conn.getInputStream();
                    OutputStream output = resolver.openOutputStream(docUri)
                ) {
                    byte[] buffer = new byte[16384];
                    int read;
                    long bytesRead = 0;
                    long total = size > 0 ? size : conn.getContentLength();
                    long lastUpdate = 0;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                        bytesRead += read;

                        long now = System.currentTimeMillis();
                        if (now - lastUpdate > 100) {
                            final long finalBytes = bytesRead;
                            final long finalTotal = total;
                            mainHandler.post(() -> updateDownloadProgress(finalBytes, finalTotal));
                            lastUpdate = now;
                        }
                    }
                    output.flush();
                }
                mainHandler.post(() -> onDownloadFinished(name, null));
            } else {
                throw new Exception("HTTP response code " + code);
            }
        } catch (Exception e) {
            mainHandler.post(() -> onDownloadFinished(name, e.getMessage()));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void deleteDocumentIfExists(Uri treeUri, String displayName) {
        String parentDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);
        try (Cursor cursor = queryPayloadDocuments(childrenUri)) {
            if (cursor != null) {
                int idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                while (cursor.moveToNext()) {
                    String name = cursor.getString(nameIndex);
                    if (displayName.equalsIgnoreCase(name)) {
                        String docId = cursor.getString(idIndex);
                        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                        DocumentsContract.deleteDocument(getContentResolver(), docUri);
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void updateDownloadProgress(long current, long total) {
        if (total > 0) {
            int pct = (int) (current * 100 / total);
            downloadProgressBar.setProgress(pct);
            downloadStatusText.setText("Downloading: " + formatSize(current) + " / " + formatSize(total) + " (" + pct + "%)");
        } else {
            downloadProgressBar.setProgress(0);
            downloadStatusText.setText("Downloading: " + formatSize(current));
        }
    }

    private void onDownloadFinished(String name, String error) {
        isDownloading = false;
        setDownloadControlsEnabled(true);
        if (error == null) {
            downloadProgressBar.setProgress(100);
            downloadStatusText.setText("Finished! Saved " + name);
            appendLog("Successfully downloaded " + name + " from GitHub.");
            refreshPayloads();
            Toast.makeText(this, "Download finished!", Toast.LENGTH_SHORT).show();
        } else {
            downloadProgressBar.setProgress(0);
            downloadStatusText.setText("Failed: " + error);
            appendLog("Download failed: " + error);
            Toast.makeText(this, "Download failed: " + error, Toast.LENGTH_LONG).show();
        }
    }

    private void showAddRepoDialog() {
        EditText input = new EditText(this);
        input.setHint("e.g. owner/repo or github link");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(Color.parseColor(COLOR_TEXT));
        input.setPadding(dp(16), dp(12), dp(16), dp(12));

        new AlertDialog.Builder(this)
            .setTitle("Add Repository")
            .setView(input)
            .setPositiveButton("Add", (dialog, which) -> {
                String val = input.getText().toString().trim();
                addRepository(val);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void addRepository(String input) {
        String repoSlug = null;
        if (input.contains("github.com/")) {
            String[] parts = input.split("github.com/")[1].split("/");
            if (parts.length >= 2) {
                repoSlug = parts[0] + "/" + parts[1];
            }
        } else {
            String[] parts = input.split("/");
            if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                repoSlug = input;
            }
        }

        if (repoSlug == null) {
            Toast.makeText(this, "Invalid format. Use 'owner/repo' or a GitHub link.", Toast.LENGTH_SHORT).show();
            return;
        }

        for (String r : repositories) {
            if (r.equalsIgnoreCase(repoSlug)) {
                Toast.makeText(this, "Repository is already in list.", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        repositories.add(repoSlug);
        selectedRepo = repoSlug;
        saveRepositories();
        renderRepoChips();
        onRepoChipSelected(repoSlug);
    }

    private void removeRepository() {
        if (repositories.size() <= 1) {
            Toast.makeText(this, "You must keep at least one repository in the list.", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Remove Repository")
            .setMessage("Are you sure you want to remove " + selectedRepo + " from your list?")
            .setPositiveButton("Remove", (dialog, which) -> {
                repositories.remove(selectedRepo);
                selectedRepo = repositories.get(0);
                saveRepositories();
                renderRepoChips();
                onRepoChipSelected(selectedRepo);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ----------------- UI VIEW CONSTRUCTORS & HELPERS -----------------
    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setBackground(rounded(COLOR_CARD, COLOR_BORDER, 12));
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(12));
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
        view.setTextColor(Color.parseColor(COLOR_TEXT));
        view.setHintTextColor(Color.parseColor(COLOR_MUTED));
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setMinHeight(dp(44));
        view.setMinimumHeight(dp(44));
        view.setBackground(rounded(COLOR_CARD, COLOR_BORDER, 8));
        return view;
    }

    private EditText compactInput(String hint) {
        EditText view = input(hint);
        view.setTextSize(13);
        view.setPadding(dp(10), 0, dp(10), 0);
        view.setMinHeight(dp(36));
        view.setMinimumHeight(dp(36));
        return view;
    }

    private Button button(String label, String fillColor, String textColor) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setTextColor(Color.parseColor(textColor));
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setBackground(rounded(fillColor, fillColor, 8));
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setMinHeight(dp(48));
        view.setMinimumHeight(dp(48));
        view.setPadding(dp(16), 0, dp(16), 0);
        return view;
    }

    private Button smallButton(String label, String fillColor, String textColor) {
        Button view = button(label, fillColor, textColor);
        view.setTextSize(12);
        view.setMinHeight(dp(36));
        view.setMinimumHeight(dp(36));
        view.setPadding(dp(10), 0, dp(10), 0);
        return view;
    }

    private GradientDrawable rounded(String fill, String stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        if (fill.equalsIgnoreCase("transparent")) {
            drawable.setColor(Color.TRANSPARENT);
        } else {
            drawable.setColor(Color.parseColor(fill));
        }

        if (stroke.equalsIgnoreCase("transparent")) {
            drawable.setStroke(0, Color.TRANSPARENT);
        } else {
            drawable.setStroke(dp(1), Color.parseColor(stroke));
        }
        drawable.setCornerRadius(dp(radiusDp));
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
