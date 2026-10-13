package mcopt.metal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mod's warnings, through the game's logger rather than stdout: they show as WARN (not "[STDOUT]" INFO) in
 * latest.log, the launcher's console and crash reports, so a failure stands out from the status lines and log filters
 * catch it, and a caught exception keeps its stack trace (where it failed, not only what). Messages say what stopped
 * working, what runs instead, and what to do when there is something to do.
 */
public final class Log {
	private static final Logger LOG = LoggerFactory.getLogger("mcopt");
	private static final Map<String, AtomicLong> REPEATS = new ConcurrentHashMap<>();

	private Log() {
	}

	/** Something didn't work and the game carries on without it (or with a fallback the message names). */
	public static void warn(String message) {
		LOG.warn(message);
	}

	/** As {@link #warn(String)}, with the exception's stack trace for the bug report. */
	public static void warn(String message, Throwable cause) {
		LOG.warn(message, cause);
	}

	/**
	 * For a failure that can repeat every frame or job without switching anything off: the first one is logged with its
	 * stack trace, later ones under the same key only as a count (every 1000th), so the log stays readable and the cause
	 * stays in it.
	 */
	public static void warnOnce(String key, String message, Throwable cause) {
		long n = REPEATS.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
		if (n == 1) LOG.warn(message + " (repeats are counted, not logged again)", cause);
		else if (n % 1000 == 0) LOG.warn(message + ": " + n + " times so far (" + cause + ")");
	}

	/** A decision worth knowing when reading a log (a speed-up stepping aside for another mod, a fallback chosen). */
	public static void info(String message) {
		LOG.info(message);
	}
}
