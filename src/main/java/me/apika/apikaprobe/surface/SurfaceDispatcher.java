package me.apika.apikaprobe.surface;

/**
 * Runtime toggle for the surface-rule dispatch swap.
 *
 * <p>When {@link #ENABLED} is true AND a compiled tree is installed via
 * {@code /ferrite surface validate}, {@code SurfaceValidatorMixin}'s
 * {@code tryApply} redirect routes through Ferrite's bytecode evaluator
 * instead of vanilla's {@code MaterialRule} tree walk. Eval results that
 * are non-null are returned to the caller as the authoritative output;
 * null results fall through to vanilla (matches the "no rule matched"
 * semantic and gives an automatic safety net for any residual evaluator
 * gap).
 *
 * <p>This is <em>simple</em> dispatch — the bytecode evaluator runs in
 * pure Java, one call per (x, y, z), no JNI per chunk. The trade-off
 * vs the batched JNI architecture is documented in the dispatcher swap
 * design discussion (this commit's change description). Simple-first
 * lets us measure whether the bytecode evaluator alone beats vanilla's
 * tree walk before committing to the bigger defer-and-batch
 * architecture.
 *
 * <p>Default OFF. Volatile, not persisted — flips back on server restart.
 * Toggle via {@code /ferrite surface dispatch on|off|status}.
 */
public final class SurfaceDispatcher {

	private SurfaceDispatcher() {}

	/**
	 * When true, intercepted {@code tryApply} calls run the bytecode
	 * evaluator and return its result (falling through to vanilla only
	 * if eval produces null). When false, the validator mixin retains
	 * its original behavior — call vanilla, optionally diff against eval.
	 */
	public static volatile boolean ENABLED = false;
}
