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

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.descriptors.*
import org.jetbrains.kotlin.backend.common.ir.createFakeOverrideDescriptor
import org.jetbrains.kotlin.backend.common.lower.*
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.codegen.isInlineCall
import org.jetbrains.kotlin.backend.jvm.codegen.isInlineIrExpression
import org.jetbrains.kotlin.codegen.FunctionCodegen
import org.jetbrains.kotlin.codegen.PropertyReferenceCodegen
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.*
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrClassImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrConstructorImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrDelegatingConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrConstructorSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.inline.InlineUtil
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter

class CallableReferenceLowering(val context: JvmBackendContext): FileLoweringPass {

    object DECLARATION_ORIGIN_FUNCTION_REFERENCE_IMPL : IrDeclarationOriginImpl("FUNCTION_REFERENCE_IMPL")

    private var functionReferenceCount = 0

    private val inlineLambdaReferences = mutableSetOf<IrFunctionReference>()

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(object: IrElementTransformerVoidWithContext() {

            override fun visitCall(expression: IrCall): IrExpression {
                val descriptor = expression.descriptor
                if (descriptor.isInlineCall(context.state)) {
                    //TODO: more wise filtering
                    descriptor.valueParameters.map { valueParameter ->
                        if (InlineUtil.isInlineLambdaParameter(valueParameter)) {
                            expression.getValueArgument(valueParameter.index)?.let {
                                if (isInlineIrExpression(it)) {
                                    (it as IrBlock).statements.filterIsInstance<IrFunctionReference>().forEach { reference ->
                                        inlineLambdaReferences.add(reference)
                                    }
                                }
                            }
                        }
                    }
                }

                //TODO: clean
                return super.visitCall(expression)
            }

            override fun visitFunctionReference(expression: IrFunctionReference): IrExpression {
                expression.transformChildrenVoid(this)

                if (!expression.type.isFunctionOrKFunctionType || inlineLambdaReferences.contains(expression)) {
                    // Not a subject of this lowering.
                    return expression
                }

                val loweredFunctionReference = FunctionReferenceBuilder(currentScope!!.scope.scopeOwner, expression).build()
                val irBuilder = context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol, expression.startOffset, expression.endOffset)
                return irBuilder.irBlock(expression) {
                    +loweredFunctionReference.functionReferenceClass
                    +irCall(loweredFunctionReference.functionReferenceConstructor.symbol).apply {
                        expression.getArguments().forEachIndexed { index, argument ->
                            putValueArgument(index, argument.second)
                        }
                    }
                }
            }
        })
    }

    private class BuiltFunctionReference(val functionReferenceClass: IrClass,
                                         val functionReferenceConstructor: IrConstructor)

    private val kotlinPackageScope = context.builtIns.builtInsPackageScope

    private val continuationClassDescriptor = context.getClass(FqName("kotlin.coroutines.experimental.Continuation"))

    //private val getContinuationSymbol = context.ir.symbols.getContinuation

    private inner class FunctionReferenceBuilder(val containingDeclaration: DeclarationDescriptor,
                                                 val irFunctionReference: IrFunctionReference) {

        private val functionDescriptor = irFunctionReference.descriptor
        private val functionParameters = functionDescriptor.explicitParameters
        private val boundFunctionParameters = irFunctionReference.getArguments().map { it.first }
        private val unboundFunctionParameters = functionParameters - boundFunctionParameters

        private lateinit var functionReferenceClassDescriptor: ClassDescriptorImpl
        private lateinit var functionReferenceClass: IrClassImpl
        private lateinit var functionReferenceThis: IrValueParameterSymbol
        private lateinit var argumentToPropertiesMap: Map<ParameterDescriptor, IrFieldSymbol>

        private val functionReference = context.ir.symbols.functionReference

        fun build(): BuiltFunctionReference {
            val startOffset = irFunctionReference.startOffset
            val endOffset = irFunctionReference.endOffset

            val returnType = functionDescriptor.returnType!!
            val superTypes = mutableListOf(
                    functionReference.owner.defaultType
            )

            val numberOfParameters = unboundFunctionParameters.size
            val functionClassDescriptor = context.getClass(FqName("kotlin.jvm.functions.Function$numberOfParameters"))
            val functionParameterTypes = unboundFunctionParameters.map { it.type }
            val functionClassTypeParameters = functionParameterTypes + returnType
            superTypes += functionClassDescriptor.defaultType.replace(functionClassTypeParameters)

            var suspendFunctionClassDescriptor: ClassDescriptor? = null
            var suspendFunctionClassTypeParameters: List<KotlinType>? = null
            val lastParameterType = unboundFunctionParameters.lastOrNull()?.type
            if (lastParameterType != null && TypeUtils.getClassDescriptor(lastParameterType) == continuationClassDescriptor) {
                // If the last parameter is Continuation<> inherit from SuspendFunction.
                suspendFunctionClassDescriptor = kotlinPackageScope.getContributedClassifier(
                        Name.identifier("SuspendFunction${numberOfParameters - 1}"), NoLookupLocation.FROM_BACKEND) as ClassDescriptor
                suspendFunctionClassTypeParameters = functionParameterTypes.dropLast(1) + lastParameterType.arguments.single().type
                superTypes += suspendFunctionClassDescriptor.defaultType.replace(suspendFunctionClassTypeParameters)
            }

            functionReferenceClassDescriptor = ClassDescriptorImpl(
                    /* containingDeclaration = */ containingDeclaration,
                    /* name                  = */ "${functionDescriptor.name}\$${functionReferenceCount++}".synthesizedName,
                    /* modality              = */ Modality.FINAL,
                    /* kind                  = */ ClassKind.CLASS,
                    /* superTypes            = */ superTypes,
                    /* source                = */ irFunctionReference.symbol.descriptor.source,
                    /* isExternal            = */ false
            )
            functionReferenceClass = IrClassImpl(
                    startOffset = startOffset,
                    endOffset   = endOffset,
                    origin      = DECLARATION_ORIGIN_FUNCTION_REFERENCE_IMPL,
                    descriptor  = functionReferenceClassDescriptor
            )

            functionReferenceClass.createParameterDeclarations()

            functionReferenceThis = functionReferenceClass.thisReceiver!!.symbol

            val invokeFunctionDescriptor = functionClassDescriptor.getFunction("invoke", functionClassTypeParameters)

            val constructorBuilder = createConstructorBuilder()
            val invokeMethodBuilder = createInvokeMethodBuilder(invokeFunctionDescriptor)
            val getSignatureBuilder = createGetSignatureMethodBuilder(functionReference.owner.descriptor.getFunction("getSignature", emptyList()))
            val getNameBuilder = createGetNameMethodBuilder(functionReference.owner.descriptor.getProperty("name", emptyList()))
            val suspendInvokeMethodBuilder =
                    if (suspendFunctionClassDescriptor != null) {
                        val suspendInvokeFunctionDescriptor = suspendFunctionClassDescriptor.getFunction("invoke", suspendFunctionClassTypeParameters!!)
                        createInvokeMethodBuilder(suspendInvokeFunctionDescriptor)
                    }
                    else null

            val inheritedScope = functionReference.descriptor.unsubstitutedMemberScope
                    .getContributedDescriptors()
                    .map { it.createFakeOverrideDescriptor(functionReferenceClassDescriptor) }
                    .filterNotNull().filterNot { it.name.asString() == "getSignature" || it.name.asString() == "name"}
            val contributedDescriptors = (
                    inheritedScope + invokeMethodBuilder.symbol.descriptor +
                    suspendInvokeMethodBuilder?.symbol?.descriptor + getSignatureBuilder.symbol.descriptor
                                         ).filterNotNull().toList()
            functionReferenceClassDescriptor.initialize(
                    SimpleMemberScope(contributedDescriptors), setOf(constructorBuilder.symbol.descriptor), null)

            functionReferenceClass.addFakeOverrides()

            constructorBuilder.initialize()
            functionReferenceClass.declarations.add(constructorBuilder.ir)

            invokeMethodBuilder.initialize()
            functionReferenceClass.declarations.add(invokeMethodBuilder.ir)

            getSignatureBuilder.initialize()
            functionReferenceClass.declarations.add(getSignatureBuilder.ir)

            getNameBuilder.initialize()
            functionReferenceClass.declarations.add(getNameBuilder.ir)

            suspendInvokeMethodBuilder?.let {
                it.initialize()
                functionReferenceClass.declarations.add(it.ir)
            }

            return BuiltFunctionReference(functionReferenceClass, constructorBuilder.ir)
        }

        private fun createConstructorBuilder()
                = object : SymbolWithIrBuilder<IrConstructorSymbol, IrConstructor>() {

            private val kFunctionRefConstructorSymbol = functionReference.constructors.filter { it.descriptor.valueParameters.size == 2 }.single()

            override fun buildSymbol() = IrConstructorSymbolImpl(
                    ClassConstructorDescriptorImpl.create(
                            /* containingDeclaration = */ functionReferenceClassDescriptor,
                            /* annotations           = */ Annotations.EMPTY,
                            /* isPrimary             = */ false,
                            /* source                = */ SourceElement.NO_SOURCE
                    )
            )

            override fun doInitialize() {
                val descriptor = symbol.descriptor as ClassConstructorDescriptorImpl
                val constructorParameters = boundFunctionParameters.mapIndexed { index, parameter ->
                    parameter.copyAsValueParameter(descriptor, index)
                }
                descriptor.initialize(constructorParameters, Visibilities.PUBLIC)
                descriptor.returnType = functionReferenceClassDescriptor.defaultType
            }

            override fun buildIr(): IrConstructor {
                argumentToPropertiesMap = boundFunctionParameters.associate {
                    it to buildPropertyWithBackingField(it.name, it.type, false)
                }

                val startOffset = irFunctionReference.startOffset
                val endOffset = irFunctionReference.endOffset
                return IrConstructorImpl(
                        startOffset = startOffset,
                        endOffset   = endOffset,
                        origin      = DECLARATION_ORIGIN_FUNCTION_REFERENCE_IMPL,
                        symbol      = symbol).apply {

                    val irBuilder = context.createIrBuilder(this.symbol, startOffset, endOffset)

                    createParameterDeclarations()

                    body = irBuilder.irBlockBody {
                        +IrDelegatingConstructorCallImpl(startOffset, endOffset,
                                                         kFunctionRefConstructorSymbol, kFunctionRefConstructorSymbol.descriptor).apply {
                            val const = IrConstImpl.int(startOffset, endOffset, context.builtIns.int.defaultType, unboundFunctionParameters.size)
                            putValueArgument(0, const)

                            //val receiver = if (needReceiver) irGet(valueParameters.single().symbol) else irNull()
                            putValueArgument(1, IrConstImpl.constNull(startOffset, endOffset, this@CallableReferenceLowering.context.builtIns.any.defaultType))
                        }
                        +IrInstanceInitializerCallImpl(startOffset, endOffset, functionReferenceClass.symbol)
                        // Save all arguments to fields.

                    }
                }
            }
        }

        protected fun FunctionDescriptor.calculateArity(): Int {
            var arity = valueParameters.size
            if (extensionReceiverParameter != null) arity++
            if (dispatchReceiverParameter != null) arity++
            return arity
        }

        // TODO: what about private package level functions?
        private val DeclarationDescriptor.fullName: String
            get() {
                return when (this) {
                    is PackageFragmentDescriptor -> fqNameSafe.asString()
                    is ClassDescriptor -> containingDeclaration.fullName + "." + fqNameSafe
                    is FunctionDescriptor -> containingDeclaration.fullName + "." + functionName
                    is PropertyDescriptor -> containingDeclaration.fullName + "." + fqNameSafe
                    else -> TODO("$this")
                }
            }

        private fun createInvokeMethodBuilder(superFunctionDescriptor: FunctionDescriptor)
                = object : SymbolWithIrBuilder<IrSimpleFunctionSymbol, IrSimpleFunction>() {

            override fun buildSymbol() = IrSimpleFunctionSymbolImpl(
                    SimpleFunctionDescriptorImpl.create(
                            /* containingDeclaration = */ functionReferenceClassDescriptor,
                            /* annotations           = */ Annotations.EMPTY,
                            /* name                  = */ Name.identifier("invoke"),
                            /* kind                  = */ CallableMemberDescriptor.Kind.DECLARATION,
                            /* source                = */ SourceElement.NO_SOURCE
                    )
            )

            override fun doInitialize() {
                val descriptor = symbol.descriptor as SimpleFunctionDescriptorImpl
                val valueParameters = superFunctionDescriptor.valueParameters
                        .map { it.copyAsValueParameter(descriptor, it.index) }

                descriptor.initialize(
                        /* receiverParameterType        = */ null,
                        /* dispatchReceiverParameter    = */ functionReferenceClassDescriptor.thisAsReceiverParameter,
                        /* typeParameters               = */ emptyList(),
                        /* unsubstitutedValueParameters = */ valueParameters,
                        /* unsubstitutedReturnType      = */ superFunctionDescriptor.returnType,
                        /* modality                     = */ Modality.FINAL,
                        /* visibility                   = */ Visibilities.PUBLIC).apply {
                    overriddenDescriptors              +=    superFunctionDescriptor
                    isSuspend                           =    superFunctionDescriptor.isSuspend
                }
            }

            override fun buildIr(): IrSimpleFunction {
                val startOffset = irFunctionReference.startOffset
                val endOffset = irFunctionReference.endOffset
                val ourSymbol = symbol
                return IrFunctionImpl(
                        startOffset = startOffset,
                        endOffset   = endOffset,
                        origin      = DECLARATION_ORIGIN_FUNCTION_REFERENCE_IMPL,
                        symbol      = ourSymbol).apply {

                    val function = this
                    val irBuilder = context.createIrBuilder(function.symbol, startOffset, endOffset)

                    createParameterDeclarations()

                    body = irBuilder.irBlockBody(startOffset, endOffset) {
                        +irReturn(
                                irCall(irFunctionReference.symbol).apply {
                                    var unboundIndex = 0
                                    val unboundArgsSet = unboundFunctionParameters.toSet()
                                    functionParameters.forEach {
                                        val argument =
                                                if (!unboundArgsSet.contains(it))
                                                // Bound parameter - read from field.
                                                    irGetField(irGet(functionReferenceThis), argumentToPropertiesMap[it]!!)
                                                else {
                                                    if (ourSymbol.descriptor.isSuspend && unboundIndex == valueParameters.size)
                                                    // For suspend functions the last argument is continuation and it is implicit.
                                                        TODO()
//                                                        irCall(getContinuationSymbol,
//                                                               listOf(ourSymbol.descriptor.returnType!!))
                                                    else
                                                        irGet(valueParameters[unboundIndex++].symbol)
                                                }
                                        when (it) {
                                            functionDescriptor.dispatchReceiverParameter -> dispatchReceiver = argument
                                            functionDescriptor.extensionReceiverParameter -> extensionReceiver = argument
                                            else -> putValueArgument((it as ValueParameterDescriptor).index, argument)
                                        }
                                    }
                                    assert(unboundIndex == valueParameters.size, { "Not all arguments of <invoke> are used" })
                                }
                        )
                    }
                }
            }
        }

        private fun buildPropertyWithBackingField(name: Name, type: KotlinType, isMutable: Boolean): IrFieldSymbol {
            val propertyBuilder = context.createPropertyWithBackingFieldBuilder(
                    startOffset = irFunctionReference.startOffset,
                    endOffset   = irFunctionReference.endOffset,
                    origin      = DECLARATION_ORIGIN_FUNCTION_REFERENCE_IMPL,
                    owner       = functionReferenceClassDescriptor,
                    name        = name,
                    type        = type,
                    isMutable   = isMutable).apply {
                initialize()
            }

            functionReferenceClass.declarations.add(propertyBuilder.ir)
            return propertyBuilder.ir.backingField!!.symbol
        }

        private fun createGetNameMethodBuilder(nameProperty: PropertyDescriptor)
                = object : SymbolWithIrBuilder<IrSimpleFunctionSymbol, IrSimpleFunction>() {

            override fun buildSymbol() = IrSimpleFunctionSymbolImpl(
                    PropertyDescriptorImpl.create(
                            /* containingDeclaration = */ functionReferenceClassDescriptor,
                            /* annotations           = */ Annotations.EMPTY,
                                                          nameProperty.modality,
                                                          nameProperty.visibility,
                            false,
                            /* name                  = */ nameProperty.name,
                            /* kind                  = */ CallableMemberDescriptor.Kind.DECLARATION,
                            /* source                = */ SourceElement.NO_SOURCE,
                                                          false, false, false, false, false, false
                    ).let { property ->



                        PropertyGetterDescriptorImpl(property,
                                                     Annotations.EMPTY,
                                                     Modality.OPEN,
                                                     Visibilities.PUBLIC,
                                                     false, false, false,
                                                     CallableMemberDescriptor.Kind.DECLARATION,
                                                     null,
                                                     SourceElement.NO_SOURCE
                        ).also {
                            property.initialize(it, null)
                            property.setType(
                                    /* outType                   = */ nameProperty.type,
                                    /* typeParameters            = */ nameProperty.typeParameters,
                                    /* dispatchReceiverParameter = */ nameProperty.dispatchReceiverParameter,
                                    /* receiverType              = */ nameProperty.extensionReceiverParameter?.type)
                        }
                    }
            )

            override fun doInitialize() {
                val descriptor = symbol.descriptor as PropertyGetterDescriptorImpl
                descriptor.initialize(nameProperty.type)
            }

            override fun buildIr(): IrSimpleFunction {
                val startOffset = irFunctionReference.startOffset
                val endOffset = irFunctionReference.endOffset
                val ourSymbol = symbol
                return IrFunctionImpl(
                        startOffset = startOffset,
                        endOffset   = endOffset,
                        origin      = DECLARATION_ORIGIN_FUNCTION_REFERENCE_IMPL,
                        symbol      = ourSymbol).apply {

                    val function = this
                    val irBuilder = context.createIrBuilder(function.symbol, startOffset, endOffset)

                    createParameterDeclarations()

                    body = irBuilder.irBlockBody(startOffset, endOffset) {
                        +irReturn(
                                IrConstImpl.string(-1, -1, context.builtIns.stringType, functionDescriptor.name.asString())
                        )
                    }
                }
            }
        }


        private fun createGetSignatureMethodBuilder(superFunctionDescriptor: FunctionDescriptor)
                = object : SymbolWithIrBuilder<IrSimpleFunctionSymbol, IrSimpleFunction>() {

            override fun buildSymbol() = IrSimpleFunctionSymbolImpl(
                    SimpleFunctionDescriptorImpl.create(
                            /* containingDeclaration = */ functionReferenceClassDescriptor,
                            /* annotations           = */ Annotations.EMPTY,
                            /* name                  = */ Name.identifier("getSignature"),
                            /* kind                  = */ CallableMemberDescriptor.Kind.DECLARATION,
                            /* source                = */ SourceElement.NO_SOURCE
                    )
            )

            override fun doInitialize() {
                val descriptor = symbol.descriptor as SimpleFunctionDescriptorImpl
                val valueParameters = superFunctionDescriptor.valueParameters
                        .map { it.copyAsValueParameter(descriptor, it.index) }

                descriptor.initialize(
                        /* receiverParameterType        = */ null,
                        /* dispatchReceiverParameter    = */ functionReferenceClassDescriptor.thisAsReceiverParameter,
                        /* typeParameters               = */ emptyList(),
                        /* unsubstitutedValueParameters = */ valueParameters,
                        /* unsubstitutedReturnType      = */ superFunctionDescriptor.returnType,
                        /* modality                     = */ Modality.FINAL,
                        /* visibility                   = */ Visibilities.PUBLIC).apply {
                    overriddenDescriptors              +=    superFunctionDescriptor
                    isSuspend                           =    superFunctionDescriptor.isSuspend
                }
            }

            override fun buildIr(): IrSimpleFunction {
                val startOffset = irFunctionReference.startOffset
                val endOffset = irFunctionReference.endOffset
                val ourSymbol = symbol
                return IrFunctionImpl(
                        startOffset = startOffset,
                        endOffset   = endOffset,
                        origin      = DECLARATION_ORIGIN_FUNCTION_REFERENCE_IMPL,
                        symbol      = ourSymbol).apply {

                    val function = this
                    val irBuilder = context.createIrBuilder(function.symbol, startOffset, endOffset)

                    createParameterDeclarations()

                    body = irBuilder.irBlockBody(startOffset, endOffset) {
                        +irReturn(
                                IrConstImpl.string(
                                        -1, -1, context.builtIns.stringType,
                                        PropertyReferenceCodegen.getSignatureString(
                                                irFunctionReference.symbol.descriptor, this@CallableReferenceLowering.context.state
                                        )
                                )
                        )
                    }
                }
            }
        }

    }

//    private fun generateFunctionReferenceMethods(descriptor: FunctionDescriptor) {
//        val flags = ACC_PUBLIC or ACC_FINAL
//        val generateBody = state.classBuilderMode.generateBodies
//
//        run {
//            val mv = v.newMethod(NO_ORIGIN, flags, "getOwner", Type.getMethodDescriptor(K_DECLARATION_CONTAINER_TYPE), null, null)
//            if (generateBody) {
//                mv.visitCode()
//                val iv = InstructionAdapter(mv)
//                generateCallableReferenceDeclarationContainer(iv, descriptor, state)
//                iv.areturn(K_DECLARATION_CONTAINER_TYPE)
//                FunctionCodegen.endVisit(iv, "function reference getOwner", element)
//            }
//        }
//
//        run {
//            val mv = v.newMethod(NO_ORIGIN, flags, "getName", Type.getMethodDescriptor(JAVA_STRING_TYPE), null, null)
//            if (generateBody) {
//                mv.visitCode()
//                val iv = InstructionAdapter(mv)
//                iv.aconst(descriptor.name.asString())
//                iv.areturn(JAVA_STRING_TYPE)
//                FunctionCodegen.endVisit(iv, "function reference getName", element)
//            }
//        }
//
//        run {
//            val mv = v.newMethod(NO_ORIGIN, flags, "getSignature", Type.getMethodDescriptor(JAVA_STRING_TYPE), null, null)
//            if (generateBody) {
//                mv.visitCode()
//                val iv = InstructionAdapter(mv)
//                PropertyReferenceCodegen.generateCallableReferenceSignature(iv, descriptor, state)
//                iv.areturn(JAVA_STRING_TYPE)
//                FunctionCodegen.endVisit(iv, "function reference getSignature", element)
//            }
//        }
//    }


}