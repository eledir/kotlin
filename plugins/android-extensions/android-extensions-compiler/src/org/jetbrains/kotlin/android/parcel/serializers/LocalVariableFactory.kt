/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.android.parcel.serializers

import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Type

interface LocalVariableFactory {
    fun withLocalVariable(type: Type, block: (Int) -> Unit)

    companion object {
        val STUB = object : LocalVariableFactory {
            override fun withLocalVariable(type: Type, block: (Int) -> Unit) = TODO("not implemented")
        }
    }
}

class LocalVariableFactoryImpl(private val mv: MethodVisitor, private val startIndex: Int) : LocalVariableFactory {
    private val declaredVariables = mutableSetOf<Int>()
    private val usedVariables = mutableSetOf<Int>()

    private var varNameIndex = 0

    override fun withLocalVariable(type: Type, block: (Int) -> Unit) {
        val startLabel = Label()
        val endLabel = Label()

        mv.visitLabel(startLabel)
        val index = obtainVariable()
        mv.visitLocalVariable("parcel${varNameIndex++}", type.descriptor, null, startLabel, endLabel, index)

        block(index)

        freeVariable(index)
        mv.visitLabel(endLabel)
    }

    private fun obtainVariable(): Int {
        var index = startIndex
        while (index in usedVariables) {
            index += 1
        }

        if (index !in declaredVariables) {
            declaredVariables.add(index)
        }

        return index
    }

    private fun freeVariable(index: Int) {
        usedVariables.remove(index)
    }
}