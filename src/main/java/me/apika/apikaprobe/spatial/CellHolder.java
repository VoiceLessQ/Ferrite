package me.apika.apikaprobe.spatial;

/**
 * Duck interface mixed into Entity: caches the entity's packed block
 * position (BlockPos.asLong format) so spatial-query filtering reads one
 * long field instead of chasing the bounding box. Long.MIN_VALUE means
 * "never set" and always passes the filter.
 */
public interface CellHolder {
	long ferrite$packedPos();

	void ferrite$setPackedPos(long packed);
}
