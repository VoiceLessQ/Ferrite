package me.apika.apikaprobe.spatial;

/**
 * Flags and counters for the entity spatial-query position filter.
 *
 * Design (LOCAL_DESIGN "Entity spatial query index"): per-entity cached
 * packed position, updated on the section-manager onMove callbacks, lets
 * EntitySection.getEntities skip non-candidates on int compares before
 * paying the bounding-box intersect and consumer call. Iteration stays in
 * vanilla list order; liveness and abort semantics are untouched because
 * the filter only skips entities the intersect test would reject anyway.
 *
 * The oracle runs both tests on every entity of sampled queries in one
 * pass: an entity whose box intersects but whose filter said skip is a
 * correctness bug and logs a MISMATCH.
 *
 * Counters are server-thread plain longs, drained by EntityQueryMonitor's
 * 5 s report.
 */
public final class EntityCellIndex {
	private EntityCellIndex() {}

	/** Master switch: -Dferrite.entityquery.cache=true (default off). */
	public static final boolean ENABLED = Boolean.getBoolean("ferrite.entityquery.cache");

	/** Oracle: sample 1 in N filtered queries; 0 disables. Default 16 while alpha. */
	public static final int ORACLE_RATE = Integer.getInteger("ferrite.entityquery.oracle", 16);

	// Server-thread counters, drained by EntityQueryMonitor.
	public static long scanned;
	public static long filteredOut;
	public static long delivered;
	public static long oracleChecks;
	public static long oracleMismatches;
	public static int queryCounter;
	public static long typedQueries;
	public static long typedScanned;
}
