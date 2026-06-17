/*
 *  Copyright 2026 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.backend.wasm.optimization;

import static org.junit.Assert.assertEquals;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import org.teavm.backend.wasm.disasm.ModelDisassembler;
import org.teavm.backend.wasm.model.WasmFunction;
import org.teavm.backend.wasm.model.WasmFunctionType;
import org.teavm.backend.wasm.model.WasmLocal;
import org.teavm.backend.wasm.model.WasmType;
import org.teavm.backend.wasm.model.instruction.WasmConditional;
import org.teavm.backend.wasm.model.instruction.WasmGetLocal;
import org.teavm.backend.wasm.model.instruction.WasmInstructionBuilder;
import org.teavm.backend.wasm.model.instruction.WasmIntBinaryOperation;
import org.teavm.backend.wasm.model.instruction.WasmIntType;

public class WasmIdentityCopyOptimizerTest {
    private WasmFunction function = new WasmFunction(new WasmFunctionType("f", WasmType.INT32, List.of()));
    private WasmLocal a = local("a");
    private WasmLocal b = local("b");
    private WasmInstructionBuilder body = function.getBody().builder();

    @Test
    public void identityCopyRemoved() {
        body.add(get(a)).setLocal(a).add(get(a)).return_();

        assertOptimized("""
                local.get $a
                return""");
    }

    @Test
    public void identityTeeReplacedWithRead() {
        body.add(get(a)).teeLocal(a).return_();

        assertOptimized("""
                local.get $a
                return""");
    }

    @Test
    public void parallelIdentityCopyRemoved() {
        body
                .add(get(a)).add(get(b)).setLocal(b).setLocal(a)
                .i32Const(2).add(get(a)).add(get(b)).intBinary(WasmIntType.INT32, WasmIntBinaryOperation.ADD)
                .intBinary(WasmIntType.INT32, WasmIntBinaryOperation.ADD)
                .return_();

        assertOptimized("""
                i32.const 2
                local.get $a
                local.get $b
                i32.add
                i32.add
                return""");
    }

    @Test
    public void identityCopyInNestedIfRemoved() {
        body.i32Const(0);
        var conditional = new WasmConditional();
        body.add(conditional);
        conditional.getThenBlock().builder().add(get(a)).setLocal(a);
        body.add(get(a)).return_();

        assertOptimized("""
                i32.const 0
                if $label_0
                end (; $label_0 ;)
                local.get $a
                return""");
    }

    @Test
    public void copyOfAnotherLocalKept() {
        body.add(get(a)).setLocal(b).add(get(b)).return_();

        assertOptimized("""
                local.get $a
                local.set $b
                local.get $b
                return""");
    }

    // Builder turns local.set followed by local.get into local.tee, which would hide identity copies
    private static WasmGetLocal get(WasmLocal local) {
        return new WasmGetLocal(local);
    }

    private WasmLocal local(String name) {
        var local = new WasmLocal(WasmType.INT32, name);
        function.add(local);
        return local;
    }

    private void assertOptimized(String expected) {
        new WasmIdentityCopyOptimizer().visitMany(function.getBody());
        var actual = ModelDisassembler.bodyToString(function).lines().collect(Collectors.joining("\n"));
        assertEquals(expected, actual);
    }
}
