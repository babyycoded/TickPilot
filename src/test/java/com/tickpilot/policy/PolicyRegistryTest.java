package com.tickpilot.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tickpilot.api.TaskPriority;
import com.tickpilot.api.TaskProfile;
import com.tickpilot.api.ThrottleAdvice;
import com.tickpilot.api.ThrottlePolicy;

import org.junit.jupiter.api.Test;

/**
 * What the table of other mods' declarations promises (SPEC FR-14): last registration wins for a
 * profile, the most cautious answer wins for a hint, and nothing here ever throws at a caller.
 *
 * <p>Keyed by {@link String} rather than {@code ResourceLocation} so the class is exercised
 * without Minecraft; the mod itself uses the registry id type.
 */
class PolicyRegistryTest {
	private final PolicyRegistry<String> registry = new PolicyRegistry<>();

	@Test
	void anUnregisteredTaskHasNoProfileRatherThanAnInventedOne() {
		assertNull(registry.taskProfile("nothing:here"));
		assertEquals(0, registry.taskProfileCount());
	}

	@Test
	void theLastRegistrationOfATaskProfileWins() {
		TaskProfile first = TaskProfile.deferrableTask(5L, TaskPriority.LOW);
		TaskProfile second = TaskProfile.deferrableTask(50L, TaskPriority.HIGH);

		assertFalse(registry.registerTaskProfile("mod:task", first),
				"a first registration is not a collision");
		assertTrue(registry.registerTaskProfile("mod:task", second),
				"a different profile under the same id is worth reporting");
		assertSame(second, registry.taskProfile("mod:task"));
	}

	@Test
	void registeringTheSameProfileTwiceIsNotACollision() {
		TaskProfile profile = TaskProfile.deferrableTask();

		registry.registerTaskProfile("mod:task", profile);
		assertFalse(registry.registerTaskProfile("mod:task", profile));
		assertFalse(registry.registerTaskProfile("mod:task", TaskProfile.deferrableTask()),
				"an equal profile is not a different one");
	}

	@Test
	void theSmallestDeferHintWins() {
		// Two mods disagreeing about someone's content is not something TickPilot can adjudicate,
		// so it keeps the more cautious of the two answers.
		assertTrue(registry.markSafeToDefer("minecraft:hopper", 40L));
		assertTrue(registry.markSafeToDefer("minecraft:hopper", 10L));
		assertTrue(registry.markSafeToDefer("minecraft:hopper", 25L));

		assertEquals(10L, registry.deferHint("minecraft:hopper", -1L));
		assertEquals(1, registry.deferHintCount());
	}

	@Test
	void aTypeWithNoHintFallsBackToWhatTheCallerAsksFor() {
		assertEquals(7L, registry.deferHint("minecraft:chest", 7L));
	}

	@Test
	void aMeaninglessDeferHintIsIgnored() {
		assertFalse(registry.markSafeToDefer("minecraft:chest", 0L));
		assertFalse(registry.markSafeToDefer("minecraft:chest", -3L));
		assertEquals(0, registry.deferHintCount());
	}

	@Test
	void asyncComputeHintsAreCountedOncePerType() {
		assertTrue(registry.markSafeForAsyncCompute("mod:thinker"));
		assertFalse(registry.markSafeForAsyncCompute("mod:thinker"), "the second one is not new");

		assertTrue(registry.isSafeForAsyncCompute("mod:thinker"));
		assertFalse(registry.isSafeForAsyncCompute("mod:other"));
		assertEquals(1, registry.asyncComputeHintCount());
	}

	@Test
	void policiesAreStoredAndExposedReadOnly() {
		ThrottlePolicy policy = (typeId, load) -> ThrottleAdvice.NEVER_THROTTLE;

		assertTrue(registry.registerPolicy("mod:policy", policy));
		assertEquals(1, registry.policyCount());
		assertTrue(registry.policies().contains(policy));
		assertThrows(UnsupportedOperationException.class, () -> registry.policies().clear());
	}

	@Test
	void nullArgumentsAreRefusedRatherThanStoredOrThrown() {
		assertFalse(registry.registerTaskProfile(null, TaskProfile.deferrableTask()));
		assertFalse(registry.registerTaskProfile("mod:task", null));
		assertFalse(registry.registerPolicy(null, (typeId, load) -> ThrottleAdvice.NO_OPINION));
		assertFalse(registry.registerPolicy("mod:policy", null));
		assertFalse(registry.markSafeToDefer(null, 10L));
		assertFalse(registry.markSafeForAsyncCompute(null));

		assertNull(registry.taskProfile(null));
		assertEquals(3L, registry.deferHint(null, 3L));
		assertFalse(registry.isSafeForAsyncCompute(null));
		assertEquals(0, registry.taskProfileCount() + registry.policyCount()
				+ registry.deferHintCount() + registry.asyncComputeHintCount());
	}

	@Test
	void clearingEmptiesEveryTable() {
		registry.registerTaskProfile("mod:task", TaskProfile.deferrableTask());
		registry.registerPolicy("mod:policy", (typeId, load) -> ThrottleAdvice.NO_OPINION);
		registry.markSafeToDefer("mod:type", 10L);
		registry.markSafeForAsyncCompute("mod:type");

		registry.clear();

		assertEquals(0, registry.taskProfileCount());
		assertEquals(0, registry.policyCount());
		assertEquals(0, registry.deferHintCount());
		assertEquals(0, registry.asyncComputeHintCount());
	}
}
