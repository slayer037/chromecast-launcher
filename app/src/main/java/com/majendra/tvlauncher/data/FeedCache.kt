package com.majendra.tvlauncher.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class FeedCache(context: Context) {
    // Resolved and created on first use, which is always off the main thread; constructing the cache touches no disk.
    private val folder by lazy { File(context.filesDir,"feeds").apply { mkdirs() } }
    fun clear(id:String) { File(folder,"$id.json").delete() }
    fun read(id:String):FeedResult? = runCatching {
        val file=File(folder,"$id.json")
        if(!file.exists() || file.length()>3_000_000) return null
        decode(JSONObject(file.readText())).copy(status=FeedStatus.CACHED)
    }.getOrNull()
    fun write(id:String,result:FeedResult) {
        if(result.status!=FeedStatus.FRESH || result.rows.isEmpty())return
        val atomic=AtomicFile(File(folder,"$id.json"))
        var stream:java.io.FileOutputStream?=null
        try { stream=atomic.startWrite(); stream.write(encode(result).toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch(e:Exception) { stream?.let { atomic.failWrite(it) } }
    }
    companion object {
        fun encode(result:FeedResult):JSONObject = JSONObject().put("status",result.status.name).put("message",result.message).put("updatedAt",result.updatedAt).put("rows",JSONArray().apply {
            result.rows.forEach { row -> put(JSONObject().put("id",row.id).put("title",row.title).put("emptyMessage",row.emptyMessage).put("items",JSONArray().apply {
                row.items.forEach { item -> put(JSONObject().put("id",item.id).put("source",item.source).put("title",item.title)
                    .put("subtitle",item.subtitle).put("description",item.description).put("imageUrl",item.imageUrl).put("landscapeImageUrl",item.landscapeImageUrl)
                    .put("startTime",item.startTime).put("endTime",item.endTime).put("isLive",item.isLive).put("progress",item.progress)
                    .put("playbackUri",item.playbackUri).put("metadata",JSONObject(item.metadata))) }
            })) }
        })
        fun decode(json:JSONObject):FeedResult {
            fun JSONObject.nullString(key:String):String?=if(has(key)&&!isNull(key))getString(key) else null
            val rows=json.getJSONArray("rows")
            return FeedResult((0 until rows.length()).map { i -> val r=rows.getJSONObject(i); val items=r.getJSONArray("items")
                ContentRow(r.getString("id"),r.getString("title"),(0 until items.length()).map { j -> val v=items.getJSONObject(j);val m=v.optJSONObject("metadata")?:JSONObject()
                    ContentItem(v.getString("id"),v.getString("source"),v.getString("title"),v.nullString("subtitle"),v.nullString("description"),v.nullString("imageUrl"),v.nullString("landscapeImageUrl"),
                        if(v.has("startTime")&&!v.isNull("startTime"))v.getLong("startTime") else null,if(v.has("endTime")&&!v.isNull("endTime"))v.getLong("endTime") else null,v.optBoolean("isLive"),
                        if(v.has("progress")&&!v.isNull("progress"))v.getDouble("progress").toFloat() else null,v.nullString("playbackUri"),m.keys().asSequence().associateWith { m.getString(it) })
                },r.nullString("emptyMessage"))
            },FeedStatus.valueOf(json.getString("status")),json.optString("message"),json.getLong("updatedAt"))
        }
    }
}
