package pizza;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Order = one or more pizzas. Totals are computed by PricingService.getOrderPrice(...),
 * not here — keeps promotions / tax hooks at service level later (OCP).
 */
public final class Order {
    private final List<Pizza> pizzas;

    public Order(List<Pizza> pizzas) {
        this.pizzas = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(pizzas, "pizzas")));
    }

    public List<Pizza> getPizzas() {
        return pizzas;
    }
}
