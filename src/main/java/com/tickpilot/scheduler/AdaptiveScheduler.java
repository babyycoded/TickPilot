package com.tickpilot.scheduler;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import com.tickpilot.api.SubmitResult;
import com.tickpilot.api.TaskPriority;
import com.tickpilot.api.TaskProfile;

/**
 * The bounded queue of deferred work behind {@code TickPilotApi.submit} (SPEC FR-6, AC-6).
 *
 * <h2>What it does and does not schedule</h2>
 * Only tasks another mod registered and submitted through the public API. TickPilot never
 * intercepts a {@link Runnable} of Minecraft's own — not the server's task queue, not chunk
 * futures, not anything else. SPEC FR-6 states this as a v1 limit, and it is also the only
 * version of this feature that can be reasoned about: vanilla's own tasks carry no profile saying
 * whether anything is waiting on them.
 *
 * <h2>The four guarantees of AC-6</h2>
 * <ol>
 *   <li><b>Bounded.</b> At most {@code maxDeferredTasks} tasks are ever held. The cap is enforced
 *       before insertion, so the queue cannot exceed it even for one call.</li>
 *   <li><b>Prioritised.</b> The queue drains {@link TaskPriority#HIGH} first and, within one
 *       priority, in submission order.</li>
 *   <li><b>Starvation-proof.</b> A task whose {@link TaskProfile#maxDelayTicks()} has elapsed runs
 *       on the next tick <em>before</em> any priority draining and <em>regardless</em> of the time
 *       budget. A permanently overloaded server therefore still empties old work; it does not
 *       accumulate it. {@link TaskProfile#MAX_DELAY_TICKS} caps how far away a deadline may be, so
 *       the guarantee cannot be opted out of.</li>
 *   <li><b>Overflow drops instead of growing.</b> When the queue is full, a submission more urgent
 *       than the least urgent queued task takes its place; otherwise the submission is refused.
 *       Either way the caller is told which happened, and the event is logged with a cooldown.</li>
 * </ol>
 * A {@link TaskProfile#critical()} task never reaches the queue at all — it runs inside
 * {@code submit}, so it can be neither delayed nor dropped.
 *
 * <h2>Structure, and why not a plain PriorityQueue</h2>
 * Two indexes over the same entries:
 * <ul>
 *   <li>one intrusive FIFO list per priority, giving O(1) "next by priority" and O(1) "least
 *       urgent, oldest" for the overflow victim;</li>
 *   <li>a binary min-heap keyed by (deadline, submission order), giving O(log n) "what has
 *       expired".</li>
 * </ul>
 * Each entry carries its own links and heap slot, so a task drained by one index is removed from
 * the other in O(log n) rather than being left behind as a tombstone. That is the difference
 * between a bound on live tasks and a bound on memory: deadlines are not monotonic within a
 * priority — a task submitted later with a shorter {@code maxDelayTicks} expires earlier — so a
 * single ordering cannot serve both, and lazy deletion would let dead entries pile up in a
 * priority that is never drained.
 *
 * <h2>Threading</h2>
 * Single-threaded by construction: every method must be called on the server thread. Nothing here
 * is synchronised and nothing needs to be, because {@code TickPilotApi} refuses a submission from
 * any other thread before it reaches this class (SPEC INV-1).
 *
 * <h2>Failure</h2>
 * A task that throws is caught, counted and reported once per cooldown; the queue keeps going and
 * the server never sees the exception (SPEC INV-9).
 *
 * <p>No {@code net.minecraft} import: the task id is a type parameter and the clock is supplied by
 * the caller, so the whole scheduler is unit-tested without the game (SPEC §8).
 *
 * @param <K> the task id type; {@code ResourceLocation} in the mod, {@code String} in tests
 */
public final class AdaptiveScheduler<K> {
	/** Ticks between two log reports of the same kind — 30 s at 20 TPS (SPEC INV-9, AC-16). */
	public static final long REPORT_COOLDOWN_TICKS = 600L;

	/**
	 * Where the sink for the rare events worth logging is plugged in.
	 *
	 * <p>An interface rather than a logger so the scheduler stays free of every dependency but the
	 * JDK, and so the cooldown that decides <em>whether</em> to report can be tested without
	 * capturing log output. All methods are called on the server thread.
	 *
	 * @param <K> the task id type
	 */
	public interface Events<K> {
		/**
		 * A task threw. Called at most once per {@link #REPORT_COOLDOWN_TICKS}.
		 *
		 * @param taskId        the task that threw
		 * @param failure       what it threw
		 * @param totalFailures how many tasks have thrown since the server started
		 */
		void taskFailed(K taskId, Throwable failure, long totalFailures);

		/**
		 * The queue is full and work is being dropped or refused. Called at most once per
		 * {@link #REPORT_COOLDOWN_TICKS}.
		 *
		 * @param queued        tasks waiting right now
		 * @param maxQueued     the configured cap
		 * @param totalDropped  queued tasks thrown away for a more urgent one, since server start
		 * @param totalRejected submissions refused outright, since server start
		 */
		void overflow(int queued, int maxQueued, long totalDropped, long totalRejected);

		/**
		 * The queue has drained back to its recovery mark after an overflow. Called once per
		 * recovery, so that a log that reported a problem also reports its end.
		 *
		 * @param queued        tasks waiting right now
		 * @param totalDropped  queued tasks thrown away, since server start
		 * @param totalRejected submissions refused, since server start
		 */
		void recovered(int queued, long totalDropped, long totalRejected);

		/**
		 * @param <K> the task id type
		 * @return a sink that discards everything, for tests and for a scheduler with no logger
		 */
		static <K> Events<K> ignore() {
			return new Events<K>() {
				@Override
				public void taskFailed(K taskId, Throwable failure, long totalFailures) {
				}

				@Override
				public void overflow(int queued, int maxQueued, long totalDropped, long totalRejected) {
				}

				@Override
				public void recovered(int queued, long totalDropped, long totalRejected) {
				}
			};
		}
	}

	/** One queued task. Mutable and intrusive: it is its own list node and knows its heap slot. */
	private static final class Entry<K> {
		private K id;
		private Runnable work;
		private TaskPriority priority;
		private long deadlineTick;
		private long sequence;
		private boolean coalescable;
		private Entry<K> previous;
		private Entry<K> next;
		private int heapIndex = -1;
	}

	private static final int INITIAL_HEAP_CAPACITY = 16;

	private int maxQueued;
	private int recoveryMark;
	private final LongSupplier nanoClock;
	private final Events<K> events;

	private final Entry<K>[] heads;
	private final Entry<K>[] tails;

	private Entry<K>[] heap;
	private int heapSize;

	/** Index of the queued coalescable tasks, so a repeat submission finds its predecessor. */
	private final Map<K, Entry<K>> coalescing = new HashMap<>();

	private int size;
	private int peakQueued;
	private boolean deferralEnabled = true;
	private boolean emergency;

	private long tick;
	private long sequence;
	private long lastOverflowReportTick = Long.MIN_VALUE;
	private long lastFailureReportTick = Long.MIN_VALUE;

	private long submitted;
	private long executedNow;
	private long deferred;
	private long coalesced;
	private long executedForced;
	private long executedBudgeted;
	private long dropped;
	private long rejected;
	private long discarded;
	private long failed;
	private long spentNanos;

	/**
	 * @param maxQueued hard cap on queued tasks, {@code max_deferred_tasks} from the config
	 *                  (SPEC FR-15). Values below 1 are raised to 1
	 * @param nanoClock source of {@link System#nanoTime()}; supplied so tests need no real time
	 * @param events    sink for the rare events worth logging; {@link Events#ignore()} is valid
	 */
	@SuppressWarnings("unchecked")
	public AdaptiveScheduler(int maxQueued, LongSupplier nanoClock, Events<K> events) {
		this.maxQueued = Math.max(1, maxQueued);
		// Half the cap: an overflow is reported once and its end is reported once, instead of a
		// pair of lines every time one task leaves and another arrives at the boundary.
		this.recoveryMark = this.maxQueued / 2;
		this.nanoClock = nanoClock;
		this.events = events;
		this.heads = new Entry[TaskPriority.values().length];
		this.tails = new Entry[TaskPriority.values().length];
		this.heap = new Entry[Math.min(this.maxQueued, INITIAL_HEAP_CAPACITY)];
	}

	/**
	 * Whether work may be queued at all.
	 *
	 * <p>Switched off in STRICT mode and whenever {@code enable_adaptive_mode} is false: delaying
	 * another mod's work is an intervention, and SPEC FR-11 says STRICT performs none. With it off,
	 * every submission runs immediately, which is exactly what would happen without TickPilot
	 * installed. Tasks queued before the switch still drain normally.
	 *
	 * @param enabled whether submissions may be deferred
	 */
	public void setDeferralEnabled(boolean enabled) {
		this.deferralEnabled = enabled;
	}

	/** @return whether submissions may currently be deferred */
	public boolean isDeferralEnabled() {
		return deferralEnabled;
	}

	/** @return tasks waiting in the queue right now */
	public int queued() {
		return size;
	}

	/** @return the configured queue cap */
	public int maxQueued() {
		return maxQueued;
	}

	/**
	 * Applies a new {@code max_deferred_tasks} without restarting the server (SPEC AC-15).
	 *
	 * <p>Lowering the cap below what is already queued drops the least urgent tasks immediately
	 * rather than letting the queue sit over its limit until it drains: an operator who lowers this
	 * key is asking for a bound to hold now. The dropped tasks are counted and reported like any
	 * other overflow.
	 *
	 * @param newMax the new cap; values below 1 are raised to 1
	 * @return how many queued tasks had to be dropped to fit
	 */
	public int setMaxQueued(int newMax) {
		maxQueued = Math.max(1, newMax);
		recoveryMark = maxQueued / 2;
		int lost = 0;

		while (size > maxQueued) {
			Entry<K> victim = leastUrgentQueued();

			if (victim == null) {
				break;
			}

			remove(victim);
			dropped++;
			lost++;
		}

		if (lost > 0) {
			enterEmergency();
		}

		return lost;
	}

	/** @return how many ticks this scheduler has run for; deadlines are counted in these */
	public long currentTick() {
		return tick;
	}

	/** @return {@code true} while the queue is in the overflow state of SPEC AC-6 */
	public boolean isEmergency() {
		return emergency;
	}

	/**
	 * Takes one submission. Server thread only.
	 *
	 * @param taskId  the registered task id
	 * @param work    what to run on the server thread
	 * @param profile what the registering mod said about this kind of work
	 * @return what happened to the work; see {@link SubmitResult}
	 */
	public SubmitResult submit(K taskId, Runnable work, TaskProfile profile) {
		if (taskId == null || work == null || profile == null) {
			return SubmitResult.UNAVAILABLE;
		}

		submitted++;

		// Critical and non-deferrable work runs here, on the caller's own tick. It never enters
		// the queue, so no later decision - priority, deadline, overflow - can apply to it (AC-6).
		if (!deferralEnabled || !profile.deferrable() || profile.critical()) {
			executedNow++;
			runGuarded(taskId, work);
			return SubmitResult.EXECUTED_NOW;
		}

		if (profile.coalescable()) {
			Entry<K> queuedAlready = coalescing.get(taskId);

			if (queuedAlready != null) {
				// Newest work wins, oldest position and deadline are kept: the semantics of
				// "dirty again", and the reason coalescing cannot starve anything.
				queuedAlready.work = work;
				coalesced++;
				return SubmitResult.COALESCED;
			}
		}

		if (size >= maxQueued && !makeRoomFor(profile.priority())) {
			return SubmitResult.REJECTED_QUEUE_FULL;
		}

		enqueue(taskId, work, profile);
		return SubmitResult.DEFERRED;
	}

	/**
	 * The overflow half of AC-6: the least urgent queued task gives way to a more urgent
	 * submission, and nothing else does.
	 *
	 * @return {@code true} when there is now room, {@code false} when the submission must be
	 *         refused because everything queued is at least as urgent as it is
	 */
	private boolean makeRoomFor(TaskPriority incoming) {
		Entry<K> victim = leastUrgentQueued();

		if (victim == null || !incoming.isMoreUrgentThan(victim.priority)) {
			// Refusing the newcomer rather than evicting an equal keeps submission order meaningful:
			// under sustained pressure the older task of the same priority is the one that survives.
			rejected++;
			enterEmergency();
			return false;
		}

		remove(victim);
		dropped++;
		enterEmergency();
		return true;
	}

	private void enqueue(K taskId, Runnable work, TaskProfile profile) {
		Entry<K> entry = new Entry<>();
		entry.id = taskId;
		entry.work = work;
		entry.priority = profile.priority();
		entry.sequence = sequence++;
		entry.deadlineTick = tick + profile.maxDelayTicks();
		entry.coalescable = profile.coalescable();

		append(entry);
		heapInsert(entry);

		if (entry.coalescable) {
			coalescing.put(taskId, entry);
		}

		size++;
		deferred++;

		if (size > peakQueued) {
			peakQueued = size;
		}
	}

	/**
	 * Runs one tick's worth of deferred work. Server thread only.
	 *
	 * <p>Expired tasks first and unconditionally — that is the starvation protection, and it is
	 * the one thing a zero budget does not stop. Then priority order for as long as the budget
	 * lasts. The budget is checked before each task and not during one, so a single long task can
	 * overrun it; the overrun is visible in {@link SchedulerStats#spentNanos()} rather than being
	 * silently absorbed.
	 *
	 * <p>Work submitted by a task while this method is running always waits for a later tick: the
	 * number of tasks one call may run is fixed at the queue length on entry, so a task that
	 * resubmits itself cannot spin the server inside a single tick.
	 *
	 * @param budgetNanos how long the priority draining may take. Zero or less runs only the
	 *                    expired tasks
	 * @return how many tasks were run
	 */
	public int runTick(long budgetNanos) {
		tick++;

		long start = nanoClock.getAsLong();
		int allowance = size;
		int executed = 0;

		while (allowance > 0) {
			Entry<K> expired = heapPeek();

			if (expired == null || expired.deadlineTick > tick) {
				break;
			}

			remove(expired);
			executedForced++;
			allowance--;
			executed++;
			runGuarded(expired.id, expired.work);
		}

		while (allowance > 0 && size > 0 && budgetNanos > 0L
				&& nanoClock.getAsLong() - start < budgetNanos) {
			Entry<K> next = pollByPriority();
			executedBudgeted++;
			allowance--;
			executed++;
			runGuarded(next.id, next.work);
		}

		spentNanos += Math.max(0L, nanoClock.getAsLong() - start);

		if (emergency && size <= recoveryMark) {
			emergency = false;
			events.recovered(size, dropped, rejected);
		}

		return executed;
	}

	/**
	 * Throws away everything still queued, without running it. Called when the server stops.
	 *
	 * <p>Running it instead would mean executing other mods' work against a world that is being
	 * torn down, on a shutdown path that is expected to finish promptly. Work that must not be
	 * lost must not be deferrable — that is stated in the API documentation, not left to be
	 * discovered.
	 *
	 * @return how many tasks were discarded
	 */
	public int discardQueued() {
		int lost = size;
		discarded += lost;

		for (int i = 0; i < heapSize; i++) {
			heap[i] = null;
		}

		heapSize = 0;

		for (int i = 0; i < heads.length; i++) {
			heads[i] = null;
			tails[i] = null;
		}

		coalescing.clear();
		size = 0;
		emergency = false;
		return lost;
	}

	/** @return an immutable count of everything this scheduler has done (SPEC FR-12) */
	public SchedulerStats stats() {
		return new SchedulerStats(size, peakQueued, maxQueued, emergency, submitted, executedNow,
				deferred, coalesced, executedForced, executedBudgeted, dropped, rejected, discarded,
				failed, spentNanos, tick);
	}

	private void runGuarded(K taskId, Runnable work) {
		try {
			work.run();
		} catch (Throwable failure) {
			// INV-9: another mod's broken task is its problem, never the server's.
			failed++;

			if (tick - lastFailureReportTick >= REPORT_COOLDOWN_TICKS
					|| lastFailureReportTick == Long.MIN_VALUE) {
				lastFailureReportTick = tick;
				events.taskFailed(taskId, failure, failed);
			}
		}
	}

	private void enterEmergency() {
		emergency = true;

		if (tick - lastOverflowReportTick >= REPORT_COOLDOWN_TICKS
				|| lastOverflowReportTick == Long.MIN_VALUE) {
			lastOverflowReportTick = tick;
			events.overflow(size, maxQueued, dropped, rejected);
		}
	}

	/** @return the oldest task of the least urgent non-empty priority, or {@code null} if empty */
	private Entry<K> leastUrgentQueued() {
		for (int priority = heads.length - 1; priority >= 0; priority--) {
			if (heads[priority] != null) {
				return heads[priority];
			}
		}

		return null;
	}

	/** @return the next task in drain order, removed from both indexes. Never called when empty. */
	private Entry<K> pollByPriority() {
		for (Entry<K> head : heads) {
			if (head != null) {
				remove(head);
				return head;
			}
		}

		throw new IllegalStateException("pollByPriority called on an empty scheduler");
	}

	private void remove(Entry<K> entry) {
		unlink(entry);
		heapRemove(entry.heapIndex);

		if (entry.coalescable) {
			coalescing.remove(entry.id, entry);
		}

		size--;
	}

	private void append(Entry<K> entry) {
		int priority = entry.priority.ordinal();
		Entry<K> tail = tails[priority];

		entry.previous = tail;
		entry.next = null;

		if (tail == null) {
			heads[priority] = entry;
		} else {
			tail.next = entry;
		}

		tails[priority] = entry;
	}

	private void unlink(Entry<K> entry) {
		int priority = entry.priority.ordinal();

		if (entry.previous == null) {
			heads[priority] = entry.next;
		} else {
			entry.previous.next = entry.next;
		}

		if (entry.next == null) {
			tails[priority] = entry.previous;
		} else {
			entry.next.previous = entry.previous;
		}

		entry.previous = null;
		entry.next = null;
	}

	private Entry<K> heapPeek() {
		return heapSize == 0 ? null : heap[0];
	}

	private void heapInsert(Entry<K> entry) {
		if (heapSize == heap.length) {
			growHeap();
		}

		heap[heapSize] = entry;
		entry.heapIndex = heapSize;
		heapSize++;
		siftUp(heapSize - 1);
	}

	private void heapRemove(int index) {
		heapSize--;
		Entry<K> moved = heap[heapSize];
		heap[heapSize] = null;

		if (index != heapSize) {
			heap[index] = moved;
			moved.heapIndex = index;
			siftDown(index);
			siftUp(moved.heapIndex);
		}
	}

	private void siftUp(int index) {
		Entry<K> entry = heap[index];

		while (index > 0) {
			int parent = (index - 1) >>> 1;

			if (!expiresBefore(entry, heap[parent])) {
				break;
			}

			place(heap[parent], index);
			index = parent;
		}

		place(entry, index);
	}

	private void siftDown(int index) {
		Entry<K> entry = heap[index];
		int half = heapSize >>> 1;

		while (index < half) {
			int child = (index << 1) + 1;
			int right = child + 1;

			if (right < heapSize && expiresBefore(heap[right], heap[child])) {
				child = right;
			}

			if (!expiresBefore(heap[child], entry)) {
				break;
			}

			place(heap[child], index);
			index = child;
		}

		place(entry, index);
	}

	private void place(Entry<K> entry, int index) {
		heap[index] = entry;
		entry.heapIndex = index;
	}

	/** Earlier deadline first; equal deadlines keep submission order. */
	private boolean expiresBefore(Entry<K> left, Entry<K> right) {
		return left.deadlineTick != right.deadlineTick
				? left.deadlineTick < right.deadlineTick
				: left.sequence < right.sequence;
	}

	@SuppressWarnings("unchecked")
	private void growHeap() {
		int capacity = Math.min(maxQueued, Math.max(INITIAL_HEAP_CAPACITY, heap.length * 2));
		Entry<K>[] grown = new Entry[capacity];
		System.arraycopy(heap, 0, grown, 0, heapSize);
		heap = grown;
	}
}
