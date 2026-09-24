package pizza;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pizza = ordered configuration (data only).
 *
 * Why no getPrice() here (SRP / interview Mild Concern fix):
 *   - Pricing rules change by store, promo, tax — not by "what a pizza is".
 *   - Multi-store needs different catalogs without mutating this object.
 *   - PricingService owns the formula; this class only holds Base/Size/Toppings.
 *
 * Immutability: once built, toppings list cannot change mid-pricing (safer in concurrent code).
 */
public final class Pizza {
    private final Base base;
    private final Size size;
    private final List<Topping> toppings;

    public Pizza(Base base, Size size, List<Topping> toppings) {
        // Fail fast on nulls — edge cases interviewers expect you to volunteer.
        this.base = Objects.requireNonNull(base, "base");
        this.size = Objects.requireNonNull(size, "size");
        // Defensive copy + unmodifiable view so callers cannot mutate after construct.
        this.toppings = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(toppings, "toppings")));
    }

    public Base getBase() {
        return base;
    }

    public Size getSize() {
        return size;
    }

    /** May be empty (valid pizza = base + size only). Duplicates allowed unless product forbids. */
    public List<Topping> getToppings() {
        return toppings;
    }
}
