package com.tickpilot.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.tickpilot.config.TickPilotConfig;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * The operator's four id lists from {@code tickpilot.toml}, resolved once into registry objects
 * (SPEC FR-15, INV-5, AC-9).
 *
 * <h2>Why resolve ahead of time</h2>
 * The membership question is asked once per candidate object per tick. Asking it as a string
 * comparison would mean turning a type into a {@link ResourceLocation} and that into a
 * {@code String} in the hot path — an allocation per object per tick and a direct breach of SPEC
 * INV-6. Resolving the lists once per config load turns the question into a hash lookup on a
 * registry singleton instead.
 *
 * <p>Immutable, and rebuilt from scratch by {@code /tickpilot reload}.
 *
 * <h2>One list, two registries</h2>
 * {@code throttle_allowlist} and {@code throttle_denylist} are single lists in the schema while
 * entities and block entities live in different registries, so each entry is looked up in both. An
 * entry that resolves in neither is kept as an unresolved name and reported once — silently
 * ignoring a typo in an allowlist is how an operator ends up believing they configured something
 * they did not.
 */
public final class TypeLists {
	private static final TypeLists EMPTY = new TypeLists();

	private final Set<EntityType<?>> entityAllowlist = new HashSet<>();
	private final Set<EntityType<?>> entityDenylist = new HashSet<>();
	private final Set<EntityType<?>> entityExcluded = new HashSet<>();
	private final Set<BlockEntityType<?>> blockEntityAllowlist = new HashSet<>();
	private final Set<BlockEntityType<?>> blockEntityDenylist = new HashSet<>();
	private final Set<BlockEntityType<?>> blockEntityExcluded = new HashSet<>();
	private final List<String> unresolved = new ArrayList<>();

	private TypeLists() {
	}

	/** @return lists with nothing in them, which is what SPEC FR-15 ships as the default */
	public static TypeLists empty() {
		return EMPTY;
	}

	/**
	 * Resolves every list in {@code config} against the registries.
	 *
	 * <p>Must run after the registries are populated, i.e. on {@code SERVER_STARTED} and on reload,
	 * never during mod initialisation.
	 *
	 * @param config the validated config snapshot
	 * @return the resolved lists
	 */
	public static TypeLists from(TickPilotConfig config) {
		TypeLists lists = new TypeLists();

		for (String id : config.throttleAllowlist()) {
			lists.resolveInto(id, lists.entityAllowlist, lists.blockEntityAllowlist);
		}

		for (String id : config.throttleDenylist()) {
			lists.resolveInto(id, lists.entityDenylist, lists.blockEntityDenylist);
		}

		for (String id : config.excludedEntityIds()) {
			lists.resolveInto(id, lists.entityExcluded, null);
		}

		for (String id : config.excludedBlockEntityIds()) {
			lists.resolveInto(id, null, lists.blockEntityExcluded);
		}

		// A namespace exclusion is expanded here rather than being matched per object: walking both
		// registries once per reload costs nothing, and it keeps the hot path a set lookup.
		if (!config.excludedModIds().isEmpty()) {
			Set<String> namespaces = new HashSet<>(config.excludedModIds());

			for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
				ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);

				if (key != null && namespaces.contains(key.getNamespace())) {
					lists.entityExcluded.add(type);
				}
			}

			for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
				ResourceLocation key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);

				if (key != null && namespaces.contains(key.getNamespace())) {
					lists.blockEntityExcluded.add(type);
				}
			}
		}

		return lists;
	}

	private void resolveInto(String id, Set<EntityType<?>> entityTarget,
			Set<BlockEntityType<?>> blockEntityTarget) {
		ResourceLocation key = ResourceLocation.tryParse(id.trim());

		if (key == null) {
			unresolved.add(id);
			return;
		}

		boolean found = false;

		if (entityTarget != null) {
			EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.getOptional(key).orElse(null);

			if (entityType != null) {
				entityTarget.add(entityType);
				found = true;
			}
		}

		if (blockEntityTarget != null) {
			BlockEntityType<?> blockEntityType = BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(key)
					.orElse(null);

			if (blockEntityType != null) {
				blockEntityTarget.add(blockEntityType);
				found = true;
			}
		}

		if (!found) {
			unresolved.add(id);
		}
	}

	/**
	 * @return the entries that matched no registered type, in config order. Reported once at load
	 *         so a typo is visible rather than silently inert
	 */
	public List<String> unresolved() {
		return Collections.unmodifiableList(unresolved);
	}

	/** @return {@code true} when no list has a single entry in it, the shipped default */
	public boolean isEmpty() {
		return entityAllowlist.isEmpty() && entityDenylist.isEmpty() && entityExcluded.isEmpty()
				&& blockEntityAllowlist.isEmpty() && blockEntityDenylist.isEmpty()
				&& blockEntityExcluded.isEmpty();
	}

	/** @return how many types the allowlists resolved to, across both registries */
	public int allowlistSize() {
		return entityAllowlist.size() + blockEntityAllowlist.size();
	}

	/**
	 * @param type the entity type
	 * @return whether the operator listed it in {@code throttle_allowlist} (SPEC INV-5)
	 */
	public boolean isAllowlisted(EntityType<?> type) {
		return entityAllowlist.contains(type);
	}

	/**
	 * @param type the entity type
	 * @return whether the operator listed it in {@code throttle_denylist}, which outranks the
	 *         allowlist
	 */
	public boolean isDenylisted(EntityType<?> type) {
		return entityDenylist.contains(type);
	}

	/**
	 * @param type the entity type
	 * @return whether the operator excluded it by id or by mod namespace, i.e. TickPilot is to
	 *         leave it alone entirely
	 */
	public boolean isExcluded(EntityType<?> type) {
		return entityExcluded.contains(type);
	}

	/**
	 * @param type the block entity type
	 * @return whether the operator listed it in {@code throttle_allowlist}
	 */
	public boolean isAllowlisted(BlockEntityType<?> type) {
		return blockEntityAllowlist.contains(type);
	}

	/**
	 * @param type the block entity type
	 * @return whether the operator listed it in {@code throttle_denylist}
	 */
	public boolean isDenylisted(BlockEntityType<?> type) {
		return blockEntityDenylist.contains(type);
	}

	/**
	 * @param type the block entity type
	 * @return whether the operator excluded it by id or by mod namespace
	 */
	public boolean isExcluded(BlockEntityType<?> type) {
		return blockEntityExcluded.contains(type);
	}
}
