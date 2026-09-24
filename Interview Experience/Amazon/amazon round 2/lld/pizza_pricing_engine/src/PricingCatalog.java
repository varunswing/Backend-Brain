package pizza;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Map-based price book for one store (or one price list).
 *
 * Why maps beat Strategy-per-topping (interview feedback):
 *   - Every topping uses the SAME algorithm: lookup(int) + add.
 *   - A class per crust/topping explodes for no behavior difference.
 *   - New topping = new enum + putTopping; no new .java Strategy file.
 *
 * Multi-store: create a NEW PricingCatalog instance per store with different values.
 * Do NOT use a static global Map — that blocks per-store injection.
 *
 * Money: int "paise"/cents avoids double floating-point rounding bugs.
 */
public final class PricingCatalog {
    // EnumMap: compact, fast, type-safe for enum keys.
    private final Map<Base, Integer> basePrices = new EnumMap<>(Base.class);
    private final Map<Size, Integer> sizePrices = new EnumMap<>(Size.class);
    private final Map<Topping, Integer> toppingPrices = new EnumMap<>(Topping.class);

    /** Fluent put — nice for building a catalog in Main / tests. */
    public PricingCatalog putBase(Base base, int paise) {
        basePrices.put(Objects.requireNonNull(base), requireNonNegative(paise));
        return this;
    }

    public PricingCatalog putSize(Size size, int paise) {
        sizePrices.put(Objects.requireNonNull(size), requireNonNegative(paise));
        return this;
    }

    public PricingCatalog putTopping(Topping topping, int paise) {
        toppingPrices.put(Objects.requireNonNull(topping), requireNonNegative(paise));
        return this;
    }

    public int getBasePrice(Base base) {
        return lookup(basePrices, base, "base");
    }

    public int getSizePrice(Size size) {
        return lookup(sizePrices, size, "size");
    }

    public int getToppingPrice(Topping topping) {
        return lookup(toppingPrices, topping, "topping");
    }

    /** Catalog integrity: never seed negative money. */
    private static int requireNonNegative(int paise) {
        if (paise < 0) {
            throw new IllegalArgumentException("price must be >= 0");
        }
        return paise;
    }

    /**
     * Missing key = data/config error (e.g. store does not sell that topping).
     * Do NOT silently return 0 — that hides bugs and undercharges.
     */
    private static <K> int lookup(Map<K, Integer> map, K key, String kind) {
        Integer price = map.get(Objects.requireNonNull(key, kind));
        if (price == null) {
            throw new IllegalArgumentException("no " + kind + " price for " + key);
        }
        return price;
    }
}
