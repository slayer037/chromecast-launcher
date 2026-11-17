package com.majendra.tvlauncher.playback
import android.content.Context
import android.content.Intent
import com.majendra.tvlauncher.data.*
import com.majendra.tvlauncher.diagnostics.DiagnosticLog
import com.majendra.tvlauncher.settings.HomeCompatibility
class PlaybackRouter(private val context:Context) {
    private val resolver=IntentResolver(context)
    fun open(result:PlaybackResult,fallbackPackage:String,item:ContentItem?=null):String? =
        PlaybackDispatchPolicy.dispatch(result) { openAllowed(result,fallbackPackage,item) }
    private fun openAllowed(result:PlaybackResult,fallbackPackage:String,item:ContentItem?):String? {
        if (!LiveTvPolicy.targetAllowed(context.liveTvEnabled,null,fallbackPackage) ||
            (item != null && !LiveTvPolicy.itemAllowed(context.liveTvEnabled,item)) ||
            !LiveTvPolicy.targetAllowed(context.liveTvEnabled,(result as? PlaybackResult.Ready)?.uri,when(result) {
                is PlaybackResult.Ready -> result.packageName
                is PlaybackResult.App -> result.packageName
                else -> null
            })) return "Live TV is unavailable on this device."
        val target=when(result) {
            is PlaybackResult.Ready->resolver.resolve(result.uri,result.packageName)
            is PlaybackResult.App->resolver.app(result.packageName)
            is PlaybackResult.Unavailable->null
        }
        val intent=if(fallbackPackage==HomeCompatibility.GOOGLE_PACKAGE) HomeCompatibility.googleHomeIntent() else target?:resolver.app(fallbackPackage)
        if(intent==null) return "App isn't installed on this TV."
        return try {
            if(fallbackPackage==HomeCompatibility.GOOGLE_PACKAGE) HomeCompatibility(context).bypassForRecovery()
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            DiagnosticLog.event(context,"playback_handoff",buildMap {
                put("service",item?.source);put("package",fallbackPackage)
                put("contentResolved",target!=null && result is PlaybackResult.Ready)
                if(item?.source!="stremio"&&item?.metadata?.get("accountAuthorised")!="true") {put("itemId",item?.id);put("title",item?.title)}
            })
            if(target==null && result is PlaybackResult.Ready) "Opened app; this title link isn't supported." else null
        } catch(e:Exception) { DiagnosticLog.event(context,"playback_error",mapOf("error" to e.javaClass.simpleName,"package" to fallbackPackage)); "Unable to open this app. Try again." }
    }
    fun openApp(packageName:String)=open(PlaybackResult.App(packageName),packageName)
}
