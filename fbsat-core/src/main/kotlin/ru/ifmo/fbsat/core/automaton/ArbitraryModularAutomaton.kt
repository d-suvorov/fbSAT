package ru.ifmo.fbsat.core.automaton

import com.github.lipen.multiarray.BooleanMultiArray
import com.github.lipen.multiarray.MultiArray
import ru.ifmo.fbsat.core.scenario.InputAction
import ru.ifmo.fbsat.core.scenario.OutputAction
import ru.ifmo.fbsat.core.scenario.positive.PositiveScenario
import ru.ifmo.fbsat.core.scenario.positive.ScenarioTree
import ru.ifmo.fbsat.core.task.modular.basic.arbitrary.PinVars
import ru.ifmo.fbsat.core.utils.mapValues

class ArbitraryModularAutomaton(
    val modules: MultiArray<Automaton>,
    val inboundVarPinParent: MultiArray<Int>,
    val inputEvents: List<InputEvent>,
    val outputEvents: List<OutputEvent>,
    val inputNames: List<String>,
    val outputNames: List<String>
) {
    /** Number of modules */
    @Suppress("PropertyName")
    val M: Int = modules.shape[0]
    val numberOfTransitions: Int = modules.sumBy { it.numberOfTransitions }
    val totalGuardsSize: Int = modules.sumBy { it.totalGuardsSize }

    constructor(
        modules: MultiArray<Automaton>,
        inboundVarPinParent: MultiArray<Int>,
        scenarioTree: ScenarioTree
    ) : this(
        modules,
        inboundVarPinParent,
        scenarioTree.inputEvents,
        scenarioTree.outputEvents,
        scenarioTree.inputNames,
        scenarioTree.outputNames
    )

    init {
        require(M >= 2)
    }

    // fun eval(
    //     inputAction: InputAction,
    //     modularState: MultiArray<Automaton.State>,
    //     modularOutputValues: MultiArray<OutputValues>
    // ): MultiArray<Automaton.EvalResult> {
    //     TODO()
    // }

    inner class EvalResult(
        val modularDestination: MultiArray<Automaton.State>,
        val modularOutputActions: MultiArray<OutputAction>,
        val outputAction: OutputAction
    )

    @Suppress("LocalVariableName")
    fun eval(inputActions: Sequence<InputAction>): Sequence<EvalResult> {
        val E = inputEvents.size
        val O = outputEvents.size
        val X = inputNames.size
        val Z = outputNames.size

        with(PinVars(M, X, Z, E, O)) {
            val currentModularState = modules.mapValues { it.initialState }
            val currentModularOutputValues = modules.mapValues { OutputValues.zeros(it.outputNames.size) }
            val currentInboundVarPinComputedValue = BooleanMultiArray.create(allInboundVarPins.size)
            val currentOutboundVarPinComputedValue = BooleanMultiArray.create(allOutboundVarPins.size)

            return inputActions.map { inputAction ->
                for (x in 1..X) {
                    val pin = externalOutboundVarPins[x - 1]
                    currentOutboundVarPinComputedValue[pin] = inputAction.values[x - 1]
                }

                for (m in 1..M) {
                    // update inbound pins
                    for (pin in modularInboundVarPins[m]) {
                        val parent = inboundVarPinParent[pin]
                        if (parent != 0) {
                            currentInboundVarPinComputedValue[pin] = currentOutboundVarPinComputedValue[parent]
                        }
                    }

                    // eval module
                    val inputValues =
                        InputValues(modularInboundVarPins[m].map { currentInboundVarPinComputedValue[it] })
                    val result = modules[m].eval(
                        inputAction = InputAction(InputEvent("REQ"), inputValues),
                        state = currentModularState[m],
                        values = currentModularOutputValues[m]
                    )

                    // save new state and output values (modular)
                    currentModularState[m] = result.destination
                    currentModularOutputValues[m] = result.outputAction.values

                    // update outbound pins
                    for (z in 1..Z) {
                        val pin = modularOutboundVarPins[m][z - 1]
                        currentOutboundVarPinComputedValue[pin] = currentModularOutputValues[m][z - 1]
                    }
                }

                // update external inbound var pins
                for (pin in externalInboundVarPins) {
                    val parent = inboundVarPinParent[pin]
                    if (parent != 0) {
                        currentInboundVarPinComputedValue[pin] = currentOutboundVarPinComputedValue[parent]
                    }
                }

                // save output values (composite)
                val outputValues = OutputValues(externalInboundVarPins.map { currentInboundVarPinComputedValue[it] })

                EvalResult(
                    currentModularState.mapValues { it },
                    currentModularOutputValues.mapValues { OutputAction(OutputEvent("CNF"), it) },
                    OutputAction(OutputEvent("CNF"), outputValues)
                )
            }
        }
    }

    @Suppress("LocalVariableName")
    fun verify(scenario: PositiveScenario): Boolean {
        TODO()
    }

    fun verify(scenarioTree: ScenarioTree): Boolean =
        scenarioTree.scenarios.all(::verify)
}