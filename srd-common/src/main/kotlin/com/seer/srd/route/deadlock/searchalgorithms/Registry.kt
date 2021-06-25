package com.seer.srd.route.deadlock.searchalgorithms

import com.seer.srd.route.deadlock.DeadlockType
import com.seer.srd.route.domain.Deadlock

typealias PathTable = Map<String, List<String>>
typealias PhasedPathTable = Map<String, PathTable>
typealias MutablePPT = MutableMap<String, MutableMap<String, List<String>>>
typealias DeadlockSolvingSearch = (deadlock: Deadlock) -> PhasedPathTable

val pathTableSearchAlgorithms = mutableMapOf<DeadlockType, MutableMap<String, DeadlockSolvingSearch>>()

fun registerSearchAlgorithms4DeadlockSolving() {
    pathTableSearchAlgorithms[DeadlockType.Exchange] = mutableMapOf()
    pathTableSearchAlgorithms[DeadlockType.Exchange]!!["naive"] = ::naiveSearch4ExchangeDeadlocks
    pathTableSearchAlgorithms[DeadlockType.Cycle] = mutableMapOf()
    pathTableSearchAlgorithms[DeadlockType.Cycle]!!["naive"] = ::pathTableGeneration4CycleDeadlocks
    pathTableSearchAlgorithms[DeadlockType.Other] = mutableMapOf()
    pathTableSearchAlgorithms[DeadlockType.Other]!!["naive"] = { mapOf() }
}
