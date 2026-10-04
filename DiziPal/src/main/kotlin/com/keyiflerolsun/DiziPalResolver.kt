package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Resolve the site's player configuration, then dispatch by the actual player format. */
internal class DiziPalResolver(private val name: String) {
    private val mapper = jacksonObjectMapper()
    private fun absolute(base: String, value: String): String? = runCatching {
        if (value.isBlank() || value.trim().startsWith("#")) return null
        URI(base).resolve(value.trim()).takeIf { it.scheme in listOf("https", "http") && it.host != null }?.toString()
    }.getOrNull()
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun unpack(source: String): String {
        var result = source
        repeat(2) { if (result.contains("eval(function(")) result = getAndUnpack(result) }
        return result
    }

    internal fun decryptPlayer(payload: String, script: String): String? {
        val json = mapper.readTree(payload)
        val salt = hex(json.path("salt").asText())
        val iv = hex(json.path("iv").asText())
        val ciphertext = Base64.decode(json.path("ciphertext").asText(), Base64.DEFAULT)
        // The passphrase is in the script's string table even when its identifiers are obfuscated.
        val candidates = Regex("""['"]([A-Za-z0-9+/=]{32,512})['"]""").findAll(script)
            .map { it.groupValues[1] }.distinct().take(64)
        val iterationText = Regex("""['"]iterations['"]\s*:\s*(0x[\da-fA-F]+|\d+)""")
            .find(script)?.groupValues?.get(1) ?: return null
        val iterations = if (iterationText.startsWith("0x")) iterationText.drop(2).toInt(16) else iterationText.toInt()
        if (iterations !in 1..100000 || iv.size != 16 || salt.isEmpty()) return null
        for (password in candidates) {
            val value = runCatching {
                val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
                val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
                spec.clearPassword()
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                String(cipher.doFinal(ciphertext), Charsets.UTF_8).trim()
            }.getOrNull() ?: continue
            if (value.startsWith("https://") || value.startsWith("http://") || value.startsWith("//")) return value
        }
        return null
    }

    private suspend fun playerUrls(pageUrl: String, document: Document): Set<String> {
        val urls = linkedSetOf<String>()
        document.select("#cstk iframe[src], #video-area iframe[src], #player iframe[src]").forEach {
            absolute(pageUrl, it.attr("src"))?.let(urls::add)
        }
        val payloads = document.select("[data-rm-k]").map { it.text() }.filter { it.startsWith("{") }
        if (payloads.isEmpty()) return urls
        val inline = document.select("script:not([src])").joinToString("\n") { it.data() }
        val scripts = document.select("script[src]").mapNotNull { absolute(pageUrl, it.attr("src")) }
            .filter { URI(it).host == URI(pageUrl).host }.distinct()
            .sortedByDescending { it.contains("app-") }.take(12)
        suspend fun tryScript(script: String) {
            if (!script.contains("PBKDF2")) return
            val decoded = unpack(script)
            payloads.forEach { payload -> decryptPlayer(payload, decoded)?.let { absolute(pageUrl, it) }?.let(urls::add) }
        }
        tryScript(inline)
        for (scriptUrl in scripts) {
            if (urls.isNotEmpty()) break
            tryScript(app.get(scriptUrl, referer = pageUrl).text)
        }
        return urls
    }

    suspend fun resolve(pageUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val root = URI(pageUrl).resolve("/").toString()
        val page = app.get(pageUrl, referer = root)
        if (page.code >= 400) throw ErrorLoadingException("DiziPal bölüm sayfası HTTP ${page.code} döndürdü")
        val players = playerUrls(pageUrl, page.document)
        Log.d("DZP", "player candidates=${players.size}")
        if (players.isEmpty()) throw ErrorLoadingException("DiziPal oynatıcı yapılandırması çözümlenemedi")
        val emitted = mutableSetOf<String>()
        val subs = mutableSetOf<String>()
        val onSubtitle: (SubtitleFile) -> Unit = { if (subs.add(it.url)) subtitleCallback(it) }
        val onLink: (ExtractorLink) -> Unit = { if (emitted.add(it.url)) callback(it) }
        var lastError: String? = null
        for (player in players) {
            try {
                resolvePlayer(player, root, onSubtitle, onLink)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                lastError = e.message
                Log.w("DZP", "player host=${URI(player).host}: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (emitted.isEmpty()) throw ErrorLoadingException(lastError ?: "DiziPal oynatıcıda kullanılabilir video bulunamadı")
        return true
    }

    private suspend fun resolvePlayer(playerUrl: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val response = app.get(playerUrl, referer = referer)
        Log.d("DZP", "player host=${URI(playerUrl).host} status=${response.code}")
        if (response.code >= 400) throw ErrorLoadingException("Video sağlayıcısı HTTP ${response.code} döndürdü (${URI(playerUrl).host})")
        val html = unpack(response.text)
        val tokens = Regex("""(?:window\s*\.\s*)?openPlayer\s*\(\s*['"]([^'"]+)['"]""")
            .findAll(html).map { it.groupValues[1] }.distinct().toList()
        if (tokens.isEmpty()) {
            loadExtractor(playerUrl, referer, subtitleCallback, callback)
            return
        }
        // ContentX family: start the source request directly; its popup/ad click isn't a video dependency.
        val endpoint = Regex("""(?:getJSON|fetch)\s*\(\s*['"]([^'"]+\?v=)['"]\s*\+""")
            .find(html)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("ContentX kaynak isteği bulunamadı")
        val rewrite = Regex("""\.replace\(\s*['"]([^'"]+\.php)['"]\s*,\s*['"]([^'"]+\.m3u8)['"]\s*\)""")
            .find(html)?.groupValues
        Regex("""\{[^{}]*"(?:kind|file)"[^{}]*\}""").findAll(html).forEach { match ->
            val track = runCatching { mapper.readTree(match.value) }.getOrNull() ?: return@forEach
            if (track.path("kind").asText() in listOf("captions", "subtitles")) {
                absolute(playerUrl, track.path("file").asText())?.let {
                    subtitleCallback(SubtitleFile(track.path("label").asText("Altyazı"), it))
                }
            }
        }
        for (token in tokens) {
            val sourceUrl = absolute(playerUrl, endpoint + URLEncoder.encode(token, "UTF-8")) ?: continue
            val sourceResponse = app.get(sourceUrl, referer = playerUrl,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
            Log.d("DZP", "source status=${sourceResponse.code}")
            if (sourceResponse.code >= 400) throw ErrorLoadingException("Video kaynak isteği HTTP ${sourceResponse.code}")
            val json = mapper.readTree(sourceResponse.text)
            if (json.path("expired").asBoolean()) throw ErrorLoadingException("Video oturumu süresi doldu; tekrar deneyin")
            val playlist = json.path("playlist")
            for (item in playlist) {
                for (source in item.path("sources")) {
                    var file = source.path("file").asText()
                    if (rewrite != null) file = file.replace(rewrite[1], rewrite[2])
                    val stream = absolute(playerUrl, file)?.takeIf { file.isNotBlank() } ?: continue
                    callback(newExtractorLink(name, "$name ${source.path("title").asText("Video")}", stream, INFER_TYPE) {
                        this.referer = playerUrl
                    })
                }
                emitTracks(item.path("tracks"), playerUrl, subtitleCallback)
            }
            emitTracks(json.path("subtitles"), playerUrl, subtitleCallback)
        }
    }

    private fun emitTracks(tracks: JsonNode, base: String, callback: (SubtitleFile) -> Unit) {
        for (track in tracks) {
            val file = track.path("file").asText().takeIf { it.isNotBlank() } ?: continue
            absolute(base, file)?.let { callback(SubtitleFile(track.path("label").asText("Altyazı"), it)) }
        }
    }
}
