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
    // 处理内容（保留图片但移除正文中的链接）
    val messageContent = html?.let { it.toRichMessage(subject) } ?: 
        text.orEmpty()
            .removeUrlsFromText() // 只移除纯文本中的URL
            .toPlainText()

    // 处理转发信息格式
    val cleanContent = when (messageContent) {
        is MessageChain -> {
            val textContent = messageContent.joinToString("") { 
                if (it is PlainText) it.content else "" 
            }.replace(Regex("(?i)forwarded from ([^\n]+)"), "【Forwarded From $1】\n")
            buildMessageChain {
                append(textContent.toPlainText())
                // 保留非文本内容（如图片）
                messageContent.filterNot { it is PlainText }.forEach { append(it) }
            }
        }
        else -> {
            messageContent.toString()
                .replace(Regex("(?i)forwarded from ([^\n]+)"), "【Forwarded From $1】\n")
                .toPlainText()
        }
    }

    // 构建底部信息
    val footer = buildMessageChain {
        appendLine("\n------")
        appendLine("源URL: ${link ?: "无"}")
        appendLine("发布时间: ${published ?: "未知"}")
    }

    // 组合完整消息
    val fullMessage = buildMessageChain {
        when (cleanContent) {
            is MessageChain -> append(cleanContent)
            else -> append(cleanContent.toString().toPlainText())
        }
        append(footer)
    }

    return if (forward) {
        val second = last.orNow().toEpochSecond().toInt()
        buildForwardMessage(subject) {
            subject.bot at second says fullMessage
            displayStrategy = toDisplayStrategy()
        }
    } else {
        if (fullMessage.content.length <= limit) fullMessage else "内容过长".toPlainText()
    }
}

@PublishedApi
internal suspend fun Element.toRichMessage(subject: Contact): MessageChain {
    val visitor = object : NodeVisitor, MutableList<Node> by ArrayList() {
        override fun head(node: Node, depth: Int) {
            if (node is TextNode) add(node)
        }

        override fun tail(node: Node, depth: Int) {
            if (node is Element) add(node) // 保留所有元素，后面会特殊处理<a>标签
        }
    }
    NodeTraversor.traverse(visitor, this)

    val builder = MessageChainBuilder()
    visitor.forEach { node ->
        when (node) {
            is TextNode -> {
                var text = node.wholeText.removePrefix("\n\t").removeSuffix("\n")
                // 处理转发信息格式
                text = text.replace(Regex("(?i)forwarded from ([^\n]+)"), "【Forwarded From $1】\n")
                // 移除文本中的URL（但不影响图片URL）
                text = text.removeUrlsFromText()
                if (text.isNotBlank()) {
                    builder.append(text.toPlainText())
                }
            }
            is Element -> when (node.nodeName()) {
                "img" -> try {
                    builder.append(node.image(subject)) // 保留图片
                } catch (e: Exception) {
                    logger.warning({ "图片上传失败" }, e)
                    builder.append("[图片]".toPlainText())
                }
                "a" -> {
                    // 对于链接，只保留文本内容，不保留链接
                    val linkText = node.text()
                    if (linkText.isNotBlank() && linkText != node.attr("href")) {
                        builder.append(linkText.toPlainText())
                    }
                }
                "br" -> builder.append("\n".toPlainText())
                // 其他元素可以在这里添加处理逻辑
            }
        }
    }

    return builder.build()
}

// 新增扩展函数，只移除纯文本中的URL
internal fun String.removeUrlsFromText(): String {
    return this.replace(Regex("""(?<!src=["']|href=["'])(https?://\S+)"""), "")
        .replace(Regex("""<a\s+[^>]*href\s*=\s*["'][^"']*["'][^>]*>(.*?)</a>"""), "$1")
}

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