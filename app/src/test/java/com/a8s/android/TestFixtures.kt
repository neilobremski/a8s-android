package com.a8s.android

import org.json.JSONObject

/** Shared principals-based config for unit tests. */
object TestFixtures {

    fun config(
        device: String = "android-pixel-7",
        rolesJson: String = """{"owner":{"commands":["*"]}}""",
        principalsJson: String = """
            [
              {"agent":"alice","roles":["owner"]},
              {"agent":"operator-phone","phone":"+15551234567","roles":["owner"]}
            ]
        """.trimIndent(),
    ): A8sAndroid.Config {
        val json = JSONObject(
            """
            {
              "device": "$device",
              "roles": $rolesJson,
              "principals": $principalsJson,
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
