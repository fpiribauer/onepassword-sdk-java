package io.github.fpiribauer.onepassword_sdk_java

import java.io.Closeable

/**
 * Initialize a DesktopAuth instance.
 * @param accountName The name of the account.
 */
data class DesktopAuth(val accountName: String)

class Client private constructor() : Closeable {
    lateinit var secrets: Secrets
    //lateinit var items: Items
    //lateinit var vaults: Vaults
    //lateinit var groups: Groups

    private var clientId: Int = -1
    private lateinit var core: DesktopCore

    companion object {
        /**
         * Replaces the Python @classmethod authenticate.
         * Usage: val client = Client.authenticate("my-account", "DBeaver", "1.0.0")
         */
        suspend fun authenticate(
            auth: DesktopAuth,
            integrationName: String,
            integrationVersion: String
        ): Client {
            val core = DesktopCore(auth.accountName)
            val clientId = core.initClient(integrationName, integrationVersion)
            val innerClient = InnerClient(clientId, core)

            val authenticatedClient = Client()
            authenticatedClient.core = core
            authenticatedClient.clientId = clientId

            authenticatedClient.secrets = Secrets(innerClient)
            //authenticatedClient.items = Items(innerClient)
            //authenticatedClient.vaults = Vaults(innerClient)
            //authenticatedClient.groups = Groups(innerClient)

            return authenticatedClient
        }
    }

    /**
     * Call this when you're done to release the client memory in the Rust library.
     */
    override fun close() {
        if (clientId != -1) {
            core.releaseClient(clientId)
            clientId = -1
        }
    }
}

/**
 * A simple container to pass the session around to the sub-apis
 */
class InnerClient(
    val clientId: Int,
    val core: DesktopCore
)