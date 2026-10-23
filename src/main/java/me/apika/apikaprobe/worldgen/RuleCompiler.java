package me.apika.apikaprobe.worldgen;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.condition.BiomeCondition;
import net.minecraft.world.level.levelgen.material.condition.ConditionEvaluator;
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
import net.minecraft.world.level.levelgen.material.condition.NotCondition;
import net.minecraft.world.level.levelgen.material.rule.BlockRule;
import net.minecraft.world.level.levelgen.material.rule.ConditionRule;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;

// Material rule tree as one hidden class per folded shape, one call site per node, vanilla leaves; default auto (-Dferrite.rules=auto|on|off).
public final class RuleCompiler {

	private RuleCompiler() {}

	// Set by FerriteMixinPlugin when another mod mixes into the material rule classes; auto then stays off.
	private static final String YIELD = System.getProperty("ferrite.rules.yield");
	private static final String MODE = System.getProperty("ferrite.rules", "auto");

	public static volatile boolean enabled = "on".equals(MODE) || ("auto".equals(MODE) && YIELD == null);

	// 1 in N chunks also run vanilla's evaluator on every block and keep its answer; 0 disables.
	public static volatile int oracleRate = Integer.getInteger("ferrite.rules.oracle", 64);

	private static final int LIVE = 0;
	private static final int TRUE = 1;
	private static final int FALSE = 2;
	private static final int CACHE_LIMIT = 4096;
	// Longer sequences are split so no generated method nears HotSpot's 8000-byte compile limit.
	private static final int MAX_CHILDREN = 64;

	private static final ClassDesc SELF = ClassDesc.of("me.apika.apikaprobe.worldgen.CompiledRules");
	private static final ClassDesc CD_RULE = ClassDesc.of(RuleEvaluator.class.getName());
	private static final ClassDesc CD_COND = ClassDesc.of(ConditionEvaluator.class.getName());
	private static final ClassDesc CD_STATE = ClassDesc.of(BlockState.class.getName());
	private static final MethodTypeDesc MTD_APPLY = MethodTypeDesc.of(CD_STATE,
			ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int);
	private static final MethodTypeDesc MTD_TEST = MethodTypeDesc.of(ConstantDescs.CD_boolean);
	private static final MethodTypeDesc MTD_INIT = MethodTypeDesc.of(ConstantDescs.CD_void,
			ConstantDescs.CD_Object.arrayType());
	private static final ClassHierarchyResolver RESOLVER = ClassHierarchyResolver.defaultResolver()
			.orElse(ClassHierarchyResolver.of(List.of(), Map.of(SELF, ConstantDescs.CD_Object)))
			.orElse(ClassHierarchyResolver.ofClassLoading(RuleCompiler.class.getClassLoader()));
	private static final RuleEvaluator NULL_RULE = (blockX, blockY, blockZ) -> null;

	private static final ConcurrentHashMap<Key, MethodHandle> CACHE = new ConcurrentHashMap<>();

	private static final LongAdder chunks = new LongAdder();
	private static final LongAdder generated = new LongAdder();
	private static final LongAdder genNanos = new LongAdder();
	private static final LongAdder fallbacks = new LongAdder();
	private static final LongAdder oracleChunks = new LongAdder();
	private static final LongAdder oracleBlocks = new LongAdder();
	private static final LongAdder mismatches = new LongAdder();
	private static final AtomicLong chunkCount = new AtomicLong();
	private static final AtomicInteger logged = new AtomicInteger();

	public static RuleEvaluator compile(MaterialRule root, MaterialRuleContext context) {
		if (!enabled) return root.compile(context);
		RuleEvaluator fast;
		try {
			Walk walk = new Walk(context);
			Node shape = walk.rule(root);
			if (shape == null) {
				fast = NULL_RULE;
			} else {
				if (CACHE.size() > CACHE_LIMIT) CACHE.clear();
				MethodHandle ctor = CACHE.computeIfAbsent(new Key(root, walk.bits.toString()),
						k -> generate(shape, walk.kinds.toString()));
				fast = (RuleEvaluator) ctor.invokeExact(walk.fields.toArray());
			}
		} catch (Throwable t) {
			fallbacks.increment();
			enabled = false;
			ExampleMod.LOGGER.error("[ferrite/rules] compile failed, rule compiler off", t);
			return root.compile(context);
		}
		chunks.increment();
		int rate = oracleRate;
		if (rate > 0 && chunkCount.incrementAndGet() % rate == 0) {
			oracleChunks.increment();
			return new Oracle(fast, root.compile(context));
		}
		return fast;
	}

	public static String report() {
		return String.format("[ferrite/rules] mode=%s%s oracle=1/%d chunks=%d shapes=%d generated=%d gen=%.1fms fallbacks=%d"
				+ " | oracle: %d chunks %d blocks %d mismatch",
				enabled ? "on" : "off", YIELD == null ? "" : " (yields to a mixin in " + YIELD + ")", oracleRate,
				chunks.sum(), CACHE.size(), generated.sum(), genNanos.sum() / 1e6,
				fallbacks.sum(), oracleChunks.sum(), oracleBlocks.sum(), mismatches.sum());
	}

	public static void reset() {
		chunks.reset();
		generated.reset();
		genNanos.reset();
		fallbacks.reset();
		oracleChunks.reset();
		oracleBlocks.reset();
		mismatches.reset();
		logged.set(0);
	}

	private sealed interface Node permits Seq, Cond, Leaf, Block {
		boolean terminal();
	}

	private record Seq(List<Node> children, boolean terminal) implements Node {}

	private record Cond(int field, Node then) implements Node {
		@Override
		public boolean terminal() {
			return false;
		}
	}

	private record Leaf(int field) implements Node {
		@Override
		public boolean terminal() {
			return false;
		}
	}

	private record Block(int field) implements Node {
		@Override
		public boolean terminal() {
			return true;
		}
	}

	// Same root and the same fold outcomes in visit order give the same code.
	private record Key(MaterialRule root, String bits) {
		@Override
		public boolean equals(Object o) {
			return o instanceof Key k && k.root == this.root && k.bits.equals(this.bits);
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(this.root) * 31 + this.bits.hashCode();
		}
	}

	private static final class Walk {
		private final MaterialRuleContext context;
		private final Set<Holder<Biome>> possible;
		private final List<Object> fields = new ArrayList<>();
		private final StringBuilder kinds = new StringBuilder();
		private final StringBuilder bits = new StringBuilder();

		Walk(MaterialRuleContext context) {
			this.context = context;
			this.possible = context.possibleBiomes();
		}

		// Vanilla compile order; null means the rule can only return null. Leaves after a terminal child never run.
		Node rule(MaterialRule rule) {
			while (rule instanceof MaterialRule.HolderHolder holder) rule = holder.holder().value();
			if (rule instanceof SequenceRule sequence) {
				List<Node> children = new ArrayList<>();
				for (MaterialRule child : sequence.sequence()) {
					Node node = this.rule(child);
					if (node == null) continue;
					children.add(node);
					if (node.terminal()) break;
				}
				return group(children);
			}
			if (rule instanceof ConditionRule condition) {
				int fold = this.foldOf(condition.ifTrue());
				this.bits.append((char) ('0' + fold));
				if (fold == FALSE) return null;
				int field = fold == LIVE ? this.add(condition.ifTrue().compile(this.context), 'C') : -1;
				Node then = this.rule(condition.thenRun());
				if (then == null) return null;
				return fold == TRUE ? then : new Cond(field, then);
			}
			if (rule instanceof BlockRule block) return new Block(this.add(block.resultState(), 'B'));
			return new Leaf(this.add(rule.compile(this.context), 'R'));
		}

		private static Node group(List<Node> children) {
			if (children.isEmpty()) return null;
			if (children.size() == 1) return children.getFirst();
			if (children.size() > MAX_CHILDREN) {
				List<Node> groups = new ArrayList<>();
				for (int i = 0; i < children.size(); i += MAX_CHILDREN) {
					groups.add(group(new ArrayList<>(children.subList(i, Math.min(i + MAX_CHILDREN, children.size())))));
				}
				return group(groups);
			}
			return new Seq(children, children.getLast().terminal());
		}

		private int foldOf(MaterialCondition condition) {
			while (condition instanceof MaterialCondition.HolderHolder holder) condition = holder.holder().value();
			if (condition instanceof BiomeCondition biome) return this.foldBiome(biome.biomes());
			if (condition instanceof NotCondition not) {
				int inner = this.foldOf(not.target());
				return inner == TRUE ? FALSE : inner == FALSE ? TRUE : LIVE;
			}
			return LIVE;
		}

		// BiomeCondition.canNeverMatch, then willAlwaysMatch, against the chunk's possible biomes.
		private int foldBiome(HolderSet<Biome> biomes) {
			if (this.possible == null) return LIVE;
			boolean any = false;
			for (Holder<Biome> biome : biomes) {
				if (this.possible.contains(biome)) {
					any = true;
					break;
				}
			}
			if (!any) return FALSE;
			for (Holder<Biome> biome : this.possible) {
				if (!biomes.contains(biome)) return LIVE;
			}
			return TRUE;
		}

		private int add(Object value, char kind) {
			this.fields.add(value);
			this.kinds.append(kind);
			return this.fields.size() - 1;
		}
	}

	private static MethodHandle generate(Node root, String kinds) {
		long start = System.nanoTime();
		List<Seq> methods = new ArrayList<>();
		byte[] bytes = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(RESOLVER)).build(SELF, cls -> {
			cls.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC);
			cls.withInterfaceSymbols(CD_RULE);
			for (int i = 0; i < kinds.length(); i++) {
				cls.withField("f" + i, desc(kinds.charAt(i)), ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
			}
			cls.withMethodBody(ConstantDescs.INIT_NAME, MTD_INIT, ClassFile.ACC_PUBLIC, code -> {
				code.aload(0).invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
				for (int i = 0; i < kinds.length(); i++) {
					ClassDesc type = desc(kinds.charAt(i));
					code.aload(0).aload(1).loadConstant(i).aaload().checkcast(type).putfield(SELF, "f" + i, type);
				}
				code.return_();
			});
			cls.withMethodBody("tryApply", MTD_APPLY, ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL,
					code -> body(code, root, methods));
			// Nested sequences queue themselves while earlier bodies are emitted.
			for (int m = 0; m < methods.size(); m++) {
				Seq seq = methods.get(m);
				cls.withMethodBody("m" + m, MTD_APPLY, ClassFile.ACC_FINAL, code -> body(code, seq, methods));
			}
		});
		try {
			MethodHandles.Lookup lookup = MethodHandles.lookup().defineHiddenClass(bytes, true);
			MethodHandle ctor = lookup.findConstructor(lookup.lookupClass(), MethodType.methodType(void.class, Object[].class))
					.asType(MethodType.methodType(RuleEvaluator.class, Object[].class));
			generated.increment();
			genNanos.add(System.nanoTime() - start);
			return ctor;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	// Returns the first non-null state; a non-terminal body ends with null.
	private static void body(CodeBuilder code, Node node, List<Seq> methods) {
		if (node instanceof Seq seq) {
			for (Node child : seq.children()) emit(code, child, methods);
		} else {
			emit(code, node, methods);
		}
		if (!node.terminal()) code.aconst_null().areturn();
	}

	private static void emit(CodeBuilder code, Node node, List<Seq> methods) {
		switch (node) {
			case Block block -> code.aload(0).getfield(SELF, "f" + block.field(), CD_STATE).areturn();
			case Leaf leaf -> {
				code.aload(0).getfield(SELF, "f" + leaf.field(), CD_RULE).iload(1).iload(2).iload(3)
						.invokeinterface(CD_RULE, "tryApply", MTD_APPLY);
				returnIfNonNull(code);
			}
			case Cond cond -> {
				Label skip = code.newLabel();
				code.aload(0).getfield(SELF, "f" + cond.field(), CD_COND).invokeinterface(CD_COND, "test", MTD_TEST)
						.ifeq(skip);
				emit(code, cond.then(), methods);
				code.labelBinding(skip);
			}
			case Seq seq -> {
				int index = methods.size();
				methods.add(seq);
				code.aload(0).iload(1).iload(2).iload(3).invokevirtual(SELF, "m" + index, MTD_APPLY);
				if (seq.terminal()) code.areturn();
				else returnIfNonNull(code);
			}
		}
	}

	private static void returnIfNonNull(CodeBuilder code) {
		Label none = code.newLabel();
		code.dup().ifnull(none).areturn().labelBinding(none).pop();
	}

	private static ClassDesc desc(char kind) {
		return kind == 'C' ? CD_COND : kind == 'R' ? CD_RULE : CD_STATE;
	}

	// Oracle chunks run both evaluators on every block and keep vanilla's state.
	private static final class Oracle implements RuleEvaluator {
		private final RuleEvaluator fast;
		private final RuleEvaluator vanilla;

		Oracle(RuleEvaluator fast, RuleEvaluator vanilla) {
			this.fast = fast;
			this.vanilla = vanilla;
		}

		@Override
		public BlockState tryApply(int blockX, int blockY, int blockZ) {
			BlockState got = this.fast.tryApply(blockX, blockY, blockZ);
			BlockState want = this.vanilla.tryApply(blockX, blockY, blockZ);
			oracleBlocks.increment();
			if (got != want) {
				mismatches.increment();
				if (logged.incrementAndGet() <= 8) {
					ExampleMod.LOGGER.warn("[ferrite/rules] MISMATCH at {} {} {}: compiled={} vanilla={}",
							blockX, blockY, blockZ, got, want);
				}
			}
			return want;
		}
	}
}
