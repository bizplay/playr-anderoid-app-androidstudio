package biz.playr;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

/**
 * Coordinates application restarts. Use {@link #restartImmediateRecreate(Activity, boolean)} or
 * {@link #restartImmediateRelaunch(Context, boolean)} while the app is still in the foreground.
 * Use {@link #scheduleDelayedBackgroundRestart(Context, boolean)} from {@code onStop}/{@code onDestroy}
 * when the app is being closed.
 */
final class AppRestarter {
	private static final String className = "biz.playr.AppRestarter";
	private static final int RESTART_PENDING_INTENT_REQUEST_CODE = 1001;

	// Short delay so onDestroy can finish tearing down the WebView, while the foreground service
	// still has launch privileges. The historical 30s wait was for Android 3/4 resource cleanup
	// and is no longer needed.
	static final long RESTART_DELAY_MS = 2000;

	private static volatile boolean restartPending = false;

	private AppRestarter() {
	}

	static boolean isRestartPending() {
		return restartPending;
	}

	static void clearRestartPending() {
		restartPending = false;
	}

	static Intent createRestartActivityIntent(Context context) {
		Intent activityIntent = new Intent(context, MainActivity.class);
		activityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
				| Intent.FLAG_ACTIVITY_CLEAR_TASK
				| Intent.FLAG_ACTIVITY_NEW_TASK);
		activityIntent.setAction(Intent.ACTION_MAIN);
		activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);
		return activityIntent;
	}

	/**
	 * Launch {@link MainActivity} after boot. {@code BOOT_COMPLETED} may start a foreground
	 * service, but starting an activity directly from the receiver is a BAL-blocked background
	 * start on Android 10+. {@link Application#onCreate()} is not an exemption either: the
	 * process is created only to deliver the broadcast.
	 */
	static boolean launchFromBoot(Context context) {
		if (!context.getResources().getBoolean(R.bool.auto_start)) {
			Log.i(className, ".launchFromBoot: auto_start disabled");
			return false;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			Log.i(className, ".launchFromBoot: starting foreground service to launch MainActivity");
			if (!RestartForegroundService.scheduleRestart(context, 0)) {
				Log.e(className, ".launchFromBoot: foreground service start was rejected");
				return false;
			}
			return true;
		}
		Log.i(className, ".launchFromBoot: starting MainActivity directly (pre-Q)");
		context.startActivity(createRestartActivityIntent(context));
		return true;
	}

	/**
	 * Restarts in-process via {@link Activity#recreate()} while the activity is still alive.
	 * Used by the watchdog and low-memory recovery paths.
	 */
	static boolean restartImmediateRecreate(Activity activity, boolean force) {
		if (!shouldRestart(activity, force)) {
			return false;
		}
		if (!tryMarkRestartPending()) {
			Log.i(className, ".restartImmediateRecreate: restart already pending, skipping");
			return false;
		}

		Log.i(className, ".restartImmediateRecreate: scheduling recreate()");
		runOnMainThread(activity, () -> {
			try {
				activity.recreate();
			} catch (RuntimeException ex) {
				Log.e(className, ".restartImmediateRecreate: recreate failed, relaunching activity", ex);
				clearRestartPending();
				restartImmediateRelaunch(activity, force);
			}
		});
		return true;
	}

	/**
	 * Relaunches {@link MainActivity} immediately. Safe to call from any thread; uses the
	 * application context so it still works during crash handling.
	 */
	static boolean restartImmediateRelaunch(Context context, boolean force) {
		if (!shouldRestart(context, force)) {
			return false;
		}
		if (!tryMarkRestartPending()) {
			Log.i(className, ".restartImmediateRelaunch: restart already pending, skipping");
			return false;
		}

		Log.i(className, ".restartImmediateRelaunch: starting MainActivity");
		context.getApplicationContext().startActivity(createRestartActivityIntent(context));
		return true;
	}

	/**
	 * Schedules a delayed restart for when the activity is being closed. On Android 10+ this uses
	 * a short-lived foreground service so the relaunch is not treated as a blocked background
	 * activity start. Pre-Q keeps AlarmManager, which was not subject to BAL.
	 */
	static boolean scheduleDelayedBackgroundRestart(Context context, boolean force) {
		if (!shouldRestart(context, force)) {
			Log.i(className, ".scheduleDelayedBackgroundRestart: restart disabled for this build");
			return false;
		}
		if (!tryMarkRestartPending()) {
			Log.i(className, ".scheduleDelayedBackgroundRestart: restart already pending, skipping");
			return false;
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			Log.i(className, ".scheduleDelayedBackgroundRestart: starting foreground service, delay "
					+ RESTART_DELAY_MS + " ms");
			if (!RestartForegroundService.scheduleRestart(context, RESTART_DELAY_MS)) {
				Log.e(className, ".scheduleDelayedBackgroundRestart: could not start foreground service");
				clearRestartPending();
				return false;
			}
			return true;
		}

		return scheduleAlarmRestart(context);
	}

	private static boolean scheduleAlarmRestart(Context context) {
		PendingIntent launchPendingIntent = PendingIntent.getActivity(
				context.getApplicationContext(),
				RESTART_PENDING_INTENT_REQUEST_CODE,
				createRestartActivityIntent(context),
				PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

		AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		if (alarmManager == null) {
			Log.e(className, ".scheduleAlarmRestart: AlarmManager unavailable");
			clearRestartPending();
			return false;
		}

		long triggerAt = System.currentTimeMillis() + RESTART_DELAY_MS;
		Log.i(className, ".scheduleAlarmRestart: alarm in " + RESTART_DELAY_MS + " ms");
		try {
			alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, launchPendingIntent);
		} catch (SecurityException ex) {
			Log.e(className, ".scheduleAlarmRestart: alarm scheduling failed", ex);
			clearRestartPending();
			return false;
		}
		return true;
	}

	private static boolean shouldRestart(Context context, boolean force) {
		return context.getResources().getBoolean(R.bool.restart) || force;
	}

	private static boolean tryMarkRestartPending() {
		synchronized (AppRestarter.class) {
			if (restartPending) {
				return false;
			}
			restartPending = true;
			return true;
		}
	}

	private static void runOnMainThread(Activity activity, Runnable action) {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			action.run();
		} else {
			activity.runOnUiThread(action);
		}
	}
}
