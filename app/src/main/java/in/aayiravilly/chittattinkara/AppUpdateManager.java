package in.aayiravilly.chittattinkara;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/** APK-distribution updater. Google Play builds should use Play's update mechanism instead. */
public final class AppUpdateManager {
    private static final long CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final int MANIFEST_LIMIT = 64 * 1024;
    private static final long APK_LIMIT = 50L * 1024 * 1024;
    private static final String RELEASE_PREFIX = "/Devan920/aayiravilly-temple-android/releases/";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private final WeakReference<Activity> activity;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final SharedPreferences preferences;
    private volatile boolean closed;
    private volatile boolean cancelDownload;
    private volatile HttpsURLConnection connection;
    private AlertDialog dialog;
    private UpdateInfo downloaded;
    private File downloadedFile;
    private boolean waitingForInstallPermission;

    public AppUpdateManager(Activity owner) {
        activity = new WeakReference<>(owner);
        context = owner.getApplicationContext();
        preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE);
    }

    /** Call on foreground/resume, and with true from the explicit check-for-updates action. */
    public void check(boolean manual) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> check(manual));
            return;
        }
        if (closed || currentActivity() == null) return;
        if (waitingForInstallPermission) {
            waitingForInstallPermission = false;
            if (canInstall()) installDownloaded();
            else toast("Installation permission was not enabled. You can try again from Check for updates.");
            return;
        }
        if (busy.get() || dialog != null) {
            if (manual) toast("An update check or download is already in progress.");
            return;
        }
        if (downloaded != null && downloadedFile != null && downloadedFile.isFile()) {
            if (manual) confirmInstall();
            return;
        }
        String endpoint = BuildConfig.UPDATE_MANIFEST_URL.trim();
        if (endpoint.isEmpty()) {
            if (manual) showMessage("App updates", "Automatic app-version checks are not configured in this build. Published website changes still appear when the page is refreshed.");
            return;
        }
        long now = System.currentTimeMillis();
        long previous = preferences.getLong("last_check", 0);
        if (!manual && now >= previous && now - previous < CHECK_INTERVAL_MS) return;
        if (!busy.compareAndSet(false, true)) return;
        preferences.edit().putLong("last_check", now).apply();
        if (manual) toast("Checking for updates…");
        executor.execute(() -> {
            UpdateInfo update = null;
            String failure = null;
            try {
                URI uri = new URI(endpoint);
                if (!isTrustedReleaseUrl(uri)) throw new IOException("Invalid update address.");
                update = readManifest(uri);
            } catch (Exception e) {
                failure = "Could not check for updates. Check your internet connection and try again.";
            } finally {
                disconnect();
            }
            final UpdateInfo result = update;
            final String error = failure;
            post(() -> {
                busy.set(false);
                if (error != null) {
                    if (manual) showMessage("Update check", error);
                } else if (result != null) {
                    offerUpdate(result);
                } else if (manual) {
                    toast("You have the latest app version.");
                }
            });
        });
    }

    private UpdateInfo readManifest(URI uri) throws Exception {
        HttpsURLConnection http = open(uri, false);
        long length = http.getContentLengthLong();
        if (length > MANIFEST_LIMIT) throw new IOException("Update manifest is too large.");
        byte[] bytes;
        try (InputStream input = http.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                ensureRunning(false);
                if (output.size() + count > MANIFEST_LIMIT) throw new IOException("Update manifest is too large.");
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        String rawCode = json.get("versionCode").toString();
        if (!rawCode.matches("[1-9][0-9]{0,17}")) throw new IOException("Invalid version.");
        long versionCode = Long.parseLong(rawCode);
        String packageName = json.getString("packageName");
        if (!context.getPackageName().equals(packageName)) throw new IOException("Wrong application.");
        URI apk = new URI(json.getString("apkUrl"));
        if (!isTrustedReleaseUrl(apk)) throw new IOException("Invalid download address.");
        String sha256 = json.getString("sha256").toLowerCase(Locale.ROOT);
        if (!sha256.matches("[0-9a-f]{64}")) throw new IOException("Invalid checksum.");
        String name = cleanText(json.getString("versionName"), 80);
        if (name.isEmpty()) throw new IOException("Missing version name.");
        String notes = cleanText(json.optString("releaseNotes", ""), 4000);
        if (versionCode <= installedVersion()) return null;
        return new UpdateInfo(versionCode, name, apk, sha256, notes);
    }

    private void offerUpdate(UpdateInfo info) {
        Activity owner = currentActivity();
        if (owner == null) return;
        String message = "Version " + info.versionName + " is available."
                + (info.notes.isEmpty() ? "" : "\n\n" + info.notes)
                + "\n\nDownload over your current connection? Android will ask you to confirm installation.";
        show(new AlertDialog.Builder(owner).setTitle("App update available").setMessage(message)
                .setNegativeButton("Later", null)
                .setPositiveButton("Download", (d, which) -> download(info)).create());
    }

    private void download(UpdateInfo info) {
        if (closed || !busy.compareAndSet(false, true)) return;
        cancelDownload = false;
        Activity owner = currentActivity();
        if (owner == null) { busy.set(false); return; }
        show(new AlertDialog.Builder(owner).setTitle("Downloading update")
                .setMessage("Downloading and checking version " + info.versionName + "…")
                .setCancelable(false).setNegativeButton("Cancel", (d, which) -> {
                    cancelDownload = true;
                    disconnect();
                }).create());
        executor.execute(() -> {
            File partial = null;
            File completed = null;
            String failure = null;
            try {
                File directory = new File(context.getCacheDir(), "updates");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create update folder.");
                partial = new File(directory, "new.apk.part");
                HttpsURLConnection http = open(info.apkUrl, true);
                long length = http.getContentLengthLong();
                if (length > APK_LIMIT) throw new IOException("Update exceeds the 50 MB download limit.");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long total = 0;
                try (InputStream input = http.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[32768];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        ensureRunning(true);
                        total += count;
                        if (total > APK_LIMIT) throw new IOException("Update exceeds the 50 MB download limit.");
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                ensureRunning(true);
                if (total == 0 || (length >= 0 && total != length)) throw new IOException("Incomplete download.");
                if (!MessageDigest.isEqual(digest.digest(), decodeHex(info.sha256))) throw new IOException("Download checksum did not match.");
                verifyArchive(partial, info);
                completed = new File(directory, "new.apk");
                if (completed.exists() && !completed.delete()) throw new IOException("Cannot replace the previous download.");
                if (!partial.renameTo(completed)) throw new IOException("Cannot save update.");
            } catch (Exception e) {
                failure = cancelDownload ? "Download cancelled." : "The update could not be downloaded or verified. Please try again later.";
                completed = null;
            } finally {
                disconnect();
                if (partial != null && partial.exists()) partial.delete();
            }
            final File result = completed;
            final String error = failure;
            post(() -> {
                busy.set(false);
                dismissDialog();
                if (error != null) {
                    if (cancelDownload) toast(error); else showMessage("Update download", error);
                } else {
                    downloaded = info;
                    downloadedFile = result;
                    beginInstallation();
                }
            });
        });
    }

    private void confirmInstall() {
        Activity owner = currentActivity();
        if (owner == null || downloaded == null) return;
        show(new AlertDialog.Builder(owner).setTitle("Update ready")
                .setMessage("Version " + downloaded.versionName + " has been downloaded and verified. Install it now?")
                .setNegativeButton("Later", null).setPositiveButton("Install", (d, which) -> beginInstallation()).create());
    }

    private void beginInstallation() {
        Activity owner = currentActivity();
        if (owner == null || downloadedFile == null) return;
        if (canInstall()) { installDownloaded(); return; }
        show(new AlertDialog.Builder(owner).setTitle("Allow this app to install its update")
                .setMessage("Android requires permission to install this downloaded APK. Open settings, allow updates from this app, then return here. Android will still ask you to confirm the installation.")
                .setNegativeButton("Later", null).setPositiveButton("Open settings", (d, which) -> {
                    Activity active = currentActivity();
                    if (active == null) return;
                    try {
                        waitingForInstallPermission = true;
                        active.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + context.getPackageName())));
                    } catch (ActivityNotFoundException | SecurityException e) {
                        waitingForInstallPermission = false;
                        showMessage("Installation settings", "This device does not allow opening the installation setting. The verified update is saved; you can try again later.");
                    }
                }).create());
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || context.getPackageManager().canRequestPackageInstalls();
    }

    private void installDownloaded() {
        if (downloadedFile == null || downloaded == null || closed || !busy.compareAndSet(false, true)) return;
        final File file = downloadedFile;
        final UpdateInfo info = downloaded;
        executor.execute(() -> {
            boolean valid = false;
            try {
                if (!file.isFile() || file.length() <= 0 || file.length() > APK_LIMIT) throw new IOException("Missing update.");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = new java.io.FileInputStream(file)) {
                    byte[] buffer = new byte[32768];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        ensureRunning(false);
                        digest.update(buffer, 0, count);
                    }
                }
                if (!MessageDigest.isEqual(digest.digest(), decodeHex(info.sha256))) throw new IOException("Checksum changed.");
                verifyArchive(file, info);
                valid = true;
            } catch (Exception ignored) { }
            final boolean checked = valid;
            post(() -> {
                busy.set(false);
                if (!checked) {
                    downloaded = null;
                    downloadedFile = null;
                    file.delete();
                    showMessage("Cannot install update", "The saved update is no longer valid. Please check for updates again.");
                    return;
                }
                Activity owner = currentActivity();
                if (owner == null) return;
                try {
                    Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".files", file);
                    Intent install = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    owner.startActivity(install);
                } catch (ActivityNotFoundException | IllegalArgumentException | SecurityException e) {
                    showMessage("Cannot open installer", "Android could not open the package installer. The verified update is saved; try again from Check for updates.");
                }
            });
        });
    }

    @SuppressWarnings("deprecation")
    private void verifyArchive(File file, UpdateInfo info) throws Exception {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        if (archive == null || !context.getPackageName().equals(archive.packageName)
                || versionOf(archive) != info.versionCode || versionOf(archive) <= versionOf(installed)) {
            throw new IOException("The update package or version did not match.");
        }
        Signature[] expected = signatures(installed);
        Signature[] actual = signatures(archive);
        // Exact current signer-set matching deliberately rejects unexpected key changes.
        if (expected == null || actual == null || expected.length == 0 || actual.length == 0
                || !new HashSet<>(Arrays.asList(expected)).equals(new HashSet<>(Arrays.asList(actual)))) {
            throw new IOException("The update was signed by a different publisher.");
        }
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        return info.signatures;
    }

    @SuppressWarnings("deprecation")
    private static long versionOf(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private long installedVersion() throws PackageManager.NameNotFoundException {
        return versionOf(context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
    }

    private HttpsURLConnection open(URI initial, boolean downloading) throws Exception {
        URI uri = initial;
        for (int redirects = 0; redirects <= 5; redirects++) {
            ensureRunning(downloading);
            if (!isTrustedDownloadUrl(uri)) throw new IOException("Untrusted update redirect.");
            HttpsURLConnection http = (HttpsURLConnection) uri.toURL().openConnection();
            connection = http;
            http.setInstanceFollowRedirects(false);
            http.setConnectTimeout(10000);
            http.setReadTimeout(15000);
            http.setUseCaches(false);
            http.setRequestProperty("Accept-Encoding", "identity");
            http.setRequestProperty("User-Agent", "AayiravillyTemple/" + BuildConfig.VERSION_NAME);
            int status = http.getResponseCode();
            ensureRunning(downloading);
            if (status == 200) return http;
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = http.getHeaderField("Location");
                http.disconnect();
                if (location == null || redirects == 5) throw new IOException("Too many update redirects.");
                uri = uri.resolve(location);
            } else {
                http.disconnect();
                throw new IOException("Update server returned " + status + ".");
            }
        }
        throw new IOException("Too many redirects.");
    }

    /** Initial URLs are confined to this repository's release assets. */
    static boolean isTrustedReleaseUrl(URI uri) {
        if (!isHttps(uri) || !"github.com".equalsIgnoreCase(uri.getHost())) return false;
        String path = uri.getRawPath();
        if (path == null || !path.startsWith(RELEASE_PREFIX) || path.contains("%")
                || path.indexOf('\\') >= 0 || uri.getRawQuery() != null) return false;
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    /** GitHub's signed asset redirects carry no authorization headers from this app. */
    static boolean isTrustedDownloadUrl(URI uri) {
        if (!isHttps(uri)) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return isTrustedReleaseUrl(uri) || host.equals("release-assets.githubusercontent.com")
                || host.equals("objects.githubusercontent.com");
    }

    private static boolean isHttps(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    static byte[] decodeHex(String hex) {
        if (hex == null || !hex.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Invalid SHA-256.");
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    private static String cleanText(String value, int limit) {
        String text = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        return text.length() > limit ? text.substring(0, limit) : text;
    }

    private void ensureRunning(boolean download) throws IOException {
        if (closed || Thread.currentThread().isInterrupted() || (download && cancelDownload)) throw new IOException("Cancelled.");
    }

    private void disconnect() {
        HttpsURLConnection active = connection;
        if (active != null) active.disconnect();
        connection = null;
    }

    private Activity currentActivity() {
        Activity owner = activity.get();
        return closed || owner == null || owner.isFinishing() || owner.isDestroyed() ? null : owner;
    }

    private void post(Runnable action) {
        if (!closed) main.post(() -> { if (!closed && currentActivity() != null) action.run(); });
    }

    private void toast(String text) { if (!closed) Toast.makeText(context, text, Toast.LENGTH_LONG).show(); }

    private void showMessage(String title, String message) {
        Activity owner = currentActivity();
        if (owner != null) show(new AlertDialog.Builder(owner).setTitle(title).setMessage(message).setPositiveButton("OK", null).create());
    }

    private void show(AlertDialog value) {
        dismissDialog();
        dialog = value;
        value.setOnDismissListener(d -> { if (dialog == value) dialog = null; });
        if (currentActivity() != null) value.show();
    }

    private void dismissDialog() {
        AlertDialog previous = dialog;
        dialog = null;
        if (previous != null) previous.dismiss();
    }

    public void close() {
        closed = true;
        cancelDownload = true;
        disconnect();
        executor.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (Looper.myLooper() == Looper.getMainLooper()) dismissDialog();
        else main.post(this::dismissDialog);
        activity.clear();
    }

    private static final class UpdateInfo {
        final long versionCode;
        final String versionName;
        final URI apkUrl;
        final String sha256;
        final String notes;

        UpdateInfo(long code, String name, URI apk, String checksum, String releaseNotes) {
            versionCode = code;
            versionName = name;
            apkUrl = apk;
            sha256 = checksum;
            notes = releaseNotes;
        }
    }
}
