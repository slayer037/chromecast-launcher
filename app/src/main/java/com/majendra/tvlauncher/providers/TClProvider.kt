package com.majendra.tvlauncher.providers

import android.content.Context
import android.content.pm.PackageManager
import android.media.tv.TvInputManager
import android.media.tv.TvContract
import com.majendra.tvlauncher.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The former TCL TV section. Its channels and inputs now appear in Live TV; kept for saved feeds. */
class TClProvider(private val context: Context): FeedAdapter {
    override val id="tcl"
    override val displayName="TCL TV"
    override suspend fun isAvailable() = context.packageManager.getLeanbackLaunchIntentForPackage("com.tcl.tv") != null
    override suspend fun load(forceRefresh:Boolean):FeedResult = withContext(Dispatchers.IO) {
        val inputs=TvChannelSource.inputs(context).map { input ->
            ContentItem(input.id,id,input.label, subtitle="Open native TV input",playbackUri=input.playbackUri,metadata=mapOf("inputId" to input.id))
        }
        val channels=TvChannelSource.channels(context).map { ch ->
            ContentItem("channel:${ch.id}",id,ch.name,
                subtitle=if(ch.number.isBlank()) "Watch live" else "Channel ${ch.number}",
                playbackUri=ch.playbackUri,
                metadata=mapOf("channelName" to ch.name,"channelNumber" to ch.number,"inputId" to ch.inputId,"type" to "channel"))
        }
        val rows=listOf(
            ContentRow("tcl-antenna","Antenna channels",channels.filter { TclChannelPolicy.isTuner(it.metadata["inputId"]) }),
            ContentRow("tcl-channel","TCL Channel",channels.filter { TclChannelPolicy.isTclChannel(it.metadata["inputId"]) }),
            ContentRow("inputs","HDMI & inputs",inputs))
        val message=if(channels.isEmpty()) "No channels found. Scan for antenna channels in the TV's own settings, then refresh." else "Native tuner, TCL Channel and HDMI inputs"
        FeedResult(rows.filter { it.items.isNotEmpty() },FeedStatus.FRESH,message)
    }
    override suspend fun resolvePlayback(item:ContentItem):PlaybackResult = item.playbackUri?.takeIf { it.isNotBlank() }?.let { PlaybackResult.Ready(it,item.metadata["package"]?:"com.tcl.tv") }?: PlaybackResult.App("com.tcl.tv")
}

internal data class TvChannel(val id:Long, val name:String, val number:String, val inputId:String, val iconUrl:String?=null) {
    val playbackUri:String get() = TvContract.buildChannelUri(id).toString()
    /** The logo the TV input stored for this channel, if any; loading falls back when it has none. */
    val logoUri:String get() = TvContract.buildChannelLogoUri(id).toString()
    val tclChannel get() = TclChannelPolicy.isTclChannel(inputId)
}
internal data class TvInput(val id:String, val label:String, val playbackUri:String)

/** The TV's own channel list and HDMI inputs, read with the TV-listings permission. */
internal object TvChannelSource {
    fun channels(context:Context):List<TvChannel> {
        if(context.checkSelfPermission("android.permission.READ_TV_LISTINGS")!=PackageManager.PERMISSION_GRANTED) return emptyList()
        return runCatching {
            val result=mutableListOf<TvChannel>()
            context.contentResolver.query(TvContract.Channels.CONTENT_URI,
                arrayOf("_id","display_name","display_number","input_id","browsable","app_link_icon_uri"),null,null,null)?.use { c ->
                while(c.moveToNext() && result.size<TclChannelPolicy.MAX_CHANNELS) {
                    val inputId=c.getString(3)
                    val name=c.getString(1)?.trim().orEmpty()
                    if(c.getInt(4)==0 || !TclChannelPolicy.supported(inputId) || name.isBlank()) continue
                    // Streaming inputs such as TCL Channel often publish their logo as the app-link icon.
                    val icon=c.getString(5)?.takeIf { it.startsWith("https://") }
                    result.add(TvChannel(c.getLong(0),name,c.getString(2)?.trim().orEmpty(),inputId.orEmpty(),icon))
                }
            }
            result.sortedWith(compareBy({ TclChannelPolicy.sortKey(it.number) },{ it.name }))
        }.getOrDefault(emptyList())
    }

    fun inputs(context:Context):List<TvInput> {
        val manager=context.getSystemService(TvInputManager::class.java) ?: return emptyList()
        return runCatching { manager.tvInputList.filter { it.isPassthroughInput }.sortedBy { it.loadLabel(context).toString() }.map { input ->
            TvInput(input.id,input.loadLabel(context).toString(),TvContract.buildChannelUriForPassthroughInput(input.id).toString())
        }}.getOrDefault(emptyList())
    }
}

internal object TclChannelPolicy {
    const val MAX_CHANNELS = 300
    fun isTuner(inputId:String?) = inputId?.startsWith("com.tcl.tvinput/.TunerInputService")==true
    fun isTclChannel(inputId:String?) = inputId?.startsWith("com.tcl.channelplus/")==true
    fun supported(inputId:String?) = isTuner(inputId) || isTclChannel(inputId)
    /** "7", "7.1" and "72" sort numerically; unnumbered channels go last. */
    fun sortKey(number:String?):Double = number?.toDoubleOrNull() ?: Double.MAX_VALUE
}
