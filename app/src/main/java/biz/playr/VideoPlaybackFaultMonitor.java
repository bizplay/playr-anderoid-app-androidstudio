package biz.playr;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Detects repeated hard video decode faults while the Java activity is still alive.
 * Deliberately ignores routine playlist {@code play()}/{@code pause()} AbortErrors — those
 * are common during slide changes and previously caused restart loops on Amlogic devices.
 */
final class VideoPlaybackFaultMonitor {
	private static final String className = BuildConfig.APP_NAMESPACE + ".VideoFaultMon";

	/** Hard decode failures required before requesting a restart. */
	static final int FAULT_THRESHOLD = 5;
	/** Rolling window for those failures. */
	static final long FAULT_WINDOW_MS = 10 * 60_000L;
	/** Ignore bursts that are really one codec open failure reported several ways. */
	static final long DEDUPE_MS = 20_000L;
	/** Do not count faults right after content load / activity recreate. */
	static final long GRACE_AFTER_CONTENT_MS = 120_000L;
	/** Minimum time between restart requests from this monitor. */
	static final long COOLDOWN_MS = 10 * 60_000L;

	interface Listener {
		void onVideoPlaybackFaultsExceeded(String lastDetail);
	}

	private final ArrayDeque<Long> recentFaultAtMs = new ArrayDeque<>();
	private final Listener listener;
	private long lastRestartRequestMs;
	private long lastCountedFaultMs;
	private long contentReadyAtMs;

	VideoPlaybackFaultMonitor(Listener listener) {
		this.listener = listener;
	}

	void reset() {
		recentFaultAtMs.clear();
		lastCountedFaultMs = 0L;
		contentReadyAtMs = 0L;
	}

	/** Call when player content has finished loading so grace period starts. */
	void onContentReady() {
		contentReadyAtMs = System.currentTimeMillis();
		recentFaultAtMs.clear();
		lastCountedFaultMs = 0L;
		Log.i(className, "content ready; fault grace " + (GRACE_AFTER_CONTENT_MS / 1000) + "s");
	}

	/**
	 * Console noise is mostly playlist play/pause races. Only count explicit decode / MediaCodec
	 * strings — never bare AbortError play()/pause().
	 */
	boolean considerConsoleMessage(String message) {
		if (message == null || message.isEmpty()) {
			return false;
		}
		String lower = message.toLowerCase(Locale.US);
		if (lower.contains("play() request was interrupted")
				|| (lower.contains("aborterror") && lower.contains("play()"))) {
			Log.i(className, "ignored routine play/pause AbortError");
			return false;
		}
		if (lower.contains("cannot start the media codec")
				|| lower.contains("failed to allocate buffers")
				|| lower.contains("pipeline_error_decode")
				|| lower.contains("media_err_decode")
				|| lower.contains("demuxer_error_could_not_open")
				|| (lower.contains("mediacodec") && lower.contains("error"))) {
			recordFault("console:" + trim(message, 160));
			return true;
		}
		return false;
	}

	/**
	 * JS bridge reports. Only MEDIA_ERR_DECODE (3) and MEDIA_ERR_SRC_NOT_SUPPORTED (4) count.
	 * Codes 1 (aborted) and 2 (network) are ignored here.
	 */
	void considerJsBridgeFault(String detail) {
		if (detail == null) {
			return;
		}
		String lower = detail.toLowerCase(Locale.US);
		if (lower.startsWith("video_stalled")) {
			Log.i(className, "ignored video_stalled");
			return;
		}
		if (lower.startsWith("video_element_error:")) {
			String code = lower.substring("video_element_error:".length()).trim();
			if ("3".equals(code) || "4".equals(code)) {
				recordFault(detail);
			} else {
				Log.i(className, "ignored video_element_error code=" + code);
			}
			return;
		}
		if (lower.contains("mediacodec") || lower.contains("decode")) {
			recordFault(detail);
		}
	}

	void recordFault(String detail) {
		long now = System.currentTimeMillis();
		if (contentReadyAtMs == 0L || now - contentReadyAtMs < GRACE_AFTER_CONTENT_MS) {
			Log.i(className, "ignored during grace: " + detail);
			return;
		}
		if (lastCountedFaultMs > 0L && now - lastCountedFaultMs < DEDUPE_MS) {
			Log.i(className, "deduped (same burst): " + detail);
			return;
		}

		while (!recentFaultAtMs.isEmpty() && now - recentFaultAtMs.peekFirst() > FAULT_WINDOW_MS) {
			recentFaultAtMs.removeFirst();
		}
		recentFaultAtMs.addLast(now);
		lastCountedFaultMs = now;
		Log.e(className, "video fault (" + recentFaultAtMs.size() + "/" + FAULT_THRESHOLD
				+ " in " + (FAULT_WINDOW_MS / 1000) + "s): " + detail);

		if (recentFaultAtMs.size() < FAULT_THRESHOLD) {
			return;
		}
		if (now - lastRestartRequestMs < COOLDOWN_MS) {
			Log.i(className, "fault threshold reached but cooldown active; skip restart");
			return;
		}
		lastRestartRequestMs = now;
		recentFaultAtMs.clear();
		Log.e(className, ".\n"
				+ "********************************************************************************\n"
				+ "*** VIDEO PLAYBACK FAULT THRESHOLD REACHED → requesting app restart\n"
				+ "*** lastDetail: " + detail + "\n"
				+ "********************************************************************************\n.");
		if (listener != null) {
			listener.onVideoPlaybackFaultsExceeded(detail);
		}
	}

	private static String trim(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max) + "…";
	}
}
