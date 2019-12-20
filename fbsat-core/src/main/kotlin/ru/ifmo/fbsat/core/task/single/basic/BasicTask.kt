package ru.ifmo.fbsat.core.task.single.basic

import com.soywiz.klock.DateTime
import ru.ifmo.fbsat.core.automaton.Automaton
import ru.ifmo.fbsat.core.constraints.declareAutomatonBfsConstraints
import ru.ifmo.fbsat.core.constraints.declareAutomatonStructureConstraints
import ru.ifmo.fbsat.core.constraints.declarePositiveMappingConstraints
import ru.ifmo.fbsat.core.scenario.positive.ScenarioTree
import ru.ifmo.fbsat.core.solver.Solver
import ru.ifmo.fbsat.core.solver.declareComparatorLessThanOrEqual
import ru.ifmo.fbsat.core.solver.declareTotalizer
import ru.ifmo.fbsat.core.utils.Globals
import ru.ifmo.fbsat.core.utils.checkMapping
import ru.ifmo.fbsat.core.utils.log
import ru.ifmo.fbsat.core.utils.secondsSince
import java.io.File

@Suppress("LocalVariableName")
class BasicTask(
    scenarioTree: ScenarioTree,
    numberOfStates: Int, // C
    maxOutgoingTransitions: Int? = null, // K, =C if null
    maxTransitions: Int? = null, // T, unconstrained if null
    val outDir: File,
    val solver: Solver,
    val autoFinalize: Boolean = true,
    isEncodeReverseImplication: Boolean = true
) {
    val vars: BasicVariables

    init {
        val timeStart = DateTime.nowLocal()
        val nvarStart = solver.numberOfVariables
        val nconStart = solver.numberOfClauses

        with(solver) {
            /* Variables */
            vars = declareBasicVariables(
                scenarioTree,
                C = numberOfStates,
                K = maxOutgoingTransitions ?: numberOfStates
            )

            /* Constraints */
            declareAutomatonStructureConstraints(vars)
            if (Globals.IS_BFS_AUTOMATON) declareAutomatonBfsConstraints(vars)
            declarePositiveMappingConstraints(vars, isEncodeReverseImplication = isEncodeReverseImplication)
            declareAdhocConstraints()
        }

        /* Initial cardinality constraints */
        updateCardinality(maxTransitions)

        val nvarDiff = solver.numberOfVariables - nvarStart
        val nconDiff = solver.numberOfClauses - nconStart
        log.info(
            "BasicTask: Done declaring variables ($nvarDiff) and constraints ($nconDiff) in %.2f s"
                .format(secondsSince(timeStart))
        )
    }

    private fun Solver.declareAdhocConstraints() {
        comment("ADHOC constraints")
        with(vars) {
            if (Globals.IS_FORBID_TRANSITIONS_TO_FIRST_STATE) {
                comment("Ad-hoc: no transition to the first state")
                for (c in 1..C)
                    for (k in 1..K)
                        clause(-transitionDestination[c, k, 1])
            }
        }
    }

    fun updateCardinality(newMaxTransitions: Int?) {
        with(solver) {
            with(vars) {
                maxTransitions?.let { T ->
                    check(newMaxTransitions != null && newMaxTransitions <= T) { "Cannot soften UB" }
                }

                if (newMaxTransitions == null && !Globals.IS_ENCODE_TOTALIZER) return
                if (totalizer == null) {
                    totalizer = declareTotalizer {
                        for (c in 1..C)
                            for (k in 1..K)
                                yield(-transitionDestination[c, k, C + 1])
                    }
                }
                if (newMaxTransitions == null) return

                declareComparatorLessThanOrEqual(totalizer!!, newMaxTransitions, maxTransitions)
                maxTransitions = newMaxTransitions
            }
        }
    }

    fun infer(): Automaton? {
        val rawAssignment = solver.solve()?.data
        if (autoFinalize) finalize2()
        if (rawAssignment == null) return null

        val assignment = BasicAssignment.fromRaw(rawAssignment, vars)
        val automaton = assignment.toAutomaton()

        with(vars) {
            check(
                automaton.checkMapping(
                    scenarios = scenarioTree.scenarios,
                    mapping = assignment.mapping
                )
            ) { "Positive mapping mismatch" }
        }

        return automaton
    }

    fun finalize2() {
        solver.finalize2()
    }
}
