package com.synapsedb.engine;

/**
 * Outcome of {@link SynapseEngine#appendThought}.
 *
 * @param slot      the new thought's slot id (the slot IS the thought id)
 * @param salience  the salience the core computed for this slot
 * @param persisted whether the record was written to the ring file (always true on success)
 */
public record AppendResult(int slot, float salience, boolean persisted) {}
