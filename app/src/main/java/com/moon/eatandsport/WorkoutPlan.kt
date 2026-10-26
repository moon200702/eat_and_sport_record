package com.moon.eatandsport

import java.util.UUID

data class WorkoutPlan(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(name.isNotBlank() && name.length <= 80)
        require(description.length <= 20_000)
    }
}

data class NoteSnapshot(val records: List<Record>, val plans: List<WorkoutPlan> = emptyList()) {
    val dailyRecords get() = records.filter { it.planId == null }
    fun items(planId: String) = records.filter { it.planId == planId }.sortedBy { it.planPosition }
}
