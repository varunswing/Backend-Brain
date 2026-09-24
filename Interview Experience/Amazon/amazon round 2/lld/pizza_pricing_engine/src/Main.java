package pizza;

import java.util.List;

/**
 * End-to-end demo — proves the design is WIRED (not just class names on a whiteboard).
 *
 * Interview call path to narrate while coding:
 *   1) Build per-store PricingCatalog (map data)
 *   2) StoreManager.register(storeId, catalog)
 *   3) PricingService service = new PricingService(storeManager.getCatalog(id))
 *   4) service.getPrice(pizza) / getOrderPrice(order)
 *
 * Anti-patterns this Main deliberately avoids:
 *   - getPrice() on Pizza
 *   - Strategy class per topping
 *   - static global prices
 *
 * Run (from this src directory):
 *   javac -d . *.java && java pizza.Main
 */
public final class Main {
    public static void main(String[] args) {
        // --- Store A price book (paise) ---
        PricingCatalog storeA = new PricingCatalog()
                .putBase(Base.THIN, 10000)
                .putBase(Base.THICK, 12000)
                .putBase(Base.CHEESE_BURST, 15000)
                .putSize(Size.SMALL, 0)       // size adder can be 0
                .putSize(Size.MEDIUM, 3000)
                .putSize(Size.LARGE, 5000)
                .putTopping(Topping.CHEESE, 2000)
                .putTopping(Topping.OLIVE, 1500)
                .putTopping(Topping.MUSHROOM, 1800)
                .putTopping(Topping.PEPPERONI, 2500);

        // --- Store B: SAME menu enums, DIFFERENT prices (multi-store) ---
        PricingCatalog storeB = new PricingCatalog()
                .putBase(Base.THIN, 11000)
                .putBase(Base.THICK, 13000)
                .putBase(Base.CHEESE_BURST, 16000)
                .putSize(Size.SMALL, 0)
                .putSize(Size.MEDIUM, 3500)
                .putSize(Size.LARGE, 5500)
                .putTopping(Topping.CHEESE, 2200)
                .putTopping(Topping.OLIVE, 1600)
                .putTopping(Topping.MUSHROOM, 2000)
                .putTopping(Topping.PEPPERONI, 2700);

        // Registry holds both catalogs — PricingService never sees store ids.
        StoreManager stores = new StoreManager();
        stores.register("store-a", storeA);
        stores.register("store-b", storeB);

        // Same pizza config priced under two stores → different totals (proves DI works).
        // Expected store-a: thin 10000 + large 5000 + cheese 2000 + olive 1500 = 18500
        Pizza pizza = new Pizza(Base.THIN, Size.LARGE, List.of(Topping.CHEESE, Topping.OLIVE));
        Order order = new Order(List.of(pizza));

        // Constructor injection: service is bound to one catalog instance.
        PricingService pricingA = new PricingService(stores.getCatalog("store-a"));
        PricingService pricingB = new PricingService(stores.getCatalog("store-b"));

        System.out.println("Store A pizza paise: " + pricingA.getPrice(pizza));
        System.out.println("Store A order paise: " + pricingA.getOrderPrice(order));
        System.out.println("Store B pizza paise: " + pricingB.getPrice(pizza));
    }

    private Main() {
        // utility holder — prevent instantiation
    }
}
