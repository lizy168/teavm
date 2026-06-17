# TODO: remove map lookups from WasmLivenessAnalysis

After the structural rewrite, most of the remaining liveness cost in the profile is `HashMap` lookups
(see `compiler-benchmarks/build/liveness-2026-10-25/profile.md`; percentages are of
`WasmGCMethodGenerator.generateRegularMethodBody` samples):

- `iterate()`: `listInfos.get(list)`, ~3.7% (suspiciously high for one lookup per call:
  it may be cache misses or JFR attributing samples to the wrong line, so check first);
- `walkList()`: `listInfos.get(block.getBody())` for every block/try/conditional, ~1.0%;
- `walkBackwards()`: `exitOf()` and `listInfos.get(list).entry` at the end of every delta walk;
- `transfer()`: `listInfos.get(...)` for every block/try, `instructionNodes.get(insn)` for every
  branch/call/conditional (calls outside of `try_table` always miss);
- `Collector`: `listInfo()`/`computeIfAbsent` while building the structures.

## Idea

The collector visits instructions in program order, so it can record per-list data positionally instead of in maps:

- give `ListInfo` an array of joint items in list order: for every block/try/conditional, the `ListInfo`(s)
  of its nested lists; for every instruction with an `InstructionNode`, the node.
- `walkList()` and `iterate()`/`transfer()` walk the list backwards, so they can consume this array with
  a decreasing index instead of looking things up. When an instruction may have a node, compare
  `items[k].instruction == insn` to decide whether to take it.
- store in each `InstructionNode` its own index in the owner's array, plus a reference to the owner `ListInfo`.
  Delta walks (`walkBackwards`) then start from a known position and find the next joint item and the list
  entry without lookups. Store the `ListInfo` of nested lists on block-like items so that `exitOf()` goes away.
- `iterate(list)` still needs one lookup per call, since the API takes a list. If that lookup really is
  expensive, add a way to get the info of nested lists from the parent (e.g. `cursor.iterateNested()`,
  or the analysis keeps a cursor-side reference) for `WasmLocalsOptimizer`'s walker, which always enters
  nested lists from the parent.

This removes the `instructionNodes` map from the hot paths entirely, and `listInfos` is only needed
for the public API.
