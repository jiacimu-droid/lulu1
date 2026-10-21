package com.jiacimu.lulu.data

/** User's current intent outranks old telephone music and alarm plans. */
internal object CallMediaUserIntent {
    fun stopped(raw: String): Boolean {
        val text = raw.replace(Regex("\\s+"), "")
        return Regex("(?:(?:把|将|我|已经|刚才|先|现在).{0,9})?(?:音乐|歌曲|歌|播放器).{0,12}(?:关掉|关了|停了|停止|暂停|别放|不要放|不想听)|(?:别|不要|停止|暂停|关掉|不想).{0,6}(?:放歌|播放音乐|听歌|音乐|歌曲|播放器|听音乐)").containsMatchIn(text)
    }
    fun restarted(raw: String): Boolean {
        val text = raw.replace(Regex("\\s+"), "")
        return !stopped(text) && Regex("(?:重新|继续|再|现在|开始).{0,6}(?:播放|放)(?:音乐|歌)|(?:给我|帮我|请|你).{0,4}(?:再|继续|重新)?(?:播放|放)(?:音乐|歌)|(?:把).{0,4}(?:音乐|歌).{0,3}(?:打开|放起来|播放)|^(?:播放|放)(?:音乐|歌)(?:吧|呀|一下)?$").containsMatchIn(text)
    }
}
