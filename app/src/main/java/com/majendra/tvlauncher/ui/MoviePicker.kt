package com.majendra.tvlauncher.ui

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.ContentRow
import com.majendra.tvlauncher.providers.cinemeta.CinemetaCatalogue
import com.majendra.tvlauncher.providers.cinemeta.CinemetaFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Pick a genre, then get one random well-rated movie at a time until one is a yes. Offers come from the whole
 * indexed Cinemeta catalogue, which is read further as the unseen pool runs low; the loaded rows are the fallback.
 * [hidden]: Seen and Not interested title keys. [genres]: every genre on the full launcher, null to list loaded rows only.
 */
class MoviePicker(private val context:Context, private val scope:CoroutineScope, private val rows:()->List<ContentRow>,
                  private val onWatch:(ContentItem)->Unit, private val onHide:(ContentItem)->Unit,
                  private val source:MoviePickerSource, private val hidden:suspend ()->Collection<String>,
                  private val describe:suspend (ContentItem)->ContentItem, private val genres:List<String>?) {
    private var job:Job?=null
    fun start() {
        val options=MoviePickerPolicy.options(rows(),genres)
        AlertDialog.Builder(context).setTitle("What are you in the mood for?").setItems(options.map{it.first}.toTypedArray()){_,which->
            offer(options[which].first,options[which].second,emptySet())
        }.setNegativeButton("Cancel",null).show()
    }
    private fun offer(label:String,genre:String?,rejected:Set<String>) {
        job?.cancel()
        job=scope.launch {
            val choice=MoviePickerPolicy.offer(genre,rejected,hidden(),Random.Default,source,
                onExtending={Toast.makeText(context,"Finding more ${if(genre==null)"" else "$genre "}movies…",Toast.LENGTH_SHORT).show()},
                loaded={MoviePickerPolicy.genres(rows()).firstOrNull{it.first==label}?.second.orEmpty()})
            if(choice==null){
                Toast.makeText(context,if(rejected.isEmpty())"Movies could not be loaded. Try again in a moment." else "That's every $label movie for now. Pick another genre.",Toast.LENGTH_LONG).show()
                if(rejected.isNotEmpty())start()
                return@launch
            }
            // The index keeps no descriptions; the dialog asks for this one title's.
            val full=if(choice.description.isNullOrBlank())describe(choice)else choice
            FocusedDetailsView.dialog(context,full,false,false,{onWatch(full)},{},openLabel="Yes, watch it",
                neutral="Seen it" to {onHide(full);offer(label,genre,rejected+full.id)},
                negative="No, another" to {offer(label,genre,rejected+full.id)})
        }
    }
}

/** The catalogue as the picker needs it: an interface so its rules run in plain JVM tests. */
interface MoviePickerSource {
    /** Indexed matches not in [excluded], and whether the catalogue has unread pages that could hold more. */
    suspend fun pool(filter:CinemetaFilter,excluded:Collection<String>):Pair<Int,Boolean>
    /** Reads more of the catalogue until [wanted] such matches are indexed, the listing ends or the read fails. */
    suspend fun extend(filter:CinemetaFilter,wanted:Int,excluded:Collection<String>)
    suspend fun draw(filter:CinemetaFilter,excluded:Collection<String>,random:Random):ContentItem?
}

class CatalogueMoviePickerSource(private val catalogue:CinemetaCatalogue):MoviePickerSource {
    override suspend fun pool(filter:CinemetaFilter,excluded:Collection<String>)=catalogue.query(filter,0,1,excluded).let{it.total to it.mayHaveMore}
    override suspend fun extend(filter:CinemetaFilter,wanted:Int,excluded:Collection<String>){catalogue.extend(filter,wanted,excluded)}
    override suspend fun draw(filter:CinemetaFilter,excluded:Collection<String>,random:Random)=catalogue.draw(filter,excluded,random)?.toContentItem()
}

object MoviePickerPolicy {
    /** The picker has always offered only titles rated IMDb 7 or higher. */
    const val MINIMUM_RATING=7.0
    /** Unseen candidates wanted in hand before a draw; fewer than this reads further into the catalogue. */
    const val RESERVE=20
    private const val ANY="Any genre"

    /** "Any genre" plus each loaded genre row, all already limited to IMDb 7+ and unhidden titles. */
    fun genres(rows:List<ContentRow>):List<Pair<String,List<ContentItem>>> {
        val genreRows=rows.filter{it.id.startsWith("movies-genre-")&&it.items.isNotEmpty()}
        val all=rows.filter{it.id.startsWith("movies-")}.flatMap{it.items}.distinctBy{it.id}
        return if(all.isEmpty())emptyList() else listOf(ANY to all)+genreRows.map{it.title to it.items}
    }
    fun next(pool:List<ContentItem>,rejected:Set<String>,random:Random):ContentItem? =
        pool.filter{it.id !in rejected}.takeIf{it.isNotEmpty()}?.random(random)

    /**
     * Label and catalogue genre (null for any). With [catalogue] genres every one is offered, loaded or not;
     * without them only the genres whose rows are loaded.
     */
    fun options(rows:List<ContentRow>,catalogue:List<String>?):List<Pair<String,String?>> =
        listOf(ANY to null)+(catalogue?:genres(rows).drop(1).map{it.first}).map{it to it}

    fun filter(genre:String?)=CinemetaFilter("movie",setOfNotNull(genre),minRating=MINIMUM_RATING)

    /**
     * The next offer. [rejected] ids and [hidden] title keys are never offered. When fewer than [RESERVE] candidates
     * are indexed the catalogue is read further first, so a genre only runs out when the catalogue does. With nothing
     * from the catalogue (offline, nothing indexed yet) the loaded rows are used, as before.
     */
    suspend fun offer(genre:String?,rejected:Set<String>,hidden:Collection<String>,random:Random,source:MoviePickerSource,
                      onExtending:()->Unit={},loaded:()->List<ContentItem>):ContentItem? {
        val filter=filter(genre)
        val excluded=hidden+rejected
        val (remaining,mayHaveMore)=source.pool(filter,excluded)
        if(remaining<RESERVE&&mayHaveMore){onExtending();source.extend(filter,RESERVE*2,excluded)}
        return source.draw(filter,excluded,random)?:next(loaded().filter{"movie:${it.id}" !in hidden},rejected,random)
    }
}
