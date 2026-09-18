package biz.playr;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;

/**
 * Combines Android's low-memory signals into a single {@link MemoryStatus}. The threshold-only
 * check ({@code availMem} vs {@code MemoryInfo.threshold}) misses device-wide pressure that still
 * triggers LMK kills while {@code lowMemory} remains false.
 */
final class MemoryPressureEvaluator {
	static final int TRIM_NONE = -1;

	// Tuned for always-on WebView players on low-RAM STBs: routine 4K decode often sits
	// around 70–85% system used without being an emergency. Previous 80/85/90 thresholds
	// triggered recreate/reload storms that made LMK more likely.
	private static final int SYSTEM_USED_MEDIUM_PCT = 88;
	private static final int SYSTEM_USED_LOW_PCT = 92;
	private static final int SYSTEM_USED_CRITICAL_PCT = 96;
	/** Session available RAM fell below this fraction of the value at startup. */
	private static final int AVAIL_VS_INITIAL_CRITICAL_PCT = 30;

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

	private static MemoryStatus systemUsedStatus(ActivityManager.MemoryInfo info) {
		if (info.totalMem <= 0L) {
			return MemoryStatus.OK;
		}
		int usedPct = (int) (((info.totalMem - info.availMem) * 100L) / info.totalMem);
		if (usedPct >= SYSTEM_USED_CRITICAL_PCT) {
			return MemoryStatus.CRITICAL;
		}
		if (usedPct >= SYSTEM_USED_LOW_PCT) {
			return MemoryStatus.LOW;
		}
		if (usedPct >= SYSTEM_USED_MEDIUM_PCT) {
			return MemoryStatus.MEDIUM;
		}
		return MemoryStatus.OK;
	}

	private static MemoryStatus availVsInitialStatus(long availMem, long initialAvailMem) {
		if (initialAvailMem <= 0L) {
			return MemoryStatus.OK;
		}
		int pct = (int) ((availMem * 100L) / initialAvailMem);
		if (pct < AVAIL_VS_INITIAL_CRITICAL_PCT) {
			return MemoryStatus.CRITICAL;
		}
		if (pct < 40) {
			return MemoryStatus.LOW;
		}
		if (pct < 55) {
			return MemoryStatus.MEDIUM;
		}
		return MemoryStatus.OK;
	}

	private static MemoryStatus trimLevelFloor(int trimLevel) {
		if (trimLevel == TRIM_NONE) {
			return MemoryStatus.OK;
		}
		// Trim alone is a hint, not proof the UI must be torn down. Cap at MEDIUM so
		// freeMemoryWhenNeeded can soft-reclaim while content is playing; CRITICAL still
		// comes from threshold / lowMemory / extreme %used.
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
