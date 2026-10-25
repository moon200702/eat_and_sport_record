package com.moon.eatandsport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class RecordTest {
    private val today = LocalDate.of(2026, 10, 26)

    @Test fun summarySeparatesFoodExerciseAndDates() {
        val records = listOf(
            Record(date = today, type = RecordType.FOOD, name = "午餐", calories = 650),
            Record(date = today, type = RecordType.FOOD, name = "水果", calories = 120),
            Record(date = today, type = RecordType.EXERCISE, name = "慢跑", calories = 250, minutes = 30),
            Record(date = today.minusDays(1), type = RecordType.FOOD, name = "昨天", calories = 900),
        )
        assertEquals(DaySummary(770, 250, 30), summarize(records, today))
        assertEquals(DaySummary(0, 0, 0), summarize(records, today.plusDays(1)))
    }

    @Test fun rejectsBlankAndOverlongNames() {
        listOf(" ", "a".repeat(81)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                Record(date = today, type = RecordType.FOOD, name = name, calories = 100)
            }
        }
    }

    @Test fun rejectsInvalidCalories() {
        listOf(-1, 50_001).forEach { calories ->
            assertThrows(IllegalArgumentException::class.java) {
                Record(date = today, type = RecordType.FOOD, name = "餐點", calories = calories)
            }
        }
    }

    @Test fun exerciseRequiresValidMinutes() {
        listOf(-1, 1_441).forEach { minutes ->
            assertThrows(IllegalArgumentException::class.java) {
                Record(date = today, type = RecordType.EXERCISE, name = "慢跑", calories = 100, minutes = minutes)
            }
        }
    }

    @Test fun foodCannotHaveExerciseMinutes() {
        assertThrows(IllegalArgumentException::class.java) {
            Record(date = today, type = RecordType.FOOD, name = "餐點", calories = 100, minutes = 1)
        }
    }
}
