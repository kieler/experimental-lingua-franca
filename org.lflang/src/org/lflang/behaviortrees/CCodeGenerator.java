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

import java.util.ArrayList;

import org.lflang.lf.Parallel;
import org.lflang.lf.Reactor;

/**
 * BehaviorTree code generator for C.
 * 
 * @author Alexander Schulz-Rosengarten
 */
class CCodeGenerator implements CodeGenerator {

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
            ArrayList<TransformedNode> children) {
        // TODO Auto-generated method stub
        return null;
    }
    
}

//
//private void setMergedOutputReactionForPar(Reaction reaction, int N, int M, Reactor reactor) {
//var code = LFF.createCode();
//String codeContent = "int successCounter = 0;\n"
//        + "int failureCounter = 0;\n\n";
//for (var succTrigger : reaction.getTriggers()) {
//    var triggerVarRef = (VarRef) succTrigger;
//    String instName = triggerVarRef.getContainer().getName();
//    
//    if (triggerVarRef.getVariable().getName().equals(SUCCESS)) {
//        codeContent += "if(" + instName + ".success->is_present) {\n    "
//                + "successCounter++;\n}\n";
//    } else {
//        codeContent += "if(" + instName + ".failure->is_present) {\n    "
//                + "failureCounter++;\n}\n";
//    }
//    
//}
//codeContent += "\nif(successCounter >= " + M + ") {\n    "
//        + "SET(success, true);\n} "
//        + "else if (failureCounter > " + (N-M) + ") {\n    "
//        + "SET(failure, true);\n}";
//
//
//code.setBody(codeContent);
//reaction.setCode(code);
//reaction.getEffects().add(createRef(reactor, null, SUCCESS));
//reaction.getEffects().add(createRef(reactor, null, FAILURE));
//}
//
//private Reaction createMergedOutputReaction(Reactor reactor, String outputName) {
//var reaction = LFF.createReaction();
//var code = LFF.createCode();
//code.setBody("SET(" + outputName + ", true);");
//reaction.setCode(code);
//reaction.getEffects().add(createRef(reactor, null, outputName));
//
//return reaction;
//}
