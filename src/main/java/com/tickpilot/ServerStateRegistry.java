package com.tickpilot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Owns the key-to-state mapping used by {@link ServerStateHolder}.
 *
 * <p>Deliberately generic and free of any {@code net.minecraft} import so that the
 * lifecycle contract required by SPEC AC-19 (state is created on start, removed on stop,
 * and a second server gets a fresh instance) can be unit-tested without launching
 * Minecraft. {@link ServerStateHolder} is the Minecraft-facing facade over this class.
 *
 * @param <K> key type; compared by {@link Object#equals(Object)}
 * @param <V> per-key state type
 */
final class ServerStateRegistry<K, V> {
	private final Map<K, V> states = new ConcurrentHashMap<>();

	/**
	 * Creates state for {@code key} using {@code factory} and stores it, replacing any
	 * previously stored value for that key.
	 *
	 * @return the newly created state
	 */
	V create(K key, Function<K, V> factory) {
		V state = factory.apply(key);
		states.put(key, state);
		return state;
	}

	/**
	 * @return the state stored for {@code key}, or {@code null} if there is none
	 */
	V get(K key) {
		return states.get(key);
	}

	/**
	 * Removes and returns the state stored for {@code key}, or {@code null} if there was none.
	 */
	V remove(K key) {
		return states.remove(key);
	}

	/**
	 * @return {@code true} when no state is held at all — the expected condition while no
	 *         server is running (SPEC INV-7)
	 */
	boolean isEmpty() {
		return states.isEmpty();
	}

	/**
	 * @return how many keys currently hold state
	 */
	int size() {
		return states.size();
	}
}
