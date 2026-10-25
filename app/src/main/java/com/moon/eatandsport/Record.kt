package com.moon.eatandsport

import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

enum class RecordType { FOOD, EXERCISE }

data class Record(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val type: RecordType,
    val name: String,
    val calories: Int,
    val minutes: Int = 0,
    val time: LocalTime = LocalTime.now().withSecond(0).withNano(0),
    val note: String = "",
    val attachments: List<Attachment> = emptyList(),
    val timeKnown: Boolean = true,
) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(name.isNotBlank() && name.length <= 80)
        require(calories in 0..50_000)
        require(if (type == RecordType.EXERCISE) minutes in 0..1_440 else minutes == 0)
        require(note.length <= 20_000)
        require(time.second == 0 && time.nano == 0)
        require(attachments.size <= 10)
        require(attachments.map { it.fileName }.distinct().size == attachments.size)
    }
}

data class DaySummary(val intake: Long, val burned: Long, val minutes: Long)

fun summarize(records: List<Record>, date: LocalDate): DaySummary {
    val day = records.filter { it.date == date }
    return DaySummary(
        intake = day.filter { it.type == RecordType.FOOD }.sumOf { it.calories.toLong() },
        burned = day.filter { it.type == RecordType.EXERCISE }.sumOf { it.calories.toLong() },
        minutes = day.sumOf { it.minutes.toLong() },
    )
}
