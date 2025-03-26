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
    val forwardedSource = html?.extractForwardedSource()?.takeIf { it.isNotBlank() }
        ?: text.orEmpty().extractForwardedFromText()

    // 2. 处理正文内容
    val (cleanContent, hasImages) = html?.let { 
        val content = it.toRichMessage(subject, keepForwardedContent = false)
        content to content.any { it is Image }
    } ?: run {
        val textContent = text.orEmpty()
            .removeForwardedHeader()
            .removeUrlsFromText()
            .toPlainText()
        textContent to false
    }

    // 3. 构建消息结构
    val messageBuilder = buildMessageChain {
        forwardedSource?.let { append("【Forwarded From $it】\n".toPlainText()) }
        
        when (cleanContent) {
            is MessageChain -> append(cleanContent)
            else -> append(cleanContent.toString().toPlainText())
        }
        
        // 始终添加元信息（无论是否有图片或其他内容）
        append("\n------\n".toPlainText())
        append("源URL: ${link ?: "无"}\n".toPlainText())
        append("发布时间: ${published ?: "未知"}".toPlainText())
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
internal suspend fun Element.toRichMessage(
    subject: Contact,
    keepForwardedContent: Boolean = false
): MessageChain {
    val contentRoot = if (!keepForwardedContent) {
        clone().apply {
            select("p:contains(Forwarded From), p:contains(转发自)").remove()
        }
    } else {
        this
    }

    return MessageChainBuilder().apply {
        suspend fun processNode(node: Node) {
            when (node) {
                is TextNode -> {
                    val text = node.wholeText
                        .trimIndent()
                        .replace(Regex("""\s+"""), " ")
                    if (text.isNotBlank()) {
                        append(text.toPlainText())
                    }
                }
                
                is Element -> {
                    when (node.tagName()) {
                        "br" -> append("\n".toPlainText())
                        "img" -> {
                            try {
                                append(node.image(subject)) // 在协程体内调用挂起函数
                            } catch (e: Exception) {
                                logger.warning({ "图片上传失败: ${node.attr("src")}" }, e)
                                append("[图片]".toPlainText())
                            }
                        }
                        else -> node.childNodes().forEach { processNode(it) }
                    }
                }
            }
        }

        contentRoot.childNodes().forEach { processNode(it) }
    }.build().optimizeSpaces()
}

// region 辅助扩展函数
private fun Element.extractForwardedSource(): String? {
    return select("p:contains(Forwarded From) a, p:contains(转发自) a")
        .firstOrNull()
        ?.text()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private fun String.extractForwardedFromText(): String? {
    return Regex("""(?:Forwarded From|转发自)[:\s]*([^\n]+)""", RegexOption.IGNORE_CASE)
        .find(this)
        ?.groupValues
        ?.get(1)
        ?.trim()
}

private fun String.removeForwardedHeader(): String {
    return replace(Regex("""(?:Forwarded From|转发自)[^\n]*\n?""", RegexOption.IGNORE_CASE), "")
}

private fun String.removeUrlsFromText(): String {
    return replace(Regex("""https?://\S+"""), "")
        .replace(Regex("""<a\b[^>]*>(.*?)</a>"""), "$1")
}

private fun MessageChain.optimizeSpaces(): MessageChain {
    val content = this.joinToString("") { it.content }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
    return buildMessageChain { append(content.toPlainText()) }
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