package com.a8s.android

import org.json.JSONObject

object CmdConfig {
    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val args = cmd.args
        if (args.isEmpty()) {
            val text = "Settings:\nsms_truncate_limit = ${config.smsTruncateLimit}"
            service.replyToSender(config, cmd, text)
            return
        }
        val action = args[0]
        if (action == "get") {
            if (args.size < 2) {
                service.replyToSender(config, cmd, "usage: /config get <key>")
                return
            }
            when (val key = args[1]) {
                "sms_truncate_limit" -> service.replyToSender(config, cmd, "$key = ${config.smsTruncateLimit}")
                else -> service.replyToSender(config, cmd, "unknown setting: $key")
            }
            return
        }
        if (action == "set") {
            if (args.size < 3) {
                service.replyToSender(config, cmd, "usage: /config set <key> <value>")
                return
            }
            val key = args[1]
            val value = args[2]
            when (key) {
                "sms_truncate_limit" -> {
                    val limit = value.toIntOrNull()
                    if (limit == null) {
                        service.replyToSender(config, cmd, "invalid integer: $value")
                        return
                    }
                    val updated = updateSetting(service, "sms_truncate_limit", limit)
                    if (updated) {
                        service.replyToSender(config, cmd, "set $key = $limit")
                    } else {
                        service.replyToSender(config, cmd, "failed to update config")
                    }
                }
                else -> service.replyToSender(config, cmd, "unknown setting: $key")
            }
            return
        }
        service.replyToSender(config, cmd, "usage: /config [get|set] <key> [value]")
    }

    internal fun updateSetting(service: A8sService, key: String, value: Any): Boolean {
        val store = SecureConfigStore(service)
        val rawText = store.loadConfigJson() ?: return false
        return try {
            val obj = JSONObject(rawText)
            val settings = obj.optJSONObject("settings") ?: JSONObject()
            settings.put(key, value)
            obj.put("settings", settings)
            val newText = obj.toString(2)
            val parsed = ConfigParser.parse(JSONObject(newText))
            store.saveConfigJson(newText)
            A8sAndroid.updateConfig(parsed)
            true
        } catch (e: Exception) {
            false
        }
    }
}
