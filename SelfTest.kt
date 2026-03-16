private fun assertArrivalAt(
    actualByStation: Map<StationId, Set<CargoId>>,
    stationId: StationId,
    expectedCargo: Set<CargoId>,
) {
    val actualCargo = actualByStation.getValue(stationId)
    check(actualCargo == expectedCargo) {
        "arrival facts mismatch at station ${stationId.value}: expected=$expectedCargo, actual=$actualCargo"
    }
}

private fun testStartStationOnly() {
    val entry = StationId(3)
    val red = CargoId(17)

    val problem = Problem(
        stations = listOf(entry),
        stationInfo = mapOf(
            entry to Station(unload = red, load = red),
        ),
        edges = mapOf(
            entry to emptyList(),
        ),
        start = entry,
    )

    val actualByStation = RailwayAnalysis.solve(problem)

    assertArrivalAt(actualByStation, entry, emptySet())
}

private fun testSingleEdge() {
    val entry = StationId(2)
    val mid = StationId(9)
    val red = CargoId(31)
    val blue = CargoId(47)

    val problem = Problem(
        stations = listOf(entry, mid),
        stationInfo = mapOf(
            entry to Station(unload = red, load = red),
            mid to Station(unload = blue, load = blue),
        ),
        edges = mapOf(
            entry to listOf(mid),
            mid to emptyList(),
        ),
        start = entry,
    )

    val actualByStation = RailwayAnalysis.solve(problem)

    assertArrivalAt(actualByStation, entry, emptySet())
    assertArrivalAt(actualByStation, mid, setOf(red))
}

private fun testUnloadWorks() {
    val entry = StationId(10)
    val mid = StationId(12)
    val tail = StationId(15)

    val red = CargoId(101)
    val blue = CargoId(202)
    val green = CargoId(303)

    val problem = Problem(
        stations = listOf(entry, mid, tail),
        stationInfo = mapOf(
            entry to Station(unload = red, load = red),
            mid to Station(unload = red, load = blue),
            tail to Station(unload = green, load = green),
        ),
        edges = mapOf(
            entry to listOf(mid),
            mid to listOf(tail),
            tail to emptyList(),
        ),
        start = entry,
    )

    val actualByStation = RailwayAnalysis.solve(problem)

    assertArrivalAt(actualByStation, mid, setOf(red))
    assertArrivalAt(actualByStation, tail, setOf(blue))
}

private fun testCycleBackToStart() {
    val entry = StationId(1)
    val mid = StationId(3)

    val red = CargoId(7)
    val blue = CargoId(11)

    val problem = Problem(
        stations = listOf(entry, mid),
        stationInfo = mapOf(
            entry to Station(unload = red, load = red),
            mid to Station(unload = blue, load = blue),
        ),
        edges = mapOf(
            entry to listOf(mid),
            mid to listOf(entry),
        ),
        start = entry,
    )

    val actualByStation = RailwayAnalysis.solve(problem)

    assertArrivalAt(actualByStation, mid, setOf(red, blue))
    assertArrivalAt(actualByStation, entry, setOf(red, blue))
}

private fun testMergeFromTwoPaths() {
    val entry = StationId(100)
    val left = StationId(130)
    val right = StationId(170)
    val join = StationId(190)

    val red = CargoId(3)
    val blue = CargoId(5)
    val green = CargoId(7)
    val gold = CargoId(11)

    val problem = Problem(
        stations = listOf(entry, left, right, join),
        stationInfo = mapOf(
            entry to Station(unload = red, load = red),
            left to Station(unload = blue, load = blue),
            right to Station(unload = green, load = green),
            join to Station(unload = gold, load = gold),
        ),
        edges = mapOf(
            entry to listOf(left, right),
            left to listOf(join),
            right to listOf(join),
            join to emptyList(),
        ),
        start = entry,
    )

    val actualByStation = RailwayAnalysis.solve(problem)

    assertArrivalAt(actualByStation, left, setOf(red))
    assertArrivalAt(actualByStation, right, setOf(red))
    assertArrivalAt(actualByStation, join, setOf(red, blue, green))
}

private fun testParseProblemMatchesStatementLayout() {
    val text = """
        4 4
        1 10 10
        2 20 20
        3 30 30
        4 40 40
        1 2
        1 3
        2 4
        3 4
        1
    """.trimIndent()

    val parsed = parseProblem(text)

    check(parsed.stations == listOf(StationId(1), StationId(2), StationId(3), StationId(4)))
    check(parsed.stationInfo.getValue(StationId(1)) == Station(CargoId(10), CargoId(10)))
    check(parsed.stationInfo.getValue(StationId(2)) == Station(CargoId(20), CargoId(20)))
    check(parsed.stationInfo.getValue(StationId(3)) == Station(CargoId(30), CargoId(30)))
    check(parsed.stationInfo.getValue(StationId(4)) == Station(CargoId(40), CargoId(40)))
    check(parsed.edges.getValue(StationId(1)) == listOf(StationId(2), StationId(3)))
    check(parsed.edges.getValue(StationId(2)) == listOf(StationId(4)))
    check(parsed.edges.getValue(StationId(3)) == listOf(StationId(4)))
    check(parsed.edges.getValue(StationId(4)).isEmpty())
    check(parsed.start == StationId(1))
}

private fun testRenderChosenFormat() {
    val first = StationId(1)
    val second = StationId(4)

    val rendered = renderAnswer(
        stations = listOf(first, second),
        answer = mapOf(
            first to emptySet(),
            second to linkedSetOf(CargoId(10), CargoId(20)),
        ),
    )

    check(rendered == "1:\n4: 10 20") {
        "renderAnswer changed the local display format: $rendered"
    }
}

fun main() {
    testParseProblemMatchesStatementLayout()
    testSingleEdge()
    testMergeFromTwoPaths()
    testRenderChosenFormat()
    testUnloadWorks()
    testStartStationOnly()
    testCycleBackToStart()

    println("self-tests: OK")
}
