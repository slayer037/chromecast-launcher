package com.majendra.tvlauncher.settings

import android.app.AlertDialog
import android.content.Context

/** All controls edit local encrypted presentation preferences, not watched history. */
internal object ContentPreferencesDialog {
    fun show(context: Context, state: ContentPreferenceState, select: (String, String?) -> Unit, restore: (String?) -> Unit) {
        AlertDialog.Builder(context).setTitle("Starred & Not interested")
            .setItems(arrayOf("Sort Starred", "Filter Starred by genre", "Restore Not interested (${state.notInterested.size})")) { _, which ->
                when (which) {
                    0 -> {
                        val values = listOf("recent", "rating", "genre")
                        AlertDialog.Builder(context).setTitle("Sort Starred")
                            .setSingleChoiceItems(arrayOf("Recently starred", "Highest IMDb rating", "Genre"), values.indexOf(state.starredSort)) { dialog, index ->
                                select(values[index], state.starredGenre); dialog.dismiss()
                            }.setNegativeButton("Cancel", null).show()
                    }
                    1 -> {
                        val genres = ContentPreferencePolicy.knownStarredGenres(state)
                        val labels = listOf("All genres") + genres
                        AlertDialog.Builder(context).setTitle("Starred genre")
                            .setSingleChoiceItems(labels.toTypedArray(), state.starredGenre?.let { genres.indexOf(it) + 1 } ?: 0) { dialog, index ->
                                select(state.starredSort, if (index == 0) null else genres[index - 1]); dialog.dismiss()
                            }.setNegativeButton("Cancel", null).show()
                    }
                    2 -> AlertDialog.Builder(context).setTitle("Restore Not interested")
                        .setItems(arrayOf("Restore all", "Restore movies", "Restore TV shows")) { _, index ->
                            restore(listOf(null, "movie", "series")[index])
                        }.setNegativeButton("Cancel", null).show()
                }
            }.setNegativeButton("Close", null).show()
    }
}
