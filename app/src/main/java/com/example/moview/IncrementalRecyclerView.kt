package com.example.moview

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class IncrementalRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {

    var isScrolling : Boolean = false

    init {
        layoutManager = object : LinearLayoutManager(context, HORIZONTAL, false) { override fun canScrollHorizontally() = false }
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        isFocusable = true
        isFocusableInTouchMode = true
    }

    var blockDown = false
    var blockUp = false

    override fun onScrollStateChanged(state: Int) {
        if (state == RecyclerView.SCROLL_STATE_IDLE)
            isScrolling = false
        else
            isScrolling = true
        super.onScrollStateChanged(state)
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        if (direction == View.FOCUS_DOWN && blockDown) return focused
        if (direction == View.FOCUS_UP && blockUp) return focused
        return super.focusSearch(focused, direction)
    }
}
