package com.jiacimu.lulu.study

/** Keeps persisted timer arithmetic explicit when seconds are stored as Int. */
internal operator fun Int.times(other: Long): Long = toLong() * other
