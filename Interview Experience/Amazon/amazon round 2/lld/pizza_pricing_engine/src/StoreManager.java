package pizza;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registry: storeId → PricingCatalog instance.
 *
 * This is the multi-store extension point from Round-2 feedback:
 *   - Store A and Store B hold DIFFERENT catalog instances (different map values).
 *   - PricingService does not know store ids — it only knows "a catalog".
 *
 * Wrong approaches (avoid):
 *   - if (storeId.equals("A")) inside PricingService.getPrice
 *   - static global price map shared by all stores
 *   - StoreAPricingService / StoreBPricingService subclasses
 */
public final class StoreManager {
    private final Map<String, PricingCatalog> catalogs = new HashMap<>();

    /** Register or replace the price book for a store. */
    public void register(String storeId, PricingCatalog catalog) {
        catalogs.put(Objects.requireNonNull(storeId, "storeId"),
                Objects.requireNonNull(catalog, "catalog"));
    }

    /**
     * Resolve catalog for a store, then inject into PricingService.
     * Unknown store fails loudly — better than silent wrong prices.
     */
    public PricingCatalog getCatalog(String storeId) {
        PricingCatalog catalog = catalogs.get(Objects.requireNonNull(storeId, "storeId"));
        if (catalog == null) {
            throw new IllegalArgumentException("unknown store: " + storeId);
        }
        return catalog;
    }
}
