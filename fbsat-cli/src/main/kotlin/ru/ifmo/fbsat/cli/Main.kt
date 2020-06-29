package ru.ifmo.fbsat.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.output.CliktHelpFormatter
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.switch
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.soywiz.klock.DateTime
import com.soywiz.klock.PerformanceCounter
import com.soywiz.klock.measureTime
import ru.ifmo.fbsat.core.automaton.Automaton
import ru.ifmo.fbsat.core.automaton.OutputValues
import ru.ifmo.fbsat.core.automaton.minimizeTruthTableGuards
import ru.ifmo.fbsat.core.scenario.negative.Counterexample
import ru.ifmo.fbsat.core.scenario.negative.NegativeScenario
import ru.ifmo.fbsat.core.scenario.negative.NegativeScenarioTree
import ru.ifmo.fbsat.core.scenario.positive.ScenarioTree
import ru.ifmo.fbsat.core.solver.Solver
import ru.ifmo.fbsat.core.task.Inferrer
import ru.ifmo.fbsat.core.task.modular.basic.arbitrary.arbitraryModularBasic
import ru.ifmo.fbsat.core.task.modular.basic.arbitrary.arbitraryModularBasicMin
import ru.ifmo.fbsat.core.task.modular.basic.consecutive.consecutiveModularBasic
import ru.ifmo.fbsat.core.task.modular.basic.consecutive.consecutiveModularBasicMin
import ru.ifmo.fbsat.core.task.modular.basic.parallel.parallelModularBasic
import ru.ifmo.fbsat.core.task.modular.basic.parallel.parallelModularBasicMin
import ru.ifmo.fbsat.core.task.modular.basic.parallel.parallelModularBasicMinC
import ru.ifmo.fbsat.core.task.modular.extended.consecutive.consecutiveModularExtended
import ru.ifmo.fbsat.core.task.modular.extended.consecutive.consecutiveModularExtendedMin
import ru.ifmo.fbsat.core.task.modular.extended.parallel.parallelModularExtended
import ru.ifmo.fbsat.core.task.modular.extended.parallel.parallelModularExtendedMin
import ru.ifmo.fbsat.core.task.single.basic.basic
import ru.ifmo.fbsat.core.task.single.basic.basicMin
import ru.ifmo.fbsat.core.task.single.basic.basicMinC
import ru.ifmo.fbsat.core.task.single.complete.cegis
import ru.ifmo.fbsat.core.task.single.complete.cegisMin
import ru.ifmo.fbsat.core.task.single.complete.complete
import ru.ifmo.fbsat.core.task.single.complete.completeMin
import ru.ifmo.fbsat.core.task.single.extended.extended
import ru.ifmo.fbsat.core.task.single.extended.extendedMin
import ru.ifmo.fbsat.core.task.single.extended.extendedMinUB
import ru.ifmo.fbsat.core.task.single.extforest.extForest
import ru.ifmo.fbsat.core.task.single.extforest.extForestMin
import ru.ifmo.fbsat.core.utils.*
import java.io.File

enum class Method(val s: String) {
    Basic("basic"),
    BasicMin("basic-min"),
    Extended("extended"),
    ExtendedMin("extended-min"),
    ExtendedMinUB("extended-min-ub"),
    ExtForest("extforest"),
    ExtForestMin("extforest-min"),
    Complete("complete"),
    CompleteMin("complete-min"),
    Cegis("cegis"),
    CegisMin("cegis-min"),
    ParallelModularBasic("modular-parallel-basic"),
    ParallelModularBasicMin("modular-parallel-basic-min"),
    ParallelModularExtended("modular-parallel-extended"),
    ParallelModularExtendedMin("modular-parallel-extended-min"),
    ParallelModularExtendedMinUB("modular-parallel-extended-min-ub"),
    ConsecutiveModularBasic("modular-consecutive-basic"),
    ConsecutiveModularBasicMin("modular-consecutive-basic-min"),
    ConsecutiveModularExtended("modular-consecutive-extended"),
    ConsecutiveModularExtendedMin("modular-consecutive-extended-min"),
    ConsecutiveModularExtendedMinUB("modular-consecutive-extended-min-ub"),
    ArbitraryModularBasic("modular-arbitrary-basic"),
    ArbitraryModularBasicMin("modular-arbitrary-basic-min"),
    Distributed("distributed")
}

@Suppress("MemberVisibilityCanBePrivate")
class FbSAT : CliktCommand() {
    val fileScenarios: String by option(
        "-i", "--scenarios",
        help = "File with scenarios",
        metavar = "<path>"
    ).required()

    val fileCounterexamples: String? by option(
        "-ce", "--counterexamples",
        help = "File with counter-examples",
        metavar = "<path>"
    )

    val smvDir: File by option(
        "--smvdir",
        help = "Directory with SMV files",
        metavar = "<path>"
    ).file(
        mustExist = true,
        canBeFile = false
    ).default(
        File("data/pnp/smv")
    )

    val outDir: File by option(
        "-o", "--outdir",
        help = "Output directory",
        metavar = "<path>"
    ).file().defaultLazy {
        val now = DateTime.nowLocal().format("yyyy-MM-dd_HH-mm-ss")
        File("out/$now")
    }

    val fileInputNames: File? by option(
        "--input-names",
        help = "File with input names [defaults to PnP names]"
    ).file(
        mustExist = true,
        canBeDir = false,
        mustBeReadable = true
    )

    val fileOutputNames: File? by option(
        "--output-names",
        help = "File with output names [defaults to PnP names]"
    ).file(
        mustExist = true,
        canBeDir = false,
        mustBeReadable = true
    )

    val method: Method by option(
        "-m", "--method",
        help = "Method to use",
        metavar = "<method>"
    ).choice(
        Method.values().associateBy { it.s }
    ).required()

    val numberOfStates: Int? by option(
        "-C",
        help = "Number of automaton states",
        metavar = "<int>"
    ).int()

    val maxOutgoingTransitions: Int? by option(
        "-K",
        help = "Maximum number of transitions from each state",
        metavar = "<int>"
    ).int()

    val maxGuardSize: Int? by option(
        "-P",
        help = "Maximum guard size (number of parse tree nodes)",
        metavar = "<int>"
    ).int()

    val maxTransitions: Int? by option(
        "-T",
        help = "Upper bound for the total number of transitions",
        metavar = "<int>"
    ).int()

    val maxTotalGuardsSize: Int? by option(
        "-N",
        help = "Upper bound for the total size of guards",
        metavar = "<int>"
    ).int()

    val maxPlateauWidth: Int? by option(
        "-w",
        help = "Maximum plateau width",
        metavar = "<int>"
    ).int()

    val numberOfModules: Int? by option(
        "-M",
        help = "Number of modules",
        metavar = "<int>"
    ).int()

    val solverCmd: String by option(
        "--solver",
        help = "SAT-solver for FileSolver backend (use %s placeholder for filename)",
        metavar = "<cmd>"
    ).default(
        "cadical %s"
    )

    val solverBackend: SolverBackend by option(
        help = "Solver backend (default: IncrementalSolver)"
    ).switch(
        "--icms" to SolverBackend.INCREMENTAL_CRYPTOMINISAT,
        "--filesolver" to SolverBackend.FILE,
        "--minisat" to SolverBackend.MINISAT,
        "--cadical" to SolverBackend.CADICAL
    ).default(
        SolverBackend.INCREMENTAL_CRYPTOMINISAT
    )

    val isForbidOr: Boolean by option(
        "--forbid-or"
    ).flag(
        "--allow-or",
        default = Globals.IS_FORBID_OR
    )

    val isForbidTransitionsToFirstState: Boolean by option(
        "--forbid-transitions-to-first-state"
    ).flag(
        "--allow-transitions-to-first-state",
        default = Globals.IS_FORBID_TRANSITIONS_TO_FIRST_STATE
    )

    val isBfsAutomaton: Boolean by option(
        "--bfs-automaton"
    ).flag(
        "--no-bfs-automaton",
        default = Globals.IS_BFS_AUTOMATON
    )

    val isBfsGuard: Boolean by option(
        "--bfs-guard"
    ).flag(
        "--no-bfs-guard",
        default = Globals.IS_BFS_GUARD
    )

    val isOnlyC: Boolean by option(
        "--only-C",
        help = "[basic-min] Minimize only C, without T"
    ).flag()

    val failIfSTVerifyFailed: Boolean by option(
        "--fail-verify-st",
        help = "Halt if verification of scenario tree has failed"
    ).flag(
        "--no-fail-verify-st",
        default = true
    )

    val failIfCEVerifyFailed: Boolean by option(
        "--fail-verify-ce",
        help = "Halt if verification of negative scenarios has failed"
    ).flag(
        "--no-fail-verify-ce",
        default = true
    )

    val fileVis: File? by option(
        "--vis",
        help = "[DEBUG] Visualize given counterexamples via graphviz"
    ).file(
        mustExist = true,
        canBeDir = false,
        mustBeReadable = true
    )

    val initialOutputValues: OutputValues? by option(
        "--initial-output-values",
        help = "Initial output values (as a bitstring)",
        metavar = "<[01]+>"
    ).convert {
        require(it.matches(Regex("[01]+"))) {
            "--initial-output-values must match [01]+"
        }
        OutputValues(it.map { c -> c == '1' })
    }

    val epsilonOutputEvents: EpsilonOutputEvents by option(
        "--epsilon-output-events",
        help = "Epsilon output events"
    ).choice(
        "start" to EpsilonOutputEvents.START,
        "onlystart" to EpsilonOutputEvents.ONLYSTART,
        "none" to EpsilonOutputEvents.NONE
    ).default(
        Globals.EPSILON_OUTPUT_EVENTS
    )

    val startStateAlgorithms: StartStateAlgorithms by option(
        "--start-state-algorithms",
        help = "Start state algorithms"
    ).choice(
        "nothing" to StartStateAlgorithms.NOTHING,
        "zero" to StartStateAlgorithms.ZERO,
        "zeronothing" to StartStateAlgorithms.ZERONOTHING,
        "any" to StartStateAlgorithms.ANY,
        "init" to StartStateAlgorithms.INIT,
        "initnothing" to StartStateAlgorithms.INITNOTHING
    ).default(
        Globals.START_STATE_ALGORITHMS
    )

    val isEncodeReverseImplication: Boolean by option(
        "--encode-reverse-implication",
        help = "Encode reverse implication"
    ).flag(
        "--no-encode-reverse-implication",
        default = true
    )

    val isEncodeTransitionsOrder: Boolean by option(
        "--encode-transitions-order",
        help = "[DEBUG] Encode transitions lexicographic order"
    ).flag(
        "--no-encode-transitions-order",
        default = Globals.IS_ENCODE_TRANSITIONS_ORDER
    )

    val isEncodeTerminalsOrder: Boolean by option(
        "--encode-terminals-order",
        help = "[DEBUG] Encode terminal numbers lexicographic order"
    ).flag(
        "--no-encode-terminals-order",
        default = Globals.IS_ENCODE_TERMINALS_ORDER
    )

    val isEncodeTerminalsMiniOrder: Boolean by option(
        "--encode-terminals-mini-order",
        help = "[DEBUG] Encode AND/OR children-terminals order"
    ).flag(
        "--no-encode-terminals-mini-order",
        default = Globals.IS_ENCODE_TERMINALS_MINI_ORDER
    )

    val isEncodeHardToExplain: Boolean by option(
        "--encode-hard-to-explain",
        help = "[DEBUG] Encode some hard to explain thing"
    ).flag(
        "--no-encode-hard-to-explain",
        default = Globals.IS_ENCODE_HARD_TO_EXPLAIN
    )

    val isEncodeTotalizer: Boolean by option(
        "--encode-totalizer",
        help = "Encode totalizer when upper bound is null"
    ).flag(
        "--no-encode-totalizer",
        default = Globals.IS_ENCODE_TOTALIZER
    )

    val isEncodeDisjunctiveTransitions: Boolean by option(
        "--encode-disjunctive-transitions",
        help = "Encode disjunctive transitions (adhocly forbid priority function)"
    ).flag(
        "--no-encode-disjunctive-transitions",
        default = Globals.IS_ENCODE_DISJUNCTIVE_TRANSITIONS
    )

    val isReuseK: Boolean by option(
        "--reuse-k",
        help = "Reuse K found by ExtendedMinTask during CEGIS"
    ).flag(
        "--no-reuse-k",
        default = Globals.IS_REUSE_K
    )

    val isDumpVarsInCnf: Boolean by option(
        "--dump-vars-in-cnf",
        help = "Dump variables in CNF"
    ).flag(
        "--no-dump-vars-in-cnf",
        default = Globals.IS_DUMP_VARS_IN_CNF
    )

    val fileVerifyCE: File? by option(
        "--verify-ce"
    ).file()

    val isDebug: Boolean by option(
        "--debug",
        help = "Debug mode"
    ).flag(
        default = Globals.IS_DEBUG
    )

    init {
        context {
            helpFormatter = CliktHelpFormatter(
                maxWidth = 999,
                requiredOptionMarker = "*",
                showDefaultValues = true,
                showRequiredTag = true
            )
        }
    }

    override fun run() {
        Globals.EPSILON_OUTPUT_EVENTS = epsilonOutputEvents
        Globals.START_STATE_ALGORITHMS = startStateAlgorithms
        Globals.IS_FORBID_OR = isForbidOr
        Globals.IS_FORBID_TRANSITIONS_TO_FIRST_STATE = isForbidTransitionsToFirstState
        Globals.IS_BFS_AUTOMATON = isBfsAutomaton
        Globals.IS_BFS_GUARD = isBfsGuard
        Globals.IS_ENCODE_TRANSITIONS_ORDER = isEncodeTransitionsOrder
        Globals.IS_ENCODE_TERMINALS_ORDER = isEncodeTerminalsOrder
        Globals.IS_ENCODE_TERMINALS_MINI_ORDER = isEncodeTerminalsMiniOrder
        Globals.IS_ENCODE_HARD_TO_EXPLAIN = isEncodeHardToExplain
        Globals.IS_ENCODE_TOTALIZER = isEncodeTotalizer
        Globals.IS_ENCODE_DISJUNCTIVE_TRANSITIONS = isEncodeDisjunctiveTransitions
        Globals.IS_REUSE_K = isReuseK
        Globals.IS_DUMP_VARS_IN_CNF = isDumpVarsInCnf
        Globals.IS_DEBUG = isDebug

        // outDir.deleteRecursively()
        // outDir.walkBottomUp().forEach { if (it != outDir) it.delete() }
        outDir.mkdirs()
        check(outDir.exists()) { "Output directory does not exist" }

        // FIXME should be a list as well
        // But it should work now
        val inputNames = fileInputNames?.readLines() ?: inputNamesPnP
        val outputNames = fileOutputNames?.readLines() ?: outputNamesPnP
        log.info("Input names: $inputNames")
        log.info("Output names: $outputNames")

        // FIXME should be a list as well
        // But it should work now
        Globals.INITIAL_OUTPUT_VALUES = initialOutputValues ?: OutputValues.zeros(outputNames.size)
        check(Globals.INITIAL_OUTPUT_VALUES.values.size == outputNames.size) {
            "Initial values size must be equal to the number of output variables"
        }

        val trees = fileScenarios.split(",").map {
            ScenarioTree.fromFile(File(it), inputNames, outputNames)
        }
        // val tree = ScenarioTree.fromFile(fileScenarios, inputNames, outputNames)
        /*log.info("Scenarios: ${tree.scenarios.size}")
        log.info("Elements: ${tree.scenarios.sumBy { it.elements.size }}")
        log.info("Scenario tree size: ${tree.size}")*/
        log.info("Scenario sources: ${trees.size}")
        for (i in trees.indices) {
            val tree = trees[i]
            log.info("Scenario source: $i")
            log.info("  Scenarios: ${tree.scenarios.size}")
            log.info("  Elements: ${tree.scenarios.sumBy { it.elements.size }}")
            log.info("  Scenario tree size: ${tree.size}")
        }

        val negTrees = if (fileCounterexamples != null)
            fileCounterexamples!!.split(",").withIndex().map { iv ->
                NegativeScenarioTree.fromFile(
                    File(iv.value),
                    trees[iv.index].inputEvents,
                    trees[iv.index].outputEvents,
                    trees[iv.index].inputNames,
                    trees[iv.index].outputNames
                )
            }
        else trees.indices.map {
            NegativeScenarioTree(
                inputEvents = trees[it].inputEvents,
                outputEvents = trees[it].outputEvents,
                inputNames = trees[it].inputNames,
                outputNames = trees[it].outputNames
            )
        }

        /*val negTree = fileCounterexamples?.let {
            NegativeScenarioTree.fromFile(
                it,
                tree.inputEvents,
                tree.outputEvents,
                tree.inputNames,
                tree.outputNames
            ).also { negTree ->
                log.info("Negative scenarios: ${negTree.negativeScenarios.size}")
                log.info("Negative elements: ${negTree.negativeScenarios.sumBy { it.elements.size }}")
            }
        }*/

        // ===
        fileVis?.let { file ->
            println("======================================")
            log.info("Visualizing <$file>...")
            val negST = NegativeScenarioTree.fromFile(
                file,
                // FIXME
                trees[0].inputEvents,
                trees[0].outputEvents,
                trees[0].inputNames,
                trees[0].outputNames
            )
            File("$file.gv").writeText(negST.toGraphvizString())
            Runtime.getRuntime().exec("dot -Tpdf -O $file.gv").waitFor()
            // Runtime.getRuntime().exec("dot -Tpng -O ce.gv").waitFor()

            println("======================================")
            log.info("Searching for multi-loops...")
            for (v in negST.verticesWithLoops) {
                val loopBacks = negST.loopBacks(v)
                if (loopBacks.size >= 2) {
                    log.info("Node v = $v has ${loopBacks.size} loop-backs: $loopBacks")
                    for ((i, ns) in negST.negativeScenarios.withIndex()) {
                        // if (ns.elements.last().nodeId == v) {
                        //     log.just(" >> NegativeScenario #${i + 1} with loop position ${ns.loopPosition} (id = ${ns.elements[ns.loopPosition!! - 1].nodeId})")
                        // }
                        if (ns.loopPosition != null &&
                            ns.elements[ns.loopPosition!! - 1].nodeId in loopBacks
                        ) {
                            log.just(" >> NegativeScenario #${i + 1} with loop position ${ns.loopPosition} (id = ${ns.elements[ns.loopPosition!! - 1].nodeId})")
                        }
                    }
                }
            }
            println("======================================")
            return
        }
        // ===

        val solverProvider: () -> Solver = when (solverBackend) {
            SolverBackend.INCREMENTAL_CRYPTOMINISAT -> {
                { Solver.icms() }
            }
            SolverBackend.FILE -> {
                { Solver.filesolver(solverCmd, File("cnf")) }
            }
            SolverBackend.MINISAT -> {
                { Solver.minisat() }
            }
            SolverBackend.CADICAL -> {
                { Solver.cadical() }
            }
        }

        // TODO: warn about ignored specified parameters

        val inferrer = Inferrer(solverProvider(), outDir)

        val tree = trees[0]
        val negTree = negTrees?.get(0)
        val automaton: Automaton? = when (method) {
            Method.Basic -> {
                inferrer.basic(
                    scenarioTree = tree,
                    numberOfStates = requireNotNull(numberOfStates),
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    maxTransitions = maxTransitions,
                    isEncodeReverseImplication = isEncodeReverseImplication
                )
            }
            Method.BasicMin -> {
                if (isOnlyC)
                    inferrer.basicMinC(
                        scenarioTree = tree,
                        isEncodeReverseImplication = isEncodeReverseImplication
                    )
                else
                    inferrer.basicMin(
                        scenarioTree = tree,
                        isEncodeReverseImplication = isEncodeReverseImplication
                    )
            }
            Method.Extended -> {
                inferrer.extended(
                    scenarioTree = tree,
                    numberOfStates = requireNotNull(numberOfStates),
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    maxGuardSize = requireNotNull(maxGuardSize),
                    maxTransitions = maxTransitions,
                    maxTotalGuardsSize = maxTotalGuardsSize,
                    isEncodeReverseImplication = isEncodeReverseImplication
                )
            }
            Method.ExtendedMin -> {
                inferrer.extendedMin(
                    scenarioTree = tree,
                    numberOfStates = numberOfStates,
                    maxGuardSize = requireNotNull(maxGuardSize)
                )
            }
            Method.ExtendedMinUB -> {
                inferrer.extendedMinUB(
                    scenarioTree = tree,
                    numberOfStates = numberOfStates,
                    maxPlateauWidth = maxPlateauWidth
                )
            }
            Method.ExtForest -> {
                inferrer.extForest(
                    scenarioTree = tree,
                    numberOfStates = requireNotNull(numberOfStates),
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    totalNodes = requireNotNull(maxGuardSize),
                    maxTransitions = maxTransitions,
                    maxTotalGuardsSize = maxTotalGuardsSize,
                    isEncodeReverseImplication = isEncodeReverseImplication
                )
            }
            Method.ExtForestMin -> {
                inferrer.extForestMin(
                    scenarioTree = tree,
                    totalNodes = requireNotNull(maxGuardSize)
                )
            }
            Method.Complete -> {
                inferrer.complete(
                    scenarioTree = tree,
                    negativeScenarioTree = negTree,
                    numberOfStates = requireNotNull(numberOfStates),
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    maxGuardSize = requireNotNull(maxGuardSize),
                    maxTransitions = maxTransitions,
                    maxTotalGuardsSize = maxTotalGuardsSize
                )
            }
            Method.CompleteMin -> {
                inferrer.completeMin(
                    scenarioTree = tree,
                    negativeScenarioTree = negTree,
                    maxGuardSize = requireNotNull(maxGuardSize)
                )
            }
            Method.Cegis -> {
                inferrer.cegis(
                    scenarioTree = tree,
                    negativeScenarioTree = negTree,
                    numberOfStates = requireNotNull(numberOfStates),
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    maxGuardSize = requireNotNull(maxGuardSize),
                    maxTransitions = maxTransitions,
                    maxTotalGuardsSize = maxTotalGuardsSize,
                    smvDir = smvDir
                )
            }
            Method.CegisMin -> {
                inferrer.cegisMin(
                    scenarioTree = tree,
                    initialNegativeScenarioTree = negTree,
                    numberOfStates = numberOfStates,
                    maxGuardSize = maxGuardSize,
                    maxPlateauWidth = maxPlateauWidth,
                    smvDir = smvDir
                )
            }
            Method.ParallelModularBasic -> {
                val modularAutomaton = inferrer.parallelModularBasic(
                    scenarioTree = tree,
                    numberOfModules = requireNotNull(numberOfModules),
                    numberOfStates = requireNotNull(numberOfStates),
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    maxTransitions = maxTransitions
                )

                if (modularAutomaton == null) {
                    log.failure("Modular automaton not found")
                } else {
                    log.info("Inferred modular automaton, consisting of ${modularAutomaton.modules.values.size} modules:")
                    for ((m, automaton) in modularAutomaton.modules.values.withIndex(start = 1)) {
                        log.info("Automaton #$m has ${automaton.numberOfStates} states and ${automaton.numberOfTransitions} transitions:")
                        automaton.pprint()
                        automaton.dump(outDir, "the_module-$m")
                    }
                    log.info("Output variables controlled by modules:")
                    for (m in 1..numberOfModules!!) {
                        val vars = (1..modularAutomaton.outputNames.size)
                            .filter { z -> modularAutomaton.outputVariableModule[z] == m }
                            .map { z -> modularAutomaton.outputNames[z - 1] }
                        log.info("Module #$m: ${vars.joinToString(" ")}")
                    }
                    modularAutomaton.dumpFbt(
                        outDir.resolve("CentralController.fbt"),
                        name = "CentralController"
                    )
                    if (modularAutomaton.verify(tree))
                        log.success("Verify: OK")
                    else {
                        log.failure("Verify: FAILED")
                    }
                }

                log.br()
                log.br("The following messages - lies.")
                log.br()
                null
            }
            Method.ParallelModularBasicMin -> {
                val modularAutomaton =
                    if (isOnlyC)
                        inferrer.parallelModularBasicMinC(
                            scenarioTree = tree,
                            numberOfModules = requireNotNull(numberOfModules)
                        )
                    else
                        inferrer.parallelModularBasicMin(
                            scenarioTree = tree,
                            numberOfModules = requireNotNull(numberOfModules)
                        )

                if (modularAutomaton == null) {
                    log.failure("Modular automaton not found")
                } else {
                    log.info("Inferred modular automaton, consisting of ${modularAutomaton.modules.values.size} modules:")
                    for ((m, automaton) in modularAutomaton.modules.values.withIndex(start = 1)) {
                        log.info("Automaton #$m has ${automaton.numberOfStates} states and ${automaton.numberOfTransitions} transitions:")
                        automaton.pprint()
                        automaton.dump(outDir, "the_module-$m")
                    }
                    log.info("Output variables controlled by modules:")
                    for (m in 1..numberOfModules!!) {
                        val vars = (1..modularAutomaton.outputNames.size)
                            .filter { z -> modularAutomaton.outputVariableModule[z] == m }
                            .map { z -> modularAutomaton.outputNames[z - 1] }
                        log.info("Module #$m: ${vars.joinToString(" ")}")
                    }
                    modularAutomaton.dumpFbt(
                        outDir.resolve("CentralController.fbt"),
                        name = "CentralController"
                    )
                    if (modularAutomaton.verify(tree))
                        log.success("Verify: OK")
                    else {
                        log.failure("Verify: FAILED")
                    }
                }

                log.br()
                log.br("The following messages - lies.")
                log.br()
                null
            }
            Method.ParallelModularExtended -> {
                val modularAutomaton = inferrer.parallelModularExtended(
                    scenarioTree = tree,
                    numberOfModules = requireNotNull(numberOfModules),
                    numberOfStates = requireNotNull(numberOfStates),
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    maxGuardSize = requireNotNull(maxGuardSize),
                    maxTransitions = maxTransitions,
                    maxTotalGuardsSize = maxTotalGuardsSize,
                    isEncodeReverseImplication = isEncodeReverseImplication
                )

                if (modularAutomaton == null) {
                    log.failure("Modular automaton not found")
                } else {
                    log.info("Inferred modular automaton, consisting of ${modularAutomaton.modules.values.size} modules, ${modularAutomaton.numberOfTransitions} transitions, ${modularAutomaton.totalGuardsSize} nodes:")
                    for ((m, automaton) in modularAutomaton.modules.values.withIndex(start = 1)) {
                        log.info("Automaton #$m has ${automaton.numberOfStates} states, ${automaton.numberOfTransitions} transitions and ${automaton.totalGuardsSize} nodes:")
                        automaton.pprint()
                        automaton.dump(outDir, "the_module-$m")
                    }
                    // modularAutomaton.dumpFbt(
                    //     outDir.resolve("modularAutomaton.fbt"),
                    //     name = "ModularController"
                    // )
                    if (modularAutomaton.verify(tree))
                        log.success("Verify: OK")
                    else {
                        log.failure("Verify: FAILED")
                    }
                }

                log.br()
                log.br("The following messages - lies.")
                log.br()
                null
            }
            Method.ParallelModularExtendedMin -> {
                val modularAutomaton = inferrer.parallelModularExtendedMin(
                    scenarioTree = tree,
                    numberOfModules = requireNotNull(numberOfModules),
                    numberOfStates = numberOfStates,
                    maxGuardSize = requireNotNull(maxGuardSize)
                )

                if (modularAutomaton == null) {
                    log.failure("Modular automaton not found")
                } else {
                    log.info("Inferred modular automaton, consisting of ${modularAutomaton.modules.values.size} modules, ${modularAutomaton.numberOfTransitions} transitions, ${modularAutomaton.totalGuardsSize} nodes:")
                    for ((m, automaton) in modularAutomaton.modules.values.withIndex(start = 1)) {
                        log.info("Automaton #$m has ${automaton.numberOfStates} states, ${automaton.numberOfTransitions} transitions and ${automaton.totalGuardsSize} nodes:")
                        automaton.pprint()
                        automaton.dump(outDir, "the_module-$m")
                    }
                    // modularAutomaton.dumpFbt(
                    //     outDir.resolve("modularAutomaton.fbt"),
                    //     name = "ModularController"
                    // )
                    if (modularAutomaton.verify(tree))
                        log.success("Verify: OK")
                    else {
                        log.failure("Verify: FAILED")
                    }
                }

                log.br()
                log.br("The following messages - lies.")
                log.br()
                null
            }
            Method.ConsecutiveModularBasic -> {
                val modularAutomaton = inferrer.consecutiveModularBasic(
                    scenarioTree = tree,
                    numberOfModules = requireNotNull(numberOfModules),
                    numberOfStates = requireNotNull(numberOfStates),
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    maxTransitions = maxTransitions
                )

                if (modularAutomaton == null) {
                    log.failure("Modular automaton not found")
                } else {
                    log.info("Inferred modular automaton, consisting of ${modularAutomaton.modules.values.size} modules, ${modularAutomaton.numberOfTransitions} transitions:")
                    for ((m, automaton) in modularAutomaton.modules.values.withIndex(start = 1)) {
                        log.info("Automaton #$m has ${automaton.numberOfStates} states and ${automaton.numberOfTransitions} transitions:")
                        automaton.pprint()
                        automaton.dump(outDir, "the_module-$m")
                    }
                    // modularAutomaton.dumpFbt(
                    //     outDir.resolve("CentralController.fbt"),
                    //     name = "CentralController"
                    // )
                    if (modularAutomaton.verify(tree))
                        log.success("Verify: OK")
                    else {
                        log.failure("Verify: FAILED")
                    }
                }

                log.br()
                log.br("The following messages - lies.")
                log.br()
                null
            }
            Method.ConsecutiveModularBasicMin -> {
                val modularAutomaton = inferrer.consecutiveModularBasicMin(
                    scenarioTree = tree,
                    numberOfModules = requireNotNull(numberOfModules)
                )

                if (modularAutomaton == null) {
                    log.failure("Modular automaton not found")
                } else {
                    log.info("Inferred modular automaton, consisting of ${modularAutomaton.modules.values.size} modules, ${modularAutomaton.numberOfTransitions} transitions:")
                    for ((m, automaton) in modularAutomaton.modules.values.withIndex(start = 1)) {
                        log.info("Automaton #$m has ${automaton.numberOfStates} states and ${automaton.numberOfTransitions} transitions:")
                        automaton.pprint()
                        automaton.dump(outDir, "the_module-$m")
                    }
                    // modularAutomaton.dumpFbt(
                    //     outDir.resolve("CentralController.fbt"),
                    //     name = "CentralController"
                    // )
                    if (modularAutomaton.verify(tree))
                        log.success("Verify: OK")
                    else {
                        log.failure("Verify: FAILED")
                    }
                }

                log.br()
                log.br("The following messages - lies.")
                log.br()
                null
            }
            Method.ConsecutiveModularExtended -> {
                val modularAutomaton = inferrer.consecutiveModularExtended(
                    scenarioTree = tree,
                    numberOfModules = requireNotNull(numberOfModules),
                    numberOfStates = requireNotNull(numberOfStates),
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    maxGuardSize = requireNotNull(maxGuardSize),
                    maxTransitions = maxTransitions,
                    maxTotalGuardsSize = maxTotalGuardsSize,
                    isEncodeReverseImplication = isEncodeReverseImplication
                )

                if (modularAutomaton == null) {
                    log.failure("Modular automaton not found")
                } else {
                    log.info("Inferred modular automaton, consisting of ${modularAutomaton.modules.values.size} modules, ${modularAutomaton.numberOfTransitions} transitions, ${modularAutomaton.totalGuardsSize} nodes:")
                    for ((m, automaton) in modularAutomaton.modules.values.withIndex(start = 1)) {
                        log.info("Automaton #$m has ${automaton.numberOfStates} states, ${automaton.numberOfTransitions} transitions and ${automaton.totalGuardsSize} nodes:")
                        automaton.pprint()
                        automaton.dump(outDir, "the_module-$m")
                    }
                    // modularAutomaton.dumpFbt(
                    //     outDir.resolve("modularAutomaton.fbt"),
                    //     name = "ModularController"
                    // )
                    if (modularAutomaton.verify(tree))
                        log.success("Verify: OK")
                    else {
                        log.failure("Verify: FAILED")
                    }
                }

                log.br()
                log.br("The following messages - lies.")
                log.br()
                null
            }
            Method.ConsecutiveModularExtendedMin -> {
                val modularAutomaton = inferrer.consecutiveModularExtendedMin(
                    scenarioTree = tree,
                    numberOfModules = requireNotNull(numberOfModules),
                    numberOfStates = numberOfStates,
                    maxGuardSize = requireNotNull(maxGuardSize)
                )

                if (modularAutomaton == null) {
                    log.failure("Modular automaton not found")
                } else {
                    log.info("Inferred modular automaton, consisting of ${modularAutomaton.modules.values.size} modules, ${modularAutomaton.numberOfTransitions} transitions, ${modularAutomaton.totalGuardsSize} nodes:")
                    for ((m, automaton) in modularAutomaton.modules.values.withIndex(start = 1)) {
                        log.info("Automaton #$m has ${automaton.numberOfStates} states, ${automaton.numberOfTransitions} transitions and ${automaton.totalGuardsSize} nodes:")
                        automaton.pprint()
                        automaton.dump(outDir, "the_module-$m")
                    }
                    // modularAutomaton.dumpFbt(
                    //     outDir.resolve("modularAutomaton.fbt"),
                    //     name = "ModularController"
                    // )
                    if (modularAutomaton.verify(tree))
                        log.success("Verify: OK")
                    else {
                        log.failure("Verify: FAILED")
                    }
                }

                log.br()
                log.br("The following messages - lies.")
                log.br()
                null
            }
            Method.ArbitraryModularBasic -> {
                val modularAutomaton = inferrer.arbitraryModularBasic(
                    scenarioTree = tree,
                    numberOfModules = requireNotNull(numberOfModules),
                    numberOfStates = requireNotNull(numberOfStates),
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    maxTransitions = maxTransitions,
                    isEncodeReverseImplication = isEncodeReverseImplication
                )
                modularAutomaton?.minimizeTruthTableGuards(tree)

                if (modularAutomaton == null) {
                    log.failure("Modular automaton not found")
                } else {
                    log.info("Inferred modular automaton, consisting of ${modularAutomaton.modules.values.size} modules, ${modularAutomaton.numberOfTransitions} transitions:")
                    for ((m, automaton) in modularAutomaton.modules.values.withIndex(start = 1)) {
                        log.info("Automaton #$m has ${automaton.numberOfStates} states and ${automaton.numberOfTransitions} transitions:")
                        automaton.pprint()
                        automaton.dump(outDir, "the_module-$m")
                    }
                    modularAutomaton.dumpFbt(
                        outDir.resolve("CentralController.fbt"),
                        name = "CentralController"
                    )
                    if (modularAutomaton.verify(tree))
                        log.success("Verify: OK")
                    else {
                        log.failure("Verify: FAILED")
                    }
                }

                log.br()
                log.br("The following messages - lies.")
                log.br()
                null
            }
            Method.ArbitraryModularBasicMin -> {
                val modularAutomaton = inferrer.arbitraryModularBasicMin(
                    scenarioTree = tree,
                    numberOfModules = requireNotNull(numberOfModules)
                )
                modularAutomaton?.minimizeTruthTableGuards(tree)

                if (modularAutomaton == null) {
                    log.failure("Modular automaton not found")
                } else {
                    log.info("Inferred modular automaton, consisting of ${modularAutomaton.modules.values.size} modules, ${modularAutomaton.numberOfTransitions} transitions:")
                    for ((m, automaton) in modularAutomaton.modules.values.withIndex(start = 1)) {
                        log.info("Automaton #$m has ${automaton.numberOfStates} states and ${automaton.numberOfTransitions} transitions:")
                        automaton.pprint()
                        automaton.dump(outDir, "the_module-$m")
                    }
                    modularAutomaton.dumpFbt(
                        outDir.resolve("CentralController.fbt"),
                        name = "CentralController"
                    )
                    if (modularAutomaton.verify(tree))
                        log.success("Verify: OK")
                    else {
                        log.failure("Verify: FAILED")
                    }
                }

                log.br()
                log.br("The following messages - lies.")
                log.br()
                null
            }
            Method.Distributed -> {
                // TODO Move to parameters
                // Hardcoded stuff
                val numberOfStatesList = intArrayOf(7, 6)
                val namingMappingSender = mapOf(
                    "REQ" to "REQ",
                    "PHONY" to "x",
                    "tmr.timeout" to "timeout",
                    "bwdc.o0" to "a0",
                    "bwdc.o1" to "a1",
                    "sndr._state" to "_state",
                    "sndr.send" to "send",
                    "sndr.p0" to "p0",
                    "sndr.done" to "done",
                    "sndr.p1" to "p1",
                    "sndr.y" to "y"
                )
                val namingMappingReceiver = mapOf(
                    "REQ" to "REQ",
                    "PHONY" to "x",
                    "fwdc.o0" to "p0",
                    "fwdc.o1" to "p1",
                    "rcvr._state" to "_state",
                    "rcvr.deliver" to "deliver",
                    "rcvr.a0" to "a0",
                    "rcvr.a1" to "a1",
                    "rcvr.y" to "y"
                )
                val mappings = arrayOf(namingMappingSender, namingMappingReceiver)

                var iteration = 0
                cegis@ while (true) {
                    iteration++
                    log.info("CEGIS iteration: $iteration")

                    // Infer automatons
                    val automatons = mutableListOfNulls<Automaton>(trees.size)
                    for (i in trees.indices) {
                        // TODO reuse solver
                        val automaton = inferrer.complete(
                            scenarioTree = trees[i],
                            negativeScenarioTree = negTrees.get(i),
                            numberOfStates = numberOfStatesList[i],
                            maxOutgoingTransitions = maxOutgoingTransitions,
                            maxGuardSize = requireNotNull(maxGuardSize),
                            maxTransitions = maxTransitions,
                            maxTotalGuardsSize = maxTotalGuardsSize
                        )
                        if (automaton == null) {
                            log.info("Automaton not found")
                            break@cegis
                        }
                        automatons[i] = automaton
                    }

                    // Dump intermediate results
                    for (i in automatons.indices) {
                        automatons[i]!!.dump(outDir, "_automaton_node%d_iter%04d".format(i, iteration))
                    }

                    // Verify
                    for (i in automatons.indices) {
                        automatons[i]!!.dumpSmv(smvDir.resolve("control_$i.smv"), name = "CONTROL_$i")
                    }
                    val cmd = "make clean model counterexamples"
                    log.debug { "Running '$cmd'..." }
                    val timeStart = PerformanceCounter.reference
                    val exitcode = Runtime.getRuntime().exec(cmd, null, smvDir).waitFor()
                    val runningTime = timeSince(timeStart)
                    log.debug { "'$cmd' returned with $exitcode in %.3f s.".format(runningTime.seconds) }
                    check(exitcode == 0) { "NuSMV exitcode: $exitcode" }

                    // Update counterexamples
                    val fileCounterexamples = smvDir.resolve("counterexamples")
                    if (!fileCounterexamples.exists()) {
                        log.info("Success, CEGIS iteration: $iteration")
                        break
                    }

                    log.info("Found counterexamples:")
                    for (i in automatons.indices) {
                        val ces: List<Counterexample> = Counterexample.fromFile(fileCounterexamples, mappings[i])
                        println(ces)
                        val negativeScenarios = ces.map {
                            NegativeScenario.fromCounterexample(
                                it,
                                trees[i].inputEvents,
                                trees[i].outputEvents,
                                trees[i].inputNames,
                                trees[i].outputNames
                            )
                        }
                        val before = negTrees[i].size
                        negativeScenarios.forEach(negTrees[i]::addNegativeScenario)
                        val diff = negTrees[i].size - before
                        log.info("Node $i negative tree diff: $diff")
                    }
                }

                null
            }
            else -> TODO("method '$method'")
        }

        if (automaton == null) {
            log.failure("Automaton not found")
        } else {
            log.info("Inferred automaton:")
            automaton.pprint()
            log.info("Inferred automaton has ${automaton.numberOfStates} states, ${automaton.numberOfTransitions} transitions and ${automaton.totalGuardsSize} nodes")

            if (automaton.verify(tree))
                log.success("Verify: OK")
            else {
                log.failure("Verify: FAILED")
                if (failIfSTVerifyFailed) error("ST verification failed")
            }

            if (negTree != null) {
                if (automaton.verify(negTree))
                    log.success("Verify CE: OK")
                else {
                    log.failure("Verify CE: FAILED")
                    if (failIfCEVerifyFailed) error("CE verification failed")
                }

                // val fileCEMarkedGv = File("ce-marked.gv")
                // fileCEMarkedGv.writeText(negTree.toGraphvizString())
                // Runtime.getRuntime().exec("dot -Tpdf -O $fileCEMarkedGv")
            }

            fileVerifyCE?.let{
                val nst = NegativeScenarioTree.fromFile(
                    it,
                    tree.inputEvents,
                    tree.outputEvents,
                    tree.inputNames,
                    tree.outputNames
                )
                if (automaton.verify(nst))
                    log.success("Verify CE from '$fileVerifyCE': OK")
                else
                    log.failure("Verify CE from '$fileVerifyCE': FAILED")
            }

            automaton.dump(outDir, "automaton")
        }
    }
}

fun main(args: Array<String>) {
    log.br(DateTime.nowLocal().format("yyyy-MM-dd HH:mm:ss"))
    val runningTime = measureTime { FbSAT().main(args) }
    log.br(DateTime.nowLocal().format("yyyy-MM-dd HH:mm:ss"))
    log.success("All done in %.3f seconds".format(runningTime.seconds))
}
