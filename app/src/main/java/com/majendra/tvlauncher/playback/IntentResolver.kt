package com.majendra.tvlauncher.playback
import android.content.Context
import android.content.Intent
import android.net.Uri

class IntentResolver(private val context:Context) {
    fun resolve(uri:String,packageName:String?):Intent? = runCatching {
        val data=if(uri.startsWith("intent:")) Intent.parseUri(uri,Intent.URI_INTENT_SCHEME).data else Uri.parse(uri)
        if(data==null)return null
        if(data.scheme !in setOf("http","https","stremio","content","app"))return null
        if(data.scheme=="content"&&data.authority!="android.media.tv")return null
        val intent=Intent(Intent.ACTION_VIEW,data).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // A TV channel replaces TCL's earlier player instead of stacking on it; otherwise Back reveals the
        // previous channel's player rather than returning here.
        if(data.scheme=="content")intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if(intent.resolveActivity(context.packageManager)!=null)intent else null
    }.getOrNull()
    fun app(packageName:String):Intent?=context.packageManager.getLeanbackLaunchIntentForPackage(packageName)?:context.packageManager.getLaunchIntentForPackage(packageName)
        ?: if(packageName=="com.google.android.apps.tv.launcherx") Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName).takeIf { it.resolveActivity(context.packageManager)!=null } else null
}
