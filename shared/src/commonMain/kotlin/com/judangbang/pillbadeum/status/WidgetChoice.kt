package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.design.PersonPalette
import com.judangbang.pillbadeum.usecase.PersonColors

/**
 * Who one home-screen widget shows (TERM-026, SPEC-005, REQ-025): everyone, or one person. Each widget keeps its
 * choice in its own Glance state, outside the database, so the choice is not backed up (05 ERD section 5.2,
 * TASK-012). A widget without a choice is `null`: after the update that brought the choice, only a widget restored
 * from a backup has none (supplement R-3, RR-74).
 */
sealed interface WidgetChoice {
    data object Everyone : WidgetChoice

    data class Person(val personId: String) : WidgetChoice

    companion object {
        /** The stored text of [Everyone]. A person id is a UUID, so it never equals this. */
        const val EVERYONE: String = "*"

        fun encode(choice: WidgetChoice): String = when (choice) {
            Everyone -> EVERYONE
            is Person -> choice.personId
        }

        /** The choice stored as [stored]; null when nothing is stored. */
        fun decode(stored: String?): WidgetChoice? = when {
            stored.isNullOrEmpty() -> null
            stored == EVERYONE -> Everyone
            else -> Person(stored)
        }
    }
}

/** What a widget draws for its choice (SCR-001). */
sealed interface WidgetView {
    /** The tiles of [person], or of everyone when it is null (REQ-025). */
    data class Items(val person: PersonEntity?) : WidgetView

    /**
     * No tiles, only "위젯을 다시 설정해 주세요", which opens SCR-002 (SCR-001#reconfigure, REQ-079). [archived]: the chosen
     * person is archived. A tap on a tile still drawn from before records nothing (SPEC-009).
     */
    data class Reconfigure(val archived: Boolean) : WidgetView
}

/**
 * The view of a widget with this choice while [people] are not archived (REQ-018, REQ-025, REQ-079, SPEC-027):
 * - no choice: everyone's tiles with one person, the reconfigure line from two people on (supplement R-3);
 * - a person no longer among [people]: the reconfigure line, never everyone's tiles, even when one person is left.
 */
fun WidgetChoice?.view(people: List<PersonEntity>): WidgetView = when (this) {
    null -> if (showsPeople(people.size)) WidgetView.Reconfigure(archived = false) else WidgetView.Items(null)
    WidgetChoice.Everyone -> WidgetView.Items(null)
    is WidgetChoice.Person -> people.firstOrNull { it.id == personId }?.let { WidgetView.Items(it) }
        ?: WidgetView.Reconfigure(archived = true)
}

/** One line of SCR-002 (09 WidgetPick): `전체 · 나·엄마의 약 4개`, then each person with their own count. */
data class PickOption(
    val choice: WidgetChoice,
    /** `전체` or the person's name. */
    val title: String,
    /** `나·엄마의 약 4개`, `약 2개`. */
    val detail: String,
    /** The person's color and first letter on the lid; null for 전체, whose lid is the dark four squares. */
    val palette: PersonPalette?,
    val letter: String?,
)

object WidgetPick {
    /**
     * The choices of SCR-002 in order: 전체 first, then every person who is not archived, oldest first (REQ-025). The
     * counts are the items the widget would show: those of [StatusBoard.items].
     */
    fun options(board: StatusBoard): List<PickOption> {
        val everyone = PickOption(
            choice = WidgetChoice.Everyone,
            title = Labels.PICK_EVERYONE,
            detail = Labels.pickEveryoneDetail(board.people.map { it.name }, board.items.size),
            palette = null,
            letter = null,
        )
        return listOf(everyone) + board.people.map { person ->
            PickOption(
                choice = WidgetChoice.Person(person.id),
                title = person.name,
                detail = Labels.pickPersonDetail(board.items.count { it.person.id == person.id }),
                palette = PersonColors.palette(person.color),
                letter = firstLetter(person.name),
            )
        }
    }

    /** 전체 alone, without a count: what SCR-002 offers when the people cannot be read (Claude default). */
    fun everyoneOnly(): PickOption =
        PickOption(choice = WidgetChoice.Everyone, title = Labels.PICK_EVERYONE, detail = "", palette = null, letter = null)

    /** What TalkBack reads for [option]: `전체, 나·엄마의 약 4개`, `엄마, 약 2개`. */
    fun spoken(option: PickOption): String = listOf(option.title, option.detail).filter { it.isNotEmpty() }.joinToString(", ")

    /** The first letter of [name] for the lid (09 `.lid`), keeping a surrogate pair whole. */
    fun firstLetter(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed[0].isHighSurrogate() && trimmed.length > 1) trimmed.substring(0, 2) else trimmed.substring(0, 1)
    }
}
