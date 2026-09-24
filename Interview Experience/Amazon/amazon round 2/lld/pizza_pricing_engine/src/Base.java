package pizza;

/**
 * Crust / base type for a pizza.
 *
 * Interview note: prefer an enum over String so the compiler rejects typos
 * ("THIN" vs "thin") and the catalog can use EnumMap for O(1) lookups.
 */
public enum Base {
    THIN,
    THICK,
    CHEESE_BURST
}
