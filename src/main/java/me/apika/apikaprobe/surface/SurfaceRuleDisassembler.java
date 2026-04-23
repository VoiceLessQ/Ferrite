package me.apika.apikaprobe.surface;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a {@link CompiledRuleTree}'s bytecode and produces a
 * human-readable disassembly — one line per opcode showing its IP
 * address, mnemonic, immediates, and (where useful) a brief decode of
 * what condition/jump-target the immediates resolve to.
 *
 * <p>Used by {@code /ferrite surface dump} to inspect the entire
 * compiled tree structure, mapping IP ranges that the trace tool
 * reports skipping back to specific rule nodes.
 *
 * <p>Static walker — no state. Each call returns a fresh list of
 * disassembly lines.
 */
public final class SurfaceRuleDisassembler {

	private SurfaceRuleDisassembler() {}

	public static List<String> disassemble(CompiledRuleTree tree) {
		byte[] bc = tree.bytecode();
		List<String> out = new ArrayList<>(bc.length / 2);
		out.add(String.format("=== bytecode: %d bytes, %d opcodes, hasFallback=%s ===",
				bc.length, tree.opcodeCount(), tree.hasFallback()));
		out.add(String.format("    blockstateTable: %d entries", tree.blockstateTable().length));
		out.add(String.format("    biomeSetTable:   %d pool entries", tree.biomeSetTable().length));
		out.add(String.format("    noiseChannels:   %d channels", tree.noiseChannelTable().length));
		out.add("---");

		int ip = 0;
		while (ip < bc.length) {
			int opIp = ip;
			byte op = bc[ip++];
			switch (op) {
				case RuleBytecode.OP_RETURN_DONE -> out.add(line(opIp, "OP_RETURN_DONE", ""));
				case RuleBytecode.OP_FALLBACK   -> out.add(line(opIp, "OP_FALLBACK",   ""));

				case RuleBytecode.OP_BLOCK -> {
					int id = readIntLE(bc, ip); ip += 4;
					String name = (id >= 0 && id < tree.blockstateTable().length)
							? String.valueOf(tree.blockstateTable()[id]) : "<oob>";
					out.add(line(opIp, "OP_BLOCK", String.format("id=%d (%s)", id, name)));
				}
				case RuleBytecode.OP_IF_ELSE -> {
					int thenOff = readIntLE(bc, ip); ip += 4;
					int elseOff = readIntLE(bc, ip); ip += 4;
					out.add(line(opIp, "OP_IF_ELSE", String.format("then=%d else=%d", thenOff, elseOff)));
				}
				case RuleBytecode.OP_SEQUENCE_NEXT -> {
					int endOff = readIntLE(bc, ip); ip += 4;
					out.add(line(opIp, "OP_SEQUENCE_NEXT", String.format("end=%d", endOff)));
				}
				case RuleBytecode.OP_NOT -> out.add(line(opIp, "OP_NOT", "(invert next condition)"));

				case RuleBytecode.OP_HOLE        -> out.add(line(opIp, "OP_HOLE", ""));
				case RuleBytecode.OP_STEEP       -> out.add(line(opIp, "OP_STEEP", ""));
				case RuleBytecode.OP_TEMPERATURE -> out.add(line(opIp, "OP_TEMPERATURE", ""));
				case RuleBytecode.OP_SURFACE     -> out.add(line(opIp, "OP_SURFACE", ""));

				case RuleBytecode.OP_BIOME -> {
					int idx = readU16LE(bc, ip); ip += 2;
					String contents = (idx < tree.biomeSetTable().length)
							? tree.biomeSetTable()[idx].toString() : "<oob>";
					out.add(line(opIp, "OP_BIOME", String.format("idx=%d %s", idx, contents)));
				}
				case RuleBytecode.OP_NOISE_THRESH -> {
					int chIdx = readU16LE(bc, ip); ip += 2;
					double minT = readDoubleLE(bc, ip); ip += 8;
					double maxT = readDoubleLE(bc, ip); ip += 8;
					String chName = (chIdx < tree.noiseChannelTable().length)
							? tree.noiseChannelTable()[chIdx] : "<oob>";
					out.add(line(opIp, "OP_NOISE_THRESH",
							String.format("ch=%d (%s) range=[%.4f, %.4f]", chIdx, chName, minT, maxT)));
				}
				case RuleBytecode.OP_ABOVE_Y -> {
					int anchorY = readIntLE(bc, ip); ip += 4;
					int sdMul = readIntLE(bc, ip); ip += 4;
					int addStone = bc[ip++] & 0xFF;
					out.add(line(opIp, "OP_ABOVE_Y",
							String.format("anchor=%d sdMul=%d addStone=%d", anchorY, sdMul, addStone)));
				}
				case RuleBytecode.OP_STONE_DEPTH -> {
					int offset = readIntLE(bc, ip); ip += 4;
					int addSurface = bc[ip++] & 0xFF;
					int sdRange = readIntLE(bc, ip); ip += 4;
					int surfType = bc[ip++] & 0xFF;
					out.add(line(opIp, "OP_STONE_DEPTH",
							String.format("offset=%d addSurface=%d sdRange=%d type=%s",
									offset, addSurface, sdRange,
									surfType == 0 ? "CEILING" : "FLOOR")));
				}
				case RuleBytecode.OP_WATER -> {
					int offset = readIntLE(bc, ip); ip += 4;
					int sdMul = readIntLE(bc, ip); ip += 4;
					int addStone = bc[ip++] & 0xFF;
					out.add(line(opIp, "OP_WATER",
							String.format("offset=%d sdMul=%d addStone=%d", offset, sdMul, addStone)));
				}
				case RuleBytecode.OP_VERT_GRADIENT -> {
					int nameIdx = readU16LE(bc, ip); ip += 2;
					int trueB = readIntLE(bc, ip); ip += 4;
					int falseA = readIntLE(bc, ip); ip += 4;
					String name = nameIdx < tree.randomNameTable().length
							? tree.randomNameTable()[nameIdx]
							: "?";
					out.add(line(opIp, "OP_VERT_GRADIENT",
							String.format("randomName='%s'(idx=%d) trueAtBelow=%d falseAtAbove=%d",
									name, nameIdx, trueB, falseA)));
				}

				default -> out.add(line(opIp, "??", String.format("opcode=0x%02x", op & 0xFF)));
			}
		}
		out.add(String.format("=== end of bytecode at %d ===", ip));
		return out;
	}

	private static String line(int ip, String mnemonic, String operands) {
		return operands.isEmpty()
				? String.format("[%04d] %s", ip, mnemonic)
				: String.format("[%04d] %-20s %s", ip, mnemonic, operands);
	}

	private static int readIntLE(byte[] b, int off) {
		return (b[off] & 0xFF)
				| ((b[off + 1] & 0xFF) << 8)
				| ((b[off + 2] & 0xFF) << 16)
				| ((b[off + 3] & 0xFF) << 24);
	}

	private static int readU16LE(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
	}

	private static double readDoubleLE(byte[] b, int off) {
		long bits = 0;
		for (int i = 0; i < 8; i++) {
			bits |= ((long)(b[off + i] & 0xFF)) << (i * 8);
		}
		return Double.longBitsToDouble(bits);
	}
}
