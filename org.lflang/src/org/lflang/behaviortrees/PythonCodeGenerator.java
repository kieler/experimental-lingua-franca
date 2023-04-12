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

import static java.lang.String.format;

import java.util.List;

import org.lflang.lf.Parallel;
import org.lflang.lf.Reactor;

/**
 * BehaviorTree code generator for Python.
 * 
 * @author Alexander Schulz-Rosengarten
 */
class PythonCodeGenerator implements CodeGenerator {

    /**
     * {@inheritDoc}
     */
    @Override
    public String getRunningInference() {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getParallelCalculation(Parallel parallel, Reactor reactor,
            List<TransformedNode> children) {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getSequentialMerge(String portName,
            List<String> sourceInstanceName, List<String> targetInstanceName) {
        var targetPort = (targetInstanceName != null ? targetInstanceName : "") + portName;
        var code = new StringBuilder();
        
        if (!sourceInstanceName.isEmpty()) {
            // Test in reverse for overriding
            for (int i = sourceInstanceName.size() - 1; i >= 0; i--) {
                if (i == sourceInstanceName.size() - 1) { // first
                    code.append(format("if %s.%s.is_present:\n", sourceInstanceName.get(i), portName));
                } else {
                    code.append(format("elif %s.%s.is_present:\n", sourceInstanceName.get(i), portName));
                }
                code.append(format("    %s.set(%s.%s.value)\n", targetPort, sourceInstanceName.get(i), portName));
            }
        }
        
        return code.toString();
    }
    
}
