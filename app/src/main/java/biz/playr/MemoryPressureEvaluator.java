package biz.playr;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;

/**
 * Combines Android's low-memory signals into a single {@link MemoryStatus}. The threshold-only
 * check ({@code availMem} vs {@code MemoryInfo.threshold}) misses device-wide pressure that still
 * triggers LMK kills while {@code lowMemory} remains false.
 * <p>
 * Heuristics ({@code % system used}, avail-vs-initial, trim) are capped at {@link MemoryStatus#MEDIUM}
 * so they only drive soft reclaim / logging. {@link MemoryStatus#CRITICAL} comes only from
 * proximity to Android's LMK {@code threshold}; hard WebView teardown while playing additionally
 * requires {@code lowMemory} (see {@code MainActivity}).
 */
final class MemoryPressureEvaluator {
	static final int TRIM_NONE = -1;

	// Always-on WebView players often sit at high system used % without being in danger.
	// Cap this signal at MEDIUM — never escalate to LOW/CRITICAL from %used alone.
	private static final int SYSTEM_USED_MEDIUM_PCT = 88;
	/** Session available RAM fell below this fraction of the value at startup → MEDIUM (capped). */
	private static final int AVAIL_VS_INITIAL_MEDIUM_PCT = 55;

	private MemoryPressureEvaluator() {
	}

	static MemoryStatus evaluate(ActivityManager.MemoryInfo info, long initialAvailMem, int trimLevel) {
		MemoryStatus status = thresholdStatus(info.availMem, info.threshold);
		status = max(status, systemUsedStatus(info));
		if (info.lowMemory) {
			status = max(status, MemoryStatus.LOW);
		}
		status = max(status, trimLevelFloor(trimLevel));
		if (initialAvailMem > 0L) {
			status = max(status, availVsInitialStatus(info.availMem, initialAvailMem));
		}
		return status;
	}

	static String describe(ActivityManager.MemoryInfo info, long initialAvailMem, int trimLevel,
			MemoryStatus status) {
		long usedMem = info.totalMem - info.availMem;
		int usedPct = info.totalMem > 0L ? (int) ((usedMem * 100L) / info.totalMem) : 0;
		int availVsInitialPct = initialAvailMem > 0L
				? (int) ((info.availMem * 100L) / initialAvailMem)
				: 0;
		return "effective=" + status
				+ " threshold=" + thresholdStatus(info.availMem, info.threshold)
				+ " systemUsed=" + usedPct + "%=>" + systemUsedStatus(info)
				+ " lowMemoryFlag=" + info.lowMemory
				+ " trimLevel=" + trimLevel + "=>" + trimLevelFloor(trimLevel)
				+ " availVsInitial=" + availVsInitialPct + "%=>"
				+ (initialAvailMem > 0L
						? availVsInitialStatus(info.availMem, initialAvailMem)
						: MemoryStatus.OK);
	}

	/** Platform LMK threshold proximity — the only path that may return CRITICAL. */
	private static MemoryStatus thresholdStatus(long availableMemory, long threshold) {
		if (availableMemory > 1.25 * threshold) {
			return MemoryStatus.OK;
		}
		if (availableMemory > 1.15 * threshold) {
			return MemoryStatus.MEDIUM;
		}
		if (availableMemory > 1.05 * threshold) {
			return MemoryStatus.LOW;
		}
		return MemoryStatus.CRITICAL;
	}

	/** Busy-RAM heuristic only; capped at MEDIUM. */
	private static MemoryStatus systemUsedStatus(ActivityManager.MemoryInfo info) {
		if (info.totalMem <= 0L) {
			return MemoryStatus.OK;
		}
		int usedPct = (int) (((info.totalMem - info.availMem) * 100L) / info.totalMem);
		if (usedPct >= SYSTEM_USED_MEDIUM_PCT) {
			return MemoryStatus.MEDIUM;
		}
		return MemoryStatus.OK;
	}

	/** Session drift heuristic only; capped at MEDIUM. */
	private static MemoryStatus availVsInitialStatus(long availMem, long initialAvailMem) {
		if (initialAvailMem <= 0L) {
			return MemoryStatus.OK;
		}
		int pct = (int) ((availMem * 100L) / initialAvailMem);
		if (pct < AVAIL_VS_INITIAL_MEDIUM_PCT) {
			return MemoryStatus.MEDIUM;
		}
		return MemoryStatus.OK;
	}

	private static MemoryStatus trimLevelFloor(int trimLevel) {
		if (trimLevel == TRIM_NONE) {
			return MemoryStatus.OK;
		}
		// Trim alone is a hint, not proof the UI must be torn down. Cap at MEDIUM.
		if (trimLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
			return MemoryStatus.MEDIUM;
		}
		if (trimLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
			return MemoryStatus.MEDIUM;
		}
		// UI_HIDDEN / BACKGROUND: foreground kiosk players ignore for status floor.
		return MemoryStatus.OK;
	}

	private static MemoryStatus max(MemoryStatus a, MemoryStatus b) {
		return a.ordinal() >= b.ordinal() ? a : b;
	}
}
