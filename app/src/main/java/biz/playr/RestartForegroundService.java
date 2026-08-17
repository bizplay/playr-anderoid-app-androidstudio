package biz.playr;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.session.MediaSession;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * Launches {@link MainActivity} from a foreground service. Used after boot and after the
 * activity is closed, where a direct {@code startActivity()} from a receiver or AlarmManager
 * is blocked (BAL). {@code BOOT_COMPLETED} may start this service; it may not start an activity.
 * A {@code specialUse} FGS is not a BAL exemption on Android 14, so the notification uses a
 * full-screen intent and {@link PendingIntent#send} is always tried after {@code startActivity}.
 */
public class RestartForegroundService extends Service {
	private static final String className = "biz.playr.RestartFgService";
	private static final String EXTRA_DELAY_MS = "delay_ms";
	private static final int NOTIFICATION_ID = 1001;
	private static final int RESTART_PENDING_INTENT_REQUEST_CODE = 1001;
	static final String NOTIFICATION_CHANNEL_ID = "playr_launch";
	private static final long STOP_AFTER_LAUNCH_MS = 4000;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private Runnable restartTask;
	private Runnable stopTask;
	private MediaSession mediaSession;

	static boolean scheduleRestart(Context context, long delayMs) {
		Intent intent = new Intent(context, RestartForegroundService.class);
		intent.putExtra(EXTRA_DELAY_MS, delayMs);
		Context appContext = context.getApplicationContext();
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				appContext.startForegroundService(intent);
			} else {
				appContext.startService(intent);
			}
			return true;
		} catch (RuntimeException ex) {
			// Android 12+ throws ForegroundServiceStartNotAllowedException when the
			// process is not in an allowed state (cached after standby, background, etc.).
			Log.e(className, ".scheduleRestart: startForegroundService not allowed", ex);
			return false;
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		long delayMs = AppRestarter.RESTART_DELAY_MS;
		if (intent != null) {
			delayMs = intent.getLongExtra(EXTRA_DELAY_MS, AppRestarter.RESTART_DELAY_MS);
		}

		createNotificationChannel();
		ensureMediaSession();
		Notification notification = buildLaunchNotification();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			try {
				startForeground(
						NOTIFICATION_ID,
						notification,
						ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
								| ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
			} catch (RuntimeException ex) {
				Log.e(className, ".onStartCommand: mediaPlayback+specialUse startForeground failed, retry specialUse", ex);
				startForeground(
						NOTIFICATION_ID,
						notification,
						ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
			}
		} else {
			startForeground(NOTIFICATION_ID, notification);
		}

		if (restartTask != null) {
			handler.removeCallbacks(restartTask);
		}
		restartTask = this::relaunchMainActivity;
		handler.postDelayed(restartTask, delayMs);
		Log.i(className, ".onStartCommand: relaunch in " + delayMs + " ms");
		return START_NOT_STICKY;
	}

	@Override
	public void onDestroy() {
		if (restartTask != null) {
			handler.removeCallbacks(restartTask);
			restartTask = null;
		}
		if (stopTask != null) {
			handler.removeCallbacks(stopTask);
			stopTask = null;
		}
		if (mediaSession != null) {
			mediaSession.release();
			mediaSession = null;
		}
		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private void relaunchMainActivity() {
		if (MainActivity.isInstanceAlive()) {
			Log.i(className, ".relaunchMainActivity: MainActivity already running, skip CLEAR_TASK relaunch");
			stopAfterDelay();
			return;
		}
		Intent activityIntent = AppRestarter.createRestartActivityIntent(this);
		AppRestarter.scheduleAlarmClockLaunch(this, 1000);
		// startActivity does not throw when BAL blocks the launch (result code 102).
		startActivityWithBalOptIn(activityIntent);
		sendRestartPendingIntent(activityIntent);
		stopAfterDelay();
	}

	private void stopAfterDelay() {
		if (stopTask != null) {
			handler.removeCallbacks(stopTask);
		}
		stopTask = this::stopNow;
		handler.postDelayed(stopTask, STOP_AFTER_LAUNCH_MS);
	}

	private void stopNow() {
		if (MainActivity.isInstanceAlive()) {
			Log.i(className, ".stopNow: MainActivity is running");
		} else {
			Log.e(className, ".stopNow: MainActivity still not visible after launch attempts");
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			stopForeground(STOP_FOREGROUND_REMOVE);
		} else {
			stopForeground(true);
		}
		stopSelf();
	}

	private void startActivityWithBalOptIn(Intent activityIntent) {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				Log.i(className, ".startActivityWithBalOptIn: overlay granted="
						+ Settings.canDrawOverlays(this)
						+ ", fullScreenIntent=" + AppRestarter.fullScreenIntentStatus(this)
						+ ", exactAlarms=" + AppRestarter.exactAlarmStatus(this));
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				startActivity(activityIntent, createBackgroundStartOptions().toBundle());
			} else {
				startActivity(activityIntent);
			}
			Log.i(className, ".startActivityWithBalOptIn: startActivity issued (BAL may still block)");
		} catch (RuntimeException ex) {
			Log.e(className, ".startActivityWithBalOptIn: startActivity failed", ex);
		}
	}

	private void sendRestartPendingIntent(Intent activityIntent) {
		PendingIntent launchPendingIntent = launchPendingIntent(activityIntent);
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				launchPendingIntent.send(this, 0, null, null, null, null,
						createBackgroundStartOptions().toBundle());
			} else {
				launchPendingIntent.send();
			}
			Log.i(className, ".sendRestartPendingIntent: PendingIntent sent");
		} catch (PendingIntent.CanceledException | RuntimeException ex) {
			Log.e(className, ".sendRestartPendingIntent: send failed", ex);
		}
	}

	private PendingIntent launchPendingIntent(Intent activityIntent) {
		return PendingIntent.getActivity(
				this,
				RESTART_PENDING_INTENT_REQUEST_CODE,
				activityIntent,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
	}

	@SuppressWarnings("deprecation")
	private ActivityOptions createBackgroundStartOptions() {
		ActivityOptions options = ActivityOptions.makeBasic();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
			options.setPendingIntentBackgroundActivityStartMode(
					ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			options.setPendingIntentBackgroundActivityStartMode(
					ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
		}
		return options;
	}

	private void createNotificationChannel() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return;
		}
		NotificationChannel channel = new NotificationChannel(
				NOTIFICATION_CHANNEL_ID,
				getString(R.string.restart_notification_channel_name),
				NotificationManager.IMPORTANCE_HIGH);
		channel.setDescription(getString(R.string.restart_notification_channel_description));
		channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
		NotificationManager notificationManager =
				(NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (notificationManager != null) {
			notificationManager.createNotificationChannel(channel);
		}
	}

	@SuppressWarnings("deprecation")
	private Notification buildLaunchNotification() {
		PendingIntent fullScreenIntent = launchPendingIntent(
				AppRestarter.createRestartActivityIntent(this));
		Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
				? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
				: new Notification.Builder(this);
		builder.setContentTitle(getString(R.string.restart_notification_title))
				.setContentText(getString(R.string.restart_notification_text))
				.setSmallIcon(R.drawable.ic_launcher)
				.setOngoing(true)
				.setCategory(Notification.CATEGORY_ALARM)
				.setContentIntent(fullScreenIntent)
				.setFullScreenIntent(fullScreenIntent, true);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
		}
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			builder.setPriority(Notification.PRIORITY_HIGH);
		}
		return builder.build();
	}

	private void ensureMediaSession() {
		if (mediaSession != null) {
			return;
		}
		mediaSession = new MediaSession(this, className);
		mediaSession.setActive(true);
	}
}
