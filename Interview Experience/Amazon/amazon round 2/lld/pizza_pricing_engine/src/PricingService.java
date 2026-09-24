package pizza;

import java.util.Objects;

/**
 * Owns the pricing FORMULA. Depends on a PricingCatalog (Dependency Inversion / DI).
 *
 * Wiring interviewers want to hear:
 *   catalog = storeManager.getCatalog(storeId);
 *   service = new PricingService(catalog);   // constructor injection
 *   total   = service.getPrice(pizza);
 *
 * Multi-store extension: same class, different catalog instance — no if (storeId) inside getPrice.
 */
public final class PricingService {
    private final PricingCatalog catalog;

    /**
     * Constructor injection: catalog is fixed for this service instance.
     * Prefer this over a mutable setCatalog(...) for thread-safety in interviews.
     */
    public PricingService(PricingCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * price(pizza) = base + size + sum(toppings)
     * Pizza stays dumb; all policy lives here + catalog data.
     */
    public int getPrice(Pizza pizza) {
        Objects.requireNonNull(pizza, "pizza");
        int total = catalog.getBasePrice(pizza.getBase());
        total += catalog.getSizePrice(pizza.getSize());
        // Empty toppings list is valid — loop simply adds nothing.
        for (Topping topping : pizza.getToppings()) {
            // Duplicate topping in list → charged twice (product may later dedupe).
            total += catalog.getToppingPrice(topping);
        }
        return total;
    }

    /** Sum of per-pizza prices. Later: apply order-level promos / tax HERE, not on Pizza. */
    public int getOrderPrice(Order order) {
        Objects.requireNonNull(order, "order");
        // Edge case: empty order — volunteer this without waiting for interviewer prompt.
        if (order.getPizzas().isEmpty()) {
            throw new IllegalArgumentException("order has no pizzas");
        }
        int total = 0;
        for (Pizza pizza : order.getPizzas()) {
            total += getPrice(pizza);
        }
        return total;
    }
}
