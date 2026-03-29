# Bosk JUnit Parameter Injection - User Guide

_Written with an LLM. This document needs a lot of work to be a decent user guide._

This document describes the parameter injection feature that lets you write parameterized tests where parameters are injected by custom `Injector` implementations.

### Core Annotations

 **`@InjectFrom`**
Applied to a test class. Specifies which `Injector` classes to use:

```java
@InjectFrom({StringInjector.class, IntInjector.class})
class MyTest {
    @InjectedTest
    void test(String s, int i) { }
}
```

The order matters: earlier injectors can provide constructor parameters for later injectors.

---

 **`@Injected`**
Applied to fields or method parameters to mark them for injection:

```java
// For fields (class-level injection)
@Injected String myField;

// For parameters (method-level injection)
@InjectedTest
void test(String s) { }
```

---

 **`@InjectedTest`**
Marks a method as a test that receives injected parameters:

```java
@InjectedTest
void test(String name, int count) { }
```

---

 **`@InjectFields`**
Required when using field injection. Enables class-level templates:

```java
@InjectFields
@InjectFrom(MyInjector.class)
class MyTest {
    @Injected String value;

    @InjectedTest
    void test() { }
}
```

---

### The Injector Interface

Create your own `Injector` implementations to provide test values:

```java
record StringInjector() implements Injector {
    @Override
    public boolean supports(AnnotatedElement element, Class<?> elementType) {
        return elementType == String.class;
    }

    @Override
    public List<String> values() {
        return List.of("a", "b", "c");
    }
}
```

An injector can target either fields or parameters (or both) by implementing `supportsField()` and `supportsParameter()`.

---

### How It Works

 **Method-Level Injection:**
Each combination of injector values creates a separate test invocation. If you have:
- `StringInjector` with values: `"a", "b"`
- `IntInjector` with values: `1, 2`

You get 4 test invocations (2 × 2), one for each combination.

---

 **Field Injection:**
The test class is instantiated multiple times, once per combination. Each instance has its `@Injected` fields set to the corresponding values:

```java
@InjectFields
@InjectFrom(StringInjector.class)
class MyTest {
    @Injected String value;

    @InjectedTest
    void test() {
        // Called once per value: "a", then "b"
    }
}
```

---

### Dependent Injectors

Injectors can depend on other injectors. An injector with constructor parameters receives values from earlier injectors:

```java
record BaseInjector() implements Injector {
    @Override
    public boolean supports(AnnotatedElement e, Class<?> t) { return t == int.class; }
    @Override
    public List<Integer> values() { return List.of(10, 20); }
}

record DependentInjector(int baseValue) implements Injector {
    @Override
    public boolean supports(AnnotatedElement e, Class<?> t) { return t == String.class; }
    @Override
    public List<String> values() {
        return List.of("based-on-" + baseValue);  // Uses baseValue!
    }
}

@InjectFrom({BaseInjector.class, DependentInjector.class})
class MyTest {
    @Injected int baseValue;
    @Injected String dependentValue;

    @InjectedTest
    void test() { }
}
```

 **Important:** The test runs 2 times, not 4. The values are correlated:
- baseValue=10, dependentValue="based-on-10"
- baseValue=20, dependentValue="based-on-20"

This is because `DependentInjector` receives `baseValue` from `BaseInjector` as a constructor parameter. They are instantiated together, one instance per base value.

---

### Combined Field + Parameter Injection

You can use both field and method-level injection together:

```java
@InjectFields
@InjectFrom({BaseInjector.class, DependentInjector.class})
class MyTest {
    @Injected int fieldValue;

    @InjectedTest
    void test(String paramValue) {
        // fieldValue from field injection
        // paramValue from method injection
    }
}
```

When an injector is used for both a field and a parameter, the SAME injector instance is used for both, preserving value correlations.

---

### Inheritance

Field injection works through class inheritance. If a superclass has `@InjectFields` and `@InjectFrom`, all subclasses will also have their fields injected, multiplying the number of test runs.

```java
// Superclass
@InjectFields
@InjectFrom(ValueInjector.class)
class ParentTest {
    @Injected String parentValue;

    @InjectedTest
    void test() { }
}

// Subclass - runs twice (once per parentValue)
@InjectFrom(ChildInjector.class)
class ChildTest extends ParentTest {
    @Injected String childValue;

    // Runs 2 × 2 = 4 times:
    // parentValue="a", childValue="x"
    // parentValue="a", childValue="y"
    // parentValue="b", childValue="x"
    // parentValue="b", childValue="y"
}

record ValueInjector() implements Injector {
    @Override public boolean supports(AnnotatedElement e, Class<?> t) { return t == String.class; }
    @Override public List<String> values() { return List.of("a", "b"); }
}

record ChildInjector() implements Injector {
    @Override public boolean supports(AnnotatedElement e, Class<?> t) { return t == String.class; }
    @Override public List<String> values() { return List.of("x", "y"); }
}
```

The `@InjectFrom` annotations from parent and child are combined, and all injectors participate in the cartesian product (or correlation, if dependent).

---

### Corner Cases

 **1. Injector with multiple values + dependent injector:**
```java
record MultiValueInjector() implements Injector {
    @Override public List<String> values() { return List.of("a", "b"); }
}

record DependentInjector(String prefix) implements Injector {
    @Override public List<String> values() {
        return List.of(prefix + "-x", prefix + "-y");  // 2 values per prefix
    }
}

@InjectFrom({MultiValueInjector.class, DependentInjector.class})
```
Result: 4 test invocations (2 × 2 = 4), all valid combinations.

 **2. Injector not supporting an element:**
If an injector returns `false` from `supports()`, it's simply not used for that element. No error occurs.

 **3. No matching injector:**
Parameter injection can coexist with other resolvers.
If a parameter has no matching resolver, that is an error.

Fields annotated with `@Injected` can only be resolved by injectors.
If such a field has no matching injector, that is an error.

 **4. Injector order determines precedence:**
When multiple injectors could provide values for the same element, the LATER injector in `@InjectFrom` takes precedence.

---

### Summary of Expectations

1. **Cartesian product by default:** Unrelated injectors multiply test count
2. **Correlation when dependent:** Dependent injectors maintain value consistency
3. **Field + parameter sharing:** Same injector class used for both field and parameter uses the same instance
4. **Order matters:** `@InjectFrom` order determines constructor injection dependencies and override precedence
5. **Inheritance multiplies:** Field injection in a superclass multiplies all subclass test runs
