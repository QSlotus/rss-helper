package io.github.gnuf0rce.mirai.rss.command

import io.ktor.http.*
import io.ktor.util.*
import kotlinx.coroutines.*
import net.mamoe.mirai.console.command.*
import net.mamoe.mirai.console.command.descriptor.*
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.data.MessageSource.Key.quote

val RssCommandArgumentContext = buildCommandArgumentContext {
    Url::class with { raw, _ ->
        try {
            if (raw.startsWith("http")) {
                Url(raw)
            } else {
                Url(raw.decodeBase64String())
            }
        } catch (cause: Throwable) {
            throw CommandArgumentParserException("无法解析${raw}为URL", cause)
        }
    }
}

val SendLimit = """本群每分钟只能发\d+条消息""".toRegex()

suspend fun <T : CommandSenderOnMessage<*>> T.sendMessage(block: suspend T.(Contact) -> Message): Boolean {
    return runCatching {
        block(fromEvent.subject)
    }.onSuccess { message ->
        quoteReply(message)
    }.onFailure {
        when {
            SendLimit.containsMatchIn(it.message.orEmpty()) -> {
                delay(60 * 1000L)
                quoteReply(SendLimit.find(it.message!!)!!.value)
            }
            else -> {
                quoteReply("发送消息失败， ${it.message}")
            }
        }
    }.isSuccess
}

suspend fun CommandSenderOnMessage<*>.quoteReply(message: Message) = sendMessage(fromEvent.message.quote() + message)

suspend fun CommandSenderOnMessage<*>.quoteReply(message: String) = quoteReply(message.toPlainText())