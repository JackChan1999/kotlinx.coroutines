package kotlinx.coroutines.debug.transformer

import kotlinx.coroutines.debug.manager.*
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.tree.ClassNode
import org.jetbrains.org.objectweb.asm.tree.MethodInsnNode
import org.jetbrains.org.objectweb.asm.tree.MethodNode
import java.io.File
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain

private fun ClassNode.isCoroutineImplOrSubType() =
        name == COROUTINE_IMPL_TYPE.internalName || superName == COROUTINE_IMPL_TYPE.internalName

private fun argumentVarIndex(argumentTypes: Array<Type>, argumentIndex: Int) =
        argumentTypes.take(argumentIndex).map { it.size }.sum()

private fun MethodNode.findContinuationVarIndex(classNode: ClassNode): Int {
    if (classNode.isCoroutineImplOrSubType()) return 0 //index of `this`
    require(isSuspend(), { "Method should be suspend, got $desc instead" })
    val continuationArgIndex = Type.getType(desc).argumentTypes.size - continuationOffsetFromEndInDesc(name)
    val isStatic = access and Opcodes.ACC_STATIC != 0
    return argumentVarIndex(Type.getArgumentTypes(desc), continuationArgIndex) + if (isStatic) 0 else 1
}

private fun MethodNode.addSuspendCallHandlers(
        suspendCallsInThisMethod: List<MethodInsnNode>,
        continuationVarIndex: Int,
        classNode: ClassNode
) {
    val lines = instructions.methodCallLineNumber()
    suspendCallsInThisMethod.forEach {
        val position = CallPosition(classNode.sourceFile, lines[it] ?: -1)
        val call = SuspendCall(it.buildMethodId(), this.buildMethodId(classNode), position)
        val index = allSuspendCalls.appendWithIndex(call)
        instructions.insert(it, generateAfterSuspendCall(continuationVarIndex, index))
    }
}

private fun MethodNode.transformCreate() {
    val completion = Type.getType(desc).argumentTypes.lastIndex + 1
    debug { "create type: $desc, completion: $completion" }
    var newInsn = instructions.first
    while (newInsn.opcode != Opcodes.NEW)
        newInsn = newInsn?.nextMeaningful
    instructions.insertBefore(newInsn, generateNewWrappedCompletion(completion))
}

private fun MethodNode.transformMethod(classNode: ClassNode) {
    if (isCreateCoroutine(classNode)) {
        transformCreate()
        return
    }
    if (!isSuspend() && !isDoResume) return
    val continuation = findContinuationVarIndex(classNode)
    val isAnonymous = isStateMachineForAnonymousSuspendFunction()
    debug {
        ">>in method ${classNode.name}.${name} with description: ${desc}, " +
                "cont: $continuation, isAnonymous: $isAnonymous"
    }
    if (isDoResume) {
        val index = knownDoResumeFunctions.appendWithIndex(buildMethodId(classNode))
        instructions.insert(generateHandleDoResumeCallEnter(continuation, index))
        if (!isAnonymous) return
    }
    val suspendCalls = suspendCallInstructions(classNode)
    if (suspendCalls.isEmpty()) return
    debug {
        buildString { append("suspend calls:\n"); suspendCalls.forEach { append("${it.buildMethodId()}\n") } }
    }
    addSuspendCallHandlers(suspendCalls, continuation, classNode)
}

class CoroutinesDebugTransformer : ClassFileTransformer {
    override fun transform(
            loader: ClassLoader?,
            className: String?,
            classBeingRedefined: Class<*>?,
            protectionDomain: ProtectionDomain?,
            classfileBuffer: ByteArray?
    ): ByteArray {
        //if (className?.startsWith(DEBUG_AGENT_PACKAGE_PREFIX) == true && classfileBuffer != null) return classfileBuffer
        val reader = ClassReader(classfileBuffer)
        val classNode = ClassNode()
        reader.accept(classNode, 0)
        for (method in classNode.methods.map { it as MethodNode }) {
            try {
                method.transformMethod(classNode)
            } catch (e: Throwable) {
                exceptions?.appendWithIndex(e)
                val message = "while instrumenting ${classNode.name}.${method.name} with desc: ${method.desc} " +
                        "exception : ${e.stackTraceToString()}"
                error { message }
                debug { message + "\nbytecode: ${classNode.byteCodeString()}" }
            }
        }
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        classNode.accept(writer)
        return writer.toByteArray()
    }

    fun transformBytes(bytes: ByteArray?) = transform(null, null, null, null, bytes)
}

class FilesTransformer(private val inputDir: File, private val outputDir: File = inputDir) {
    fun transform() {
        exceptions = AppendOnlyThreadSafeList()
        info { "Transforming from $inputDir to $outputDir" }
        inputDir.walk().filter { it.isFile }.forEach { file ->
            if (file.isClassFile()) info { "Transforming $file" }
            val bytes = file.readBytes()
            val outBytes = if (file.isClassFile()) classesTransformer.transformBytes(bytes) else bytes
            val outFile = file.toOutputFile()
            outFile.parentFile.mkdirs()
            outFile.writeBytes(outBytes)
        }
        if (exceptions?.isNotEmpty() == true)
            throw Exception("Encountered errors while transforming", exceptions?.firstOrNull())
    }

    private fun File.toOutputFile() = File(outputDir, relativeTo(inputDir).toString())
}

private val classesTransformer = CoroutinesDebugTransformer()

private fun File.isClassFile() = toString().endsWith(".class")


fun main(args: Array<String>) {
    if (args.size != 1) {
        println("Usage: CoroutineDebugTransformerKt <dir>")
        return
    }
    val t = FilesTransformer(File(args[0]))
    Logger.config = LoggerConfig(LogLevel.INFO)
    t.transform()
}