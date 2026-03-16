import java.util.ArrayDeque
import java.util.BitSet

@JvmInline
value class StationId(val value: Int)

@JvmInline
value class CargoId(val value: Int)

data class Station(
    val unload: CargoId,
    val load: CargoId,
)

data class Problem(
    val stations: List<StationId>,
    val stationInfo: Map<StationId, Station>,
    val edges: Map<StationId, List<StationId>>,
    val start: StationId,
)

private class TokenReader(text: String) {
    private val tokens: List<String> =
        text.trim().split(Regex("\\s+")).filter(String::isNotEmpty)

    private var index: Int = 0

    fun nextInt(context: String): Int {
        require(index < tokens.size) {
            "tokenReader: expected integer ($context), but input ended."
        }

        val token = tokens[index++]
        return token.toIntOrNull()
            ?: error("tokenReader: expected integer ($context), but got '$token'.")
    }

    fun ensureFullyConsumed() {
        require(index == tokens.size) {
            "tokenReader: unexpected extra input at token '${tokens[index]}'."
        }
    }
}

internal fun parseProblem(text: String): Problem {
    val reader = TokenReader(text)

    val stationCount = reader.nextInt("S")
    val trackCount = reader.nextInt("T")

    require(stationCount > 0) { "parseProblem: station count (S) must be > 0" }
    require(trackCount >= 0) { "parseProblem: track count (T) must be >= 0" }

    val stations = ArrayList<StationId>(stationCount)
    val stationInfo = LinkedHashMap<StationId, Station>(stationCount)

    repeat(stationCount) {
        val stationId = StationId(reader.nextInt("s"))
        val unload = CargoId(reader.nextInt("c_unload"))
        val load = CargoId(reader.nextInt("c_load"))

        require(stationInfo.put(stationId, Station(unload, load)) == null) {
            "parseProblem: same station id (s) found twice ${stationId.value}"
        }
        stations += stationId
    }

    val edges = LinkedHashMap<StationId, MutableList<StationId>>(stationCount)
    for (stationId in stations) {
        edges[stationId] = mutableListOf()
    }

    repeat(trackCount) {
        val from = StationId(reader.nextInt("s_from"))
        val to = StationId(reader.nextInt("s_to"))

        val outgoing = edges[from]
            ?: error("parseProblem: unknown s_from ${from.value}.")

        require(to in stationInfo) {
            "parseProblem: unknown s_to ${to.value}."
        }

        outgoing += to
    }

    val start = StationId(reader.nextInt("s_0"))
    reader.ensureFullyConsumed()

    require(start in stationInfo) {
        "parseProblem: unknown start station (s_0) ${start.value}."
    }

    return Problem(
        stations = stations,
        stationInfo = stationInfo,
        edges = edges.mapValues { (_, outgoing) -> outgoing.toList() },
        start = start,
    )
}

internal object RailwayAnalysis {
    /*
     * Dataflow analysis on station graph
     *
     * For each station, we maintain the set of cargo types that could be present
     * when a train arrives there. The station then applies a simple transfer rule:
     * it removes the cargo unloaded at that station and adds the cargo loaded there.
     * The resulting set can then flow along outgoing edges.
     *
     * The equations are solved with a standard fixed-point worklist algorithm.
     * The start station begins with the empty cargo set. We repeatedly propagate
     * information through outgoing edges, and whenever a station receives a new
     * cargo type, it is scheduled for reprocessing.
     *
     * To keep propagation efficient, we compress all distinct cargo ids for BitSet
     * storage and move from typed StationId and CargoId value classes at the model
     * boundary to integer numbering and arrays inside the solver internals.
     *
     * Because arrival sets only grow, the process converges to a fixed point.
     * In this BitSet-based implementation, the worst-case running time is
     * O((S + T) * C^2).
     */

    fun solve(problem: Problem): Map<StationId, Set<CargoId>> {
        val context = DenseProblemEncoding(problem)

        val arrivalFacts = Array(context.stationCount) { BitSet(context.cargoCount) }
        val pending = ArrayDeque<Int>()
        val scheduled = BooleanArray(context.stationCount)

        enqueue(context.startStationNumber, pending, scheduled)

        while (pending.isNotEmpty()) {
            val stationNumber = pending.removeFirst()
            scheduled[stationNumber] = false

            val outgoing = transfer(
                incoming = arrivalFacts[stationNumber],
                station = context.stations[stationNumber],
            )

            for (nextStationNumber in context.outgoingByStation[stationNumber]) {
                if (joinInto(arrivalFacts[nextStationNumber], outgoing)) {
                    enqueue(nextStationNumber, pending, scheduled)
                }
            }
        }

        val result = LinkedHashMap<StationId, Set<CargoId>>(context.stationCount)
        for ((stationNumber, station) in context.stations.withIndex()) {
            result[station.stationId] = arrivalFacts[stationNumber].toCargoIdSet(context.cargoIds)
        }
        return result
    }

    private fun transfer(incoming: BitSet, station: NumberedStation): BitSet {
        val outgoing = incoming.copy()
        outgoing.clear(station.unloadCargoNumber)
        outgoing.set(station.loadCargoNumber)
        return outgoing
    }

    private fun joinInto(target: BitSet, incoming: BitSet): Boolean {
        val before = target.cardinality()
        target.or(incoming)
        return target.cardinality() != before
    }

    private fun enqueue(
        stationNumber: Int,
        pending: ArrayDeque<Int>,
        scheduled: BooleanArray,
    ) {
        if (!scheduled[stationNumber]) {
            scheduled[stationNumber] = true
            pending.addLast(stationNumber)
        }
    }

    private class DenseProblemEncoding(problem: Problem) {
        val stationNumberById: Map<StationId, Int>
        val cargoIds: List<CargoId>
        val cargoNumberById: Map<CargoId, Int>
        val stations: List<NumberedStation>
        val outgoingByStation: List<IntArray>
        val startStationNumber: Int

        val stationCount: Int
            get() = stations.size

        val cargoCount: Int
            get() = cargoIds.size

        init {
            stationNumberById = problem.stations
                .mapIndexed { number, stationId -> stationId to number }
                .toMap()

            cargoIds = problem.stationInfo.values
                .flatMap { listOf(it.unload, it.load) }
                .distinct()
                .sortedBy(CargoId::value)

            cargoNumberById = cargoIds
                .mapIndexed { number, cargoId -> cargoId to number }
                .toMap()

            stations = problem.stations.map { stationId ->
                val station = problem.stationInfo.getValue(stationId)
                NumberedStation(
                    stationId = stationId,
                    unloadCargoNumber = cargoNumberById.getValue(station.unload),
                    loadCargoNumber = cargoNumberById.getValue(station.load),
                )
            }

            val edgeBuckets = List(problem.stations.size) { mutableListOf<Int>() }
            for ((fromId, outgoing) in problem.edges) {
                val fromNumber = stationNumberById.getValue(fromId)
                for (toId in outgoing) {
                    edgeBuckets[fromNumber] += stationNumberById.getValue(toId)
                }
            }

            outgoingByStation = edgeBuckets.map { it.toIntArray() }
            startStationNumber = stationNumberById.getValue(problem.start)
        }
    }

    private data class NumberedStation(
        val stationId: StationId,
        val unloadCargoNumber: Int,
        val loadCargoNumber: Int,
    )
}

private fun BitSet.copy(): BitSet =
    clone() as BitSet

private fun BitSet.toCargoIdSet(cargoIds: List<CargoId>): Set<CargoId> {
    val result = LinkedHashSet<CargoId>()
    var bit = nextSetBit(0)
    while (bit >= 0) {
        result += cargoIds[bit]
        bit = nextSetBit(bit + 1)
    }
    return result
}

internal fun renderAnswer(stations: List<StationId>, answer: Map<StationId, Set<CargoId>>): String =
    buildString {
        for ((index, stationId) in stations.withIndex()) {
            val cargo = answer.getValue(stationId)
                .map(CargoId::value)

            if (cargo.isEmpty()) {
                append("${stationId.value}:")
            } else {
                append("${stationId.value}: ${cargo.joinToString(" ")}")
            }

            if (index + 1 < stations.size) {
                appendLine()
            }
        }
    }

fun main() {
    val inputText = generateSequence(::readLine).joinToString("\n")
    val problem = parseProblem(inputText)
    val answer = RailwayAnalysis.solve(problem)
    print(renderAnswer(problem.stations, answer))
}
