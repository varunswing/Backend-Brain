# Design Patterns

A comprehensive guide to software design patterns with code examples.

## Creational Patterns
*Purpose: Deal with object creation mechanisms*

### Singleton
Restricts instantiation to a single instance.

```java
public class Principal {
    private static Principal instance;

    private Principal() {}

    public static Principal getInstance() {
        if (instance == null) {
            synchronized (Principal.class) {
                if (instance == null) {
                    instance = new Principal();
                }
            }
        }
        return instance;
    }
}
```

### Factory Method
Defines interface for creating objects, subclasses decide type.

```java
abstract class Bread { abstract void bake(); }

class WheatBread extends Bread {
    void bake() { System.out.println("Baking wheat bread."); }
}

abstract class Bakery {
    abstract Bread createBread();
    
    public void bakeBread() {
        Bread bread = createBread();
        bread.bake();
    }
}

class WheatBakery extends Bakery {
    Bread createBread() { return new WheatBread(); }
}
```

### Builder
Separates construction of complex objects from representation.

```java
class Sandwich {
    private String bread, meat, cheese;

    public static class Builder {
        private String bread, meat, cheese;

        public Builder bread(String b) { this.bread = b; return this; }
        public Builder meat(String m) { this.meat = m; return this; }
        public Builder cheese(String c) { this.cheese = c; return this; }

        public Sandwich build() {
            Sandwich s = new Sandwich();
            s.bread = this.bread;
            s.meat = this.meat;
            s.cheese = this.cheese;
            return s;
        }
    }
}

// Usage
Sandwich s = new Sandwich.Builder()
    .bread("Whole Grain")
    .meat("Turkey")
    .cheese("Cheddar")
    .build();
```

### Prototype
Creates new objects by copying existing ones.

### Abstract Factory
Creates families of related objects.

---

## Structural Patterns
*Purpose: Deal with object composition*

### Adapter
Converts one interface to another clients expect.

```java
interface AmericanPlug { void plugIn(); }
interface EuropeanPlug { void connect(); }

class EuropeanAdapter implements EuropeanPlug {
    private AmericanPlug americanPlug;

    public EuropeanAdapter(AmericanPlug americanPlug) {
        this.americanPlug = americanPlug;
    }

    public void connect() {
        americanPlug.plugIn();
    }
}
```

### Decorator
Adds behavior to objects dynamically.

```java
interface Coffee {
    String getDescription();
    double getCost();
}

class SimpleCoffee implements Coffee {
    public String getDescription() { return "Simple coffee"; }
    public double getCost() { return 5; }
}

class MilkDecorator implements Coffee {
    private Coffee decoratedCoffee;
    
    public MilkDecorator(Coffee coffee) {
        this.decoratedCoffee = coffee;
    }
    
    public String getDescription() {
        return decoratedCoffee.getDescription() + ", milk";
    }
    
    public double getCost() {
        return decoratedCoffee.getCost() + 1.5;
    }
}
```

### Facade
Provides simplified interface to complex subsystem.

### Proxy
Provides surrogate for another object to control access.

### Composite
Composes objects into tree structures.

### Bridge
Separates abstraction from implementation.

### Flyweight
Shares common state between multiple objects.

---

## Behavioral Patterns
*Purpose: Deal with object communication*

### Observer (Pub/Sub)
One-to-many dependency for automatic updates.

```java
interface Observer { void update(int temperature); }
interface Subject {
    void addObserver(Observer o);
    void removeObserver(Observer o);
    void notifyObservers();
}

class WeatherStation implements Subject {
    private List<Observer> observers = new ArrayList<>();
    private int temperature;

    public void addObserver(Observer o) { observers.add(o); }
    public void removeObserver(Observer o) { observers.remove(o); }
    
    public void notifyObservers() {
        for (Observer o : observers) {
            o.update(temperature);
        }
    }

    public void setTemperature(int temp) {
        this.temperature = temp;
        notifyObservers();
    }
}
```

### Strategy
Defines family of interchangeable algorithms.

```java
interface SortStrategy { void sort(int[] data); }

class BubbleSortStrategy implements SortStrategy {
    public void sort(int[] data) {
        System.out.println("Sorting using Bubble Sort");
    }
}

class Sorter {
    private SortStrategy strategy;
    
    public Sorter(SortStrategy strategy) {
        this.strategy = strategy;
    }
    
    public void setStrategy(SortStrategy s) {
        this.strategy = s;
    }
    
    public void performSort(int[] data) {
        strategy.sort(data);
    }
}
```

### Command
Encapsulates request as an object.

```java
interface Command { void execute(); }

class TurnOnLightCommand implements Command {
    private Light light;
    
    public TurnOnLightCommand(Light light) {
        this.light = light;
    }
    
    public void execute() {
        light.turnOn();
    }
}

class RemoteControl {
    private Command command;
    
    public void setCommand(Command c) { this.command = c; }
    public void pressButton() { command.execute(); }
}
```

### State
Allows object to alter behavior when state changes.

```java
interface TrafficLightState {
    void change(TrafficLight light);
}

class RedLightState implements TrafficLightState {
    public void change(TrafficLight light) {
        System.out.println("Red Light - Stop");
        light.setState(new GreenLightState());
    }
}

class TrafficLight {
    private TrafficLightState state;
    
    public TrafficLight() {
        setState(new RedLightState());
    }
    
    public void setState(TrafficLightState state) {
        this.state = state;
    }
    
    public void change() {
        state.change(this);
    }
}
```

### Chain of Responsibility
Passes request along chain of handlers.

### Mediator
Defines object that encapsulates how objects interact.

### Iterator
Provides way to access elements sequentially.

### Template Method
Defines skeleton of algorithm in base class.

---

## Quick Reference

| Pattern | Category | Use When |
|---------|----------|----------|
| **Singleton** | Creational | Need exactly one instance |
| **Factory** | Creational | Delegate object creation to subclasses |
| **Builder** | Creational | Constructing complex objects step by step |
| **Adapter** | Structural | Making incompatible interfaces work together |
| **Decorator** | Structural | Adding behavior dynamically |
| **Facade** | Structural | Simplifying complex subsystem |
| **Observer** | Behavioral | One-to-many notifications |
| **Strategy** | Behavioral | Interchangeable algorithms |
| **Command** | Behavioral | Encapsulating requests |
| **State** | Behavioral | Object behavior depends on state |

---

## SOLID Principles Connection

| Principle | Related Patterns |
|-----------|------------------|
| **Single Responsibility** | Facade, Chain of Responsibility |
| **Open/Closed** | Strategy, Decorator |
| **Liskov Substitution** | Factory, Template Method |
| **Interface Segregation** | Adapter, Facade |
| **Dependency Inversion** | Factory, Abstract Factory, Strategy |
