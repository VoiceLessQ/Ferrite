/*
 * Derived from Alternate Current's Config (MIT, © 2022 Space Walker) but
 * reduced to the minimal surface Ferrite needs: two volatile knobs, no
 * file I/O, no per-world instances. Flipped at runtime by the
 * /ferrite redstone ac command.
 */
package me.apika.apikaprobe.redstone;

/**
 * Global, in-memory configuration for the Alternate-Current-derived
 * wire algorithm. Kept as static volatile fields so the A/B command
 * can flip them without synchronization, and because the update
 * algorithm reads them inside the hot loop.
 *
 * <p>Not persisted: the toggle returns to default on server restart.
 * If persistent configuration becomes useful, route through the
 * existing Ferrite command system rather than growing a config file.
 */
public final class FerriteWireConfig {

	private FerriteWireConfig() {}

	/**
	 * A/B master switch. When {@code false}, {@link FerriteRedstoneController}
	 * delegates everything to the cached vanilla controller — effectively
	 * invisible. When {@code true}, the Ferrite wire algorithm handles
	 * power updates.
	 *
	 * <p>Default off for safety; users enable explicitly via
	 * {@code /ferrite redstone ac on}.
	 */
	public static volatile boolean ENABLED = false;

	/**
	 * Neighbor traversal order used during wire power propagation.
	 * See {@link UpdateOrder} for the four variants.
	 *
	 * <p>Default matches AC's upstream default. The lag machine
	 * baseline was measured against this variant; switching orders
	 * changes observable update-order semantics in edge cases and
	 * should be deliberate.
	 */
	public static volatile UpdateOrder UPDATE_ORDER = UpdateOrder.HORIZONTAL_FIRST_OUTWARD;
}
