package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf

/**
 * The sign-in side of the running instance's OpenVPN/OpenConnect endpoints that [VpnAuthRuntime] reports to the UI
 * (ISagerNetService.vpnAuthState, cbVpnAuthUpdate): the challenges left to a person (the desktop's VpnAuthChallenge)
 * and the endpoints stopped on an error. Keys follow libcore.proto, plus the name of the profile behind each endpoint.
 */
data class VpnAuthState(
    val challenges: List<Challenge> = emptyList(),
    val failures: List<Failure> = emptyList(),
    /** Tags of the endpoints whose tunnel is up. */
    val connected: Set<String> = emptySet(),
) {

    data class Option(val value: String, val label: String)

    data class Field(
        val submissionKey: String,
        val name: String,
        val label: String,
        /** text | password | select | hidden */
        val kind: String,
        val value: String,
        val options: List<Option>,
    )

    data class Challenge(
        val tag: String,
        val name: String,
        val id: String,
        /** openvpn: credentials | secret | message | open-url; openconnect: form | browser */
        val kind: String,
        val username: String,
        val message: String,
        val banner: String,
        val error: String,
        val url: String,
        /** False: the answer is a secret and is masked. */
        val echo: Boolean,
        /** Unix seconds, 0 = none. */
        val deadline: Long,
        val fields: List<Field>,
    ) {
        val key: String get() = "$tag\u001f$id"

        fun expired(nowMs: Long = System.currentTimeMillis()): Boolean = deadline > 0 && deadline * 1000 <= nowMs
    }

    /** An endpoint stopped on [error]; [authFailed]: the server refused the sign-in, which only a restart tries again. */
    data class Failure(val tag: String, val name: String, val error: String, val authFailed: Boolean)

    fun challenge(tag: String): Challenge? = challenges.firstOrNull { it.tag == tag }

    fun failure(tag: String): Failure? = failures.firstOrNull { it.tag == tag }

    fun toJson(): String {
        val challengeArray = JsonArray()
        for (challenge in challenges) {
            val fields = JsonArray()
            for (field in challenge.fields) {
                val options = JsonArray()
                field.options.forEach { options.add(jsonObjectOf("value" to it.value, "label" to it.label)) }
                fields.add(
                    jsonObjectOf(
                        "submission_key" to field.submissionKey,
                        "name" to field.name,
                        "label" to field.label,
                        "kind" to field.kind,
                        "value" to field.value,
                        "options" to options,
                    )
                )
            }
            challengeArray.add(
                jsonObjectOf(
                    "endpoint_tag" to challenge.tag,
                    "name" to challenge.name,
                    "id" to challenge.id,
                    "kind" to challenge.kind,
                    "username" to challenge.username,
                    "message" to challenge.message,
                    "banner" to challenge.banner,
                    "error" to challenge.error,
                    "url" to challenge.url,
                    "echo" to challenge.echo,
                    "deadline" to challenge.deadline,
                    "fields" to fields,
                )
            )
        }
        val failureArray = JsonArray()
        failures.forEach {
            failureArray.add(
                jsonObjectOf("tag" to it.tag, "name" to it.name, "error" to it.error, "auth_failed" to it.authFailed)
            )
        }
        return jsonObjectOf(
            "challenges" to challengeArray,
            "failures" to failureArray,
            "connected" to JsonArray().also { array -> connected.forEach { array.add(it) } },
        ).toCompact()
    }

    companion object {
        const val KIND_CREDENTIALS = "credentials"
        const val KIND_SECRET = "secret"
        const val KIND_MESSAGE = "message"
        const val KIND_OPEN_URL = "open-url"
        const val KIND_FORM = "form"
        const val KIND_BROWSER = "browser"

        const val FIELD_PASSWORD = "password"
        const val FIELD_SELECT = "select"
        const val FIELD_HIDDEN = "hidden"

        private const val STATE_ERROR = "error"

        @JvmField
        val EMPTY = VpnAuthState()

        /** Anything unreadable is [EMPTY]. */
        fun parse(json: String?): VpnAuthState {
            if (json.isNullOrBlank()) return EMPTY
            val obj = JsonInput.parseObjectOrNull(json) ?: return EMPTY
            return VpnAuthState(
                challenges = obj.array("challenges").mapNotNull { item ->
                    (item as? JsonObject)?.let { challengeOf(it, it.string("name")) }
                },
                failures = obj.array("failures").mapNotNull { item ->
                    (item as? JsonObject)?.let {
                        Failure(it.string("tag"), it.string("name"), it.string("error"), it.bool("auth_failed"))
                    }
                },
                connected = obj.array("connected").strings().toSet(),
            )
        }

        /** Instance.vpnStatus's endpoint list; [nameOf] names an endpoint by its tag. */
        fun fromCore(statusJson: String?, nameOf: (String) -> String): VpnAuthState {
            val endpoints = statusJson?.let(JsonInput::parseValue) as? JsonArray ?: return EMPTY
            val challenges = ArrayList<Challenge>()
            val failures = ArrayList<Failure>()
            val connected = LinkedHashSet<String>()
            for (item in endpoints) {
                val endpoint = item as? JsonObject ?: continue
                val tag = endpoint.string("tag")
                if (endpoint.bool("connected")) connected.add(tag)
                if (endpoint.isObject("challenge")) {
                    challengeOf(endpoint.obj("challenge"), nameOf(tag))
                        .takeIf { it.id.isNotEmpty() }
                        ?.let { challenges.add(if (it.tag.isEmpty()) it.copy(tag = tag) else it) }
                } else if (endpoint.string("state") == STATE_ERROR) {
                    failures.add(Failure(tag, nameOf(tag), endpoint.string("error"), endpoint.bool("auth_failed")))
                }
            }
            return VpnAuthState(challenges, failures, connected)
        }

        private fun challengeOf(obj: JsonObject, name: String) = Challenge(
            tag = obj.string("endpoint_tag"),
            name = name,
            id = obj.string("id"),
            kind = obj.string("kind"),
            username = obj.string("username"),
            message = obj.string("message"),
            banner = obj.string("banner"),
            error = obj.string("error"),
            url = obj.string("url"),
            echo = obj.bool("echo"),
            deadline = obj.integer("deadline"),
            fields = obj.array("fields").mapNotNull { item ->
                val field = item as? JsonObject ?: return@mapNotNull null
                Field(
                    submissionKey = field.string("submission_key"),
                    name = field.string("name"),
                    label = field.string("label"),
                    kind = field.string("kind"),
                    value = field.string("value"),
                    options = field.array("options").mapNotNull { option ->
                        (option as? JsonObject)?.let { Option(it.string("value"), it.string("label")) }
                    },
                )
            },
        )
    }
}
