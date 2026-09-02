package biz.playr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Out-of-process watchdog ({@code :watchdog}). Survives native crashes in the main WebView
 * process; restarts {@link MainActivity} when heartbeats stop or the server commands reboot.
 */
public class PlayerWatchdogService extends Service {
	static final String ACTION_ENABLE = BuildConfig.APP_NAMESPACE + ".watchdog.ENABLE";
	static final String ACTION_HEARTBEAT = BuildConfig.APP_NAMESPACE + ".watchdog.HEARTBEAT";
	static final String ACTION_DISABLE = BuildConfig.APP_NAMESPACE + ".watchdog.DISABLE";
	static final String EXTRA_PLAYER_ID = "player_id";

	private static final String className = BuildConfig.APP_NAMESPACE + ".PlayerWatchdo";
	private static final String NOTIFICATION_CHANNEL_ID = "playr_watchdog";
	private static final int NOTIFICATION_ID = 1002;
	private static final String REBOOT_RESPONSE = "1";

	static final long CHECK_INTERVAL_MS = 30_000L;
	static final long HEARTBEAT_STALE_MS = 90_000L;
	static final long SERVER_POLL_INTERVAL_MS = 180_000L;
	static final long MIN_RESTART_INTERVAL_MS = 30_000L;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable checkTask = this::runChecks;
	private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "playr-watchdog-net");
		t.setDaemon(true);
		return t;
	});

	private volatile long lastHeartbeatMs;
	private volatile long lastServerPollMs;
	private volatile long lastRestartAttemptMs;
	private volatile long monitoringSinceMs;
	private volatile boolean monitoringEnabled;
	private volatile long stableHeartbeatSinceMs;
	private volatile boolean serverPollInFlight;
	private String playerId = "";

	@Override
	public void onCreate() {
		super.onCreate();
		Log.i(className, "override onCreate (watchdog process)");
		createNotificationChannel();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		String action = intent != null ? intent.getAction() : null;
		if (ACTION_DISABLE.equals(action)) {
			monitoringEnabled = false;
			lastHeartbeatMs = 0;
			stableHeartbeatSinceMs = 0;
			monitoringSinceMs = 0;
			Log.i(className, ".onStartCommand: monitoring disabled");
			return START_STICKY;
		}

		if (intent != null && intent.hasExtra(EXTRA_PLAYER_ID)) {
			String id = intent.getStringExtra(EXTRA_PLAYER_ID);
			if (id != null && !id.isEmpty()) {
				playerId = id;
			}
		}
		if (playerId.isEmpty()) {
			playerId = readStoredPlayerId();
		}

		if (ACTION_HEARTBEAT.equals(action)) {
			long now = System.currentTimeMillis();
			lastHeartbeatMs = now;
			if (!monitoringEnabled) {
				monitoringEnabled = true;
				monitoringSinceMs = now;
				stableHeartbeatSinceMs = now;
				Log.i(className, ".onStartCommand: heartbeat re-enabled monitoring");
			}
			maybeResetBackoffAfterStableSession(now);
			ensureForeground();
			scheduleChecks();
			return START_STICKY;
		}

		if (ACTION_ENABLE.equals(action)) {
			long now = System.currentTimeMillis();
			monitoringEnabled = true;
			lastHeartbeatMs = now;
			monitoringSinceMs = now;
			stableHeartbeatSinceMs = now;
			Log.i(className, ".onStartCommand: monitoring enabled, playerId="
					+ (playerId.isEmpty() ? "empty" : "***" + playerId.substring(Math.max(0, playerId.length() - 6))));
			ensureForeground();
			scheduleChecks();
			return START_STICKY;
		}

		// Sticky restart after process death (null intent / no action): keep monitoring but do
		// not pretend a heartbeat arrived. A real HEARTBEAT from MainActivity refreshes the clock.
		monitoringEnabled = true;
		if (monitoringSinceMs == 0L) {
			monitoringSinceMs = System.currentTimeMillis();
		}
		Log.i(className, ".onStartCommand: sticky/recreate resume, lastHeartbeatMs=" + lastHeartbeatMs);
		ensureForeground();
		scheduleChecks();
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		handler.removeCallbacks(checkTask);
		networkExecutor.shutdownNow();
		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private void ensureForeground() {
		Notification notification = buildNotification();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			try {
				startForeground(
						NOTIFICATION_ID,
						notification,
						ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
			} catch (RuntimeException ex) {
				Log.e(className, ".ensureForeground: specialUse failed, retry without type", ex);
				startForeground(NOTIFICATION_ID, notification);
			}
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			startForeground(NOTIFICATION_ID, notification);
		} else {
			startForeground(NOTIFICATION_ID, notification);
		}
	}

	private void scheduleChecks() {
		handler.removeCallbacks(checkTask);
		handler.postDelayed(checkTask, CHECK_INTERVAL_MS);
	}

	private void runChecks() {
		if (!monitoringEnabled) {
			return;
		}

		long now = System.currentTimeMillis();
		if (AppRestarter.wasRestartScheduledRecently(this, HEARTBEAT_STALE_MS)) {
			Log.i(className, ".runChecks: restart already scheduled recently, skip");
			scheduleChecks();
			return;
		}

		long heartbeatReferenceMs = lastHeartbeatMs > 0L ? lastHeartbeatMs : monitoringSinceMs;
		if (heartbeatReferenceMs > 0L && now - heartbeatReferenceMs > HEARTBEAT_STALE_MS) {
			Log.e(className, ".runChecks: heartbeat stale ("
					+ (now - heartbeatReferenceMs) + " ms), requesting player restart");
			stableHeartbeatSinceMs = 0;
			requestPlayerRestart(false, "watchdog_stale_heartbeat");
			scheduleChecks();
			return;
		}

		if (now - lastServerPollMs >= SERVER_POLL_INTERVAL_MS) {
			pollServerForRestartAsync();
		}

		scheduleChecks();
	}

	private void pollServerForRestartAsync() {
		if (serverPollInFlight) {
			return;
		}
		if (playerId.isEmpty()) {
			playerId = readStoredPlayerId();
		}
		if (playerId.isEmpty()) {
			return;
		}

		serverPollInFlight = true;
		lastServerPollMs = System.currentTimeMillis();
		final String id = playerId;
		networkExecutor.execute(() -> {
			boolean reboot = false;
			try {
				reboot = checkServerForRestart(id);
			} catch (RuntimeException ex) {
				Log.e(className, ".pollServerForRestartAsync: unexpected error", ex);
			} finally {
				serverPollInFlight = false;
			}
			if (reboot) {
				handler.post(() -> {
					Log.i(className, ".runChecks: server commanded restart");
					requestPlayerRestart(true, "watchdog_server_command");
				});
			}
		});
	}

	private void requestPlayerRestart(boolean force, String reason) {
		long now = System.currentTimeMillis();
		if (!force && now - lastRestartAttemptMs < MIN_RESTART_INTERVAL_MS) {
			Log.i(className, ".requestPlayerRestart: throttled");
			return;
		}
		lastRestartAttemptMs = now;
		if (AppRestarter.scheduleDelayedBackgroundRestart(getApplicationContext(), force, reason)) {
			Log.i(className, ".requestPlayerRestart: scheduled relaunch (force=" + force + ", reason=" + reason + ")");
			stableHeartbeatSinceMs = 0;
		} else {
			Log.e(className, ".requestPlayerRestart: schedule failed (force=" + force + ", reason=" + reason + ")");
			ensureForeground();
		}
	}

	private void maybeResetBackoffAfterStableSession(long now) {
		if (stableHeartbeatSinceMs <= 0) {
			stableHeartbeatSinceMs = now;
			return;
		}
		if (now - stableHeartbeatSinceMs >= RestartBackoff.STABLE_SESSION_MS) {
			RestartBackoff.resetAfterStableSession(this);
			stableHeartbeatSinceMs = now;
			ensureForeground();
		}
	}

	private boolean checkServerForRestart(String id) {
		String response = "";
		HttpURLConnection urlConnection = null;
		InputStream inputStream = null;
		try {
			URL url = new URL("https://ajax.playr.biz/watchdogs/" + id + "/command");
			Log.i(className, ".checkServerForRestart URL: " + url);
			urlConnection = (HttpURLConnection) url.openConnection();
			urlConnection.setConnectTimeout(15_000);
			urlConnection.setReadTimeout(15_000);
			inputStream = new BufferedInputStream(urlConnection.getInputStream());
			response = readStream(inputStream).trim();
		} catch (MalformedURLException e) {
			Log.e(className, ".checkServerForRestart: malformed URL", e);
		} catch (IOException e) {
			Log.e(className, ".checkServerForRestart: IO error", e);
		} finally {
			if (inputStream != null) {
				try {
					inputStream.close();
				} catch (IOException e) {
					Log.e(className, ".checkServerForRestart: close failed", e);
				}
			}
			if (urlConnection != null) {
				urlConnection.disconnect();
			}
		}
		Log.i(className, ".checkServerForRestart response: " + response);
		return REBOOT_RESPONSE.equals(response);
	}

	private String readStream(InputStream inputStream) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
		StringBuilder total = new StringBuilder();
		String line;
		while ((line = reader.readLine()) != null) {
			total.append(line).append('\n');
		}
		return total.toString();
	}

	private String readStoredPlayerId() {
		// Activity.getPreferences() stores under the local class name ("MainActivity").
		SharedPreferences prefs = getSharedPreferences("MainActivity", MODE_PRIVATE);
		return prefs.getString(getString(R.string.player_id_store), "");
	}

	private void createNotificationChannel() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return;
		}
		NotificationChannel channel = new NotificationChannel(
				NOTIFICATION_CHANNEL_ID,
				getString(R.string.watchdog_notification_channel_name),
				NotificationManager.IMPORTANCE_LOW);
		channel.setDescription(getString(R.string.watchdog_notification_channel_description));
		NotificationManager notificationManager = getSystemService(NotificationManager.class);
		if (notificationManager != null) {
			notificationManager.createNotificationChannel(channel);
		}
	}

	private Notification buildNotification() {
		Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
				? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
				: new Notification.Builder(this);
		String contentText = getString(R.string.watchdog_notification_text);
		String backoffSummary = RestartBackoff.notificationSummary(this);
		if (backoffSummary != null) {
			contentText = backoffSummary;
		}
		builder.setContentTitle(getString(R.string.watchdog_notification_title))
				.setContentText(contentText)
				.setSmallIcon(R.drawable.ic_launcher)
				.setOngoing(true);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
		}
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			builder.setPriority(Notification.PRIORITY_LOW);
		}
		return builder.build();
	}
}
