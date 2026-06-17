# Performance TODO: `WasmGCMethodGenerator#generateRegularMethodBody`

Source profile:
`compiler-benchmarks/build/liveness-2026-10-25/profile-change/org.teavm.compilerbenchmarks.WasmGCCompilerBenchmark.bufferedInputStream-AverageTime-optimization-SIMPLE/profile.jfr`

Percentages below are shares of the 6,829 samples in the `generateRegularMethodBody` subtree, which is about
14% of the whole profile. Nothing here was implemented or measured; the estimates come from sample counts only.

## Where the time goes

- `WasmCodeGenerator.generate`: 58%. Within it, `FlowReconstruction` is 12% and `BaseTypeInference` is 9%.
- `WasmLocalsOptimizer`: 20%.
- `WasmDeadStoreOptimizer`: 7%.
- `WasmInstructionsOptimizer` (two runs): 6%.
- `eliminateMultipleNullConstantUsages`: 4.5%.
- `WasmIdentityCopyOptimizer`: 3.5%.

## Easy wins

1. **Skip the second pass of `eliminateMultipleNullConstantUsages` when it has nothing to do** (~3.4%).
   Almost all of its 309 samples are in the rewrite pass (`WasmGCMethodGenerator.java` lines 463–490), which
   runs even when no null constant is used more than once. Set a flag in the normalisation loop at lines
   435–443 and return early if it was never set.

2. **Stop rebuilding the `reads` list for every instruction** (~2.8%).
   `WasmLocalAssignmentFinder.java:71,74` and `WasmIdentityCopyOptimizer.java:64,67` call
   `reads.subList(..).clear()` and `reads.addAll(Collections.nCopies(..))` on every instruction. Each call
   allocates, and `addAll` makes a temporary array. Plain `while (size > n) remove(last)` and
   `while (size < n) add(null)` loops would do the same job. `ArrayList.addAll` alone is about 160 samples.

3. **Use the cached method resolver for `invokespecial`** (~2.7%).
   `WasmCodeGenerator.java:1424` calls `context.classes().resolve(...)`, the default
   `ClassReaderSource.resolve`. That builds a stream of ancestors with no cache and runs on every invoke
   instruction. `context.hierarchy().resolve(...)` gives the same result from a cache. This costs 185 samples.

## A bit less easy

4. **Map lookups in `WasmLivenessAnalysis`** (~5.5%).
   The `listInfos.get(list)` lookups in this `HashMap` cost more than the liveness work itself:
   - `iterate:159` spends 271 samples on the lookup and 145 on `transfer`.
   - `walkList:270`, `walkNestedList` and `getLiveIn` add about 110 more.

   The time is in `HashMap.getNode`. The quick experiment is to swap it for `IdentityHashMap`. The proper fix
   is to store the `ListInfo`, or an int id, directly on `WasmInstructionList`. That touches the model, so it's
   not quite trivial. `instructionNodes` would benefit from the same treatment.

5. **`phi.getIncomings()` loops** (~6.4%, cause uncertain).
   About 440 samples are self time in `AbstractList$Itr.hasNext` on three of these loops:
   - `WasmCodeGenerator.generate:221` (200 samples)
   - `eliminateMultipleNullConstantUsages:477` (187)
   - `BaseTypeInference.prepare:102` (53)

   The first pass of `eliminateMultipleNullConstantUsages` (line 428) runs the same loop and takes only
   8 samples. Hypothesis: megamorphic `size()`/`get()` calls through the shared `AbstractList.Itr` on
   `Phi.safeIncomings` and `BasicBlock.safePhis`; the dispatch-stub time would show up as `hasNext` self time.
   A cheap test is to give `safeIncomings` its own `iterator()` (keeping `remove()` working) and re-profile.

6. **Smaller items, about 1–2% each:**
   - **Array-construction pass:** `WasmArrayConstructionOptimizer.process` runs in both
     `WasmInstructionsOptimizer` passes (132 samples). Skip it in the second pass, or skip it entirely when
     codegen emitted no array-constructor calls.
   - **Field loads:** `loadNormalField` looks up the field index (40 samples), then does a separate
     `classes().get(...).getField(...)` lookup in a `LinkedHashMap` (57 samples) just to get the field's type.
     Caching the type next to the field index would remove the second lookup.
   - **Second register allocation:** when `needsCoalescing` is set, `WasmLocalsOptimizer.allocate()` runs
     twice; the second run is 234 samples (3.4%). Avoiding it isn't easy, but it's a clear target.

## Not worth chasing

- **One-time setup:** about 5% is class info, functions and intrinsics being created the first time something
  asks for them (`suggestForType`, `createStaticFunction`/`createInstanceFunction`, `resolveIntrinsic`). That
  work would show up somewhere else anyway.
- **`FlowReconstruction` and `BaseTypeInference`:** they're large, but the cost is spread across their
  algorithms with no single hot spot.
