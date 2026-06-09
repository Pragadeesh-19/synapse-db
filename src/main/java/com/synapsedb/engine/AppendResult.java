package com.synapsedb.engine;

/**
 * Outcome of {@link SynapseEngine#appendThought}. Carries the values the engine read
 * back from the graph AFTER {@code append()} generated them (the timestamp and salience
 * are produced inside the core, not by the caller), so the controller never touches the
 * graph arrays directly.
 *
 * @param slot      the new thought's slot id (also its thought id — the slot IS the id)
 * @param salience  the seeded salience the core computed for this slot
 * @param persisted whether the record was written to the ring file (always true on success)
 */
public record AppendResult(int slot, float salience, boolean persisted) {}
