package biz.playr;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.concurrent.TimeUnit;

/**
 * Cross-process restart backoff and operator-visible logging. Prevents tight restart loops
 * when a device has a permanent fault (native WebView crash, broken content, etc.).
 */
final class RestartBackoff {
	private static final String className = BuildConfig.APP_NAMESPACE + ".RestartBackoff";
	private static final String PREFS = "playr_restart_backoff";
	private static final String KEY_STREAK = "streak";
	private static final String KEY_WINDOW_START = "window_start_ms";
	private static final String KEY_WINDOW_COUNT = "window_count";
	private static final String KEY_NEXT_ALLOWED = "next_allowed_ms";
	private static final String KEY_CIRCUIT_OPEN = "circuit_open";
	private static final String KEY_LAST_REASON = "last_reason";
	private static final String KEY_LAST_ATTEMPT = "last_attempt_ms";

	static final long STABLE_SESSION_MS = TimeUnit.MINUTES.toMillis(5);
	static final int MAX_RESTARTS_PER_HOUR = 6;
	private static final long ROLLING_WINDOW_MS = TimeUnit.HOURS.toMillis(1);
	private static final long[] BACKOFF_MS = {
			TimeUnit.SECONDS.toMillis(2),
			TimeUnit.SECONDS.toMillis(30),
			TimeUnit.MINUTES.toMillis(2),
			TimeUnit.MINUTES.toMillis(10),
			TimeUnit.MINUTES.toMillis(30),
	};

	private RestartBackoff() {
	}

	static boolean canRestart(Context context, boolean force) {
		if (force) {
			return true;
		}
		SharedPreferences prefs = prefs(context);
		long now = System.currentTimeMillis();
		if (prefs.getBoolean(KEY_CIRCUIT_OPEN, false)) {
			return false;
		}
		if (now < prefs.getLong(KEY_NEXT_ALLOWED, 0L)) {
			return false;
		}
		long windowStart = prefs.getLong(KEY_WINDOW_START, 0L);
		int windowCount = prefs.getInt(KEY_WINDOW_COUNT, 0);
		if (windowStart > 0 && now - windowStart <= ROLLING_WINDOW_MS) {
			return windowCount < MAX_RESTARTS_PER_HOUR;
		}
		return true;
	}

	static void recordAttempt(Context context, String reason) {
		recordAttempt(context, reason, false);
	}

	static void recordAttempt(Context context, String reason, boolean force) {
		if (force) {
			logOperatorEvent(context, "FORCED_RESTART", reason,
					"server force bypasses circuit breaker; counters unchanged");
			return;
		}
		SharedPreferences prefs = prefs(context);
		long now = System.currentTimeMillis();
		int streak = prefs.getInt(KEY_STREAK, 0) + 1;
		long windowStart = prefs.getLong(KEY_WINDOW_START, 0L);
		int windowCount = prefs.getInt(KEY_WINDOW_COUNT, 0);
		if (windowStart == 0 || now - windowStart > ROLLING_WINDOW_MS) {
			windowStart = now;
			windowCount = 0;
		}
		windowCount++;

		int backoffIndex = Math.min(streak - 1, BACKOFF_MS.length - 1);
		long nextAllowed = now + BACKOFF_MS[backoffIndex];
		boolean circuitOpen = windowCount >= MAX_RESTARTS_PER_HOUR;

		prefs.edit()
				.putInt(KEY_STREAK, streak)
				.putLong(KEY_WINDOW_START, windowStart)
				.putInt(KEY_WINDOW_COUNT, windowCount)
				.putLong(KEY_NEXT_ALLOWED, nextAllowed)
				.putBoolean(KEY_CIRCUIT_OPEN, circuitOpen)
				.putString(KEY_LAST_REASON, reason == null ? "" : reason)
				.putLong(KEY_LAST_ATTEMPT, now)
				.commit();

		logOperatorEvent(context, circuitOpen ? "CIRCUIT_OPEN" : "RESTART_ATTEMPT", reason,
				"streak=" + streak
						+ " windowCount=" + windowCount + "/" + MAX_RESTARTS_PER_HOUR
						+ " nextBackoffMs=" + BACKOFF_MS[backoffIndex]
						+ (circuitOpen ? " autoRestart=STOPPED until stable session or server force" : ""));
	}

	static void resetAfterStableSession(Context context) {
		SharedPreferences prefs = prefs(context);
		if (prefs.getInt(KEY_STREAK, 0) == 0
				&& !prefs.getBoolean(KEY_CIRCUIT_OPEN, false)
				&& prefs.getLong(KEY_NEXT_ALLOWED, 0L) <= System.currentTimeMillis()) {
			return;
		}
		prefs.edit()
				.putInt(KEY_STREAK, 0)
				.putLong(KEY_WINDOW_START, 0L)
				.putInt(KEY_WINDOW_COUNT, 0)
				.putLong(KEY_NEXT_ALLOWED, 0L)
				.putBoolean(KEY_CIRCUIT_OPEN, false)
				.commit();
		logOperatorEvent(context, "STABLE_SESSION", "playback ran " + (STABLE_SESSION_MS / 1000) + "s",
				"backoff counters reset; auto-restart re-enabled");
	}

	static void logRestartBlocked(Context context, String reason) {
		SharedPreferences prefs = prefs(context);
		long now = System.currentTimeMillis();
		long nextAllowed = prefs.getLong(KEY_NEXT_ALLOWED, 0L);
		long waitMs = Math.max(0L, nextAllowed - now);
		String detail = prefs.getBoolean(KEY_CIRCUIT_OPEN, false)
				? "circuit breaker open (" + prefs.getInt(KEY_WINDOW_COUNT, 0) + " restarts in last hour)"
				: "backoff active (wait " + (waitMs / 1000) + "s)";
		logOperatorEvent(context, "RESTART_BLOCKED", reason, detail);
	}

	static void logOperatorStatus(Context context) {
		SharedPreferences prefs = prefs(context);
		long now = System.currentTimeMillis();
		long nextAllowed = prefs.getLong(KEY_NEXT_ALLOWED, 0L);
		long waitMs = Math.max(0L, nextAllowed - now);
		Log.e(className, ".\n"
				+ "********************************************************************************\n"
				+ "*** RESTART BACKOFF STATUS\n"
				+ "*** circuitOpen: " + prefs.getBoolean(KEY_CIRCUIT_OPEN, false) + "\n"
				+ "*** streak: " + prefs.getInt(KEY_STREAK, 0) + "\n"
				+ "*** restartsInWindow: " + prefs.getInt(KEY_WINDOW_COUNT, 0) + "/" + MAX_RESTARTS_PER_HOUR + "\n"
				+ "*** nextRestartAllowedIn: " + (waitMs / 1000) + " s\n"
				+ "*** lastReason: " + prefs.getString(KEY_LAST_REASON, "") + "\n"
				+ "*** lastAttemptAge: "
				+ (prefs.getLong(KEY_LAST_ATTEMPT, 0L) == 0L ? "never"
						: ((now - prefs.getLong(KEY_LAST_ATTEMPT, 0L)) / 1000) + " s ago") + "\n"
				+ "********************************************************************************\n.");
	}

	static boolean isCircuitOpen(Context context) {
		return prefs(context).getBoolean(KEY_CIRCUIT_OPEN, false);
	}

	static String notificationSummary(Context context) {
		SharedPreferences prefs = prefs(context);
		if (prefs.getBoolean(KEY_CIRCUIT_OPEN, false)) {
			return "Auto-restart paused (too many failures). Server reboot still works.";
		}
		long waitMs = Math.max(0L, prefs.getLong(KEY_NEXT_ALLOWED, 0L) - System.currentTimeMillis());
		if (waitMs > 0L) {
			return "Watching (restart backoff " + (waitMs / 1000) + "s)";
		}
		return null;
	}

	private static SharedPreferences prefs(Context context) {
		return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	private static void logOperatorEvent(Context context, String event, String reason, String detail) {
		Log.e(className, ".\n"
				+ "********************************************************************************\n"
				+ "*** RESTART BACKOFF: " + event + "\n"
				+ "*** reason: " + (reason == null ? "" : reason) + "\n"
				+ "*** " + detail + "\n"
				+ "********************************************************************************\n.");
		logOperatorStatus(context);
	}
}
