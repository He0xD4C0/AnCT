package dev.anct.runtime

import android.content.Context
import dev.anct.core.CatalogDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class CatalogService(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadBundled(): CatalogDocument = withContext(Dispatchers.IO) {
        val payload = context.assets.open("catalog.json").readBytes()
        val signature = context.assets.open("catalog.sig").readBytes()
        val publicKey = context.assets.open("catalog.pub.pem").bufferedReader().readText()
        verify(payload, signature, publicKey)
        json.decodeFromString(payload.decodeToString())
    }

    suspend fun loadRemote(
        url: String,
        signature: ByteArray? = null,
        publicKeyPem: String? = null,
    ): CatalogDocument = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Catalog URLs must use HTTPS" }
        val body = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Catalog HTTP ${response.code}" }
            response.body.bytes()
        }
        check(body.size <= MAX_CATALOG_BYTES) { "Catalog is too large" }
        if (signature != null && publicKeyPem != null) {
            verify(body, signature, publicKeyPem)
        }
        json.decodeFromString(body.decodeToString())
    }

    fun verify(payload: ByteArray, signature: ByteArray, publicKeyPem: String) {
        val stripped = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s"), "")
        val keyBytes = Base64.getDecoder().decode(stripped)
        val key: PublicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(keyBytes))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(payload)
        check(verifier.verify(signature)) { "Catalog signature verification failed" }
    }

    companion object {
        private const val MAX_CATALOG_BYTES = 2 * 1024 * 1024
    }
}

