package com.tickpilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Covers the per-server state lifecycle required by SPEC AC-19 and INV-7, using plain objects
 * as stand-ins for {@code MinecraftServer} so no Minecraft runtime is needed.
 */
class ServerStateRegistryTest {
	/** Stand-in for a server instance: identity is all the registry relies on. */
	private static final class FakeServer {
	}

	private static ServerStateRegistry<FakeServer, Object> registry() {
		return new ServerStateRegistry<>();
	}

	@Test
	void startsEmpty() {
		assertTrue(registry().isEmpty());
	}

	@Test
	void createStoresStateThatGetReturns() {
		ServerStateRegistry<FakeServer, Object> registry = registry();
		FakeServer server = new FakeServer();

		Object created = registry.create(server, key -> new Object());

		assertNotNull(created);
		assertSame(created, registry.get(server));
		assertEquals(1, registry.size());
	}

	@Test
	void getReturnsNullForUnknownServer() {
		ServerStateRegistry<FakeServer, Object> registry = registry();
		registry.create(new FakeServer(), key -> new Object());

		assertNull(registry.get(new FakeServer()));
	}

	@Test
	void removeDropsStateAndLeavesRegistryEmpty() {
		ServerStateRegistry<FakeServer, Object> registry = registry();
		FakeServer server = new FakeServer();
		Object created = registry.create(server, key -> new Object());

		assertSame(created, registry.remove(server));
		assertNull(registry.get(server));
		assertTrue(registry.isEmpty(), "nothing may survive a stopped server (INV-7)");
	}

	@Test
	void removeIsSafeWhenNothingWasStored() {
		assertNull(registry().remove(new FakeServer()));
	}

	/**
	 * AC-19: entering world A, leaving it, then entering world B must start from zero. Two
	 * different server instances therefore never share state.
	 */
	@Test
	void secondServerGetsFreshStateAfterFirstIsRemoved() {
		ServerStateRegistry<FakeServer, Object> registry = registry();
		FakeServer worldA = new FakeServer();
		FakeServer worldB = new FakeServer();

		Object stateA = registry.create(worldA, key -> new Object());
		registry.remove(worldA);
		assertTrue(registry.isEmpty());

		Object stateB = registry.create(worldB, key -> new Object());

		assertNotSame(stateA, stateB);
		assertNull(registry.get(worldA));
		assertSame(stateB, registry.get(worldB));
	}

	@Test
	void createReplacesExistingStateForTheSameServer() {
		ServerStateRegistry<FakeServer, Object> registry = registry();
		FakeServer server = new FakeServer();

		Object first = registry.create(server, key -> new Object());
		Object second = registry.create(server, key -> new Object());

		assertNotSame(first, second);
		assertSame(second, registry.get(server));
		assertEquals(1, registry.size());
	}

	@Test
	void factoryReceivesTheKeyAndRunsExactlyOncePerCreate() {
		ServerStateRegistry<FakeServer, Object> registry = registry();
		FakeServer server = new FakeServer();
		AtomicInteger calls = new AtomicInteger();

		registry.create(server, key -> {
			assertSame(server, key);
			calls.incrementAndGet();
			return new Object();
		});
		registry.get(server);
		registry.get(server);

		assertEquals(1, calls.get());
	}
}
