package com.majendra.tvlauncher.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Search text stays in the activity, never in account files or diagnostics. */
class SearchControls(context:Context, private val onSearch:(String)->Unit,
                     private val onStremio:()->Unit, private val onPicker:()->Unit={}, private val onRefresh:()->Unit={},
                     private val onTonight:()->Unit={}, private val onStarred:()->Unit={},
                     private val onFilter:()->Unit={}, private val onClearFilter:()->Unit={}):LinearLayout(context) {
    /** Streaming Movies has search plus an optional picker; legacy picker-only and YouTube modes remain supported. */
    enum class Mode { SEARCH, PICKER, YOUTUBE, STARRED }
    private var label="Movies"
    private var query=""
    private var mode=Mode.SEARCH
    private var streaming=false
    private var filterSummary:String?=null
    private var filterable=false
    private val search=button("Search movies"){showSearch()}
    private val clear=button("Clear"){onSearch("")}
    private val app=button("Search in Stremio"){if(mode==Mode.YOUTUBE)onRefresh()else onStremio()}
    private val picker=button("🎲  Movie picker"){onPicker()}
    private val tonight=button("Refresh tonight"){onTonight()}
    private val starred=button("Sort & filter"){onStarred()}
    private val filter=button("Filter"){onFilter()}
    private val clearFilter=button("Clear filter"){onClearFilter()}
    init {
        orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        addView(search,LayoutParams(0,context.dp(36),1f).apply {marginEnd=context.dp(6)})
        addView(picker,LayoutParams(context.dp(200),context.dp(36)).apply {marginEnd=context.dp(6)})
        addView(filter,LayoutParams(context.dp(FILTER_WIDTH),context.dp(36)).apply {marginEnd=context.dp(6)})
        addView(clearFilter,LayoutParams(context.dp(118),context.dp(36)).apply {marginEnd=context.dp(6)})
        addView(clear,LayoutParams(context.dp(58),context.dp(36)).apply {marginEnd=context.dp(6)})
        addView(app,LayoutParams(context.dp(155),context.dp(36)))
        addView(tonight,LayoutParams(context.dp(145),context.dp(36)).apply {marginStart=context.dp(6)})
        addView(starred,LayoutParams(context.dp(160),context.dp(36)))
        filter.visibility=GONE;clearFilter.visibility=GONE
        visibility=GONE
    }
    fun setStreamingMode(enabled:Boolean) {
        if(streaming==enabled)return
        streaming=enabled
        (search.layoutParams as LayoutParams).apply {width=if(enabled)context.dp(164)else 0;weight=if(enabled)0f else 1f}.also {search.layoutParams=it}
        (picker.layoutParams as LayoutParams).apply {width=context.dp(if(enabled)112 else 200)}.also {picker.layoutParams=it}
        (app.layoutParams as LayoutParams).apply {width=context.dp(if(enabled)140 else 155)}.also {app.layoutParams=it}
        render(label,query,mode,filterSummary,filterable)
    }
    /** [filter]: the active filter in words, or null when the section shows its normal rows. [filterable]: this section offers Filter. */
    fun render(serviceLabel:String,value:String,mode:Mode=Mode.SEARCH,filter:String?=null,filterable:Boolean=false) {
        label=serviceLabel;query=value;this.mode=mode;filterSummary=filter;this.filterable=filterable
        val shown=FilterControlPolicy.controls(filterable&&streaming,filter)
        picker.visibility=if(mode==Mode.PICKER&&(!streaming||value.isBlank())&&shown.picker)VISIBLE else GONE
        picker.text=if(streaming)"Pick a movie" else "🎲  Movie picker"
        listOf(search,clear,app).forEach{it.visibility=if(mode==Mode.PICKER&&!streaming)GONE else VISIBLE}
        search.text=if(value.isBlank())"Search ${label.lowercase()}"else"Search: $value"
        search.contentDescription=search.text
        clear.isEnabled=value.isNotBlank();clear.alpha=if(clear.isEnabled)1f else .4f
        app.text=if(mode==Mode.YOUTUBE)"↻  New mix" else "Search in Stremio"
        app.isEnabled=mode==Mode.YOUTUBE||value.isNotBlank();app.alpha=if(app.isEnabled)1f else .4f
        if(streaming) {
            val clearingFocused=clear.hasFocus()&&value.isBlank()
            app.visibility=if(value.isNotBlank())VISIBLE else GONE
            clear.visibility=if(value.isNotBlank())VISIBLE else GONE
            if(clearingFocused)search.requestFocus()
        }
        tonight.visibility=if(streaming && mode in setOf(Mode.SEARCH,Mode.PICKER) && value.isBlank() && shown.tonight)VISIBLE else GONE
        starred.visibility=if(mode==Mode.STARRED)VISIBLE else GONE
        if(mode==Mode.STARRED)listOf(search,clear,app,picker).forEach{it.visibility=GONE}
        // The active filter is spelled out on its own control and takes the spare width; Clear filter sits beside it.
        val clearingFilterFocused=clearFilter.hasFocus()&&!shown.clear
        this.filter.text=shown.label;this.filter.contentDescription=shown.label
        (this.filter.layoutParams as LayoutParams).let {params->
            val width=if(shown.clear)0 else context.dp(FILTER_WIDTH);val weight=if(shown.clear)1f else 0f
            if(params.width!=width||params.weight!=weight){params.width=width;params.weight=weight;this.filter.layoutParams=params}
        }
        this.filter.visibility=if(shown.filter)VISIBLE else GONE
        clearFilter.visibility=if(shown.clear)VISIBLE else GONE
        if(clearingFilterFocused)this.filter.requestFocus()
    }
    fun showSearch() {
        val input=EditText(context).apply {
            setSingleLine(true);hint=if(mode==Mode.YOUTUBE)"Videos, channels or topics" else "Title, actor or keyword";setText(query)
            filters=arrayOf(InputFilter.LengthFilter(200));imeOptions=EditorInfo.IME_ACTION_SEARCH
            setSelectAllOnFocus(true)
        }
        val dialog=AlertDialog.Builder(context).setTitle("Search ${label.lowercase()}")
            .setView(input).setPositiveButton("Search",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener {
            fun submit(){onSearch(input.text.toString().trim());dialog.dismiss()}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{submit()}
            input.setOnEditorActionListener {_,action,_->if(action==EditorInfo.IME_ACTION_SEARCH){submit();true}else false}
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }
    private fun button(text:String,click:()->Unit):TextView=Theme.label(context,12f,true).apply {
        this.text=text;gravity=Gravity.CENTER;isFocusable=true;isFocusableInTouchMode=true
        isClickable=true;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
        setPadding(context.dp(8),0,context.dp(8),0);background=Theme.shape(context)
        setOnFocusChangeListener{v,f->Theme.focus(v,f)};setOnClickListener{click()}
    }
    private companion object { const val FILTER_WIDTH=96 }
}

/** Which controls the Filter feature shows. While a filter is active the row-shaping controls step aside. */
object FilterControlPolicy {
    data class Controls(val filter:Boolean,val clear:Boolean,val picker:Boolean,val tonight:Boolean,val label:String)
    /** [offered]: this section and build have the Filter control. [active]: the filter in words, or null. */
    fun controls(offered:Boolean,active:String?):Controls {
        val on=offered&&active!=null
        return Controls(offered,on,!on,!on,if(on)"Filter: $active" else "Filter")
    }
}
