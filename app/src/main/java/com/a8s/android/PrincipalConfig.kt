package com.a8s.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.PatternSyntaxException

/** Named command allow-list. `*` grants every verb. */
data class RoleSpec(val commands: Set<String>)

/** Cluster agent; optional [phone] when this device bridges SMS for that agent. */
data class Principal(
    val agent: String,
    val phone: String?,
    val roles: Set<String>,
    /** When set, only matching senders may MQTT-forward to this phone principal. */
    val allowFrom: List<AllowFromMatcher>? = null,
)

/**
 * One `allow_from` entry: exact agent name, or a regex when the pattern
 * contains regex metacharacters (e.g. `alice-.*`).
 */
data class AllowFromMatcher(val source: String) {
    private val exact: String?
    private val regex: Regex?

    init {
        if (looksLikeRegex(source)) {
            exact = null
            regex = Regex(source)
        } else {
            exact = source
            regex = null
        }
    }

    val isLiteral: Boolean get() = exact != null

    fun matches(from: String): Boolean =
        from == exact || regex?.matches(from) == true

    companion object {
        internal fun looksLikeRegex(s: String): Boolean {
            for (c in s) {
                if (c in REGEX_METACHAR) return true
            }
            return false
        }

        private const val REGEX_METACHAR = ".^$+*?[](){}|\\"
    }
}

data class RoutingConfig(val smsInboundAgent: String)

/**
 * Parsed auth + routing from `a8s.json`. Pure Kotlin for unit tests.
 *
 * - MQTT commands run only when `to == device` and `from` is a known agent
 *   with a role permitting the verb.
 * - MQTT to a phone-backed agent (`to == operator-phone`) forwards opaque SMS;
 *   slash-prefixed content is **not** executed locally. Optional
 *   `allow_from` on that principal restricts which agents may trigger SMS.
 * - SMS from a matched phone principal: `/verb` runs on-device; plain text
 *   publishes `from: <phone-agent>` → `routing.sms_inbound_agent`.
 */
class PrincipalRegistry(
    val device: String,
    val roles: Map<String, RoleSpec>,
    private val principals: List<Principal>,
    val routing: RoutingConfig,
) {
    private val byAgent: Map<String, Principal> = principals.associateBy { it.agent }

    /** Every agent defined in this config (for diagnostics). */
    val localAgents: Set<String> = byAgent.keys

    /** Agents whose MQTT envelopes we originate locally (phone-backed). */
    val phoneAgents: Set<String> = principals.filter { it.phone != null }.map { it.agent }.toSet()

    fun principalByAgent(agent: String): Principal? = byAgent[agent.trim()]

    fun principalByPhone(incomingNumber: String): Principal? =
        principals.firstOrNull { p ->
            p.phone != null && PhoneNormalize.phoneDigitsMatch(incomingNumber, p.phone)
        }

    fun phoneForAgent(agent: String): String? = byAgent[agent.trim()]?.phone

    fun isPhoneAgent(agent: String): Boolean = phoneForAgent(agent) != null

    fun allowsCommand(principal: Principal, verb: String): Boolean =
        RolePolicy.allows(principal.roles, roles, verb)

    fun allowsCommandByAgent(agent: String, verb: String): Boolean {
        val p = principalByAgent(agent) ?: return false
        return allowsCommand(p, verb)
    }

    /**
     * Whether [fromAgent] may MQTT-forward to phone-backed [targetAgent].
     * Absent `allow_from` on the target → any sender (non-loopback handled upstream).
     */
    fun allowsPhoneForward(fromAgent: String, targetAgent: String): Boolean {
        val target = principalByAgent(targetAgent.trim()) ?: return false
        if (target.phone == null) return false
        val allowed = target.allowFrom ?: return true
        if (allowed.isEmpty()) return true
        val from = fromAgent.trim()
        return allowed.any { it.matches(from) }
    }
}

object RolePolicy {
    const val WILDCARD: String = "*"

    fun allows(principalRoles: Set<String>, roleSpecs: Map<String, RoleSpec>, verb: String): Boolean {
        val v = verb.lowercase()
        for (role in principalRoles) {
            val spec = roleSpecs[role] ?: continue
            if (WILDCARD in spec.commands || v in spec.commands) return true
        }
        return false
    }
}

object ConfigParser {

    private val ROOT_ALLOWED = setOf("device", "roles", "principals", "routing", "remotes", "services")
    private val ROLE_ALLOWED = setOf("commands")
    private val PRINCIPAL_ALLOWED = setOf("agent", "phone", "roles", "allow_from")
    private val ROUTING_ALLOWED = setOf("sms_inbound_agent")

    fun parse(root: JSONObject): A8sAndroid.Config {
        rejectUnknownKeys(root, ROOT_ALLOWED)
        val device = root.getString("device").trim()
        require(device.isNotEmpty()) { "device must not be blank" }
        val roles = parseRoles(root.getJSONObject("roles"))
        require("owner" in roles) { "roles must include 'owner'" }
        val principals = parsePrincipals(root.getJSONArray("principals"))
        require(principals.isNotEmpty()) { "principals must not be empty" }
        for (p in principals) {
            require(p.agent != device) { "principal agent '${p.agent}' must not equal device" }
            for (r in p.roles) {
                require(r in roles) { "principal '${p.agent}': unknown role '$r'" }
            }
            p.allowFrom?.forEach { matcher ->
                if (!matcher.isLiteral) return@forEach
                val sender = matcher.source
                require(sender != device) {
                    "principal '${p.agent}': allow_from must not include device"
                }
                require(sender != p.agent) {
                    "principal '${p.agent}': allow_from must not include self"
                }
                require(principals.any { it.agent == sender }) {
                    "principal '${p.agent}': allow_from '$sender' is not a configured agent"
                }
            }
            require(p.allowFrom == null || p.phone != null) {
                "principal '${p.agent}': allow_from requires phone"
            }
        }
        val routing = parseRouting(root.getJSONObject("routing"), principals, device)
        val registry = PrincipalRegistry(device, roles, principals, routing)
        val remotes = Network.parseRemotes(root)
        require(remotes.isNotEmpty()) { "remotes must not be empty" }
        val services = Network.parseServices(root)
        return A8sAndroid.Config(device, registry, remotes, services)
    }

    private fun parseRoles(obj: JSONObject): Map<String, RoleSpec> {
        val out = linkedMapOf<String, RoleSpec>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val spec = obj.getJSONObject(name)
            rejectUnknownKeys(spec, ROLE_ALLOWED)
            val arr = spec.getJSONArray("commands")
            val commands = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val c = arr.optString(i).trim()
                if (c.isNotEmpty()) {
                    commands += if (c == RolePolicy.WILDCARD) c else c.removePrefix("/").lowercase()
                }
            }
            require(commands.isNotEmpty()) { "role '$name': commands must not be empty" }
            out[name] = RoleSpec(commands)
        }
        return out
    }

    private fun parsePrincipals(arr: JSONArray): List<Principal> {
        val out = mutableListOf<Principal>()
        val seenAgents = mutableSetOf<String>()
        val seenPhones = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            rejectUnknownKeys(obj, PRINCIPAL_ALLOWED)
            val agent = obj.getString("agent").trim()
            require(agent.isNotEmpty()) { "principal[$i]: agent must not be blank" }
            require(agent !in seenAgents) { "duplicate agent '$agent'" }
            seenAgents += agent
            val phone = obj.optString("phone").trim().ifBlank { null }
            if (phone != null) {
                val digits = PhoneNormalize.normalizePhoneDigits(phone)
                require(digits !in seenPhones) { "duplicate phone for agent '$agent'" }
                seenPhones += digits
            }
            val rolesArr = obj.getJSONArray("roles")
            val roles = mutableSetOf<String>()
            for (j in 0 until rolesArr.length()) {
                val r = rolesArr.optString(j).trim()
                if (r.isNotEmpty()) roles += r
            }
            require(roles.isNotEmpty()) { "principal '$agent': roles must not be empty" }
            val allowFrom = parseAllowFrom(obj, agent)
            out += Principal(agent, phone, roles, allowFrom)
        }
        return out
    }

    private fun parseAllowFrom(obj: JSONObject, agent: String): List<AllowFromMatcher>? {
        if (!obj.has("allow_from")) return null
        val arr = obj.getJSONArray("allow_from")
        val seen = mutableSetOf<String>()
        val out = mutableListOf<AllowFromMatcher>()
        for (j in 0 until arr.length()) {
            val raw = arr.optString(j).trim()
            require(raw.isNotEmpty()) { "principal '$agent': allow_from entries must not be blank" }
            require(raw !in seen) { "principal '$agent': duplicate allow_from '$raw'" }
            seen += raw
            try {
                out += AllowFromMatcher(raw)
            } catch (e: PatternSyntaxException) {
                throw IllegalArgumentException(
                    "principal '$agent': invalid allow_from regex '$raw': ${e.message}",
                )
            }
        }
        return out
    }

    private fun parseRouting(
        obj: JSONObject,
        principals: List<Principal>,
        device: String,
    ): RoutingConfig {
        rejectUnknownKeys(obj, ROUTING_ALLOWED)
        val target = obj.getString("sms_inbound_agent").trim()
        require(target.isNotEmpty()) { "routing.sms_inbound_agent must not be blank" }
        require(target != device) { "routing.sms_inbound_agent must not equal device" }
        require(principals.any { it.agent == target }) {
            "routing.sms_inbound_agent '$target' is not a configured principal"
        }
        return RoutingConfig(target)
    }

    private fun rejectUnknownKeys(spec: JSONObject, allowed: Set<String>) {
        val keys = spec.keys()
        val unknown = mutableListOf<String>()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k !in allowed) unknown += k
        }
        require(unknown.isEmpty()) { "unknown config key(s): ${unknown.sorted()}" }
    }
}
