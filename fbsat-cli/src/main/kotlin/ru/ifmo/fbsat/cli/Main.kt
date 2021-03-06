package ru.ifmo.fbsat.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.output.CliktHelpFormatter
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.soywiz.klock.DateTime
import com.soywiz.klock.ISO8601
import ru.ifmo.fbsat.core.automaton.Automaton
import ru.ifmo.fbsat.core.scenario.negative.NegativeScenarioTree
import ru.ifmo.fbsat.core.scenario.positive.ScenarioTree
import ru.ifmo.fbsat.core.solver.Solver
import ru.ifmo.fbsat.core.task.basic.BasicMinTask
import ru.ifmo.fbsat.core.task.basic.BasicTask
import ru.ifmo.fbsat.core.task.basic.ModularBasicMinTask
import ru.ifmo.fbsat.core.task.basic.ModularBasicTask
import ru.ifmo.fbsat.core.task.complete.CompleteCegisTask
import ru.ifmo.fbsat.core.task.complete.CompleteMinCegisTask
import ru.ifmo.fbsat.core.task.complete.CompleteTask
import ru.ifmo.fbsat.core.task.extended.ExtendedMinTask
import ru.ifmo.fbsat.core.task.extended.ExtendedMinUBTask
import ru.ifmo.fbsat.core.task.extended.ExtendedTask
import ru.ifmo.fbsat.core.utils.Globals
import ru.ifmo.fbsat.core.utils.StartStateAlgorithms
import ru.ifmo.fbsat.core.utils.inputNamesPnP
import ru.ifmo.fbsat.core.utils.log
import ru.ifmo.fbsat.core.utils.outputNamesPnP
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.measureTimeMillis

enum class Method(val s: String) {
    Basic("basic"),
    BasicMin("basic-min"),
    Extended("extended"),
    ExtendedMin("extended-min"),
    ExtendedMinUb("extended-min-ub"),
    Complete("complete"),
    CompleteMin("complete-min"),
    CompleteCegis("complete-cegis"),
    CompleteMinCegis("complete-min-cegis"),
    ModularBasic("modular-basic"),
    ModularBasicMin("modular-basic-min")
}

@Suppress("MemberVisibilityCanBePrivate")
class FbSAT : CliktCommand() {
    val fileScenarios: File by option(
        "-i", "--scenarios",
        help = "File with scenarios",
        metavar = "<path>"
    ).file(
        exists = true,
        folderOkay = false,
        readable = true
    ).required()

    val fileCounterexamples: File? by option(
        "-ce", "--counterexamples",
        help = "File with counter-examples",
        metavar = "<path>"
    ).file(
        exists = true,
        folderOkay = false,
        readable = true
    )

    val smvDir: File by option(
        "--smvdir",
        help = "Directory with SMV files",
        metavar = "<path>"
    ).file(
        exists = true,
        fileOkay = false
    ).default(
        File("data/pnp/smv")
    )

    val outDir: File by option(
        "-o", "--outdir",
        help = "Output directory",
        metavar = "<path>"
    ).file().default(
        File("out/${DateTime.now().format(ISO8601.DATETIME_COMPLETE)}")
    )

    val method: Method by option(
        "-m", "--method",
        help = "Method to use",
        metavar = "<method>"
    ).choice(
        Method.values().associate { it.s to it }
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
        help = "SAT-solver",
        metavar = "<cmd>"
    ).default(
        "incremental-cryptominisat"
    )

    val isIncrementalSolver: Boolean by option(
        "--incremental",
        help = "Use IncrementalSolver backend"
    ).flag(
        "--no-incremental",
        default = true
    )

    val isForbidOr: Boolean by option(
        "--forbid-or"
    ).flag(
        "--no-forbid-or",
        default = false
    )

    val isBfsAutomaton: Boolean by option(
        "--bfs-automaton"
    ).flag(
        "--no-bfs-automaton",
        default = true
    )

    val isBfsGuard: Boolean by option(
        "--bfs-guard"
    ).flag(
        "--no-bfs-guard",
        default = false
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
        exists = true,
        folderOkay = false,
        readable = true
    )

    val startStateAlgorithms: StartStateAlgorithms by option(
        "--start-state-algorithms",
        help = "Start state algorithms"
    ).choice(
        "nothing" to StartStateAlgorithms.NOTHING,
        "zero" to StartStateAlgorithms.ZERO,
        "arbitrary" to StartStateAlgorithms.ARBITRARY
    ).default(
        StartStateAlgorithms.NOTHING
    )

    val isEncodeTransitionsOrder: Boolean by option(
        "--encode-transitions-order",
        help = "[DEBUG] Encode transitions lexicographic order"
    ).flag(
        "--no-encode-transitions-order",
        default = false
    )

    val isEncodeTerminalsOrder: Boolean by option(
        "--encode-terminals-order",
        help = "[DEBUG] Encode terminal numbers lexicographic order"
    ).flag(
        "--no-encode-terminals-order",
        default = true
    )

    val isEncodeTotalizer: Boolean by option(
        "--encode-totalizer",
        help = "Encode totalizer when upper bound is null"
    ).flag(
        "--no-encode-totalizer",
        default = true
    )

    val fileVerifyCE: File? by option(
        "--verify-ce"
    ).file()

    val isDebug: Boolean by option(
        "--debug",
        help = "Debug mode"
    ).flag(
        default = false
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
        Globals.START_STATE_ALGORITHMS = startStateAlgorithms
        Globals.IS_FORBID_OR = isForbidOr
        Globals.IS_BFS_AUTOMATON = isBfsAutomaton
        Globals.IS_BFS_GUARD = isBfsGuard
        Globals.IS_ENCODE_TRANSITIONS_ORDER = isEncodeTransitionsOrder
        Globals.IS_ENCODE_TERMINALS_ORDER = isEncodeTerminalsOrder
        Globals.IS_ENCODE_TOTALIZER = isEncodeTotalizer
        Globals.IS_DEBUG = isDebug

        // outDir.deleteRecursively()
        // outDir.walkBottomUp().forEach { if (it != outDir) it.delete() }
        outDir.mkdirs()

        val tree = ScenarioTree.fromFile(fileScenarios, inputNamesPnP, outputNamesPnP)
        println("[*] Scenarios: ${tree.scenarios.size}")
        println("[*] Elements: ${tree.scenarios.sumBy { it.elements.size }}")

        val negTree = fileCounterexamples?.let {
            NegativeScenarioTree.fromFile(
                it,
                tree.inputEvents,
                tree.outputEvents,
                tree.inputNames,
                tree.outputNames
            )
        }

        // ===
        fileVis?.let { file ->
            println("======================================")
            println("[*] Visualizing <$file>...")
            val negST = NegativeScenarioTree.fromFile(
                file,
                tree.inputEvents,
                tree.outputEvents,
                tree.inputNames,
                tree.outputNames
            )
            File("$file.gv").writeText(negST.toGraphvizString())
            Runtime.getRuntime().exec("dot -Tpdf -O $file.gv").waitFor()
            // Runtime.getRuntime().exec("dot -Tpng -O ce.gv").waitFor()

            println("======================================")
            println("[*] Searching for multi-loops...")
            for (v in negST.verticesWithLoops) {
                val loopBacks = negST.loopBacks(v)
                if (loopBacks.size >= 2) {
                    println("[*] Node v = $v has ${loopBacks.size} loop-backs: $loopBacks")
                    for ((i, ns) in negST.negativeScenarios.withIndex()) {
                        // if (ns.elements.last().nodeId == v) {
                        //     println(" >> NegativeScenario #${i + 1} with loop position ${ns.loopPosition} (id = ${ns.elements[ns.loopPosition!! - 1].nodeId})")
                        // }
                        if (ns.loopPosition != null &&
                            ns.elements[ns.loopPosition!! - 1].nodeId in loopBacks
                        ) {
                            println(" >> NegativeScenario #${i + 1} with loop position ${ns.loopPosition} (id = ${ns.elements[ns.loopPosition!! - 1].nodeId})")
                        }
                    }
                }
            }
            println("======================================")
            return
        }
        // ===

        val solverProvider: () -> Solver = if (isIncrementalSolver) {
            { Solver.incremental(solverCmd) }
        } else {
            { Solver.default(solverCmd) }
        }

        val automaton: Automaton? = when (method) {
            Method.Basic -> {
                val task = BasicTask.create(
                    scenarioTree = tree,
                    numberOfStates = numberOfStates!!,
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    maxTransitions = maxTransitions,
                    solverProvider = solverProvider,
                    outDir = outDir
                )
                task.infer()
            }
            Method.BasicMin -> {
                val task = BasicMinTask.create(
                    scenarioTree = tree,
                    numberOfStates = numberOfStates,
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    initialMaxTransitions = maxTransitions,
                    solverProvider = solverProvider,
                    outDir = outDir,
                    isOnlyC = isOnlyC
                )
                task.infer()
            }
            Method.Extended -> {
                val task = ExtendedTask.create(
                    scenarioTree = tree,
                    numberOfStates = requireNotNull(numberOfStates),
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    maxGuardSize = requireNotNull(maxGuardSize),
                    maxTotalGuardsSize = maxTotalGuardsSize,
                    outDir = outDir,
                    solverProvider = solverProvider
                )
                task.infer()
            }
            Method.ExtendedMin -> {
                val task = ExtendedMinTask.create(
                    scenarioTree = tree,
                    numberOfStates = numberOfStates,
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    maxGuardSize = requireNotNull(maxGuardSize),
                    initialMaxTotalGuardsSize = maxTotalGuardsSize,
                    outDir = outDir,
                    solverProvider = solverProvider
                )
                task.infer()
            }
            Method.ExtendedMinUb -> {
                val task = ExtendedMinUBTask.create(
                    scenarioTree = tree,
                    initialMaxTotalGuardsSize = maxTotalGuardsSize,
                    maxPlateauWidth = maxPlateauWidth,
                    outDir = outDir,
                    solverProvider = solverProvider
                )
                task.infer()
            }
            Method.Complete -> {
                val task = CompleteTask.create(
                    scenarioTree = tree,
                    negativeScenarioTree = negTree,
                    numberOfStates = requireNotNull(numberOfStates),
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    maxGuardSize = requireNotNull(maxGuardSize),
                    maxTotalGuardsSize = maxTotalGuardsSize,
                    outDir = outDir,
                    solverProvider = solverProvider
                )
                task.infer()
            }
            Method.CompleteMin -> TODO("complete-min method")
            Method.CompleteCegis -> {
                val task = CompleteCegisTask.create(
                    scenarioTree = tree,
                    negativeScenarioTree = negTree,
                    numberOfStates = requireNotNull(numberOfStates),
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    maxGuardSize = requireNotNull(maxGuardSize),
                    maxTotalGuardsSize = maxTotalGuardsSize,
                    smvDir = smvDir,
                    outDir = outDir,
                    solverProvider = solverProvider
                )
                task.infer()
            }
            Method.CompleteMinCegis -> {
                val task = CompleteMinCegisTask.create(
                    scenarioTree = tree,
                    initialNegativeScenarioTree = negTree,
                    numberOfStates = numberOfStates,
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    maxGuardSize = requireNotNull(maxGuardSize),
                    initialMaxTotalGuardsSize = maxTotalGuardsSize,
                    smvDir = smvDir,
                    outDir = outDir,
                    solverProvider = solverProvider
                )
                task.infer()
            }
            Method.ModularBasic -> {
                val task = ModularBasicTask.create(
                    scenarioTree = tree,
                    numberOfModules = numberOfModules!!,
                    numberOfStates = numberOfStates!!,
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    maxTransitions = maxTransitions,
                    solverProvider = solverProvider,
                    outDir = outDir
                )
                val modularAutomaton = task.infer()

                if (modularAutomaton == null) {
                    log.failure("Modular automaton not found")
                } else {
                    log.info("Inferred modular automaton, consisting of ${modularAutomaton.modules.size} modules:")
                    for ((m, automaton) in modularAutomaton.modules.withIndex()) {
                        log.info("Automaton #${m + 1} has ${automaton.numberOfStates} states and ${automaton.numberOfTransitions} transitions:")
                        automaton.pprint()
                        automaton.dump(outDir, "the_module-$m")
                    }
                    modularAutomaton.dumpFbt(
                        outDir.resolve("modularAutomaton.fbt"),
                        name = "ModularController"
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
            Method.ModularBasicMin -> {
                val task = ModularBasicMinTask.create(
                    scenarioTree = tree,
                    numberOfModules = numberOfModules!!,
                    numberOfStates = numberOfStates,
                    maxOutgoingTransitions = maxOutgoingTransitions,
                    initialMaxTransitions = maxTransitions,
                    solverProvider = solverProvider,
                    outDir = outDir
                )
                val modularAutomaton = task.infer()

                if (modularAutomaton == null) {
                    log.failure("Modular automaton not found")
                } else {
                    log.info("Inferred modular automaton, consisting of ${modularAutomaton.modules.size} modules:")
                    for ((m, automaton) in modularAutomaton.modules.withIndex()) {
                        log.info("Automaton #${m + 1} has ${automaton.numberOfStates} states and ${automaton.numberOfTransitions} transitions:")
                        automaton.pprint()
                        automaton.dump(outDir, "the_module-$m")
                    }
                    modularAutomaton.dumpFbt(
                        outDir.resolve("modularAutomaton.fbt"),
                        name = "ModularController"
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

            fileVerifyCE?.let {
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
    log.br(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
    val runningTime = measureTimeMillis { FbSAT().main(args) }
    log.br(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
    log.success("All done in %.3f seconds".format(runningTime / 1000.0))
}
