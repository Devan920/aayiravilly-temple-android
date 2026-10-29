package in.aayiravilly.chittattinkara;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/** Thin, hardware-accelerated client for the live temple website. */
public final class MainActivity extends Activity {
    private static final int BURGUNDY = Color.rgb(74, 24, 40);
    private static final int CREAM = Color.rgb(255, 248, 236);
    private static final int GOLD = Color.rgb(240, 204, 131);
    private static final int FILE_REQUEST = 41;
    private static final String TRUSTED_HOST = "aayiravillytemplechittattinkara.ai.studio";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private FrameLayout root, webContainer, fullScreen;
    private LinearLayout chrome, errorPanel;
    private ProgressBar progress;
    private TextView status;
    private Button[] navButtons;
    private boolean pageFailed, pageLoaded, activityPaused;
    private long pausedAt;
    private String lastGoodUrl = BuildConfig.WEBSITE_URL;
    private ValueCallback<Uri[]> fileCallback;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private AppUpdateManager updater;
    private final Runnable loadTimeout = () -> {
        if (web != null && progress.getVisibility() == View.VISIBLE) {
            web.stopLoading();
            showError("പേജ് തുറക്കാൻ കൂടുതൽ സമയം എടുക്കുന്നു. ഇന്റർനെറ്റ് പരിശോധിച്ച് വീണ്ടും ശ്രമിക്കുക.");
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        buildLayout();
        createWebView();
        updater = new AppUpdateManager(this);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        if (state == null || web.restoreState(state) == null) load(BuildConfig.WEBSITE_URL);
    }

    private void buildLayout() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BURGUNDY);
        chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.VERTICAL);
        chrome.setBackgroundColor(CREAM);
        root.addView(chrome, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(5), dp(6), dp(5));
        header.setBackgroundColor(BURGUNDY);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                chrome.setPadding(bars.left, 0, bars.right, bars.bottom);
                header.setPadding(dp(14), bars.top + dp(5), dp(6), dp(5));
            } else {
                chrome.setPadding(insets.getSystemWindowInsetLeft(), 0, insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                header.setPadding(dp(14), insets.getSystemWindowInsetTop() + dp(5), dp(6), dp(5));
            }
            return insets;
        });
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("ആയിരവില്ലി ക്ഷേത്രം", 16, CREAM);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titles.addView(title);
        titles.addView(text("ചിറ്റാറ്റിൻകര", 11, GOLD));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        Button refresh = action("↻", "പുതുക്കുക", 25);
        refresh.setOnClickListener(v -> refresh());
        header.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        Button menu = action("⋮", "കൂടുതൽ ഓപ്ഷനുകൾ", 25);
        menu.setOnClickListener(v -> showMenu(menu));
        header.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        chrome.addView(header, new LinearLayout.LayoutParams(-1, -2));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(GOLD));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(BURGUNDY));
        chrome.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        webContainer = new FrameLayout(this);
        chrome.addView(webContainer, new LinearLayout.LayoutParams(-1, 0, 1));
        status = text("", 12, BURGUNDY);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(5), dp(12), dp(5));
        status.setVisibility(View.GONE);
        chrome.addView(status);
        LinearLayout nav = new LinearLayout(this);
        nav.setBackgroundColor(CREAM);
        String[] labels = {"ക്ഷേത്രം", "അറിയിപ്പുകൾ", "പൂജാസമയം", "ഗാലറി"};
        String[] anchors = {"home", "updates", "pooja", "gallery"};
        navButtons = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final String anchor = anchors[i];
            Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
            button.setText(labels[i]);
            button.setTextSize(11);
            button.setAllCaps(false);
            button.setTextColor(BURGUNDY);
            button.setPadding(dp(2), 0, dp(2), 0);
            button.setMinWidth(0);
            button.setOnClickListener(v -> load(BuildConfig.WEBSITE_URL + "#" + anchor));
            navButtons[i] = button;
            nav.addView(button, new LinearLayout.LayoutParams(0, dp(55), 1));
        }
        chrome.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        fullScreen = new FrameLayout(this);
        fullScreen.setBackgroundColor(Color.BLACK);
        fullScreen.setVisibility(View.GONE);
        root.addView(fullScreen, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private TextView text(String content, int size, int color) {
        TextView view = new TextView(this);
        view.setText(content); view.setTextSize(size); view.setTextColor(color);
        return view;
    }
    private Button action(String label, String description, int size) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(label); button.setTextSize(size); button.setTextColor(GOLD);
        button.setContentDescription(description); button.setMinWidth(0); button.setPadding(0, 0, 0, 0);
        return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void createWebView() {
        web = new WebView(this);
        web.setBackgroundColor(CREAM);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Document picker content URIs, never broad filesystem access.
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setBuiltInZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!request.isForMainFrame()) return false;
                if (trusted(uri)) return false;
                openExternal(uri);
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageFailed = false;
                progress.setProgress(0); progress.setVisibility(View.VISIBLE);
                status.setVisibility(View.GONE);
                hideError();
                handler.removeCallbacks(loadTimeout);
                handler.postDelayed(loadTimeout, 30000);
            }
            @Override public void onPageFinished(WebView view, String url) {
                handler.removeCallbacks(loadTimeout);
                progress.setVisibility(View.GONE);
                if (pageFailed) return;
                pageLoaded = true;
                if (trusted(Uri.parse(url))) lastGoodUrl = url;
                // Preserve in-progress input before refreshing a long-suspended page.
                view.evaluateJavascript("(()=>{if(!window.__templeInputWatch){window.__templeInputWatch=true;window.__templeDirty=false;document.addEventListener('input',()=>{window.__templeDirty=true;},{passive:true});}})()", null);
                updateNavigation(url);
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                if (trusted(Uri.parse(url))) {
                    if (!pageFailed) lastGoodUrl = url;
                    updateNavigation(url);
                }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showError("ഇന്റർനെറ്റ് ലഭ്യമല്ല, അല്ലെങ്കിൽ website തുറക്കാനായില്ല. വീണ്ടും ശ്രമിക്കുക.");
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse error) {
                if (request.isForMainFrame() && error.getStatusCode() >= 400) showError("Website ഇപ്പോൾ ലഭ്യമല്ല. കുറച്ച് സമയത്തിനു ശേഷം വീണ്ടും ശ്രമിക്കുക.");
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, SslError error) {
                sslHandler.cancel();
                showError("സുരക്ഷിതമായ ബന്ധം സ്ഥാപിക്കാനായില്ല. ഫോണിലെ തീയതിയും ഇന്റർനെറ്റും പരിശോധിക്കുക.");
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                webContainer.removeView(view);
                view.destroy();
                createWebView();
                showError("പേജ് വീണ്ടും തുറക്കേണ്ടതുണ്ട്. വീണ്ടും ശ്രമിക്കുക അമർത്തുക.");
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) { progress.setProgress(value); }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                picker.addCategory(Intent.CATEGORY_OPENABLE);
                picker.setType("*/*");
                java.util.ArrayList<String> types = new java.util.ArrayList<>();
                for (String type : params.getAcceptTypes()) if (type != null && type.contains("/") && !type.contains(",")) types.add(type);
                if (!types.isEmpty()) picker.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
                picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try { startActivityForResult(picker, FILE_REQUEST); }
                catch (ActivityNotFoundException e) { fileCallback.onReceiveValue(null); fileCallback = null; toast("ഫയൽ തിരഞ്ഞെടുക്കാൻ app ലഭ്യമല്ല."); }
                return true;
            }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customViewCallback != null) { callback.onCustomViewHidden(); return; }
                customViewCallback = callback;
                fullScreen.addView(view, new FrameLayout.LayoutParams(-1, -1));
                fullScreen.setVisibility(View.VISIBLE); chrome.setVisibility(View.GONE);
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsetsController controller = getWindow().getInsetsController();
                    if (controller != null) { controller.hide(WindowInsets.Type.systemBars()); controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
                } else root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
            @Override public void onHideCustomView() { exitFullscreen(); }
        });
        web.setDownloadListener((url, userAgent, disposition, mime, length) -> openExternal(Uri.parse(url)));
        webContainer.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    private boolean trusted(Uri uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && TRUSTED_HOST.equalsIgnoreCase(uri.getHost()) && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null;
    }
    private void openExternal(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("mailto") || scheme.equalsIgnoreCase("tel") || scheme.equalsIgnoreCase("geo"))) {
            toast("ഈ link app-ൽ തുറക്കാനാവില്ല."); return;
        }
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException e) { toast("ഈ link തുറക്കാൻ അനുയോജ്യമായ app ലഭ്യമല്ല."); }
    }
    private void load(String url) {
        if (web != null && trusted(Uri.parse(url))) { pageFailed = false; hideError(); web.loadUrl(url); }
    }
    private void refresh() {
        if (web == null) return;
        if (pageFailed || !pageLoaded) load(lastGoodUrl);
        else web.evaluateJavascript("Boolean(window.__templeDirty)", dirty -> {
            if ("true".equals(dirty)) new AlertDialog.Builder(this).setTitle("പുതുക്കണോ?")
                .setMessage("പേജിൽ നൽകിക്കൊണ്ടിരിക്കുന്ന വിവരങ്ങൾ നഷ്ടപ്പെട്ടേക്കാം.")
                .setNegativeButton("വേണ്ട", null).setPositiveButton("പുതുക്കുക", (d, w) -> web.reload()).show();
            else web.reload();
        });
    }
    private void updateNavigation(String url) {
        String fragment = Uri.parse(url).getFragment();
        String[] sections = {"home", "updates", "pooja", "gallery"};
        for (int i = 0; i < sections.length; i++) {
            boolean selected = sections[i].equals(fragment) || (i == 0 && fragment == null);
            navButtons[i].setSelected(selected);
            navButtons[i].setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            navButtons[i].setBackgroundColor(selected ? Color.rgb(241, 227, 206) : CREAM);
        }
    }
    private void showError(String message) {
        pageFailed = true;
        handler.removeCallbacks(loadTimeout);
        progress.setVisibility(View.GONE);
        if (errorPanel != null) webContainer.removeView(errorPanel);
        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL); errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setBackgroundColor(CREAM); errorPanel.setPadding(dp(30), dp(24), dp(30), dp(24));
        TextView heading = text("വീണ്ടും ബന്ധപ്പെടാം", 22, BURGUNDY);
        heading.setGravity(Gravity.CENTER); errorPanel.addView(heading);
        TextView detail = text(message, 15, BURGUNDY);
        detail.setGravity(Gravity.CENTER); detail.setPadding(0, dp(18), 0, dp(18)); errorPanel.addView(detail);
        Button retry = new Button(this); retry.setText("വീണ്ടും ശ്രമിക്കുക"); retry.setAllCaps(false);
        retry.setOnClickListener(v -> load(lastGoodUrl)); errorPanel.addView(retry);
        webContainer.addView(errorPanel, new FrameLayout.LayoutParams(-1, -1));
    }
    private void hideError() { if (errorPanel != null) { webContainer.removeView(errorPanel); errorPanel = null; } }
    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "പുതുക്കുക");
        menu.getMenu().add(0, 2, 1, "App update പരിശോധിക്കുക");
        menu.getMenu().add(0, 3, 2, "Browser-ൽ തുറക്കുക");
        menu.getMenu().add(0, 4, 3, "App വിവരങ്ങൾ");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: refresh(); break;
                case 2: updater.check(true); break;
                case 3: openExternal(Uri.parse(lastGoodUrl)); break;
                case 4: new AlertDialog.Builder(this).setTitle("ആയിരവില്ലി ക്ഷേത്രം")
                    .setMessage("ചിറ്റാറ്റിൻകര\nVersion " + BuildConfig.VERSION_NAME + "\n\nWebsite-ൽ പ്രസിദ്ധീകരിക്കുന്ന മാറ്റങ്ങൾ app തുറക്കുമ്പോഴും പുതുക്കുമ്പോഴും ലഭിക്കും. App version updates-ന് ഇന്റർനെറ്റ് ആവശ്യമാണ്. Install ചെയ്യാൻ Android അനുമതി ചോദിക്കും.")
                    .setPositiveButton("ശരി", null).show(); break;
            }
            return true;
        });
        menu.show();
    }
    private void exitFullscreen() {
        if (customViewCallback == null) return;
        fullScreen.removeAllViews(); fullScreen.setVisibility(View.GONE); chrome.setVisibility(View.VISIBLE);
        customViewCallback.onCustomViewHidden(); customViewCallback = null;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.show(WindowInsets.Type.systemBars());
        } else root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != FILE_REQUEST || fileCallback == null) return;
        Uri[] results = null;
        if (result == RESULT_OK && data != null) {
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if ("content".equals(uri.getScheme())) uris.add(uri);
            }
            else if (data.getData() != null && "content".equals(data.getData().getScheme())) uris.add(data.getData());
            if (!uris.isEmpty()) results = uris.toArray(new Uri[0]);
        }
        fileCallback.onReceiveValue(results); fileCallback = null;
    }
    @Override protected void onResume() {
        super.onResume();
        activityPaused = false;
        if (web != null) {
            web.onResume(); web.resumeTimers();
            if (pageLoaded && pausedAt > 0 && SystemClock.elapsedRealtime() - pausedAt > 300000 && customViewCallback == null) {
                web.evaluateJavascript("Boolean(window.__templeDirty || Array.from(document.querySelectorAll('audio,video')).some(m=>!m.paused))", dirty -> {
                    if ("false".equals(dirty) && !activityPaused && !isFinishing() && web != null) web.reload();
                });
            }
        }
        if (updater != null) updater.check(false);
    }
    @Override protected void onPause() {
        activityPaused = true; pausedAt = SystemClock.elapsedRealtime();
        if (web != null) {
            web.evaluateJavascript("document.querySelectorAll('audio,video').forEach(m=>m.pause())", null);
            web.onPause(); web.pauseTimers();
        }
        super.onPause();
    }
    @Override public void onBackPressed() {
        handleBack();
    }
    private void handleBack() {
        if (customViewCallback != null) exitFullscreen();
        else if (web != null && web.canGoBack()) { hideError(); web.goBack(); }
        else finish();
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); if (web != null) web.saveState(state); }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (updater != null) updater.close();
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        exitFullscreen();
        if (web != null) { web.stopLoading(); webContainer.removeView(web); web.destroy(); web = null; }
        super.onDestroy();
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
