package io.github.gnuf0rce.mirai.rss

import com.rometools.rome.feed.synd.*
import com.rometools.rome.io.*
import io.github.gnuf0rce.mirai.rss.data.*
import io.github.gnuf0rce.rss.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.utils.ExternalResource.Companion.uploadAsImage
import net.mamoe.mirai.utils.*
import org.jsoup.nodes.*
import org.jsoup.select.*
import java.io.*
import java.net.*
import java.time.*
import javax.net.ssl.*

internal val logger by lazy {
    try {
        RssHelperPlugin.logger
    } catch (_: ExceptionInInitializerError) {
        MiraiLogger.Factory.create(RssHttpClient::class)
    }
}

internal val ImageFolder get() = RssHelperPlugin.dataFolder.resolve("image")

internal val TorrentFolder get() = RssHelperPlugin.dataFolder.resolve("torrent")

internal val client: RssHttpClient by lazy {
    object : RssHttpClient(), RssHttpClientConfig by HttpClientConfig {
        override val ignore: (Throwable) -> Boolean = { cause ->
            when (cause) {
                is UnknownHostException -> false
                is SSLException -> {
                    val message = cause.message.orEmpty()
                    for ((address, ssl) in RubySSLSocketFactory.logs) {
                        if (address in message) {
                            File("./rss_ssl_error.${address}.log").appendText(buildString {
                                appendLine("$address ${OffsetDateTime.now()} $message")
                                appendLine("protocols: ${ssl.protocols.asList()}")
                                appendLine("cipherSuites: ${ssl.cipherSuites.asList()}")
                                appendLine("serverNames: ${ssl.serverNames}")
                            })
                        }
                    }
                    false
                }
                is IOException -> {
                    if (cause.message == "Connection reset") {
                        false
                    } else {
                        logger.warning({ "RssHttpClient IOException" }, cause)
                        true
                    }
                }
                is ParsingFeedException -> {
                    logger.warning({ "RssHttpClient XML解析失败" }, cause)
                    false
                }
                else -> false
            }
        }
        override val timeout: Long get() = HttpClientConfig.timeout
    }
}

@PublishedApi
internal val Url.filename: String get() = encodedPath.substringAfterLast('/').decodeURLPart()

@PublishedApi
internal fun HttpMessage.contentDisposition(): ContentDisposition? {
    return ContentDisposition.parse(headers[HttpHeaders.ContentDisposition] ?: return null)
}

@PublishedApi
internal fun MessageChainBuilder.appendKeyValue(key: String, value: Any?) {
    when (value) {
        null, Unit -> Unit
        is String -> {
            if (value.isNotBlank()) appendLine("$key: $value")
        }
        is Collection<*> -> {
            if (value.isNotEmpty()) appendLine("$key: $value")
        }
        else -> appendLine("$key: $value")
    }
}

@PublishedApi
internal suspend fun SyndEntry.toMessage(subject: Contact, limit: Int, forward: Boolean): Message {
    // 1. 提取转发来源
    val forwardedSource = html?.extractCleanForwardSource() 
        ?: text.orEmpty().extractForwardedFromText()

    // 2. 处理正文内容（彻底移除转发声明）
    val rawHtml = html
    val messageContent = if (rawHtml != null) {
        rawHtml.toCleanMessage(subject, removeForwarded = true)
    } else {
        // 把 entry.links 里被拆走的 https 链接拼回来
        val sb = StringBuilder(text.orEmpty().removeForwardedDeclaration())
        links.takeIf { it.isNotEmpty() }?.forEach { link ->
            if (link.href !in sb) sb.append('\n').append(link.href)
        }
        sb.toString().toPlainText()
    }

    // 3. 构建消息
    val messageBuilder = buildMessageChain {
        // 3.1 添加正文（已完全清理转发声明）
        append(messageContent)

        // 3.2 添加底部信息
        append("\n------\n".toPlainText())
        val source = link?.let {
            java.net.URL(it).path
                .removePrefix("/")          // /yhxixinxi/73 → yhxixinxi/73
                .substringBefore("/")       // yhxixinxi/73  → yhxixinxi
        } ?: "无"
        append("消息来源：【$source】\n".toPlainText())
        val timeFmt = published?.let {
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(it)
        } ?: "未知"
        append("发布时间: $timeFmt".toPlainText())
        forwardedSource?.let {
            append("\n从 $it 转发".toPlainText())
        }
        // 消息附件（非图片 enclosure）
        enclosures
            .filterNot { it.type?.startsWith("image/") == true }
            .firstOrNull()
            ?.url
            ?.takeIf { it.isNotBlank() }
            ?.let { append("\n消息附件：$it".toPlainText()) }
    }

    return if (forward) {
        buildForwardMessage(subject) {
            subject.bot at last.orNow().toEpochSecond().toInt() says messageBuilder
            displayStrategy = toDisplayStrategy()
        }
    } else {
        if (messageBuilder.content.length <= limit) messageBuilder else "内容过长".toPlainText()
    }
}

@PublishedApi
internal suspend fun Element.toCleanMessage(
    subject: Contact,
    removeForwarded: Boolean = false
): MessageChain {
    val contentRoot = if (removeForwarded) {
        clone().apply {
            // 彻底移除所有包含转发声明的元素
            select(":contains(Forwarded From), :contains(转发自)").remove()
        }
    } else {
        this
    }

    val builder = MessageChainBuilder()
    
    // 递归处理节点的挂起函数
    suspend fun processNode(node: Node) {
        when (node) {
            is TextNode -> {
                val text = node.text()
                    .removeForwardedDeclaration()
                    .trim()
                if (text.isNotBlank()) {
                    builder.append(text.toPlainText())
                }
            }
            is Element -> when (node.tagName()) {
                "img" -> try {
                    builder.append(node.image(subject))
                } catch (e: Exception) {
                    logger.warning({ "图片上传失败: ${node.attr("src")}" }, e)
                    builder.append("[图片]".toPlainText())
                }
                "br" -> builder.append("\n".toPlainText())
                "a" -> {
                    val text = node.text().trim()
                    if (text.isNotBlank() && text != node.attr("href")) {
                        builder.append(text.toPlainText())
                    }
                }
                else -> node.childNodes().forEach { processNode(it) }
            }
        }
    }

    contentRoot.childNodes().forEach { processNode(it) }
    return builder.build()
}

// region 辅助函数
private fun Element.extractCleanForwardSource(): String? {
    return select("a:contains(Forwarded From), a:contains(转发自)")
        .firstOrNull()
        ?.text()
        ?.replace(Regex("""(Forwarded From|转发自)"""), "")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private fun String.extractForwardedFromText(): String? {
    return Regex("""(?:Forwarded From|转发自)[:\s]*(.+)""")
        .find(this)
        ?.groupValues
        ?.get(1)
        ?.trim()
}

private fun String.removeForwardedDeclaration(): String {
    return replace(Regex("""(Forwarded From|转发自).*"""), "")
        .trim()
}
// endregion

@PublishedApi
internal fun SyndEntry.toDisplayStrategy(): ForwardMessage.DisplayStrategy = object : ForwardMessage.DisplayStrategy {
    override fun generatePreview(forward: RawForwardMessage): List<String> {
        return listOf(
            title,
            author,
            last.toString(),
            categories.joinToString { it.name }
        )
    }
}

private val FULLWIDTH_CHARS = mapOf(
    '\\' to '＼',
    '/' to '／',
    ':' to '：',
    '*' to '＊',
    '?' to '？',
    '"' to '＂',
    '<' to '＜',
    '>' to '＞',
    '|' to '｜'
)

private fun CharSequence.fullwidth(): String {
    return fold(StringBuilder()) { buffer, char ->
        buffer.append(FULLWIDTH_CHARS[char] ?: char)
    }.toString()
}

@PublishedApi
internal suspend fun SyndEntry.getTorrent(): File? {
    // TODO magnet to file
    val url = Url(torrent?.takeIf { it.startsWith("http") } ?: return null)
    return try {
        TorrentFolder.resolve("${title.fullwidth()}.torrent").apply {
            if (exists().not()) {
                parentFile.mkdirs()
                writeBytes(client.useHttpClient { it.get(url).body() })
            }
        }
    } catch (cause: Exception) {
        logger.warning({ "下载种子失败" }, cause)
        null
    }
}

@PublishedApi
internal fun Element.src(): String = attr("src") ?: throw NoSuchElementException("src")

@PublishedApi
internal fun Element.href(): String = attr("href") ?: throw NoSuchElementException("href")

@PublishedApi
internal suspend fun Element.image(subject: Contact): MessageContent {
    val url = Url(src())
    return try {
        val image = if (ImageFolder.resolve(url.filename).exists()) {
            ImageFolder.resolve(url.filename)
        } else {
            client.useHttpClient { http ->
                val response = http.get(url)
                val relative = response.contentDisposition()?.parameter(ContentDisposition.Parameters.FileName)
                    ?: response.etag()?.removeSurrounding("\"")
                        ?.plus(".")?.plus(response.contentType()?.contentSubtype)
                    ?: response.request.url.filename

                val file = ImageFolder.resolve(relative)

                if (file.exists().not()) {
                    file.parentFile.mkdirs()
                    file.outputStream().use { output ->
                        val channel: ByteReadChannel = response.bodyAsChannel()

                        while (!channel.isClosedForRead) {
                            channel.copyTo(output)
                        }
                    }
                }

                file
            }
        }
        image.uploadAsImage(subject)
    } catch (cause: Exception) {
        logger.warning({ "上传图片失败, $url" }, cause)
        " [$url] ".toPlainText()
    }
}

@PublishedApi
internal suspend fun Element.toMessage(subject: Contact): MessageChain {
    val visitor = object : NodeVisitor, MutableList<Node> by ArrayList() {
        override fun head(node: Node, depth: Int) {
            if (node is TextNode) add(node)
        }

        override fun tail(node: Node, depth: Int) {
            if (node is Element) add(node)
        }
    }
    NodeTraversor.traverse(visitor, this)

    val builder = MessageChainBuilder()
    visitor.forEach { node ->
        when (node) {
            is TextNode -> builder.append(node.wholeText.removePrefix("\n\t").removeSuffix("\n"))
            is Element -> when (node.nodeName()) {
                "img" -> {
                    builder.append(node.image(subject))
                }
                "a" -> {
                    when {
                        node.text() == node.href() -> Unit
                        node.childrenSize() > 0 -> Unit
                        else -> builder.append("<${node.href()}>")
                    }
                }
                "br" -> {
                    builder.append("\n")
                }
            }
            else -> error(node)
        }
    }

    return builder.build()
}
