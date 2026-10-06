package biz.playr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Pre-Q / FGS-fallback relaunch path. AlarmManager must not fire a CLEAR_TASK activity
 * PendingIntent blindly: that destroyed a freshly started {@link MainActivity} on Android 9.
 * This receiver mirrors {@link RestartForegroundService}: skip when an instance is already
 * alive unless the reason requires a forced recycle.
 */
public class RestartAlarmReceiver extends BroadcastReceiver {
	static final String ACTION_RELAUNCH = BuildConfig.APP_NAMESPACE + ".RESTART_ALARM_RELAUNCH";
	static final String EXTRA_REASON = "reason";

	private static final String className = BuildConfig.APP_NAMESPACE + ".RestartAlarmRx";

	@Override
	public void onReceive(Context context, Intent intent) {
		if (intent == null || !ACTION_RELAUNCH.equals(intent.getAction())) {
			return;
		}
		String reason = intent.getStringExtra(EXTRA_REASON);
		if (reason == null || reason.isEmpty()) {
			reason = "alarm";
		}
		Context app = context.getApplicationContext();
		if (MainActivity.isInstanceAlive()
				&& !AppRestarter.shouldForceRelaunchEvenIfAlive(reason)) {
			Log.i(className, ".onReceive: MainActivity already running, skip CLEAR_TASK"
					+ " (reason=" + reason + ")");
			AppRestarter.onRelaunchSucceeded(app);
			return;
		}
		Log.i(className, ".onReceive: starting MainActivity (reason=" + reason + ")");
		app.startActivity(AppRestarter.createRestartActivityIntent(app));
		AppRestarter.onRelaunchSucceeded(app);
	}
}
