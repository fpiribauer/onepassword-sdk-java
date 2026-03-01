package io.github.fpiribauer.onepassword_sdk_java

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

interface OnePasswordIpcLib : Library {
    fun op_sdk_ipc_send_message(
        msgPtr: ByteArray,
        msgLen: Long,
        outBuf: PointerByReference,
        outLen: LongByReference,
        outCap: LongByReference
    ): Int

    fun op_sdk_ipc_free_response(
        outBuf: Pointer,
        outLen: Long,
        outCap: Long
    )
}

class DesktopCore(private val accountName: String) {

    private val lib: OnePasswordIpcLib
    private val gson = Gson()

    init {
        val libPath = find1PasswordLibPath()
        lib = Native.load(libPath, OnePasswordIpcLib::class.java)
    }

    private fun find1PasswordLibPath(): String {
        val osName = System.getProperty("os.name").lowercase()
        val homeDir = System.getProperty("user.home")

        val locations = when {
            osName.contains("mac") -> listOf(
                "/Applications/1Password.app/Contents/Frameworks/libop_sdk_ipc_client.dylib",
                "$homeDir/Applications/1Password.app/Contents/Frameworks/libop_sdk_ipc_client.dylib"
            )

            osName.contains("linux") -> listOf(
                "/usr/bin/1password/libop_sdk_ipc_client.so",
                "/opt/1Password/libop_sdk_ipc_client.so",
                "/snap/bin/1password/libop_sdk_ipc_client.so"
            )

            osName.contains("win") -> listOf(
                "$homeDir\\AppData\\Local\\1Password\\op_sdk_ipc_client.dll",
                "C:\\Program Files\\1Password\\app\\8\\op_sdk_ipc_client.dll",
                "C:\\Program Files (x86)\\1Password\\app\\8\\op_sdk_ipc_client.dll",
                "$homeDir\\AppData\\Local\\1Password\\app\\8\\op_sdk_ipc_client.dll"
            )

            else -> throw UnsupportedOperationException("Unsupported operating system: $osName")
        }

        for (path in locations) {
            if (File(path).exists()) {
                return path
            }
        }

        throw java.io.FileNotFoundException("1Password desktop application not found")
    }

    private fun callSharedLibrary(payload: String, operationKind: String): String {
        val encodedPayload = Base64.getEncoder().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))

        val requestJson = JsonObject().apply {
            addProperty("kind", operationKind)
            addProperty("account_name", accountName)
            addProperty("payload", encodedPayload)
        }

        val messageBytes = gson.toJson(requestJson).toByteArray(StandardCharsets.UTF_8)

        // Prepare output pointers for C-Interop
        val outBuf = PointerByReference()
        val outLen = LongByReference()
        val outCap = LongByReference()

        // Call the native Rust function
        val retCode = lib.op_sdk_ipc_send_message(
            messageBytes,
            messageBytes.size.toLong(),
            outBuf,
            outLen,
            outCap
        )

        val err = errorFromReturnCode(retCode)
        if (err != null) {
            throw err
        }

        val actualPointer = outBuf.value
            ?: throw IllegalStateException("Received null pointer from native library")

        val dataBytes = actualPointer.getByteArray(0, outLen.value.toInt())
        val dataString = String(dataBytes, StandardCharsets.UTF_8)

        // Free memory using the native Rust deallocator
        lib.op_sdk_ipc_free_response(actualPointer, outLen.value, outCap.value)

        val parsed = gson.fromJson(dataString, JsonObject::class.java)

        val responsePayloadJson = parsed.getAsJsonArray("payload")
        val responsePayloadBytes = ByteArray(responsePayloadJson.size()) { i ->
            responsePayloadJson[i].asByte
        }
        val responsePayloadString = String(responsePayloadBytes, StandardCharsets.UTF_8)

        val success = parsed.get("success")?.asBoolean ?: false
        if (!success) {
            throw Exception(responsePayloadString)
        }

        return responsePayloadString
    }

    suspend fun initClient(integrationName: String, integrationVersion: String): Int = withContext(Dispatchers.IO) {
        val configMap = mutableMapOf<String, Any>(
            "programmingLanguage" to "Kotlin",
            "sdkVersion" to "0040003",
            "integrationName" to integrationName,
            "integrationVersion" to integrationVersion,
            "requestLibraryName" to "reqwest",
            "requestLibraryVersion" to "0.11.24",
            "os" to System.getProperty("os.name").lowercase(),
            "osVersion" to System.getProperty("os.version"),
            "architecture" to System.getProperty("os.arch")
        )

        configMap["auth"] = mapOf(
            "Desktop" to mapOf(
                "account_name" to accountName
            )
        )

        val payload = gson.toJson(configMap)

        // This calls your existing callSharedLibrary logic
        val resp = callSharedLibrary(payload, "init_client")
        gson.fromJson(resp, JsonElement::class.java).asInt
    }

    suspend fun invoke(invokeConfig: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val payload = gson.toJson(invokeConfig)
        callSharedLibrary(payload, "invoke")
    }

    fun releaseClient(clientId: Int) {
        val payload = clientId.toString()
        try {
            callSharedLibrary(payload, "release_client")
        } catch (e: Exception) {
            println("failed to release client: ${e.message}")
        }
    }

    // --- Error Handling ---

    private fun errorFromReturnCode(retCode: Int): Exception? {
        if (retCode == 0) return null

        val isDarwin = System.getProperty("os.name").lowercase().contains("mac")

        val errChannelClosed =
            "desktop app connection channel is closed. Make sure Settings > Developer > Integrate with other apps is enabled, or contact 1Password support"
        val errConnectionDropped =
            "connection was unexpectedly dropped by the desktop app. Make sure the desktop app is running and Settings > Developer > Integrate with other apps is enabled, or contact 1Password support"
        val errInternalFmt =
            "an internal error occurred. Please contact 1Password support and mention the return code: $retCode"

        return if (isDarwin) {
            when (retCode) {
                -3 -> RuntimeException(errChannelClosed)
                -7 -> RuntimeException(errConnectionDropped)
                else -> RuntimeException(errInternalFmt)
            }
        } else {
            when (retCode) {
                -2 -> RuntimeException(errChannelClosed)
                -5 -> RuntimeException(errConnectionDropped)
                else -> RuntimeException(errInternalFmt)
            }
        }
    }
}