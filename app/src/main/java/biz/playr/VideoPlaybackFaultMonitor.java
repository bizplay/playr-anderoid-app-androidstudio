package biz.playr;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Detects repeated video/MediaCodec-style playback faults while the Java activity is still
 * alive (heartbeats keep flowing). After enough faults in a short window, asks the host to
 * restart so field devices do not stay on a black/broken player indefinitely.
 */
final class VideoPlaybackFaultMonitor {
	private static final String className = BuildConfig.APP_NAMESPACE + ".VideoFaultMon";

	static final int FAULT_THRESHOLD = 3;
	static final long FAULT_WINDOW_MS = 90_000L;
	static final long COOLDOWN_MS = 120_000L;

	interface Listener {
		void onVideoPlaybackFaultsExceeded(String lastDetail);
	}

	private final ArrayDeque<Long> recentFaultAtMs = new ArrayDeque<>();
	private final Listener listener;
	private long lastRestartRequestMs;

	VideoPlaybackFaultMonitor(Listener listener) {
		this.listener = listener;
	}

	void reset() {
		recentFaultAtMs.clear();
	}

	/**
	 * @return true if this message looks like a hardware/decode/playback fault worth counting
	 */
	boolean considerConsoleMessage(String message) {
		if (message == null || message.isEmpty()) {
			return false;
		}
		String lower = message.toLowerCase(Locale.US);
		if (lower.contains("mediacodec")
				|| lower.contains("media codec")
				|| lower.contains("cannot start the media codec")
				|| lower.contains("failed to allocate buffers")
				|| (lower.contains("aborterror") && lower.contains("play()"))
				|| (lower.contains("play() request was interrupted") && lower.contains("pause()"))
				|| lower.contains("pipeline_error_decode")
				|| lower.contains("demuxer_error")
				|| lower.contains("media_err_decode")) {
			recordFault("console:" + trim(message, 160));
			return true;
		}
		return false;
	}

	void recordFault(String detail) {
		long now = System.currentTimeMillis();
		while (!recentFaultAtMs.isEmpty() && now - recentFaultAtMs.peekFirst() > FAULT_WINDOW_MS) {
			recentFaultAtMs.removeFirst();
		}
		recentFaultAtMs.addLast(now);
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
