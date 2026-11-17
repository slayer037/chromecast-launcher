package com.majendra.tvlauncher.ui

import android.app.AlertDialog
import android.content.Context
import com.majendra.tvlauncher.providers.cinemeta.CinemetaSort

/**
 * The Filter choices, in the same list-and-pick dialogs as Starred's "Sort & filter": D-pad, OK and Back only.
 * One list shows the five choices with their current values; OK on a line opens its options and returns here.
 * Nothing is applied until "Show results". Back from an options list returns to this list unchanged.
 */
class MovieFilterDialog(private val context: Context, private val section: String, private val genres: List<String>,
    private val currentYear: Int, private val active: Boolean, private val onApply: (MovieFilter) -> Unit, private val onClear: () -> Unit) {

    fun show(filter: MovieFilter, line: Int = 0) {
        val builder = AlertDialog.Builder(context).setTitle("Filter ${MovieFilterPolicy.noun(section)}")
            .setItems(MovieFilterPolicy.lines(filter).toTypedArray()) { _, which ->
                when (which) {
                    0 -> chooseGenres(filter)
                    1 -> chooseYears(filter)
                    2 -> chooseRating(filter)
                    3 -> chooseSort(filter)
                    else -> show(filter.copy(hideSeen = !filter.hideSeen), 4)
                }
            }
            .setPositiveButton("Show results") { _, _ -> onApply(MovieFilterPolicy.normalise(filter, genres, currentYear)) }
            .setNegativeButton("Cancel", null)
        if (active) builder.setNeutralButton("Clear filter") { _, _ -> onClear() }
        val dialog = builder.create()
        dialog.show()
        dialog.listView?.setSelection(line)
    }

    private fun chooseGenres(filter: MovieFilter) {
        val ticked = genres.map { it in filter.genres }.toBooleanArray()
        AlertDialog.Builder(context).setTitle("Genres: a title must have every one you tick")
            .setMultiChoiceItems(genres.toTypedArray(), ticked) { _, index, value -> ticked[index] = value }
            .setPositiveButton("Done") { _, _ -> show(filter.copy(genres = genres.filterIndexed { index, _ -> ticked[index] }.toSet()), 0) }
            .setNeutralButton("Any genre") { _, _ -> show(filter.copy(genres = emptySet()), 0) }
            .setNegativeButton("Back") { _, _ -> show(filter, 0) }
            .setOnCancelListener { show(filter, 0) }.show()
    }

    private fun chooseYears(filter: MovieFilter) {
        val choices = MovieFilterPolicy.yearChoices(currentYear)
        AlertDialog.Builder(context).setTitle("Years")
            .setItems((choices.map { it.first } + "Choose first and last year…").toTypedArray()) { _, which ->
                if (which < choices.size) show(filter.copy(yearFrom = choices[which].second, yearTo = choices[which].third), 1)
                else chooseYear(filter, first = true)
            }
            .setNegativeButton("Back") { _, _ -> show(filter, 1) }
            .setOnCancelListener { show(filter, 1) }.show()
    }

    /** First year, then last year; each list opens at the current choice. */
    private fun chooseYear(filter: MovieFilter, first: Boolean) {
        val years = MovieFilterPolicy.years(currentYear)
        val current = if (first) filter.yearFrom else filter.yearTo
        val labels = listOf(if (first) "No first year" else "No last year") + years.map { it.toString() }
        AlertDialog.Builder(context).setTitle(if (first) "First year" else "Last year")
            .setSingleChoiceItems(labels.toTypedArray(), current?.let { years.indexOf(it) + 1 } ?: 0) { dialog, index ->
                val year = if (index == 0) null else years[index - 1]
                dialog.dismiss()
                if (first) chooseYear(filter.copy(yearFrom = year), first = false)
                else show(MovieFilterPolicy.normalise(filter.copy(yearTo = year), genres, currentYear), 1)
            }
            .setNegativeButton("Back") { _, _ -> show(filter, 1) }
            .setOnCancelListener { show(filter, 1) }.show()
    }

    private fun chooseRating(filter: MovieFilter) {
        val ratings = MovieFilterPolicy.ratings
        AlertDialog.Builder(context).setTitle("Lowest IMDb rating")
            .setSingleChoiceItems(ratings.map(MovieFilterPolicy::ratingLabel).toTypedArray(), ratings.indexOf(filter.minRating).coerceAtLeast(0)) { dialog, index ->
                dialog.dismiss(); show(filter.copy(minRating = ratings[index]), 2)
            }
            .setNegativeButton("Back") { _, _ -> show(filter, 2) }
            .setOnCancelListener { show(filter, 2) }.show()
    }

    private fun chooseSort(filter: MovieFilter) {
        val sorts = CinemetaSort.entries
        AlertDialog.Builder(context).setTitle("Order")
            .setSingleChoiceItems(sorts.map(MovieFilterPolicy::sortLabel).toTypedArray(), sorts.indexOf(filter.sort)) { dialog, index ->
                dialog.dismiss(); show(filter.copy(sort = sorts[index]), 3)
            }
            .setNegativeButton("Back") { _, _ -> show(filter, 3) }
            .setOnCancelListener { show(filter, 3) }.show()
    }
}
