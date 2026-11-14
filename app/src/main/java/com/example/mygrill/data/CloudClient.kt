package com.example.mygrill.data

import com.example.mygrill.BuildConfig
import com.example.mygrill.core.CloudParse
import com.example.mygrill.core.CookCommand
import com.example.mygrill.core.CloudReading
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A signed-in Ninja account: the Auth0 id_token the AWS backend accepts, and how to renew it. */
data class Session(val idToken: String, val refreshToken: String?, val expiresAtMs: Long, val userId: String) {
    fun toJson(): String = JSONObject()
        .put("idToken", idToken).put("refreshToken", refreshToken)
        .put("expiresAtMs", expiresAtMs).put("userId", userId).toString()

    companion object {
        fun fromJson(s: String): Session? = runCatching {
            val o = JSONObject(s)
            Session(o.getString("idToken"), o.optString("refreshToken").ifEmpty { null }, o.getLong("expiresAtMs"), o.getString("userId"))
        }.getOrNull()
    }
}

/** The credentials were refused (wrong password, or a session that can no longer be renewed). */
class AuthException(message: String) : Exception(message)

/**
 * SharkNinja cloud, EU region: Auth0 sign-in, then the AWS backend the grill migrated to.
 * Blocking calls: run them off the main thread.
 */
class CloudClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()
    private var household: String? = null
    private var deviceId: String? = null

    fun login(email: String, password: String): Session = token(
        JSONObject()
            .put("grant_type", "http://auth0.com/oauth/grant-type/password-realm")
            .put("username", email)
            .put("password", password)
            .put("audience", BuildConfig.AUTH0_AUDIENCE)
            .put("scope", "openid profile email read:current_user offline_access")
            .put("client_id", BuildConfig.AUTH0_CLIENT_ID)
            .put("realm", "Username-Password-Authentication"),
        previousRefresh = null,
    )

    fun refresh(session: Session): Session {
        val refresh = session.refreshToken ?: throw AuthException("no refresh token")
        return token(
            JSONObject()
                .put("grant_type", "refresh_token")
                .put("client_id", BuildConfig.AUTH0_CLIENT_ID)
                .put("refresh_token", refresh),
            previousRefresh = refresh,
        )
    }

    /** The account's grill as the AWS backend sees it, or null when the account has none. */
    fun fetchGrill(session: Session, nowMillis: Long): CloudReading? =
        grillRecord(session)?.let { CloudParse.device(it, nowMillis) }

    fun forget() {
        household = null
        deviceId = null
    }

    // ------------------------------------------------------------------ commands

    /**
     * Start (or re-issue) a cook. If the grill doesn't confirm in time the command is withdrawn
     * with a stop: it may have reached the grill after all, and a cook nobody knows about is worse.
     */
    fun startCook(session: Session, command: JSONObject) = send(session, command, withdrawWithStop = true)

    /** Stop a cook, or wake a panel that switched itself off (a stop does both). */
    fun stopCook(session: Session, stop: JSONObject) = send(session, stop, withdrawWithStop = false)

    /**
     * One command through the device shadow. AWS queues a desired command for a grill that is
     * offline and fires it whenever the grill reconnects (hours later, maybe mid-way through a
     * cook started over Bluetooth), so: only to a connected grill; delivered means the grill's
     * reported command or its cook state changed (temperatures alone don't count: they drift on
     * their own); and whatever happens, the desired command is cleared afterwards, so nothing is
     * left waiting in the shadow.
     */
    private fun send(session: Session, command: JSONObject, withdrawWithStop: Boolean) {
        val record = grillRecord(session) ?: throw IOException("no grill on this account")
        if (record.optJSONObject("connectivityStatus")?.optBoolean("connected") != true) {
            throw IOException("the grill is not connected to the cloud")
        }
        val before = reportedCommand(session)
        val stateBefore = cookSignature(record)
        patchCookCommand(session, command)
        try {
            val deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(ACK_POLL_MS)
                val acked = runCatching {
                    reportedCommand(session) != before || cookSignature(grillRecord(session)) != stateBefore
                }.getOrDefault(false)
                if (acked) return
            }
            if (withdrawWithStop) {
                runCatching {
                    patchCookCommand(session, CookCommand.cloudStop())
                    Thread.sleep(WITHDRAW_SETTLE_MS)
                }
            }
            throw IOException("the grill did not confirm within ${ACK_TIMEOUT_MS / 1000} s; the command was withdrawn")
        } finally {
            runCatching { patchCookCommand(session, null) }
        }
    }

    /** What a command can change about the grill (phase, mode, target, time, smoke, probe targets). */
    private fun cookSignature(record: JSONObject?): String? = record?.let {
        val r = CloudParse.device(it, 0)
        listOf(r.phase, r.mode, r.setpoint, r.secondsSet, r.smoke, r.probes.map { p -> p.targetC }).toString()
    }

    private fun patchCookCommand(session: Session, command: JSONObject?) {
        val body = JSONObject().put(
            "shadow", JSONObject().put("properties", JSONObject().put("desired",
                JSONObject().put("Cook_Command", command ?: JSONObject.NULL))),
        )
        request(session, devicePath(session), "PATCH", body)
    }

    private fun reportedCommand(session: Session): String? {
        val device = request(session, devicePath(session), "GET", null)
        val shadow = device.optJSONObject("shadow")
        val props = shadow?.optJSONObject("properties") ?: shadow
        return props?.optJSONObject("reported")?.opt("Cook_Command")?.toString()
    }

    private fun devicePath(session: Session): String {
        if (household == null || deviceId == null) grillRecord(session)
        val id = deviceId ?: throw IOException("no grill on this account")
        return "/devicesEndUserController/$household/devices/$id"
    }

    private fun grillRecord(session: Session): JSONObject? {
        val hh = household ?: get(session, "/householdsEndUser", mapOf("userId" to session.userId))
            .optJSONArray("households")?.optString(0)?.ifEmpty { null }
            ?.also { household = it }
            ?: return null
        val items = get(
            session, "/devicesEndUserController/$hh/users/${session.userId}",
            mapOf("includeRegistry" to "true", "includeConnectivityStatus" to "true"),
        ).optJSONArray("items") ?: return null
        val grill = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.firstOrNull() ?: return null
        deviceId = grill.optString("deviceId").ifEmpty { null }
        return grill
    }

    // ------------------------------------------------------------------ http

    private fun token(body: JSONObject, previousRefresh: String?): Session {
        val request = Request.Builder()
            .url("${BuildConfig.AUTH0_BASE}/oauth/token")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        http.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val data = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (res.code == 401 || res.code == 403) {
                throw AuthException(data.optString("error_description").ifEmpty { data.optString("error", "HTTP ${res.code}") })
            }
            if (!res.isSuccessful) throw IOException("Auth0 HTTP ${res.code}: ${data.optString("error")}")
            val idToken = data.optString("id_token").ifEmpty { throw IOException("Auth0 returned no id_token") }
            val claims = jwtClaims(idToken)
            val exp = claims.optLong("exp", 0L).takeIf { it > 0 }?.times(1000)
                ?: (System.currentTimeMillis() + data.optLong("expires_in", 3600) * 1000)
            return Session(
                idToken = idToken,
                refreshToken = data.optString("refresh_token").ifEmpty { previousRefresh },
                expiresAtMs = exp - 60_000,
                userId = claims.optString("sub").removePrefix("auth0|"),
            )
        }
    }

    private fun get(session: Session, path: String, query: Map<String, String>): JSONObject =
        request(session, path, "GET", null, query)

    private fun request(session: Session, path: String, method: String, body: JSONObject?, query: Map<String, String> = emptyMap()): JSONObject {
        val url = "${BuildConfig.AWS_BASE}$path".toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .method(method, body?.toString()?.toRequestBody(jsonType))
            .header("authorization", "Bearer ${session.idToken}")
            .header("x-api-key", BuildConfig.AWS_API_KEY)
            .header("x-iotn-caller", "ENDUSER_MOBILEAPP")
            .header("x-sn-nonce", "12345")
            .header("x-sn-date", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString())
            .header("accept", "application/json")
            .build()
        http.newCall(request).execute().use { res ->
            if (res.code == 401) throw AuthException("token rejected")
            if (res.code == 404) forget()
            if (!res.isSuccessful) throw IOException("AWS HTTP ${res.code}")
            return JSONObject(res.body?.string().orEmpty().ifEmpty { "{}" })
        }
    }

    private fun jwtClaims(jwt: String): JSONObject = runCatching {
        JSONObject(String(Base64.getUrlDecoder().decode(jwt.split(".")[1]), Charsets.UTF_8))
    }.getOrDefault(JSONObject())

    private companion object {
        const val ACK_TIMEOUT_MS = 20_000L
        const val ACK_POLL_MS = 2_000L
        const val WITHDRAW_SETTLE_MS = 4_000L
    }
}
