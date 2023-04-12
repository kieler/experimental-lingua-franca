/*************
 * Copyright (c) 2023, Kiel University.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 ***************/
package org.lflang.behaviortrees;

import java.util.List;

import org.lflang.lf.Parallel;
import org.lflang.lf.Reactor;

/**
 * Interface for target code generation.
 * 
 * @author Alexander Schulz-Rosengarten
 */
interface CodeGenerator {

    /**
     * Infer RUNNING output for SUCCESS and FAILIURE.
     * 
     * @return the code
     */
    String getRunningInference();

    /**
     * Computation for the result of a parallel node based on its children and
     * M.
     * 
     * @param parallel
     * @param reactor
     * @param children
     * @return the code
     */
    String getParallelCalculation(Parallel parallel, Reactor reactor,
            List<TransformedNode> children);

    /**
     * Sequential merge.
     * 
     * @param name
     * @param writerNames
     * @param object
     * @return
     */
    String getSequentialMerge(String portName, List<String> sourceInstanceName,
            List<String> targetInstanceName);

}