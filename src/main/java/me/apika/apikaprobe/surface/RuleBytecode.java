package me.apika.apikaprobe.surface;

/**
 * Opcode constants for the surface-rule bytecode IR.
 *
 * Spec: docs/SURFACE_RULE_BUFFER_SPEC.md
 *
 * The bytecode is emitted by {@link SurfaceRuleCompiler} from vanilla's
 * {@code MaterialRules} tree at world load and consumed (eventually) by
 * the Rust evaluator one chunk at a time. Opcode numbering matches the
 * spec's condition→opcode table so the same constants can be mirrored
 * on the Rust side without translation.
 */
public final class RuleBytecode {

	private RuleBytecode() {}

	// Conditions — return boolean
	public static final byte OP_ABOVE_Y       = 0x01;
	public static final byte OP_NOISE_THRESH  = 0x02;
	public static final byte OP_VERT_GRADIENT = 0x03;
	public static final byte OP_STONE_DEPTH   = 0x04;
	public static final byte OP_WATER         = 0x05;
	public static final byte OP_HOLE          = 0x06;
	public static final byte OP_SURFACE       = 0x07;
	public static final byte OP_BIOME         = 0x08;
	public static final byte OP_TEMPERATURE   = 0x09;
	public static final byte OP_STEEP         = 0x0A;
	public static final byte OP_NOT           = 0x0B;

	// Rules — produce blockstate or empty
	public static final byte OP_SEQUENCE      = 0x0C;
	public static final byte OP_CONDITION     = 0x0D;
	public static final byte OP_BLOCK         = 0x0E;

	// Sentinel — emitted when an unknown node is encountered. The
	// containing CompiledRuleTree.hasFallback flag is set in the same
	// step, and the dispatcher must route the chunk back to vanilla.
	public static final byte OP_FALLBACK      = 0x7F;
}
