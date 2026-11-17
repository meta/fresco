<!-- analysis-metadata: { "date": "2026-03-25", "time_window": "30d", "max_diffs": 50, "diffs_analyzed": 7 } -->
# Fresco Core Library - API & Safety Patterns

**Team**: MF Client - QoE
**Oncall**: fresco
**Last Updated**: 2026-03-25

---

## 1. API Level Compatibility

### The Problem
Java 8+ stream APIs require API 24+, but Fresco supports API 21+.

### Why It Causes Problems
- Runtime crashes on older devices
- Hard to catch in testing (usually test on newer devices)
- Build passes but app crashes in production

### How to Think About It
When you see Java streams (`.stream()`, `.parallelStream()`, `.collect()`), check if there's an API level guard or use simple loops instead.

```kotlin
// ❌ Requires API 24+
items.parallelStream().map { ... }.collect(Collectors.toList())

// ✅ Works on API 21+
items.map { ... }  // Kotlin stdlib works everywhere
```

---

## 2. Feature Gating: The A/B Testing Requirement

### The Problem
Behavioral changes without feature flags can't be safely rolled back if problems emerge.

### Why It Causes Problems
- No way to disable problematic changes without a new release
- Can't gradually roll out to detect issues
- Risky for changes affecting image loading (high-traffic code)

### How to Think About It
Any change that modifies:
- Image loading behavior
- Cache behavior
- Network request behavior
- Rendering behavior

Should be gated with a MobileConfig flag defaulting to `false`.

```kotlin
// ✅ Gated: Can be disabled without release
if (config.useNewBehavior) {
    newCodePath()
} else {
    existingCodePath()
}
```

---

## 3. Loop Variable Misuse

### The Problem
Using a method parameter instead of a loop iterator variable, causing incorrect behavior.

### Why It Causes Problems
- Subtle bugs that are hard to spot in review
- Tests may pass if they don't iterate
- Can cause crashes or silent data corruption

### How to Think About It
In any loop, verify that:
1. The iterator variable is used inside the loop (not the parameter)
2. The loop bounds are correct

```kotlin
// ❌ Bug: Uses frameNumber (parameter) instead of i (iterator)
fun processFrames(frameNumber: Int) {
    for (i in 0 until frameCount) {
        frames[frameNumber].process()  // Should be frames[i]!
    }
}

// ✅ Correct
fun processFrames(frameNumber: Int) {
    for (i in 0 until frameCount) {
        frames[i].process()
    }
}
```

### Real Bug Examples
- D95237240: Fix loop variable in DropFramesFrameScheduler

---

## 4. Native Code Buffer Validation

### The Problem
Native code trusting size values from external sources without validation can cause buffer overflows.

### Why It Causes Problems
- Security vulnerabilities (OOB read/write)
- Crashes from invalid memory access
- Potential for arbitrary code execution

### How to Think About It
When passing sizes to native code, especially from:
- Image file metadata
- Network responses
- Serialized data

Always validate bounds before use.

```cpp
// ❌ Dangerous: Trusts chunk_size from file
void* readChunk(int chunk_size) {
    return malloc(chunk_size);  // What if chunk_size is negative or huge?
}

// ✅ Safe: Validates before use
void* readChunk(int chunk_size) {
    if (chunk_size <= 0 || chunk_size > MAX_CHUNK_SIZE) {
        return nullptr;
    }
    return malloc(chunk_size);
}
```

### Real Bug Examples
- D90111594: Fix trusted chunk_size oob read
- D90548421: Check for negative sizeInBytes in nativeCreateFromNativeMemory

---

## 5. Callback Lifecycle Management

### The Problem
Callbacks set on drawables may persist beyond their expected lifecycle, causing leaks or crashes.

### Why It Causes Problems
- Memory leaks from retained references
- Crashes when callbacks reference destroyed views
- Difficult to reproduce - depends on timing

### How to Think About It
When adding callbacks/listeners:
1. Document when they should be cleared
2. Provide a reset/clear method
3. Consider using weak references for long-lived callbacks

```kotlin
// ❌ Leak risk: No way to clear
class ImageDrawable {
    var onLoadCallback: (() -> Unit)? = null
}

// ✅ Explicit lifecycle
class ImageDrawable {
    var onLoadCallback: (() -> Unit)? = null

    fun reset() {
        onLoadCallback = null  // Clear to prevent leaks
    }
}
```

---

## Review Checklist (API & Safety)

- [ ] **API Level**: Does this work on API 21+?
- [ ] **Feature Flags**: Should this behavioral change be gated?
- [ ] **Loops**: Is the correct variable used inside the loop?
- [ ] **Native Bounds**: Are external sizes validated?
- [ ] **Callbacks**: Is the lifecycle documented and cleanup possible?
- [ ] **Producer Dependencies**: Does this producer import a consumer-layer type? If so, inline the primitive instead.

---

## 6. Producer Dependency Direction

### The Problem
Producers in `imagepipeline/producers/` must not import or depend on higher-level concepts and product logic such as feed options or vito options. The producer layer is a lower-level abstraction and must remain decoupled from higher-level UI configuration objects.

### Why It Causes Problems
- Introduces a dependency inversion that violates the layered architecture
- Creates circular or unexpected dependency graphs that break Buck2 builds or cause subtle runtime behavior
- Makes the producer layer non-reusable across different consumer contexts and features (feed, vito, etc.)

### How to Think About It
When a producer needs cross-cutting information that originates from a consumer layer, do **not** pass the consumer-layer object directly. Instead:
1. Inline the primitive value (Boolean, Int, String) into `ImageRequest`, `CallerContext`, or the request extras map
2. Let the consumer layer extract and pack the relevant primitive before constructing the request

```kotlin
// ❌ Wrong: Producer imports a consumer-layer type
class DiskCacheReadProducer {
    fun produce(context: ProducerContext, vitoOptions: VitoImageOptions) { // BAD
        if (vitoOptions.someFlag) { ... }
    }
}

// ✅ Correct: Primitive passed through typed ImageRequest extras
class DiskCacheReadProducer {
    fun produce(context: ProducerContext) {
        val someFlag = context.imageRequest.getExtra(MY_FLAG) as? Boolean ?: false
        if (someFlag) { ... }
    }
}
```

### Real Bug Examples
- D97578134: Producer incorrectly depending on consumer-layer options, fixed by inlining primitive into ImageRequest extras
