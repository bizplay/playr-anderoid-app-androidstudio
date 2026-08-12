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
 * Use {@link #scheduleDelayedBackgroundRestart(Context, boolean)} from {@code onDestroy} when the
 * app may already be in the background.
 */
final class AppRestarter {
	private static final String className = "biz.playr.AppRestarter";
	private static final int RESTART_PENDING_INTENT_REQUEST_CODE = 1001;

	// Delay used only for background recovery (onDestroy). Kept long so users can change settings
	// or switch apps without an immediate restart loop.
	static final long RESTART_DELAY_MS = 30000;

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
	 * Schedules a delayed restart for when the activity is already being destroyed and may no
	 * longer have a visible window. Intended for {@code onDestroy} only.
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

		PendingIntent launchPendingIntent = PendingIntent.getActivity(
				context.getApplicationContext(),
				RESTART_PENDING_INTENT_REQUEST_CODE,
				createRestartActivityIntent(context),
				PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

		AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		if (alarmManager == null) {
			Log.e(className, ".scheduleDelayedBackgroundRestart: AlarmManager unavailable");
			clearRestartPending();
			return false;
		}

		long triggerAt = System.currentTimeMillis() + RESTART_DELAY_MS;
		Log.i(className, ".scheduleDelayedBackgroundRestart: alarm in " + (RESTART_DELAY_MS / 1000) + " seconds");
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, launchPendingIntent);
			} else {
				alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, launchPendingIntent);
			}
		} catch (SecurityException ex) {
			Log.e(className, ".scheduleDelayedBackgroundRestart: alarm scheduling failed", ex);
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
