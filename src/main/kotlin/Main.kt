package io.github.fpiribauer.onepassword_sdk_java
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    println("Starting 1Password IPC Test...")

    val auth = DesktopAuth(args[0])
    Client.authenticate(auth, "Unofficial Kotlin SDK TEST", "1.0.0").use{ client ->
        println("Successfully connected!")
        var result = client.secrets.resolveAll(listOf("op://Shared/Login/password", "op://Shared/Login/password"))
        println(result.individualResponses["op://Shared/Login/password"])
        println(result)
    }
}