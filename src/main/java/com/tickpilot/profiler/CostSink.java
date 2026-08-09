package com.tickpilot.profiler;

/**
 * Receives the self time of one finished profiler frame, keyed by whatever the caller identified
 * it with — an {@code EntityType} or a {@code BlockEntityType} in practice (SPEC FR-3).
 *
 * <p>Deliberately typed as {@link Object}: {@link TickProfiler} must not import anything from
 * {@code net.minecraft}, and it never does anything with a key beyond handing it back.
 *
 * <p>Called from the server thread inside the tick loop, so an implementation must not allocate
 * per call (SPEC INV-6).
 */
@FunctionalInterface
public interface CostSink {
	/**
	 * @param category   the category the frame belonged to
	 * @param key        the caller's identity for this frame; never {@code null}
	 * @param selfNanos  time spent in this frame excluding every nested frame
	 */
	void record(TickCategory category, Object key, long selfNanos);
}
