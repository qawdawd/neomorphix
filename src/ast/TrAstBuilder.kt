package ast

import arch.ConnectivityType
import arch.SnnArch
import transaction.ComparisonOp
import transaction.NeuronTx
import transaction.SpikeTx
import transaction.TxCondition
import transaction.TxFieldType
import transaction.TxOperand
import transaction.TxStatement

/**
 * Builder responsible for transforming transaction ASTs into the hierarchical transactional AST
 * used by the synthesis pipeline.
 */
class TrAstBuilder {

    /** Builds the initial AST that simply concatenates spike and neuron transactions. */
    fun buildInitial(spikeTx: SpikeTx, neuronTx: NeuronTx): AstBlock {
        val statements = mutableListOf<AstNode>()
        statements += convertStatements(spikeTx.toAst().root.body.statements, TransactionKind.SPIKE)
        statements += convertStatements(neuronTx.toAst().root.body.statements, TransactionKind.NEURON)
        return AstBlock(origin = null, statements = statements)
    }

    /**
     * Splits the initial AST into dedicated phase blocks. Spike transactions form the synaptic
     * phase while neuron transactions are partitioned into somatic, emission and refractory phases.
     */
    fun attachPhaseRegions(ast: AstBlock): AstBlock {
        val synapticNodes = mutableListOf<AstNode>()
        val neuronNodes = mutableListOf<AstNode>()
        for (node in ast.statements) {
            when (node.origin) {
                TransactionKind.SPIKE -> synapticNodes += node
                TransactionKind.NEURON -> neuronNodes += node
            }
        }

        val phasedStatements = mutableListOf<AstNode>()
        if (synapticNodes.isNotEmpty()) {
            phasedStatements += AstPhaseBlock(
                origin = TransactionKind.SPIKE,
                phase = AstPhase.SYNAPTIC,
                body = AstBlock(TransactionKind.SPIKE, synapticNodes)
            )
        }
        if (neuronNodes.isNotEmpty()) {
            val segments = splitNeuronPhases(neuronNodes)
            if (segments.somatic.isNotEmpty()) {
                phasedStatements += AstPhaseBlock(
                    origin = TransactionKind.NEURON,
                    phase = AstPhase.SOMATIC,
                    body = AstBlock(TransactionKind.NEURON, segments.somatic)
                )
            }
            if (segments.emission.isNotEmpty()) {
                phasedStatements += AstPhaseBlock(
                    origin = TransactionKind.NEURON,
                    phase = AstPhase.EMISSION,
                    body = AstBlock(TransactionKind.NEURON, segments.emission)
                )
            }
            if (segments.refractory.isNotEmpty()) {
                phasedStatements += AstPhaseBlock(
                    origin = TransactionKind.NEURON,
                    phase = AstPhase.REFRACTORY,
                    body = AstBlock(TransactionKind.NEURON, segments.refractory)
                )
            }
        }
        return AstBlock(origin = null, statements = phasedStatements)
    }

    /** Expands synaptic phase statements with loops according to the network topology. */
    fun expandSynapticLoops(phaseBlock: AstPhaseBlock, arch: SnnArch): AstPhaseBlock {
        require(phaseBlock.phase == AstPhase.SYNAPTIC) {
            "Synaptic loop expansion can only be applied to the synaptic phase"
        }
        val expanded = expandBlockWithPattern(phaseBlock.body, arch, TransactionKind.SPIKE)
        return phaseBlock.copy(body = expanded)
    }

    /** Expands somatic phase statements with post-synaptic loops. */
    fun expandSomaticLoops(phaseBlock: AstPhaseBlock, arch: SnnArch): AstPhaseBlock {
        require(phaseBlock.phase == AstPhase.SOMATIC) {
            "Somatic loop expansion can only be applied to the somatic phase"
        }
        val expanded = expandBlockWithPattern(phaseBlock.body, arch, TransactionKind.NEURON)
        return phaseBlock.copy(body = expanded)
    }

    /** Expands emission phase statements with post-synaptic loops when required. */
    fun expandEmissionLoops(phaseBlock: AstPhaseBlock, arch: SnnArch): AstPhaseBlock {
        require(phaseBlock.phase == AstPhase.EMISSION) {
            "Emission loop expansion can only be applied to the emission phase"
        }
        val expanded = expandBlockWithPattern(phaseBlock.body, arch, TransactionKind.NEURON)
        return phaseBlock.copy(body = expanded)
    }

    /** Expands refractory phase statements with post-synaptic loops when required. */
    fun expandRefractoryLoops(phaseBlock: AstPhaseBlock, arch: SnnArch): AstPhaseBlock {
        require(phaseBlock.phase == AstPhase.REFRACTORY) {
            "Refractory loop expansion can only be applied to the refractory phase"
        }
        val expanded = expandBlockWithPattern(phaseBlock.body, arch, TransactionKind.NEURON)
        return phaseBlock.copy(body = expanded)
    }

    /** Placeholder normalization step that will later validate the AST. */
    fun normalize(ast: AstBlock) {
        println("[TrAST] normalize(): stub invoked, AST size=${ast.statements.size}")
    }

    /** Produces a human-readable representation of an AST block or node. */
    fun dump(ast: AstBlock): String {
        val builder = StringBuilder()
        dumpBlock(ast, builder, 0)
        return builder.toString()
    }

    /** Converts transaction statements into AST nodes. */
    private fun convertStatements(source: List<TxStatement>, origin: TransactionKind): List<AstNode> {
        val result = mutableListOf<AstNode>()
        for (statement in source) {
            when (statement) {
                is TxStatement.Assignment -> result += AstOperation(
                    origin = origin,
                    kind = OperationKind.ASSIGNMENT,
                    target = statement.target,
                    operands = listOf(statement.value),
                    opcode = null,
                    statement = statement
                )

                is TxStatement.BinaryOp -> result += AstOperation(
                    origin = origin,
                    kind = OperationKind.BINARY,
                    target = statement.target,
                    operands = listOf(statement.left, statement.right),
                    opcode = statement.opcode,
                    statement = statement
                )

                is TxStatement.UnaryOp -> result += AstOperation(
                    origin = origin,
                    kind = OperationKind.UNARY,
                    target = statement.target,
                    operands = listOf(statement.operand),
                    opcode = statement.opcode,
                    statement = statement
                )

                is TxStatement.Emit -> result += AstOperation(
                    origin = origin,
                    kind = OperationKind.EMIT,
                    target = statement.target,
                    operands = emptyList(),
                    opcode = null,
                    statement = statement
                )

                is TxStatement.IfBlock -> result += convertIf(statement, origin)

                is TxStatement.Block -> {
                    result += convertStatements(statement.body.statements, origin)
                }

                else -> {
                    // Else-if and else blocks are handled inside the immutable if structure. Any
                    // unexpected statement types are ignored for now.
                }
            }
        }
        return result
    }

    /** Converts an immutable if-block into an AST condition node. */
    private fun convertIf(statement: TxStatement.IfBlock, origin: TransactionKind): AstCondition {
        val thenBlock = AstBlock(origin, convertStatements(statement.body.statements, origin))
        val elseIfBranches = statement.elseIfBlocks.map {
            AstCondition.ElseIfBranch(
                condition = it.condition,
                body = AstBlock(origin, convertStatements(it.body.statements, origin))
            )
        }
        val elseBlock = statement.elseBlock?.let {
            AstBlock(origin, convertStatements(it.body.statements, origin))
        }
        return AstCondition(origin, statement.condition, thenBlock, elseIfBranches, elseBlock)
    }

    /** Splits neuron statements into somatic, emission and refractory parts. */
    private fun splitNeuronPhases(nodes: List<AstNode>): NeuronSegments {
        val somatic = mutableListOf<AstNode>()
        val emission = mutableListOf<AstNode>()
        val refractory = mutableListOf<AstNode>()

        var emissionEncountered = false
        for (node in nodes) {
            val hasEmit = containsEmit(node)
            if (!emissionEncountered) {
                if (hasEmit) {
                    val split = extractEmission(node)
                    emission += split.emission
                    refractory += split.refractory
                    emissionEncountered = true
                } else {
                    somatic += node
                }
            } else {
                if (hasEmit) {
                    val split = extractEmission(node)
                    emission += split.emission
                    refractory += split.refractory
                } else {
                    refractory += node
                }
            }
        }

        return NeuronSegments(somatic, emission, refractory)
    }

    /** Checks whether the node contains an emit statement. */
    private fun containsEmit(node: AstNode): Boolean = when (node) {
        is AstOperation -> node.kind == OperationKind.EMIT
        is AstCondition -> {
            node.thenBlock.statements.any { containsEmit(it) } ||
                    node.elseIfBranches.any { branch -> branch.body.statements.any { containsEmit(it) } } ||
                    (node.elseBlock?.statements?.any { containsEmit(it) } ?: false)
        }
        is AstLoop -> containsEmitInBlock(node.body)
        is AstPhaseBlock -> containsEmitInBlock(node.body)
    }

    private fun containsEmitInBlock(block: AstBlock): Boolean =
        block.statements.any { containsEmit(it) }

    /** Expands a block according to the loop rules for a particular transaction kind. */
    private fun expandBlockWithPattern(
        block: AstBlock,
        arch: SnnArch,
        kind: TransactionKind
    ): AstBlock {
        val expanded = block.statements.map { expandNode(it, arch, kind) }
        return block.copy(statements = expanded)
    }

    /** Applies expansion to a specific node. */
    private fun expandNode(node: AstNode, arch: SnnArch, kind: TransactionKind): AstNode {
        val updated = when (node) {
            is AstOperation -> node
            is AstCondition -> expandCondition(node, arch, kind)
            is AstLoop -> node
            is AstPhaseBlock -> node // phase blocks are handled by callers
        }
        val pattern = when (kind) {
            TransactionKind.SPIKE -> classifySynapticPattern(updated)
            TransactionKind.NEURON -> classifyNeuronPattern(updated)
        }
        return wrapWithPattern(updated, pattern, arch)
    }

    /** Recursively expands nested condition bodies. */
    private fun expandCondition(node: AstCondition, arch: SnnArch, kind: TransactionKind): AstCondition {
        val thenExpanded = expandBlockWithPattern(node.thenBlock, arch, kind)
        val elseIfExpanded = node.elseIfBranches.map {
            AstCondition.ElseIfBranch(
                condition = it.condition,
                body = expandBlockWithPattern(it.body, arch, kind)
            )
        }
        val elseExpanded = node.elseBlock?.let { expandBlockWithPattern(it, arch, kind) }
        return node.copy(
            thenBlock = thenExpanded,
            elseIfBranches = elseIfExpanded,
            elseBlock = elseExpanded
        )
    }

    /** Wraps the node in loops depending on the required expansion pattern. */
    private fun wrapWithPattern(node: AstNode, pattern: LoopPattern, arch: SnnArch): AstNode {
        if (pattern == LoopPattern.SCALAR || node is AstLoop) {
            return node
        }
        val preCount = arch.neuronsPerLayer.firstOrNull() ?: 0
        val postCount = when (arch.connectivity) {
            ConnectivityType.FULLY_CONNECTED -> arch.neuronsPerLayer.getOrNull(1) ?: 0
            else -> arch.neuronsPerLayer.lastOrNull() ?: 0
        }

        fun wrapSingle(count: Int, iterator: String, kind: LoopKind, description: String): AstNode {
            if (count <= 0) return node
            val body = AstBlock(node.origin, listOf(node))
            return AstLoop(
                origin = node.origin,
                descriptor = LoopDescriptor(iterator, count, kind, description),
                body = body
            )
        }

        return when (pattern) {
            LoopPattern.DOUBLE -> {
                if (preCount <= 0 || postCount <= 0) return node
                val inner = wrapSingle(postCount, "postNeuron", LoopKind.POSTSYNAPTIC, "iterate post-synaptic neurons") as AstLoop
                val innerBlock = AstBlock(node.origin, listOf(inner))
                AstLoop(
                    origin = node.origin,
                    descriptor = LoopDescriptor("preNeuron", preCount, LoopKind.PRESYNAPTIC, "iterate pre-synaptic neurons"),
                    body = innerBlock
                )
            }

            LoopPattern.PRE_ONLY -> wrapSingle(preCount, "preNeuron", LoopKind.PRESYNAPTIC, "iterate pre-synaptic neurons")
            LoopPattern.POST_ONLY -> wrapSingle(postCount, "postNeuron", LoopKind.POSTSYNAPTIC, "iterate post-synaptic neurons")
            LoopPattern.SCALAR -> node
        }
    }

    /** Gathers the types referenced by the node to drive loop classification. */
    private fun collectFieldTypes(node: AstNode): Set<TxFieldType> = when (node) {
        is AstOperation -> {
            val result = mutableSetOf<TxFieldType>()
            node.target?.let { result += it.type }
            node.operands.mapNotNullTo(result) { operandType(it) }
            result
        }
        is AstCondition -> {
            val result = mutableSetOf<TxFieldType>()
            operandType(node.condition.left)?.let { result += it }
            operandType(node.condition.right)?.let { result += it }
            result += collectFieldTypes(node.thenBlock)
            node.elseIfBranches.forEach { branch -> result += collectFieldTypes(branch.body) }
            node.elseBlock?.let { result += collectFieldTypes(it) }
            result
        }
        is AstLoop -> collectFieldTypes(node.body)
        is AstPhaseBlock -> collectFieldTypes(node.body)
    }

    /** Collects field types referenced inside a block. */
    private fun collectFieldTypes(block: AstBlock): Set<TxFieldType> {
        val result = mutableSetOf<TxFieldType>()
        block.statements.forEach { result += collectFieldTypes(it) }
        return result
    }

    /** Classifies a node belonging to the spike transaction. */
    private fun classifySynapticPattern(node: AstNode): LoopPattern {
        if (node is AstOperation && node.kind == OperationKind.EMIT) {
            return LoopPattern.SCALAR
        }
        val types = collectFieldTypes(node)
        val hasSynaptic = TxFieldType.SYNAPTIC_PARAM in types
        val hasDynamic = TxFieldType.DYNAMIC in types
        val hasLocal = TxFieldType.LOCAL in types

        return when {
            hasDynamic && hasSynaptic -> LoopPattern.DOUBLE
            hasDynamic && hasLocal -> LoopPattern.POST_ONLY
            hasSynaptic && hasLocal -> LoopPattern.PRE_ONLY
            else -> LoopPattern.SCALAR
        }
    }

    /** Classifies a node belonging to neuron transactions. */
    private fun classifyNeuronPattern(node: AstNode): LoopPattern {
        if (node is AstOperation && node.kind == OperationKind.EMIT) {
            return LoopPattern.SCALAR
        }
        val types = collectFieldTypes(node)
        return if (TxFieldType.DYNAMIC in types) LoopPattern.POST_ONLY else LoopPattern.SCALAR
    }

    /** Helper used to extract the type of an operand if available. */
    private fun operandType(operand: TxOperand): TxFieldType? = when (operand) {
        is TxOperand.Constant -> null
        is TxOperand.FieldRef -> operand.field.type
        is TxOperand.ExternalFieldRef -> operand.type
        is TxOperand.EmitReference -> null
    }

    /** Dumps a block with indentation. */
    private fun dumpBlock(block: AstBlock, out: StringBuilder, indent: Int) {
        val pad = "  ".repeat(indent)
        out.appendLine("${pad}Block(origin=${block.origin})")
        for (statement in block.statements) {
            dumpNode(statement, out, indent + 1)
        }
    }

    /** Dumps a single AST node. */
    private fun dumpNode(node: AstNode, out: StringBuilder, indent: Int) {
        val pad = "  ".repeat(indent)
        when (node) {
            is AstOperation -> {
                val target = node.target?.name ?: "-"
                val operands = node.operands.joinToString { operandToString(it) }
                out.appendLine("${pad}Operation(kind=${node.kind}, target=$target, operands=[$operands])")
            }
            is AstCondition -> {
                out.appendLine("${pad}If(origin=${node.origin}) condition=${conditionToString(node.condition)}")
                dumpBlock(node.thenBlock, out, indent + 1)
                node.elseIfBranches.forEach { branch ->
                    out.appendLine("${pad}else-if ${conditionToString(branch.condition)}")
                    dumpBlock(branch.body, out, indent + 1)
                }
                node.elseBlock?.let {
                    out.appendLine("${pad}else")
                    dumpBlock(it, out, indent + 1)
                }
            }
            is AstLoop -> {
                out.appendLine("${pad}Loop(${node.descriptor.iterator}, count=${node.descriptor.count}, kind=${node.descriptor.kind})")
                dumpBlock(node.body, out, indent + 1)
            }
            is AstPhaseBlock -> {
                out.appendLine("${pad}Phase(${node.phase})")
                dumpBlock(node.body, out, indent + 1)
            }
        }
    }

    /** Formats a transaction operand for dumping. */
    private fun operandToString(operand: TxOperand): String = when (operand) {
        is TxOperand.Constant -> operand.value.toString()
        is TxOperand.FieldRef -> operand.field.name
        is TxOperand.ExternalFieldRef -> "${operand.name}:${operand.type}"
        is TxOperand.EmitReference -> "emit#${operand.emitId}"
    }

    /** Formats a condition for dumping. */
    private fun conditionToString(condition: TxCondition): String {
        val cmp = when (condition.comparison) {
            ComparisonOp.EQ -> "=="
            ComparisonOp.NEQ -> "!="
            ComparisonOp.LT -> "<"
            ComparisonOp.LTE -> "<="
            ComparisonOp.GT -> ">"
            ComparisonOp.GTE -> ">="
        }
        return "${operandToString(condition.left)} $cmp ${operandToString(condition.right)}"
    }

    /** Splits a node into emission and refractory components. */
    private fun extractEmission(node: AstNode): EmissionSplit = when (node) {
        is AstOperation -> {
            require(node.kind == OperationKind.EMIT) { "Expected emit operation during emission split" }
            EmissionSplit(listOf(node), emptyList())
        }
        is AstCondition -> splitConditionNode(node)
        is AstLoop -> splitLoopNode(node)
        is AstPhaseBlock -> error("Phase blocks are not expected during emission extraction")
    }

    /** Splits a loop node by recursively partitioning its body. */
    private fun splitLoopNode(loop: AstLoop): EmissionSplit {
        val splitBody = splitBlock(loop.body)
        val emissionNodes = mutableListOf<AstNode>()
        val refractoryNodes = mutableListOf<AstNode>()

        if (splitBody.emission.isNotEmpty()) {
            emissionNodes += loop.copy(body = loop.body.copy(statements = splitBody.emission))
        }
        if (splitBody.refractory.isNotEmpty()) {
            refractoryNodes += loop.copy(body = loop.body.copy(statements = splitBody.refractory))
        }

        return EmissionSplit(emissionNodes, refractoryNodes)
    }

    /** Splits a condition node around emit statements appearing in its branches. */
    private fun splitConditionNode(condition: AstCondition): EmissionSplit {
        val thenSplit = splitBlock(condition.thenBlock)
        val emissionElseIfs = mutableListOf<AstCondition.ElseIfBranch>()
        val refractoryElseIfs = mutableListOf<AstCondition.ElseIfBranch>()

        condition.elseIfBranches.forEach { branch ->
            val branchSplit = splitBlock(branch.body)
            if (branchSplit.emission.isNotEmpty()) {
                emissionElseIfs += AstCondition.ElseIfBranch(
                    condition = branch.condition,
                    body = branch.body.copy(statements = branchSplit.emission)
                )
            }
            if (branchSplit.refractory.isNotEmpty()) {
                refractoryElseIfs += AstCondition.ElseIfBranch(
                    condition = branch.condition,
                    body = branch.body.copy(statements = branchSplit.refractory)
                )
            }
        }

        val elseSplit = condition.elseBlock?.let { splitBlock(it) }
        val emissionElseBlock = elseSplit?.let {
            if (it.emission.isNotEmpty()) condition.elseBlock.copy(statements = it.emission) else null
        }
        val refractoryElseBlock = elseSplit?.let {
            if (it.refractory.isNotEmpty()) condition.elseBlock.copy(statements = it.refractory) else null
        }

        val emissionNodes = mutableListOf<AstNode>()
        if (thenSplit.emission.isNotEmpty() || emissionElseIfs.isNotEmpty() || emissionElseBlock != null) {
            emissionNodes += AstCondition(
                origin = condition.origin,
                condition = condition.condition,
                thenBlock = condition.thenBlock.copy(statements = thenSplit.emission),
                elseIfBranches = emissionElseIfs,
                elseBlock = emissionElseBlock
            )
        }

        val refractoryNodes = mutableListOf<AstNode>()
        if (thenSplit.refractory.isNotEmpty() || refractoryElseIfs.isNotEmpty() || refractoryElseBlock != null) {
            refractoryNodes += AstCondition(
                origin = condition.origin,
                condition = condition.condition,
                thenBlock = condition.thenBlock.copy(statements = thenSplit.refractory),
                elseIfBranches = refractoryElseIfs,
                elseBlock = refractoryElseBlock
            )
        }

        return EmissionSplit(emissionNodes, refractoryNodes)
    }

    /** Sequentially splits a block into emission-prefixed and refractory-suffix parts. */
    private fun splitBlock(block: AstBlock): SplitResult {
        val emissionStatements = mutableListOf<AstNode>()
        val refractoryStatements = mutableListOf<AstNode>()
        var emissionActive = true
        var foundEmit = false

        for (statement in block.statements) {
            val hasEmit = containsEmit(statement)
            if (hasEmit) {
                foundEmit = true
            }

            if (emissionActive) {
                if (hasEmit) {
                    val split = extractEmission(statement)
                    emissionStatements += split.emission
                    refractoryStatements += split.refractory
                    emissionActive = false
                } else {
                    emissionStatements += statement
                }
            } else {
                if (hasEmit) {
                    val split = extractEmission(statement)
                    emissionStatements += split.emission
                    refractoryStatements += split.refractory
                } else {
                    refractoryStatements += statement
                }
            }
        }

        if (!foundEmit) {
            return SplitResult(emptyList(), block.statements)
        }

        return SplitResult(emissionStatements, refractoryStatements)
    }

    /** Describes the three segments extracted from neuron statements. */
    private data class NeuronSegments(
        val somatic: List<AstNode>,
        val emission: List<AstNode>,
        val refractory: List<AstNode>
    )

    /** Result of splitting a node into emission and refractory statements. */
    private data class EmissionSplit(
        val emission: List<AstNode>,
        val refractory: List<AstNode>
    )

    /** Result of splitting a block into emission-prefixed and refractory-suffix statements. */
    private data class SplitResult(
        val emission: List<AstNode>,
        val refractory: List<AstNode>
    )

    /** Loop patterns used during expansion. */
    private enum class LoopPattern {
        DOUBLE,
        PRE_ONLY,
        POST_ONLY,
        SCALAR
    }
}
