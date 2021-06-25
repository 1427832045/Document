package com.seer.srd.domain

import kotlin.math.ceil

@Suppress("unused", "MemberVisibilityCanBePrivate", "CanBeParameter")
class PagingResult<T>(
    var total: Long = 0,
    var page: List<T> = emptyList(),
    var pageNo: Int = 0,
    var pageSize: Int = 0
) {
    var pageNum: Int = 0

    init {
        pageNum = ceil(total.toDouble() / pageSize).toInt()
    }
}