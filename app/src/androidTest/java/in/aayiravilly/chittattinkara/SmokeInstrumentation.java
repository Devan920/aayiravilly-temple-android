package in.aayiravilly.chittattinkara;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free emulator smoke check; includes the real hosted page and native quick navigation. */
public final class SmokeInstrumentation extends Instrumentation {
    private Activity activity;
    private WebView web;
    private int completed;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            status("updateOriginAndChecksumValidation", 1);
            checkUpdaterValidation();
            completed++;
            status("updateOriginAndChecksumValidation", 0);
            status("launchAndSecuritySettings", 1);
            Intent launch = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(getTargetContext(), MainActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(launch);
            waitForIdleSync();
            runOnMainSync(() -> {
                web = findWeb(activity.getWindow().getDecorView());
                require(web != null, "Activity did not create a WebView");
                require(web.getSettings().getJavaScriptEnabled(), "Live website JavaScript disabled");
                require(!web.getSettings().getAllowFileAccess(), "Unexpected broad file access");
                require(web.getSettings().getMixedContentMode() == WebSettings.MIXED_CONTENT_NEVER_ALLOW, "Mixed content was allowed");
                require(web.getSettings().getMediaPlaybackRequiresUserGesture(), "Media gesture setting missing");
                require(findButton(activity.getWindow().getDecorView(), "അറിയിപ്പുകൾ") != null, "Native notices navigation missing");
                require(BuildConfig.UPDATE_MANIFEST_URL.equals("https://github.com/Devan920/aayiravilly-temple-android/releases/latest/download/update.json"), "Wrong update endpoint");
            });
            completed++;
            status("launchAndSecuritySettings", 0);

            status("liveWebsiteAndNativeNavigation", 1);
            boolean loaded = false;
            long deadline = SystemClock.elapsedRealtime() + 35000;
            while (SystemClock.elapsedRealtime() < deadline) {
                String state = js("Boolean(document.querySelector('#main') && document.title.includes('ആയിരവില്ലി'))");
                if ("true".equals(state)) { loaded = true; break; }
                SystemClock.sleep(500);
            }
            require(loaded, "Live website did not render the expected temple content within 35 seconds (network/site failure or WebView error)");
            android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
            if (screenshot != null) {
                java.io.File directory = getTargetContext().getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
                if (directory != null) {
                    directory.mkdirs();
                    try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, "smoke-home.png"))) {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                    }
                }
                screenshot.recycle();
            }
            runOnMainSync(() -> findButton(activity.getWindow().getDecorView(), "അറിയിപ്പുകൾ").performClick());
            boolean navigated = false;
            deadline = SystemClock.elapsedRealtime() + 10000;
            while (SystemClock.elapsedRealtime() < deadline) {
                if ("true".equals(js("location.hash === '#updates' && Boolean(document.querySelector('#updates'))"))) { navigated = true; break; }
                SystemClock.sleep(250);
            }
            require(navigated, "Native notices button did not reach the live website notices section");
            completed++;
            status("liveWebsiteAndNativeNavigation", 0);
            result.putString("stream", "\nOK (3 smoke checks): update origin/checksum validation, launch, WebView security settings, hosted temple DOM, native notices navigation.\n");
            result.putInt("passed", completed);
            result.putInt("failed", 0);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL after " + completed + " checks: " + error.toString() + "\n");
            result.putInt("passed", completed);
            result.putInt("failed", 1);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private String js(String script) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>("timeout");
        runOnMainSync(() -> web.evaluateJavascript(script, result -> { value.set(result); latch.countDown(); }));
        latch.await(3, TimeUnit.SECONDS);
        return value.get();
    }
    private void status(String test, int code) {
        Bundle status = new Bundle();
        status.putString("class", getClass().getName());
        status.putString("test", test);
        status.putInt("numtests", 3);
        status.putInt("current", completed + (code == 1 ? 1 : 0));
        sendStatus(code, status);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void checkUpdaterValidation() throws Exception {
        require(AppUpdateManager.isTrustedReleaseUrl(new java.net.URI("https://github.com/Devan920/aayiravilly-temple-android/releases/latest/download/update.json")), "Configured release feed rejected");
        require(AppUpdateManager.isTrustedReleaseUrl(new java.net.URI("https://github.com/Devan920/aayiravilly-temple-android/releases/download/v1.0.1/app.apk")), "Versioned release rejected");
        String[] rejected = {
            "http://github.com/Devan920/aayiravilly-temple-android/releases/latest/download/update.json",
            "https://github.com/Other/aayiravilly-temple-android/releases/latest/download/update.json",
            "https://github.com/Devan920/other/releases/latest/download/update.json",
            "https://github.com.evil.example/Devan920/aayiravilly-temple-android/releases/download/v1/app.apk",
            "https://user@github.com/Devan920/aayiravilly-temple-android/releases/latest/download/update.json",
            "https://github.com:8443/Devan920/aayiravilly-temple-android/releases/latest/download/update.json",
            "https://github.com/Devan920/aayiravilly-temple-android/releases/latest/download/update.json?redirect=1",
            "https://github.com/Devan920/aayiravilly-temple-android/releases/latest/download/update.json#fragment",
            "https://github.com/Devan920/aayiravilly-temple-android/releases/%2e%2e/other",
            "https://github.com/Devan920/aayiravilly-temple-android/releases/../other",
            "https://github.com/Devan920/aayiravilly-temple-android/releases/..",
            "https://github.com/Devan920/aayiravilly-temple-android/releases/."
        };
        for (String url : rejected) require(!AppUpdateManager.isTrustedReleaseUrl(new java.net.URI(url)), "Untrusted update URL accepted: " + url);
        require(AppUpdateManager.isTrustedDownloadUrl(new java.net.URI("https://release-assets.githubusercontent.com/example?token=opaque")), "GitHub signed asset redirect rejected");
        require(AppUpdateManager.isTrustedDownloadUrl(new java.net.URI("https://objects.githubusercontent.com/example")), "GitHub objects redirect rejected");
        require(!AppUpdateManager.isTrustedDownloadUrl(new java.net.URI("https://release-assets.githubusercontent.com.evil.example/example")), "Spoofed asset host accepted");
        require(!AppUpdateManager.isTrustedDownloadUrl(new java.net.URI("https://evil.example/example")), "Arbitrary download host accepted");
        String hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        require(java.util.Arrays.equals(AppUpdateManager.decodeHex(hex), AppUpdateManager.decodeHex(hex.toUpperCase(java.util.Locale.ROOT))), "SHA-256 case parsing mismatch");
        require(AppUpdateManager.decodeHex(hex).length == 32, "SHA-256 bytes length incorrect");
        for (String invalid : new String[]{"", "abc", hex + "00", "z" + hex.substring(1)}) {
            boolean rejectedHex = false;
            try { AppUpdateManager.decodeHex(invalid); } catch (IllegalArgumentException expected) { rejectedHex = true; }
            require(rejectedHex, "Invalid SHA-256 accepted");
        }
    }
    private static WebView findWeb(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { WebView found = findWeb(group.getChildAt(i)); if (found != null) return found; }
        }
        return null;
    }
    private static Button findButton(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { Button found = findButton(group.getChildAt(i), label); if (found != null) return found; }
        }
        return null;
    }
}
