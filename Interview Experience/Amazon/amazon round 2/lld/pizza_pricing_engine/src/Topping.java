package pizza;

/**
 * Topping SKUs. Same algorithm for every topping (lookup + add),
 * so we do NOT create CheeseStrategy / OliveStrategy classes.
 *
 * Extending later: add a new enum constant + one catalog putTopping(...) entry.
 * PricingService does not need to change (Open/Closed).
 */
public enum Topping {
    CHEESE,
    OLIVE,
    MUSHROOM,
    PEPPERONI
}
