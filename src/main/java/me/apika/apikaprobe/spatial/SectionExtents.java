package me.apika.apikaprobe.spatial;

/**
 * Duck interface mixed into EntitySection: tracks the largest bounding
 * box half-width (xz) and height seen among members, so the position
 * filter can pad the query box conservatively. Monotonic per section
 * lifetime; sections are discarded when empty, which resets naturally.
 */
public interface SectionExtents {
	float ferrite$maxHalfXZ();

	float ferrite$maxHeight();

	void ferrite$growExtents(float halfXZ, float height);

	/** Section min block corner, stamped at creation by the storage hook. */
	void ferrite$setOrigin(int x, int y, int z);

	int ferrite$originX();

	int ferrite$originY();

	int ferrite$originZ();

	SectionGrid ferrite$grid();

	void ferrite$setGrid(SectionGrid grid);
}
