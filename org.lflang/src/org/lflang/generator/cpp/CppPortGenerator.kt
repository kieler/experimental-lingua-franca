/*************
 * Copyright (c) 2019-2021, TU Dresden.

 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:

 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.

 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.

 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
 * THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ***************/

package org.lflang.generator.cpp

import org.lflang.inferredType
import org.lflang.isBank
import org.lflang.isMultiport
import org.lflang.joinWithLn
import org.lflang.lf.*

class CppPortGenerator(private val reactor: Reactor) {

    companion object {
        private val VarRef.isMultiport get() = (variable as? Port)?.isMultiport == true
        private val VarRef.isInBank get() = container?.isBank == true

        /**
         * Return the C++ type of a port reference.
         *
         * We cannot easily infer this type directly, because it might be used within a generic reactor but the reference is
         * likely used outside of it. Instead of implementing complex logic for finding the actual type, we return a decltype
         * statement and let the C++ compiler do the job.
         */
        val VarRef.dataType: String
            get() = when {
                isInBank && isMultiport  -> "std::remove_reference<decltype(${container.name}[0]->${variable.name}[0])>::type::value_type"
                isInBank && !isMultiport -> "std::remove_reference<decltype(${container.name}[0]->${variable.name})>::type::value_type"
                !isInBank && isMultiport -> "std::remove_reference<decltype($name[0])>::type::value_type"
                else                     -> "std::remove_reference<decltype($name)>::type::value_type"
            }
    }

    private fun generateDeclaration(port: Port): String = with(port) {
        return if (isMultiport) {
            //val initializerLists = (0 until getValidWidth()).joinToString(", ") { """{"${name}_$it", this}""" }
            """$cppType $name;"""
        } else {
            """$cppType $name{"$name", this};"""
        }
    }

    /** Get the C++ type for the receiving port. */
    val Port.cppType: String
        get() {
            val portType = when (this) {
                is Input  -> "reactor::Input"
                is Output -> "reactor::Output"
                else      -> throw AssertionError()
            }

            val dataType = inferredType.cppType
            return if (isMultiport) {
                "reactor::ModifableMultiport<$portType<$dataType>>"
            } else {
                "$portType<$dataType>"
            }
        }

    /** Get the C++ interface type for the receiving port. */
    val Port.cppInterfaceType: String
        get() {
            val portType = when (this) {
                is Input  -> "reactor::Input"
                is Output -> "reactor::Output"
                else      -> throw AssertionError()
            }

            val dataType = inferredType.cppType
            return if (isMultiport) {
                "reactor::Multiport<$portType<$dataType>>"
            } else {
                "$portType<$dataType>"
            }
        }

    private fun generateConstructorInitializer(port: Port) = with(port) {
        val width = port.widthSpec.toCppCode()
        """
            // initialize port $name
            ${name}.reserve($width);
            for (size_t __lf_idx = 0; __lf_idx < $width; __lf_idx++) {
              std::string __lf_port_name = "${name}_" + std::to_string(__lf_idx);
              ${name}.emplace_back(__lf_port_name, this);
            }
        """.trimIndent()
    }

    fun generateConstructorInitializers() =
        reactor.inputs.filter { it.isMultiport }.joinWithLn { generateConstructorInitializer(it) } +
                reactor.outputs.filter { it.isMultiport }.joinWithLn { generateConstructorInitializer(it) }

    fun generateDeclarations() =
        reactor.inputs.joinToString("\n", "// input ports\n", postfix = "\n") { generateDeclaration(it) } +
                reactor.outputs.joinToString("\n", "// output ports\n", postfix = "\n") { generateDeclaration(it) }
}
