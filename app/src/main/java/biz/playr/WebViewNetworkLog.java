package biz.playr;

import android.net.Uri;
import android.util.Log;
import android.webkit.ConsoleMessage;

/**
 * Classifies WebView network / console noise so field logcats stay readable.
 * Expected subresource failures (social OAuth proxies, ads, health telemetry) are
 * logged at DEBUG; main-frame and unexpected failures stay at ERROR.
 */
final class WebViewNetworkLog {
	private static final String className = BuildConfig.APP_NAMESPACE + ".WebViewNetLog";

	enum Category {
		/** Main-frame or unexpected host — keep ERROR so operators notice. */
		UNEXPECTED,
		/** Playr health / telemetry endpoints — INFO with a clear tag. */
		TELEMETRY,
		/** Social OAuth proxies, ad networks, trackers — DEBUG only. */
		EXPECTED_NOISE
	}

	private WebViewNetworkLog() {
	}

	static Category classify(String url) {
		if (url == null || url.isEmpty()) {
			return Category.UNEXPECTED;
		}
		String lower = url.toLowerCase();
		if (lower.contains("playback_health.json")
				|| lower.contains("/playback_health")) {
			return Category.TELEMETRY;
		}
		if (isExpectedNoiseHost(lower) || isExpectedNoisePath(lower)) {
			return Category.EXPECTED_NOISE;
		}
		return Category.UNEXPECTED;
	}

	static Category classifyConsole(String message) {
		if (message == null || message.isEmpty()) {
			return Category.UNEXPECTED;
		}
		String lower = message.toLowerCase();
		if (lower.contains("playback_health")) {
			return Category.TELEMETRY;
		}
		// CORS console text often hides an underlying 401 on ajax/proxy hosts.
		if (lower.contains("cors") || lower.contains("access-control-allow-origin")
				|| lower.contains("blocked by cors")) {
			if (lower.contains("ajax.playr.biz") || lower.contains("proxy.playr.biz")
					|| lower.contains("playback_health") || lower.contains("oauth_accounts")) {
				return Category.EXPECTED_NOISE;
			}
		}
		if (isExpectedNoiseHost(lower) || isExpectedNoisePath(lower)) {
			return Category.EXPECTED_NOISE;
		}
		return Category.UNEXPECTED;
	}

	static void logHttpError(String tag, boolean mainFrame, int statusCode, String reason,
			String url) {
		Category category = mainFrame ? Category.UNEXPECTED : classify(url);
		String line = "httpError status=" + statusCode
				+ " reason=" + (reason == null ? "" : reason)
				+ " mainFrame=" + mainFrame
				+ " category=" + category
				+ " url=" + shortenUrl(url);
		if (mainFrame || category == Category.UNEXPECTED) {
			Log.e(tag, line);
		} else if (category == Category.TELEMETRY) {
			Log.i(tag, line);
		} else {
			Log.d(tag, line);
		}
	}

	static void logNetworkError(String tag, boolean mainFrame, int errorCode, CharSequence description,
			String url) {
		Category category = mainFrame ? Category.UNEXPECTED : classify(url);
		String line = "networkError code=" + errorCode
				+ " desc=" + (description == null ? "" : description)
				+ " mainFrame=" + mainFrame
				+ " category=" + category
				+ " url=" + shortenUrl(url);
		if (mainFrame || category == Category.UNEXPECTED) {
			Log.e(tag, line);
		} else if (category == Category.TELEMETRY) {
			Log.i(tag, line);
		} else {
			Log.d(tag, line);
		}
	}

	static void logConsole(String tag, ConsoleMessage consoleMessage) {
		String message = consoleMessage.message();
		Category category = classifyConsole(message);
		String line = "console." + consoleMessage.messageLevel()
				+ " category=" + category
				+ " [" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber() + "] "
				+ message;
		if (category == Category.EXPECTED_NOISE) {
			Log.d(tag, line);
			return;
		}
		if (category == Category.TELEMETRY) {
			Log.i(tag, line);
			return;
		}
		if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
			Log.e(tag, line);
		} else {
			Log.i(tag, line);
		}
	}

	private static boolean isExpectedNoiseHost(String lowerUrl) {
		return lowerUrl.contains("proxy.playr.biz")
				|| lowerUrl.contains("doubleclick.net")
				|| lowerUrl.contains("googlesyndication.com")
				|| lowerUrl.contains("googleadservices.com")
				|| lowerUrl.contains("googletagmanager.com")
				|| lowerUrl.contains("google-analytics.com")
				|| lowerUrl.contains("facebook.com/tr")
				|| lowerUrl.contains("fbcdn.net")
				|| lowerUrl.contains("connect.facebook.net")
				|| lowerUrl.contains("remotejs.com");
	}

	private static boolean isExpectedNoisePath(String lowerUrl) {
		return lowerUrl.contains("/oauth_accounts/")
				|| lowerUrl.contains("provider_data.json")
				|| lowerUrl.contains("/pagead/");
	}

	static String shortenUrl(String url) {
		if (url == null) {
			return "";
		}
		try {
			Uri uri = Uri.parse(url);
			String host = uri.getHost();
			String path = uri.getPath();
			if (host == null) {
				return url.length() > 180 ? url.substring(0, 180) + "…" : url;
			}
			String shortPath = path == null ? "" : path;
			if (shortPath.length() > 80) {
				shortPath = shortPath.substring(0, 80) + "…";
			}
			String query = uri.getQuery();
			if (query != null && !query.isEmpty()) {
				return host + shortPath + "?…";
			}
			return host + shortPath;
		} catch (RuntimeException ex) {
			Log.d(className, ".shortenUrl failed", ex);
			return url.length() > 180 ? url.substring(0, 180) + "…" : url;
		}
	}
}
