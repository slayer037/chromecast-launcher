package com.majendra.tvlauncher.data
import kotlinx.coroutines.*
class FeedScheduler(private val scope:CoroutineScope) {
    private var job:Job?=null
    fun start(refresh:suspend ()->Unit) { stop();job=scope.launch { while(isActive) {delay(1_000);refresh()} } }
    fun stop(){ job?.cancel();job=null }
}
