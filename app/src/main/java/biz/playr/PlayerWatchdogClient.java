package biz.playr;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Sends intents to {@link PlayerWatchdogService} in the {@code :watchdog} process.
 */
final class PlayerWatchdogClient {
	private static final String className = BuildConfig.APP_NAMESPACE + ".WatchdogClient";

	private PlayerWatchdogClient() {
	}

	static void enableMonitoring(Context context, String playerId) {
		Intent intent = new Intent(context, PlayerWatchdogService.class);
		intent.setAction(PlayerWatchdogService.ACTION_ENABLE);
		if (playerId != null && !playerId.isEmpty()) {
			intent.putExtra(PlayerWatchdogService.EXTRA_PLAYER_ID, playerId);
		}
		startService(context, intent);
		Log.i(className, ".enableMonitoring");
	}

	static void sendHeartbeat(Context context, String playerId) {
		Intent intent = new Intent(context, PlayerWatchdogService.class);
		intent.setAction(PlayerWatchdogService.ACTION_HEARTBEAT);
		if (playerId != null && !playerId.isEmpty()) {
			intent.putExtra(PlayerWatchdogService.EXTRA_PLAYER_ID, playerId);
		}
		context.getApplicationContext().startService(intent);
	}

	static void disableMonitoring(Context context) {
		Intent intent = new Intent(context, PlayerWatchdogService.class);
		intent.setAction(PlayerWatchdogService.ACTION_DISABLE);
		context.getApplicationContext().startService(intent);
		Log.i(className, ".disableMonitoring");
	}

	private static void startService(Context context, Intent intent) {
		Context appContext = context.getApplicationContext();
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				appContext.startForegroundService(intent);
			} else {
				appContext.startService(intent);
			}
		} catch (RuntimeException ex) {
			Log.e(className, ".startService: could not start watchdog", ex);
		}
	}
}
