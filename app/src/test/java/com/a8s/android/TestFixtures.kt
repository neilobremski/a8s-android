package com.a8s.android

import org.json.JSONObject

/** Shared principals-based config for unit tests. */
object TestFixtures {

    fun config(
        device: String = "android-pixel-7",
        rolesJson: String = """{"owner":{"commands":["*"]}}""",
        principalsJson: String = """
            [
              {"agent":"knobert","roles":["owner"]},
              {"agent":"neil-phone","phone":"+13602196756","roles":["owner"]}
            ]
        """.trimIndent(),
        routingAgent: String = "knobert",
    ): A8sAndroid.Config {
        val json = JSONObject(
            """
            {
              "device": "$device",
              "roles": $rolesJson,
              "principals": $principalsJson,
              "routing": {"sms_inbound_agent": "$routingAgent"},
              "remotes": {
                "default": {
                  "broker": "ssl://broker:8883",
                  "topic": "t",
                  "username": "u",
                  "password": "p"
                }
              }
            }
            """.trimIndent(),
        )
        return ConfigParser.parse(json)
    }
}
