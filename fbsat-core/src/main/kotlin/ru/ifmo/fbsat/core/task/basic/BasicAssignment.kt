package ru.ifmo.fbsat.core.task.basic

import ru.ifmo.fbsat.core.automaton.Algorithm
import ru.ifmo.fbsat.core.automaton.Automaton
import ru.ifmo.fbsat.core.automaton.BinaryAlgorithm
import ru.ifmo.fbsat.core.automaton.TruthTableGuard
import ru.ifmo.fbsat.core.scenario.positive.ScenarioTree
import ru.ifmo.fbsat.core.solver.RawAssignment
import ru.ifmo.multiarray.BooleanMultiArray
import ru.ifmo.multiarray.IntMultiArray
import ru.ifmo.multiarray.MultiArray

internal class BasicAssignment(
    val scenarioTree: ScenarioTree,
    val C: Int,
    val K: Int,
    val color: IntMultiArray, // [V] : 1..C
    val transition: IntMultiArray, // [C, K] : 0..C
    val actualTransition: IntMultiArray, // [C, E, U] : 0..C
    val inputEvent: IntMultiArray, // [C, K] : 0..E
    val outputEvent: IntMultiArray, // [C] : 1..O
    val algorithm: MultiArray<Algorithm>, // [C]: Algorithm
    val rootValue: BooleanMultiArray, // [C, K, U] : Boolean
    val firstFired: IntMultiArray, // [C, U] : 0..K
    val notFired: BooleanMultiArray // [C, U, K] : Boolean
) {
    @Suppress("PropertyName")
    val T: Int = transition.values.count { it != 0 }

    companion object {
        @Suppress("LocalVariableName")
        fun fromRaw(raw: RawAssignment): BasicAssignment {
            // Constants
            val scenarioTree: ScenarioTree by raw
            val C: Int by raw
            val K: Int by raw
            val V: Int by raw
            val E: Int by raw
            val O: Int by raw
            val U: Int by raw
            val X: Int by raw
            val Z: Int by raw
            // Reduction variables
            val transition: IntMultiArray by raw
            val actualTransition: IntMultiArray by raw
            val inputEvent: IntMultiArray by raw
            val outputEvent: IntMultiArray by raw
            val algorithm0: IntMultiArray by raw
            val algorithm1: IntMultiArray by raw
            val color: IntMultiArray by raw
            val rootValue: IntMultiArray by raw
            val firstFired: IntMultiArray by raw
            val notFired: IntMultiArray by raw

            return BasicAssignment(
                scenarioTree = scenarioTree,
                C = C,
                K = K,
                color = raw.intArrayOf(color, V, domain = 1..C) {
                    error("color[index = $it] is undefined")
                },
                transition = raw.intArrayOf(transition, C, K, domain = 1..C) { 0 },
                actualTransition = raw.intArrayOf(actualTransition, C, E, U, domain = 1..C) { 0 },
                inputEvent = raw.intArrayOf(inputEvent, C, K, domain = 1..E) { 0 },
                outputEvent = raw.intArrayOf(outputEvent, C, domain = 1..O) {
                    error("outputEvent[index = $it] is undefined")
                },
                algorithm = MultiArray.new<Algorithm>(C) { (c) ->
                    BinaryAlgorithm(
                        // Note: c is 1-based, z is 0-based
                        algorithm0 = BooleanArray(Z) { z -> raw[algorithm0[c, z + 1]] },
                        algorithm1 = BooleanArray(Z) { z -> raw[algorithm1[c, z + 1]] }
                    )
                },
                rootValue = raw.booleanArrayOf(rootValue, C, K, U),
                firstFired = raw.intArrayOf(firstFired, C, U, domain = 1..K) { 0 },
                notFired = raw.booleanArrayOf(notFired, C, U, K)
            )
        }
    }
}

@Suppress("LocalVariableName")
internal fun BasicAssignment.toAutomaton(): Automaton {
    val automaton = Automaton(scenarioTree)

    val C = C
    val K = K

    for (c in 1..C) {
        automaton.addState(
            c,
            scenarioTree.outputEvents[outputEvent[c] - 1],
            algorithm[c]
        )
    }

    for (c in 1..C)
        for (k in 1..K)
            if (transition[c, k] != 0)
                automaton.addTransition(
                    sourceId = c,
                    destinationId = transition[c, k],
                    inputEvent = scenarioTree.inputEvents[inputEvent[c, k] - 1],
                    guard = TruthTableGuard(
                        truthTable = (1..scenarioTree.uniqueInputs.size)
                            .asSequence()
                            .associateWith { u ->
                                when {
                                    notFired[c, u, k] -> false
                                    firstFired[c, u] == k -> true
                                    else -> null
                                }
                            }
                            .mapKeys { (u, _) ->
                                scenarioTree.uniqueInputs[u - 1]
                            },
                        inputNames = scenarioTree.inputNames,
                        uniqueInputs = scenarioTree.uniqueInputs
                    )
                )

    return automaton
}
