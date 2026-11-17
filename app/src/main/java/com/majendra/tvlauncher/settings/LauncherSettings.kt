package com.majendra.tvlauncher.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import com.majendra.tvlauncher.data.Services
import com.majendra.tvlauncher.data.LiveTvPolicy
import com.majendra.tvlauncher.data.liveTvEnabled
import com.majendra.tvlauncher.diagnostics.RemoteNavigationDiagnostics

class LauncherSettings(context:Context) {
    private val liveTvEnabled = context.liveTvEnabled
    private val prefs=context.getSharedPreferences("launcher",Context.MODE_PRIVATE)
    init {
        if(!prefs.getBoolean("content-sections-v9",false)) {
            val requested=listOf("stremio","kayo","kids","youtube","movies","series")
            val old=prefs.getString("order",null)?.split(",").orEmpty()
            val remaining=(old+Services.all.map{it.id}).filter{it !in requested && Services.all.any{service->service.id==it}}.distinct()
            prefs.edit().putString("order",(requested+remaining).joinToString(",")).putBoolean("content-sections-v9",true).apply()
        }
    }
    init {
        // YouTube sits just above Live TV, wherever the sections were reordered to.
        if(!prefs.getBoolean("youtube-above-live-v10",false)) {
            val list=order().filter{it!="youtube"}.toMutableList()
            list.add(list.indexOf("live").coerceAtLeast(0),"youtube")
            prefs.edit().putString("order",list.joinToString(",")).putBoolean("youtube-above-live-v10",true).apply()
        }
    }
    fun order():List<String> {
        val saved=prefs.getString("order",null)?.split(",").orEmpty().filter { id->Services.all.any { it.id==id } }.distinct()
        val result=(saved+Services.all.map { it.id }.filter { it !in saved }).toMutableList()
        if ("starred" !in saved) { result.remove("starred"); result.add((result.indexOf("series")+1).coerceAtLeast(0),"starred") }
        return result.filter { LiveTvPolicy.sectionAllowed(liveTvEnabled, it) }
    }
    fun feedVisible(id:String)=prefs.getBoolean("feed-$id",true)
    fun lastService()=prefs.getString("last","youtube")?.takeIf { id->Services.all.any { it.id==id } && LiveTvPolicy.sectionAllowed(liveTvEnabled,id) }?:"youtube"
    fun select(id:String){prefs.edit().putString("last",id).apply()}
    fun show(context:Context,onChanged:()->Unit,onRefresh:()->Unit,onRecovery:()->Unit,
             onRows:()->Unit={}) {
        // TV settings lead the list: with Google TV's home disabled this menu is the way in to them.
        val googleHome=SystemSettings.googleHomeAvailable(context)
        val labels=listOf("TV settings","Reorder sidebar","Show or hide feeds","Refresh selected feed","Open original Google TV","About & diagnostics","Chromecast Launcher Home compatibility","","Content rows & favourites","","Local watched list","Remote navigation diagnostics")
        val shown=labels.indices.filter { (googleHome || labels[it]!="Open original Google TV") && it !in setOf(7, 9) }
        AlertDialog.Builder(context).setTitle("Settings").setItems(shown.map { labels[it] }.toTypedArray()) { _,position->
            when(shown[position]-1) {
                -1->SystemSettings.show(context)
                0->pickService(context,"Move service", { id->
                    AlertDialog.Builder(context).setTitle(Services.get(id).displayName).setItems(arrayOf("Move up","Move down")) { _,direction->
                        val list=order().toMutableList();val from=list.indexOf(id);val to=(from+if(direction==0)-1 else 1).coerceIn(0,list.lastIndex)
                        java.util.Collections.swap(list,from,to);prefs.edit().putString("order",list.joinToString(",")).apply();onChanged()
                    }.show()
                })
                1->{val list=order();AlertDialog.Builder(context).setTitle("Visible content feeds").setMultiChoiceItems(list.map { Services.get(it).displayName }.toTypedArray(),list.map { feedVisible(it) }.toBooleanArray()) { _,i,checked->prefs.edit().putBoolean("feed-${list[i]}",checked).apply() }.setPositiveButton("Done") { _,_->onChanged() }.show()}
                2->onRefresh()
                3->onRecovery()
                5->HomeCompatibility(context).show()
                7->onRows()
                9->WatchedSyncSettings.show(context)
                10->RemoteNavigationDiagnostics.get(context).showControls(context)
                4->AlertDialog.Builder(context).setTitle("${context.getString(com.majendra.tvlauncher.R.string.app_name)}").setMessage("Native TV feeds and public catalogues. Videos play in the original apps. No HOME default or system packages are changed by this launcher.\n\nFeed states and handoffs are logged locally for ADB export. No account passwords are requested.").setPositiveButton("Close",null).show()
            }
        }.setNegativeButton("Close",null).show()
    }
    private fun pickService(context:Context,title:String,selected:(String)->Unit) {val list=order();AlertDialog.Builder(context).setTitle(title).setItems(list.map { Services.get(it).displayName }.toTypedArray()) { _,i->selected(list[i]) }.setNegativeButton("Cancel",null).show()}
}
