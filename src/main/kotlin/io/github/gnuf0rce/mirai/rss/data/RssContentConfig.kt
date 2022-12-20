package io.github.gnuf0rce.mirai.rss.data

import net.mamoe.mirai.console.data.*

@PublishedApi
internal object RssContentConfig : ReadOnlyPluginConfig("RssContentConfig") {
    @ValueDescription("Content limit length")
    val limit: Int by value(1024)

    @ValueDescription("Content send by forward")
    val forward: Boolean by value(false)
}